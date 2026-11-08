package ee.schimke.livery.client

import ee.schimke.livery.protocol.AssetEntry
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.InstallPolicy
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.ManifestSignature
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Preview
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.protocol.TemplateRef
import ee.schimke.livery.protocol.fits
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A document ready to play, and where it came from. */
data class LoadedTemplate(
  val entry: TemplateEntry,
  val design: Design?,
  val bytes: ByteArray,
  val origin: Origin,
  /** The contract from the same catalog the template came from (the bundled one, offline). */
  val contract: Contract?,
  /** The images and fonts it needs, all on disk (or bundled) when it is handed out. */
  val assets: List<AssetEntry> = emptyList(),
) {
  enum class Origin { Fresh, Stale, Bundled }
}

/** A design as a picker shows it: before, during and after install. */
data class DesignSummary(
  val design: Design,
  val previews: List<PreviewRef>,
  val installed: Boolean,
  /** Bytes still to download to install it for the configured targets. */
  val downloadBytes: Long,
  val playable: Boolean,
)

data class PreviewRef(val preview: Preview, val url: String)

sealed interface RefreshResult {
  data class Updated(val serial: Long) : RefreshResult
  data object Unchanged : RefreshResult
  data class Skipped(val reason: String) : RefreshResult
  data class Failed(val reason: String) : RefreshResult
}

/**
 * Fetches, verifies, caches and selects templates for one catalog channel, with a bundled copy to
 * fall back on. Rendering never waits on the network: [resolve] reads only what is on disk, and
 * [refresh] runs from the app's background scheduler (and on start, and when a choice changes).
 *
 * @param targets every player this app serves (in-app, launcher widget, wear widget…); install and
 *   prefetch download for each of them, so a widget works the moment it is placed.
 */
