package ee.schimke.livery.verify

import android.app.Activity
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.instantiate.InstantiateResult
import ee.schimke.livery.instantiate.TemplateInstantiator
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every launcher widget template in a catalog source tree, written with each of its contract's
 * examples the way the widget provider writes it ([TemplateInstantiator]) and played on Android 16's
 * own widget player ([PlatformWidgetPlayer]). Run it from a Robolectric test (`sdk = [36]`) and fail
 * on any [Failure]: each is a widget that would draw a warning sign on someone's home screen.
 */
object WidgetCatalogCheck {
  data class Failure(val design: String, val template: String, val example: String, val reason: String) {
    override fun toString(): String = "$design/$template with '$example': $reason"
  }

  /** Each template played; [Failure]s only for those that didn't play. */
  suspend fun launcherWidgets(
    activity: Activity,
    catalog: File,
    density: Float = activity.resources.displayMetrics.density,
    maxBytes: Long = Long.MAX_VALUE,
  ): List<Failure> {
    val contracts = File(catalog, "contracts").listFiles().orEmpty().filter { it.extension == "json" }
      .map { JSON.decodeFromString(Contract.serializer(), it.readText()) }
      .groupBy { it.ref }.mapValues { (_, v) -> v.maxBy { it.minor } }
    val instantiator = TemplateInstantiator { _, _, _ -> null }
    val failures = mutableListOf<Failure>()
    for (designDir in File(catalog, "designs").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
      val design = File(designDir, "design.json").takeIf { it.isFile }?.let { JSON.parseToJsonElement(it.readText()).jsonObject } ?: continue
      val id = design["id"]?.jsonPrimitive?.content ?: designDir.name
      val contract = contracts[design["contract"]?.jsonPrimitive?.content]
      for (sidecar in designDir.listFiles().orEmpty().filter { it.extension == "json" && it.name != "design.json" }.sortedBy { it.name }) {
        val target = JSON.parseToJsonElement(sidecar.readText()).jsonObject["target"]
          ?.let { JSON.decodeFromJsonElement(Target.serializer(), it) } ?: continue
        if (target.surface != Surfaces.LAUNCHER_WIDGET) continue
        val name = sidecar.nameWithoutExtension
        val rc = File(designDir, "$name.rc").takeIf { it.isFile }?.readBytes()
          ?: run { failures += Failure(id, name, "-", "no $name.rc"); continue }
        if (contract == null) { failures += Failure(id, name, "-", "no contract ${design["contract"]}"); continue }
        val (wDp, hDp) = sizeFor(target)
        val (w, h) = (wDp * density).toInt() to (hDp * density).toInt()
        val examples = contract.examples.ifEmpty { mapOf("(no data)" to JsonObject(emptyMap())) }
        for ((example, data) in examples) {
          val bindings = when (val b = ContractBinder.bind(contract, data)) {
            is BindResult.Ok -> b.bindings
            is BindResult.Invalid -> { failures += Failure(id, name, example, "the example doesn't bind: ${b.problems.map { it.name }}"); continue }
          }
          val doc = when (val r = instantiator.instantiate(rc, bindings, w, h, maxBytes = maxBytes, numericActions = true)) {
            is InstantiateResult.Ok -> r.bytes
            is InstantiateResult.Failed -> { failures += Failure(id, name, example, "couldn't be written for a widget: ${r.reason}"); continue }
          }
          when (val o = PlatformWidgetPlayer.play(activity, doc, w, h)) {
            is PlatformWidgetPlayer.Outcome.Played -> Unit
            is PlatformWidgetPlayer.Outcome.Failed -> failures += Failure(id, name, example, o.reason)
          }
        }
      }
    }
    return failures
  }

  /** A widget slot the template is for: its smallest size, or a phone's 4×2 cell within its maximum. */
  internal fun sizeFor(target: Target): Pair<Int, Int> {
    val s = target.size
    val w = s.minWidthDp?.takeIf { it > 0 } ?: minOf(DEFAULT_W, s.maxWidthDp ?: DEFAULT_W)
    val h = s.minHeightDp?.takeIf { it > 0 } ?: minOf(DEFAULT_H, s.maxHeightDp ?: DEFAULT_H)
    return w to h
  }

  private const val DEFAULT_W = 250
  private const val DEFAULT_H = 180
  private val JSON = Json { ignoreUnknownKeys = true }
}
