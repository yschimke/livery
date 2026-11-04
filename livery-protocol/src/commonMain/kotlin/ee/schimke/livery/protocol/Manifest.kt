package ee.schimke.livery.protocol

import kotlinx.serialization.Serializable

/**
 * The index of one catalog channel. A client fetches it, verifies its detached signature
 * ([ManifestSignature], served beside it as `manifest.sig.json`), and resolves everything else
 * from it. Blobs and previews are content addressed and immutable.
 *
 * The same file works from any host: a static site, a GitHub release (flat asset names, so
 * [blobBase] is the release's download URL), a CDN, a design server, or app assets.
 *
 * @property catalog stable id of the catalog ("acme-promos"). Clients cache per catalog + channel.
 * @property channel `main`, `staging`, `pr-123`, … A channel is a whole manifest, never a diff.
 * @property serial monotonic per catalog + channel; a client refuses a lower serial than one it
 *   accepted, so a host can't replay an old manifest to hide a revocation.
 * @property expiresAtEpochMs after this the manifest is stale: still played, no longer "fresh".
 * @property minClient protocol level a client must implement; older clients use bundled templates.
 * @property blobBase where blobs and previews live, absolute or relative to the manifest URL.
 *   `blobs/` by default. A blob's URL is `blobBase + <sha256>.rc`.
 */
@Serializable
data class Manifest(
  val schema: Int = SCHEMA,
  val catalog: String,
  val channel: String = "main",
  val serial: Long,
  val generatedAtEpochMs: Long,
  val expiresAtEpochMs: Long,
  val ttlSeconds: Long = 3600,
  val minClient: Int = 0,
  val blobBase: String = "blobs/",
  val previewBase: String = "previews/",
  /** Where images and fonts ([assets]) live; `assets/` by default. */
  val assetBase: String = "assets/",
  val contracts: List<Contract> = emptyList(),
  val designs: List<Design> = emptyList(),
  val templates: List<TemplateEntry> = emptyList(),
  val themes: List<ThemePack> = emptyList(),
  val placements: List<PlacementRule> = emptyList(),
  /** Images and fonts that templates need, content addressed. */
  val assets: List<AssetEntry> = emptyList(),
  /** How remote images in datasets are fetched; null means only hosts the app allowlists. */
  val imageProxy: ImageProxy? = null,
  /** Free-form provenance: commit, PR, author tool. Never read by clients for decisions. */
  val provenance: Map<String, String> = emptyMap(),
) {
  fun contract(ref: String): Contract? {
    val (id, major) = ContractRef.parse(ref)
    return contracts.filter { it.id == id && it.major == major }.maxByOrNull { it.minor }
  }

  fun design(id: String): Design? = designs.firstOrNull { it.id == id }

  fun templatesFor(designId: String): List<TemplateEntry> = templates.filter { it.designId == designId }

  fun asset(sha256: String): AssetEntry? = assets.firstOrNull { it.sha256 == sha256 }

  /** The assets [template] needs, as listed; unknown hashes are skipped (the publisher refuses them). */
  fun assetsFor(template: TemplateEntry): List<AssetEntry> = template.assets.mapNotNull(::asset)

  companion object {
    const val SCHEMA: Int = 1
    const val FILE: String = "manifest.json"
    const val SIGNATURE_FILE: String = "manifest.sig.json"
  }
}

/**
 * Detached signature over the exact bytes of `manifest.json`, so nothing needs canonical JSON.
 * Ed25519 over the raw manifest bytes; [keyId] picks the publisher key the client trusts.
 */
@Serializable
data class ManifestSignature(
  val alg: String = "Ed25519",
  val keyId: String,
  /** Base64 (standard, padded) signature. */
  val signature: String,
)

/**
 * A design: one look for one contract, as a person picks it ("Compact", "Retro LCD"). It groups
 * every [TemplateEntry] that draws it, across surfaces, sizes, API levels and themes, so a choice
 * stored by [id] keeps working as the catalog adds targets.
 */
@Serializable
data class Design(
  val id: String,
  val contract: String,
  val title: String,
  val description: String? = null,
  val author: String? = null,
  /** Layout/shape language; `default` always exists for a contract that has designs. */
  val variant: String = DEFAULT_VARIANT,
  val tags: Map<String, String> = emptyMap(),
  /** Pictures of the design, for pickers that show it before anything is downloaded. */
  val previews: List<Preview> = emptyList(),
  /** Set for a fork: the design it was copied from and that design's revision. */
  val forkedFrom: String? = null,
  val revision: String? = null,
  val revoked: Boolean = false,
  /** How a client treats it: see [InstallPolicy]. */
  val install: InstallPolicy = InstallPolicy.OnDemand,
) {
  companion object {
    const val DEFAULT_VARIANT: String = "default"
  }
}

/** When a client downloads a design's blobs. */
@Serializable
enum class InstallPolicy {
  /** With every refresh, for the targets the client serves (the built-in look, a hot placement). */
  Always,
  /** Only when the app or the person installs it (skins, themes a person picks). */
  OnRequest,
  /** When something first needs it (feed items, ads), cached by LRU and evictable. */
  OnDemand,
}

/** One playable document: a values-free `.rc` for one [Target]. */
@Serializable
data class TemplateEntry(
  val sha256: String,
  val bytes: Long,
  val designId: String,
  /** `contractId@major` this document binds; must equal its design's contract major. */
  val contract: String,
  val target: Target,
  val tags: Map<String, String> = emptyMap(),
  val previews: List<Preview> = emptyList(),
  val revoked: Boolean = false,
  /** Named values the document actually reads, as the publisher decoded them. */
  val reads: List<String> = emptyList(),
  /** Host actions the document can emit. */
  val emits: List<String> = emptyList(),
  /** Hashes of the [AssetEntry]s (images, fonts) it needs, downloaded with it. */
  val assets: List<String> = emptyList(),
  /** Font families the document names; each is a catalog font asset, `google:<family>`, or a system family. */
  val fonts: List<String> = emptyList(),
)

/** An image of a design or template, content addressed under [Manifest.previewBase]. */
@Serializable
data class Preview(
  val sha256: String,
  val mime: String = "image/webp",
  val widthPx: Int,
  val heightPx: Int,
  /** `light`, `dark`, or a theme pack id. */
  val theme: String? = null,
  /** The contract example it was drawn with. */
  val example: String? = null,
  val surface: String? = null,
) {
  val fileName: String
    get() = "$sha256.${mime.substringAfter('/').substringBefore('+')}"
}

/**
 * A theme as data: named values (colours, dimensions, flags) pushed beside every template's own
 * data. Cheap to install (no blob) and combines with any design that reads `theme.*` roles. Fonts
 * can't be bound in a document, so a typeface belongs to a design variant, not a theme pack.
 */
@Serializable
data class ThemePack(
  val id: String,
  val title: String,
  val mode: String = "light",
  val values: Map<String, NamedValue>,
  val previews: List<Preview> = emptyList(),
)