class TemplateRepository(
  private val source: CatalogSource,
  private val fetcher: Fetcher,
  private val crypto: Crypto,
  trust: TrustPolicy,
  private val blobs: BlobStore,
  private val kv: KeyValueStore,
  private val clock: Clock,
  private val targets: List<PlayerCapabilities>,
  private val bundled: Bundled? = null,
  private val json: Json = DefaultJson,
) {
  /** Default location of a message's signed document when it supplies no URL. */
  suspend fun documentUrl(sha256: String): String =
    resolveUrl(source.manifestUrl, (current()?.blobBase ?: "blobs/") + sha256 + ".rc")

  /** Hosts a message may fetch signed documents from: configured catalog and its verified blob base. */
  suspend fun documentHosts(): Set<String> = listOfNotNull(
    ImageResolver.remoteHost(source.manifestUrl),
    current()?.let { ImageResolver.remoteHost(resolveUrl(source.manifestUrl, it.blobBase)) },
  ).toSet()

  /** The app's own copy of a catalog, produced by the same publisher at build time. */
  class Bundled(val manifest: Manifest, val read: suspend (sha256: String) -> ByteArray?)

  private val verifier = ManifestVerifier(crypto, trust)
  /** Guards [current] and its disk copy only; never held across network calls. */
  private val stateLock = Mutex()
  /** Serialises refreshes. Held across the network; nothing on the render path takes it. */
  private val refreshLock = Mutex()
  private var current: Manifest? = null

  @Serializable
  private data class Meta(
    val etag: String? = null,
    val acceptedSerial: Long = 0,
    val fetchedAtEpochMs: Long = 0,
    val failures: Int = 0,
    val nextAttemptEpochMs: Long = 0,
  )

  private fun k(name: String) = "livery/${source.id}/$name"

  private val _revision = MutableStateFlow(0L)

  /**
   * Bumped whenever what [resolve] can answer changes: a newly accepted catalog, an install or an
   * uninstall. A slot that plans from this repository replans when it changes (`LiverySlot` does).
   */
  val revision: StateFlow<Long> = _revision.asStateFlow()

  suspend fun manifest(): Manifest? = stateLock.withLock { loadCachedLocked() }

  /**
   * The catalog slots draw from: the downloaded manifest while it is usable, else the bundled one.
   * Placement rules and theme packs read this rather than [manifest], so a fresh install (nothing
   * downloaded yet) or an offline one still has them, as selection already does.
   */
  suspend fun current(): Manifest? = usable() ?: bundled?.manifest

  /**
   * A theme pack's named values from [current], to pass as a slot's `extra` bindings (themes are
   * pushed beside the data); null when the catalog has no pack [id].
   */
  suspend fun theme(id: String): Bindings? =
    current()?.themes?.firstOrNull { it.id == id }?.let { Bindings(it.values) }

  private suspend fun loadCachedLocked(): Manifest? {
    current?.let { return it }
    val text = kv.get(k("manifest")) ?: return null
    return runCatching { json.decodeFromString(Manifest.serializer(), text) }.getOrNull()?.also { current = it }
  }

  private suspend fun meta(): Meta = kv.get(k("meta"))?.let { runCatching { json.decodeFromString(Meta.serializer(), it) }.getOrNull() } ?: Meta()

  private suspend fun putMeta(m: Meta) = kv.put(k("meta"), json.encodeToString(Meta.serializer(), m))

  /**
   * Revalidates the manifest (ETag), verifies it, refuses rollbacks, then downloads every blob the
   * install policy asks for. [force] ignores the TTL and backoff (a user pressed refresh).
   *
   * Refreshes run one at a time ([refreshLock]), but the network work holds no lock that reading
   * the manifest needs: [resolve] keeps drawing from what is on disk while a refresh is in flight.
   */
  suspend fun refresh(force: Boolean = false): RefreshResult = refreshLock.withLock {
    val now = clock.nowEpochMs()
    val m = meta()
    val cached = manifest()
    if (!force) {
      if (now < m.nextAttemptEpochMs) return RefreshResult.Skipped("backing off")
      if (cached != null && now - m.fetchedAtEpochMs < cached.ttlSeconds * 1000 && now < cached.expiresAtEpochMs) {
        // A retry of downloads an earlier refresh couldn't finish: new bytes can change an answer.
        if (prefetch(cached)) _revision.update { it + 1 }
        return RefreshResult.Skipped("fresh")
      }
    }
    suspend fun fail(reason: String, retryAfter: Long? = null): RefreshResult.Failed {
      val failures = m.failures + 1
      val backoff = retryAfter?.times(1000) ?: BACKOFF_MS[minOf(failures - 1, BACKOFF_MS.lastIndex)]
      putMeta(m.copy(failures = failures, nextAttemptEpochMs = now + backoff))
      return RefreshResult.Failed(reason)
    }
    when (val r = fetcher.get(source.manifestUrl, if (cached != null) m.etag else null)) {
      is FetchResult.Failed -> fail("manifest: ${r.status} ${r.message}", r.retryAfterSeconds)
      FetchResult.NotModified -> {
        putMeta(m.copy(fetchedAtEpochMs = now, failures = 0, nextAttemptEpochMs = 0))
        if (cached != null && prefetch(cached)) _revision.update { it + 1 }
        RefreshResult.Unchanged
      }
      is FetchResult.Ok -> {
        val sig = when (val s = fetcher.get(source.signatureUrl, null)) {
          is FetchResult.Ok -> runCatching { json.decodeFromString(ManifestSignature.serializer(), s.bytes.decodeToString()) }.getOrNull()
          else -> null
        }
        when (val v = verifier.verify(r.bytes, sig) { json.decodeFromString(Manifest.serializer(), it.decodeToString()) }) {
          is Verification.Rejected -> fail(v.reason)
          is Verification.Accepted -> {
            val next = v.manifest
            when {
              next.serial < m.acceptedSerial -> fail("rollback: serial ${next.serial} < ${m.acceptedSerial}")
              next.minClient > PROTOCOL_LEVEL -> fail("needs client level ${next.minClient}")
              else -> {
                stateLock.withLock {
                  kv.put(k("manifest"), r.bytes.decodeToString())
                  current = next
                }
                putMeta(Meta(etag = r.etag, acceptedSerial = next.serial, fetchedAtEpochMs = now))
                prefetch(next)
                _revision.update { it + 1 }
                RefreshResult.Updated(next.serial)
              }
            }
          }
        }
      }
    }
  }

  // --- selection ------------------------------------------------------------------------------

  /**
   * The template for [query], from disk only: the fresh cached manifest, else the stale one (if not
   * past [STALE_GRACE_MS] beyond its expiry), else the bundled catalog. Null means the app draws its
   * native fallback. Never touches the network; [ensure] does for on-demand designs.
   */
  suspend fun resolve(query: TemplateQuery): LoadedTemplate? {
    val installed = installedIds()
    // Never draw a skin nobody installed, from the downloaded catalog or the bundled one; a design
    // the query names explicitly is still allowed.
    val automatic = { d: Design -> d.install == InstallPolicy.Always || d.id in installed }
    val m = usable()
    if (m != null) {
      val origin = if (clock.nowEpochMs() < m.expiresAtEpochMs) LoadedTemplate.Origin.Fresh else LoadedTemplate.Origin.Stale
      // Choose among what is on disk, so a preferred template that failed to download never hides
      // a playable cached one. The chosen design first, else the automatic one: never nothing.
      val have = blobs.keys()
      val cached = m.copy(templates = m.templates.filter { t -> t.sha256 in have && t.assets.all { it in have } })
      for (q in listOfNotNull(query, query.copy(designId = null).takeIf { query.designId != null })) {
        val e = TemplateSelector.select(cached, q, automatic = automatic) ?: continue
        blobs.get(e.sha256)?.let { return loaded(m, e, it, origin) }
      }
    }
    val b = bundled ?: return null
    val e = TemplateSelector.select(b.manifest, query, automatic = automatic)
      ?: TemplateSelector.select(b.manifest, query.copy(designId = null), automatic = automatic)
      ?: return null
    return b.read(e.sha256)?.let { loaded(b.manifest, e, it, LoadedTemplate.Origin.Bundled) }
  }

  /**
   * The bundled catalog's template with exactly [sha256], when it passes [TemplateSelector.eligible]
   * for [query] and its bytes and assets are bundled: an exact reference (an A2UI message's
   * `sha256`) the app's own catalog vouches for, whatever the downloaded one lists. Never a design
   * the downloaded catalog has revoked.
   */
  suspend fun bundledTemplate(sha256: String, query: TemplateQuery): LoadedTemplate? {
    val b = bundled ?: return null
    val e = b.manifest.templates.firstOrNull { it.sha256 == sha256 && TemplateSelector.eligible(b.manifest, it, query) } ?: return null
    if (usable()?.design(e.designId)?.revoked == true) return null
    val bytes = b.read(e.sha256) ?: return null
    if (e.assets.any { b.read(it) == null }) return null
    return loaded(b.manifest, e, bytes, LoadedTemplate.Origin.Bundled)
  }

  /**
   * Whether automatic selection may pick [designId]: live, and shipped by default (`Always`) or
   * installed. An experiment's design arm is automatic selection, so it uses this too.
   */
  suspend fun automaticallySelectable(designId: String): Boolean {
    val d = (usable() ?: bundled?.manifest)?.design(designId) ?: return false
    return !d.revoked && (d.install == InstallPolicy.Always || designId in installedIds())
  }

  /** The cached manifest while it may still be played: until [STALE_GRACE_MS] past its expiry. */
  private suspend fun usable(): Manifest? = manifest()?.takeIf { clock.nowEpochMs() < it.expiresAtEpochMs + STALE_GRACE_MS }

  /**
   * Resolves a feed item's or decision's reference, downloading the blob if needed (on-demand;
   * never inside [CacheOnly], where a missing blob is recorded and the answer is null).
   *
   * An exact reference ([TemplateRef.sha256]) is played exactly or not at all: it must be in the
   * manifest, live, and pass the same eligibility checks as selection ([TemplateSelector.eligible]),
   * and nothing is substituted when it isn't, or when its download fails. A design reference picks
   * that design's template for the player; only a reference that names neither falls back to
   * [resolve].
   */
  suspend fun ensure(ref: TemplateRef, query: TemplateQuery): LoadedTemplate? {
    // A catalog too stale for resolve() is too stale here: same deadline for every slot.
    val m = usable()
    ref.sha256?.let { sha ->
      // The same bytes may be listed for several targets or designs: any entry that matches the
      // reference (and its design, if it names one) and passes the gate will do.
      val catalog = m ?: bundled?.manifest ?: return null
      val e = catalog.templates.firstOrNull {
        it.sha256 == sha && (ref.designId == null || it.designId == ref.designId) && TemplateSelector.eligible(catalog, it, query)
      } ?: return null
      if (m == null) {
        val b = bundled ?: return null
        if (e.assets.any { b.read(it) == null }) return null
        return b.read(sha)?.let { loaded(catalog, e, it, LoadedTemplate.Origin.Bundled) }
      }
      val bytes = obtain(m, e) ?: return null
      return loaded(m, e, bytes, originOf(m))
    }
    val designId = ref.designId ?: return resolve(query)
    if (m != null) {
      TemplateSelector.select(m, query.copy(designId = designId)) { false }?.let { e ->
        obtain(m, e)?.let { return loaded(m, e, it, originOf(m)) }
      }
    }
    // Offline or not downloadable: the bundled copy of that same design, if the app ships one.
    return resolve(query.copy(designId = designId))?.takeIf { it.entry.designId == designId }
  }

  private fun originOf(m: Manifest) =
    if (clock.nowEpochMs() < m.expiresAtEpochMs) LoadedTemplate.Origin.Fresh else LoadedTemplate.Origin.Stale

  private fun loaded(m: Manifest, e: TemplateEntry, bytes: ByteArray, origin: LoadedTemplate.Origin) =
    LoadedTemplate(e, m.design(e.designId), bytes, origin, m.contract(e.contract), m.assetsFor(e))

  // --- install / list / preview ---------------------------------------------------------------

  /** Every live design for [contract], as a picker lists them, with previews and download size. */
  suspend fun designs(contract: String): List<DesignSummary> {
    val m = manifest() ?: bundled?.manifest ?: return emptyList()
    val installed = installedIds()
    val have = blobs.keys()
    // A design that isn't automatic (a season's) is drawn by an arm, never chosen in a picker.
    return m.designs.filter { it.contract == contract && !it.revoked && it.automatic }.map { d ->
      val needed = entriesForTargets(m, d.id)
      DesignSummary(
        design = d,
        previews = (d.previews + needed.flatMap { it.previews }).distinctBy { it.sha256 }
          .map { PreviewRef(it, resolveUrl(source.manifestUrl, m.previewBase + it.fileName)) },
        installed = d.install == InstallPolicy.Always || d.id in installed,
        downloadBytes = needed.filter { it.sha256 !in have }.sumOf { it.bytes } +
          needed.flatMap { m.assetsFor(it) }.distinctBy { it.sha256 }.filter { it.sha256 !in have }.sumOf { it.bytes },
        playable = needed.isNotEmpty(),
      )
    }
  }

  /** Downloads [designId] for every configured target, then records it. A failed download installs nothing. */
  suspend fun install(designId: String): Boolean {
    val m = manifest() ?: return false
    val needed = entriesForTargets(m, designId)
    if (needed.isEmpty()) return false
    for (e in needed) if (obtain(m, e) == null) return false
    kv.put(k("installed"), json.encodeToString(SetSerializer, installedIds() + designId))
    _revision.update { it + 1 }
    return true
  }

  suspend fun uninstall(designId: String) {
    kv.put(k("installed"), json.encodeToString(SetSerializer, installedIds() - designId))
    _revision.update { it + 1 }
  }

  suspend fun installedIds(): Set<String> =
    kv.get(k("installed"))?.let { runCatching { json.decodeFromString(SetSerializer, it) }.getOrNull() } ?: emptySet()

  /**
   * A design that isn't installed, playable once: its blob for [query] is downloaded into the
   * cache (evictable, not installed), so the app can show it live with its contract's examples or
   * the person's own data before they commit.
   */
  suspend fun preview(designId: String, query: TemplateQuery): LoadedTemplate? =
    ensure(TemplateRef(designId = designId), query.copy(designId = designId))?.takeIf { it.entry.designId == designId }

  /**
   * Drops cached blobs nothing needs: not installed, not `Always`, not in [keep] (e.g. what visible
   * feed items use). Previewed and on-demand blobs go first; installed ones never.
   *
   * Only blobs this repository downloaded are candidates, so a blob store shared with another
   * catalog keeps that catalog's blobs. When repositories share a store, prune them together with
   * [TemplateRepository.prune] on the companion, so a blob either of them needs survives.
   */
  suspend fun prune(keep: Set<String> = emptySet()) = prune(listOf(this), keep)

  /** Hashes this repository needs on disk: `Always` and installed designs, for every target, with their assets. */
  suspend fun retained(): Set<String> {
    val m = manifest() ?: return emptySet()
    val installed = installedIds()
    val entries = m.designs.filter { it.install == InstallPolicy.Always || it.id in installed }.flatMap { entriesForTargets(m, it.id) }
    return (entries.map { it.sha256 } + entries.flatMap { it.assets }).toSet()
  }

  /**
   * An asset's bytes from the cache (or the bundled catalog), e.g. for a player's font or image
   * loader. Only assets of templates this repository handed out are on disk.
   */
  suspend fun asset(sha256: String): ByteArray? = blobs.get(sha256) ?: bundled?.read?.invoke(sha256)

  private suspend fun owned(): Set<String> =
    kv.get(k("owned"))?.let { runCatching { json.decodeFromString(SetSerializer, it) }.getOrNull() } ?: emptySet()

  private suspend fun setOwned(owned: Set<String>) = kv.put(k("owned"), json.encodeToString(SetSerializer, owned))

  // --- internals ------------------------------------------------------------------------------

  /** Downloads what the install policy asks for; true when a template became available. */
  private suspend fun prefetch(m: Manifest): Boolean {
    val installed = installedIds()
    var added = false
    m.designs.filter { !it.revoked && (it.install == InstallPolicy.Always || it.id in installed) }
      .flatMap { entriesForTargets(m, it.id) }
      .forEach { e ->
        val had = blobs.get(e.sha256) != null && e.assets.all { blobs.get(it) != null }
        if (!had && obtain(m, e) != null) added = true
      }
    return added
  }


  private fun entriesForTargets(m: Manifest, designId: String): List<TemplateEntry> =
    m.templatesFor(designId).filter { e -> !e.revoked && targets.any { e.target.fits(it) && e.bytes <= it.maxBytes } }

  /**
   * The template's bytes once it and every asset it needs are on disk, downloading what is missing
   * (each verified by size and hash). Null if anything can't be had: a template is never handed
   * out without its fonts and images.
   */
  private suspend fun obtain(m: Manifest, e: TemplateEntry): ByteArray? {
    // A render on a deadline: download nothing, and tell the host what to fetch afterwards.
    currentCoroutineContext()[CacheOnly]?.let { cacheOnly ->
      val complete = blobs.get(e.sha256)?.takeIf { e.assets.all { blobs.get(it) != null } }
      if (complete == null) cacheOnly.miss { obtain(m, e) != null }
      return complete
    }
    for (sha in e.assets) {
      if (blobs.get(sha) != null) continue
      val a = m.asset(sha) ?: return null
      fetchVerified(resolveUrl(source.manifestUrl, m.assetBase + a.fileName), a.sha256, a.bytes) ?: return null
    }
    return blobs.get(e.sha256) ?: fetchVerified(resolveUrl(source.manifestUrl, m.blobBase + e.sha256 + ".rc"), e.sha256, e.bytes)
  }

  private suspend fun fetchVerified(url: String, sha256: String, size: Long): ByteArray? {
    val r = fetcher.get(url, null) as? FetchResult.Ok ?: return null
    if (r.bytes.size.toLong() != size || crypto.sha256Hex(r.bytes) != sha256) return null
    blobs.put(sha256, r.bytes)
    setOwned(owned() + sha256)
    return r.bytes
  }


  companion object {
    /**
     * The protocol level this client implements; manifests set `minClient` against it. Level 2
     * added resources (assets, fonts, the image proxy): a level-1 client would hand out templates
     * without them, so catalogs that use them require level 2. Level 3 added contract minors
     * (`requiresMinor`, `TemplateQuery.minor`): a catalog whose minor policy is strict requires it.
     * Level 4 added experiments: a catalog with placement rules limited to an arm requires it.
     * Level 5 added secondary screens: a catalog with `present` actions or screen rules requires it.
     * Level 6 added designs that aren't automatic: a catalog with one requires it.
     */
    const val PROTOCOL_LEVEL: Int = 8
    val BACKOFF_MS = longArrayOf(60_000, 3_600_000, 4 * 3_600_000, 24 * 3_600_000)
    const val STALE_GRACE_MS: Long = 30L * 24 * 3_600_000
    /**
     * Prunes [repositories] that share one blob store: a blob goes only when one of them downloaded
     * it and none of them retains it.
     */
    suspend fun prune(repositories: List<TemplateRepository>, keep: Set<String> = emptySet()) {
      val retained = repositories.flatMap { it.retained() }.toSet() + keep
      for (r in repositories) {
        val owned = r.owned()
        val drop = owned - retained
        drop.forEach { r.blobs.delete(it) }
        r.setOwned(owned - drop)
      }
    }

    val DefaultJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val SetSerializer = SetSerializer(String.serializer())
  }
}
