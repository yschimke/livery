package ee.schimke.livery.verify

import android.app.Activity
import android.graphics.Bitmap
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.instantiate.InstantiateResult
import ee.schimke.livery.instantiate.TemplateInstantiator
import ee.schimke.livery.protocol.Contract
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.system.exitProcess

/**
 * `livery play --surface launcher-widget`, outside Gradle: one template, written with one set of
 * data the way the widget provider writes it and played on Android 16's own widget player under
 * Robolectric. The `livery` CLI starts it in a Java 21 JVM with this release's Android classes;
 * Robolectric downloads its Android SDK on first use.
 *
 * Arguments are system properties (`livery.play.*`). Writes the PNG and a one-line result file:
 * `played`, or `failed: <reason>`; exits 0 when it played, 1 when it didn't, 2 when it couldn't run.
 */
object WidgetPlay {
  @JvmStatic
  fun main(args: Array<String>) {
    val result = JUnitCore.runClasses(Run::class.java)
    val out = File(System.getProperty("livery.play.result") ?: exitProcess(2))
    if (!result.wasSuccessful() && !out.isFile) {
      result.failures.forEach { System.err.println(it.trace) }
      exitProcess(2)
    }
    exitProcess(if (out.readText().startsWith("played")) 0 else 1)
  }

  // No @Config: its defaults name android.app.Application, which a plain JVM's class path doesn't
  // have. The SDK and manifest come from ee/schimke/livery/verify/robolectric.properties.
  @RunWith(RobolectricTestRunner::class)
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  class Run {
    @Test fun play() {
      fun prop(name: String) = requireNotNull(System.getProperty("livery.play.$name")) { "livery.play.$name not set" }
      val result = File(prop("result"))
      val outcome = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val contract = json.decodeFromString(Contract.serializer(), File(prop("contract")).readText())
        val data = json.parseToJsonElement(File(prop("data")).readText()).jsonObject
        val bindings = when (val b = ContractBinder.bind(contract, data)) {
          is BindResult.Ok -> b.bindings
          is BindResult.Invalid -> return@runBlocking PlatformWidgetPlayer.Outcome.Failed("the data doesn't bind to ${contract.ref}: ${b.problems.map { "${it.name}: ${it.message}" }}")
        }
        val density = prop("density").toFloat()
        val (w, h) = (prop("width").toInt() * density).toInt() to (prop("height").toInt() * density).toInt()
        val catalog = System.getProperty("livery.play.catalog")?.let(::File)
        val images = catalog?.let { WidgetCatalogCheck.images(it) { _, _, _ -> null } } ?: { _, _, _ -> null }
        when (val r = TemplateInstantiator(images).instantiate(File(prop("template")).readBytes(), bindings, w, h, numericActions = true)) {
          is InstantiateResult.Failed -> PlatformWidgetPlayer.Outcome.Failed("couldn't be written for a widget: ${r.reason}")
          is InstantiateResult.Ok -> {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            PlatformWidgetPlayer.play(activity, r.bytes, w, h)
          }
        }
      }
      when (outcome) {
        is PlatformWidgetPlayer.Outcome.Played -> {
          File(prop("out")).outputStream().use { outcome.image.compress(Bitmap.CompressFormat.PNG, 100, it) }
          result.writeText("played\n")
        }
        is PlatformWidgetPlayer.Outcome.Failed -> result.writeText("failed: ${outcome.reason}\n")
      }
    }
  }
}
