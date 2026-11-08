package ee.schimke.livery.web.core

import ee.schimke.livery.client.ActionArguments
import ee.schimke.livery.client.ActionGate
import ee.schimke.livery.client.ActionRouter
import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.EventSink
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.RenderPlan
import ee.schimke.livery.client.SlotPlanner
import ee.schimke.livery.client.TemplateQuery
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.client.ImageResolver
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.instantiate.CachedImages
import ee.schimke.livery.instantiate.ImageRequest
import ee.schimke.livery.instantiate.InstantiateResult
import ee.schimke.livery.instantiate.TemplateInstantiator
import ee.schimke.livery.protocol.ActionKinds
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.TemplateEvent
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import ee.schimke.livery.client.CacheOnly
import kotlinx.coroutines.withContext
import ee.schimke.livery.protocol.FeedItem
import ee.schimke.livery.protocol.TemplateRef
import ee.schimke.livery.protocol.ValueType
import kotlinx.serialization.json.JsonNull

/** Host-neutral web preparation. The repository, binder and action router remain Livery's gates. */
class LiveryWeb(
  val repository: TemplateRepository,
  private val planner: SlotPlanner,
  val capabilities: PlayerCapabilities,
  private val events: EventSink,
  private val clock: Clock,
  private val images: ImageResolver? = null,
  private val urlAllowlist: (String) -> Boolean = { false },
  private val appKinds: Set<String> = emptySet(),
) {
  data class Space(val widthDp: Int? = null, val heightDp: Int? = null, val density: Float = 2f, val themeArgb: Map<String, Long> = emptyMap(), val decisionId: String? = null)
  sealed interface Rendered {
    data class Ready(val id: Int, val document: ByteArray, val documentKey: String, val values: Map<String, JsonElement>, val designId: String, val sha256: String, val heightDp: Int? = null) : Rendered
    data class Fallback(val reason: String) : Rendered
    data class Waiting(val reason: String) : Rendered
  }
  sealed interface Routed {
    data class Set(val input: String, val value: JsonElement, val checked: Map<String, String>) : Routed
    data class App(val kind: String, val action: String, val args: Map<String, String>, val checked: Map<String, String>) : Routed
    data class Dropped(val reason: String) : Routed
  }
  private class Live(val plan: RenderPlan, val handledActions: Set<String>)
  private val live = LinkedHashMap<Int, Live>()
  private var next = 1
  private val downloads = mutableListOf<CacheOnly>()
  private val missing = LinkedHashSet<ImageRequest>()

  /** Exact pinned publications use the repository's existing exact-template path. */
  suspend fun render(contract: String, data: JsonObject, space: Space = Space(), designId: String? = null, sha256: String? = null): Rendered {
    val query = TemplateQuery(contract, capabilities, widthDp = space.widthDp, designId = designId)
    val specification = repository.current()?.contracts?.filter { it.ref == contract }?.maxByOrNull { it.minor }
      ?: return Rendered.Fallback("unknown contract $contract")
    val themed = JsonObject(data + themeValues(specification, space.themeArgb))
    val cache = CacheOnly()
    val planned = withContext(cache) { if (sha256 == null) planner.forAppData(query, themed, decisionId = space.decisionId)
      else planner.forFeedItem(query, FeedItem(space.decisionId ?: "preview", TemplateRef(designId, sha256, contract), themed)) }
    if (cache.misses > 0) { downloads += cache; return Rendered.Waiting("template resources are not cached") }
    return when (planned) {
      is PlanResult.Fallback -> Rendered.Fallback(planned.reason)
      is PlanResult.Ready -> if ((sha256 != null && planned.plan.template.entry.sha256 != sha256) || (designId != null && sha256 != null && planned.plan.template.entry.designId != designId)) Rendered.Fallback("pinned template unavailable") else prepare(planned.plan, space)
    }
  }

  /** Adapters may prepare a plan obtained through Livery's gates, adding only their explicitly wired action names. */
  suspend fun prepare(plan: RenderPlan, space: Space = Space(), handledActions: Set<String> = emptySet()): Rendered {
    val px = { dp: Int? -> ((dp ?: DEFAULT_IMAGE_DP) * space.density).roundToInt().coerceAtLeast(1) }
    // What a playing document can't take as a named value is written in; the rest stays live.
    val written = Bindings(plan.bindings.values.filterValues { it is NamedValue.Image || it is NamedValue.L })
    val live = plan.bindings.values.filterValues { it !is NamedValue.Image && it !is NamedValue.L }.mapValues { (_, v) -> playerValue(v) }
    // The names bound are part of the key: a value left out reloads the document, so it draws the
    // template's own default instead of keeping the last value set.
    val key = plan.template.entry.sha256 + written.values.entries.sortedBy { it.key }.joinToString("") { "|${it.key}=${it.value}" } +
      "|" + live.keys.sorted().joinToString(",")
    // Any image the document draws is written in: the web player draws none from a URL.
    val draws = written.values.isNotEmpty() || drawsImages(plan.template.entry.sha256, plan.template.bytes)
    if (!draws) {
      val id = next++
      this.live[id] = Live(plan, handledActions)
      return Rendered.Ready(id, plan.template.bytes, key, live, plan.template.entry.designId, plan.template.entry.sha256, heightDp(plan))
    }
    val cached = images?.let(::CachedImages)
    val instantiator = cached?.instantiator ?: NO_IMAGES
    return when (val i = instantiator.instantiate(plan.template.bytes, written, px(space.widthDp), px(space.heightDp), capabilities.maxBytes)) {
      is InstantiateResult.Failed -> {
        val misses = cached?.misses.orEmpty()
        if (misses.isEmpty()) return Rendered.Fallback("${plan.contract.ref}: ${i.reason}")
        missing += misses
        Rendered.Waiting("${plan.contract.ref}: ${i.reason}")
      }
      is InstantiateResult.Ok -> {
        val id = next++
        this.live[id] = Live(plan, handledActions)
        Rendered.Ready(id, i.bytes, key, live, plan.template.entry.designId, plan.template.entry.sha256, heightDp(plan))
      }
    }
  }

  /**
   * Fetches the images earlier renders were [Rendered.Waiting] for, off the render path; true when
   * there were some and all arrived, so a new [render] can draw.
   */
  suspend fun fetchImages(): Boolean {
    val resolver = images ?: return false
    val asked = missing.toList()
    missing.clear()
    // Each is attempted even after one fails; one that fails is asked for again by the next render.
    return asked.isNotEmpty() && asked.map { resolver.resolve(it.ref, it.widthPx, it.heightPx) != null }.all { it }
  }

  /** The page shows render [id] (the player is ready): its impression, as `LiveryTemplate` reports after its first frame. */
  fun shown(id: Int) {
    val plan = live[id]?.plan ?: return
    events.emit(event(TemplateEvent.Type.Impression, plan))
  }

  /** The page stopped showing render [id]: [action] no longer answers for it. */
  fun release(id: Int) {
    live.remove(id)
  }

  suspend fun fetchResources(): Boolean {
    val pending = downloads.toList(); downloads.clear()
    val fetched = pending.map { it.fetchMissed() }
    val hadImages = missing.isNotEmpty()
    val imagesOk = if (hadImages) fetchImages() else true
    return (pending.isNotEmpty() || hadImages) && fetched.all { it } && imagesOk
  }

  /** Drops every active render and pending image request when the host session ends. */
  fun close() { live.clear(); missing.clear(); downloads.clear(); imagesBySha.clear(); heightBySha.clear() }

  suspend fun action(id: Int, name: String, valueType: String, value: String?): Routed {
    val state = live[id] ?: return Routed.Dropped("render $id is not on screen")
    val isSet = state.plan.contract.actions[name]?.kind == ActionKinds.SET
    val args = when (valueType) {
      "string" -> if (isSet) mapOf(ActionGate.VALUE to value.orEmpty()) else ActionArguments.parse(value) ?: return Routed.Dropped("malformed arguments")
      "int", "float" -> value?.let { mapOf(ActionGate.VALUE to it) } ?: emptyMap()
      "none" -> emptyMap()
      else -> return Routed.Dropped("not a Livery action ($valueType)")
    }
    var routed: Routed? = null
    val route: suspend (RenderPlan, String, Map<String, String>) -> Unit = { plan, action, all ->
      val checked = ActionGate.check(plan.contract, action, all).args
      val spec = plan.contract.actions.getValue(action)
      routed = when {
        spec.kind == ActionKinds.SET -> Routed.Set(spec.input!!, writable(checked[ActionGate.VALUE], plan.contract.inputs.getValue(spec.input!!).type), checked)
        spec.kind in appKinds || action in state.handledActions -> Routed.App(spec.kind, action, all, checked)
        else -> null
      }
      if (routed == null && spec.kind != ActionKinds.TRACK) events.emit(event(TemplateEvent.Type.Error, plan).copy(action = action, detail = "no handler for ${spec.kind}"))
    }
    val kinds = state.plan.contract.actions.values.map { it.kind }.toSet()
    ActionRouter(kinds.associateWith { route }, urlAllowlist, events, clock).dispatch(state.plan, name, args)
    return routed ?: Routed.Dropped(ActionGate.check(state.plan.contract, name, args).problem ?: "not handled")
  }

  private val imagesBySha = HashMap<String, Boolean>()
  private val heightBySha = HashMap<String, Int?>()

  /**
   * The template's own height, for a component the page gives none: the smallest height its target
   * declares, else the height its document was authored at.
   */
  private fun heightDp(plan: RenderPlan): Int? {
    val entry = plan.template.entry
    entry.target.size.minHeightDp?.let { return it }
    return heightBySha.getOrPut(entry.sha256) { runCatching { TemplateInstantiator.authoredHeightDp(plan.template.bytes) }.getOrNull() }
  }

  private fun drawsImages(sha256: String, template: ByteArray): Boolean =
    imagesBySha.getOrPut(sha256) { runCatching { TemplateInstantiator.bitmapUrls(template).isNotEmpty() }.getOrDefault(true) }

  /** A named value as the web player sets it: booleans as 1 or 0, colours as `#AARRGGBB`. */
  private fun playerValue(v: NamedValue): JsonElement = when (v) {
    is NamedValue.Str -> JsonPrimitive(v.value)
    is NamedValue.I -> JsonPrimitive(v.value)
    is NamedValue.L -> JsonPrimitive(v.value)
    is NamedValue.F -> JsonPrimitive(v.value)
    is NamedValue.B -> JsonPrimitive(if (v.value) 1 else 0)
    is NamedValue.Color -> JsonPrimitive("#" + (v.argb and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0'))
    is NamedValue.Image -> JsonPrimitive(v.ref)
  }

  private fun event(type: TemplateEvent.Type, plan: RenderPlan) = TemplateEvent(type, clock.nowEpochMs(), plan.decision?.placement, plan.decisionId, plan.feedItem?.id, plan.template.entry.designId, plan.template.entry.sha256, experiments = plan.experiments)

  companion object {
    private const val DEFAULT_IMAGE_DP = 360
    private val NO_IMAGES = TemplateInstantiator { _, _, _ -> null }
    fun themeValues(contract: Contract, values: Map<String, Long>): JsonObject = JsonObject(contract.inputs.mapNotNull { (key, spec) ->
      val argb = spec.role?.let(values::get)?.takeIf { spec.type == ValueType.Color } ?: return@mapNotNull null
      key to JsonPrimitive("#" + (argb and 0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase())
    }.toMap())
    private fun writable(value: String?, type: ValueType): JsonElement = when (type) {
      ValueType.Int, ValueType.Long -> value?.toLongOrNull()?.let(::JsonPrimitive)
      ValueType.Float -> value?.toDoubleOrNull()?.let(::JsonPrimitive)
      ValueType.Bool -> value?.toBooleanStrictOrNull()?.let(::JsonPrimitive)
      else -> value?.let(::JsonPrimitive)
    } ?: JsonNull
  }
}
