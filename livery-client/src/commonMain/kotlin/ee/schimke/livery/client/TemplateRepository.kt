package ee.schimke.livery.client

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

  suspend fun manifest(): Manifest? = stateLock.withLock { loadCachedLocked() }

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
        prefetch(cached)
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
        cached?.let { prefetch(it) }
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
    val now = clock.nowEpochMs()
    val m = manifest()
    if (m != null && now < m.expiresAtEpochMs + STALE_GRACE_MS) {
      val origin = if (now < m.expiresAtEpochMs) LoadedTemplate.Origin.Fresh else LoadedTemplate.Origin.Stale
      val installed = installedIds()
      val automatic = { d: Design -> d.install == InstallPolicy.Always || d.id in installed }
      // The chosen design, else the automatic one; a chosen design not downloaded yet falls back
      // to what is, never to nothing.
      for (q in listOfNotNull(query, query.copy(designId = null).takeIf { query.designId != null })) {
        val e = TemplateSelector.select(m, q, automatic = automatic) ?: continue
        blobs.get(e.sha256)?.let { return loaded(m, e, it, origin) }
      }
    }
    val b = bundled ?: return null
    val e = TemplateSelector.select(b.manifest, query) ?: TemplateSelector.select(b.manifest, query.copy(designId = null)) ?: return null
    return b.read(e.sha256)?.let { loaded(b.manifest, e, it, LoadedTemplate.Origin.Bundled) }
  }

  /**
   * Resolves a feed item's or decision's reference, downloading the blob if needed (on-demand).
   *
   * An exact reference ([TemplateRef.sha256]) is played exactly or not at all: it must be in the
   * manifest, live, and pass the same eligibility checks as selection ([TemplateSelector.eligible]),
   * and nothing is substituted when it isn't, or when its download fails. A design reference picks
   * that design's template for the player; only a reference that names neither falls back to
   * [resolve].
   */
  suspend fun ensure(ref: TemplateRef, query: TemplateQuery): LoadedTemplate? {
    val m = manifest()
    ref.sha256?.let { sha ->
      val e = m?.templates?.firstOrNull { it.sha256 == sha }?.takeIf { TemplateSelector.eligible(m, it, query) } ?: return null
      val bytes = blobs.get(e.sha256) ?: download(m, e) ?: return null
      return loaded(m, e, bytes, LoadedTemplate.Origin.Fresh)
    }
    val designId = ref.designId ?: return resolve(query)
    if (m != null) {
      TemplateSelector.select(m, query.copy(designId = designId)) { false }?.let { e ->
        (blobs.get(e.sha256) ?: download(m, e))?.let { return loaded(m, e, it, LoadedTemplate.Origin.Fresh) }
      }
    }
    // Offline or not downloadable: the bundled copy of that same design, if the app ships one.
    return resolve(query.copy(designId = designId))?.takeIf { it.entry.designId == designId }
  }

  private fun loaded(m: Manifest, e: TemplateEntry, bytes: ByteArray, origin: LoadedTemplate.Origin) =
    LoadedTemplate(e, m.design(e.designId), bytes, origin, m.contract(e.contract))

  // --- install / list / preview ---------------------------------------------------------------

  /** Every live design for [contract], as a picker lists them, with previews and download size. */
  suspend fun designs(contract: String): List<DesignSummary> {
    val m = manifest() ?: bundled?.manifest ?: return emptyList()
    val installed = installedIds()
    val have = blobs.keys()
    return m.designs.filter { it.contract == contract && !it.revoked }.map { d ->
      val needed = entriesForTargets(m, d.id)
      DesignSummary(
        design = d,
        previews = (d.previews + needed.flatMap { it.previews }).distinctBy { it.sha256 }
          .map { PreviewRef(it, resolveUrl(source.manifestUrl, m.previewBase + it.fileName)) },
        installed = d.install == InstallPolicy.Always || d.id in installed,
        downloadBytes = needed.filter { it.sha256 !in have }.sumOf { it.bytes },
        playable = needed.isNotEmpty(),
      )
    }
  }

  /** Downloads [designId] for every configured target, then records it. A failed download installs nothing. */
  suspend fun install(designId: String): Boolean {
    val m = manifest() ?: return false
    val needed = entriesForTargets(m, designId)
    if (needed.isEmpty()) return false
    for (e in needed) if (blobs.get(e.sha256) == null && download(m, e) == null) return false
    kv.put(k("installed"), json.encodeToString(SetSerializer, installedIds() + designId))
    return true
  }

  suspend fun uninstall(designId: String) {
    kv.put(k("installed"), json.encodeToString(SetSerializer, installedIds() - designId))
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
   */
  suspend fun prune(keep: Set<String> = emptySet()) {
    val m = manifest() ?: return
    val installed = installedIds()
    val needed = m.designs.filter { it.install == InstallPolicy.Always || it.id in installed }
      .flatMap { entriesForTargets(m, it.id) }.map { it.sha256 }.toSet()
    blobs.keys().filterNot { it in needed || it in keep }.forEach { blobs.delete(it) }
  }

  // --- internals ------------------------------------------------------------------------------

  private suspend fun prefetch(m: Manifest) {
    val installed = installedIds()
    m.designs.filter { !it.revoked && (it.install == InstallPolicy.Always || it.id in installed) }
      .flatMap { entriesForTargets(m, it.id) }
      .filter { blobs.get(it.sha256) == null }
      .forEach { download(m, it) }
  }

  private fun entriesForTargets(m: Manifest, designId: String): List<TemplateEntry> =
    m.templatesFor(designId).filter { e -> !e.revoked && targets.any { e.target.fits(it) && e.bytes <= it.maxBytes } }

  private suspend fun download(m: Manifest, e: TemplateEntry): ByteArray? {
    val r = fetcher.get(resolveUrl(source.manifestUrl, m.blobBase + e.sha256 + ".rc"), null) as? FetchResult.Ok ?: return null
    if (r.bytes.size.toLong() != e.bytes || crypto.sha256Hex(r.bytes) != e.sha256) return null
    blobs.put(e.sha256, r.bytes)
    return r.bytes
  }

  companion object {
    /** The protocol level this client implements; manifests set `minClient` against it. */
    const val PROTOCOL_LEVEL: Int = 1
    val BACKOFF_MS = longArrayOf(60_000, 3_600_000, 4 * 3_600_000, 24 * 3_600_000)
    const val STALE_GRACE_MS: Long = 30L * 24 * 3_600_000
    val DefaultJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val SetSerializer = SetSerializer(String.serializer())
  }
}
