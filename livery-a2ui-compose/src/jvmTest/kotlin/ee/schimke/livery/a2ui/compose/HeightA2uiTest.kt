package ee.schimke.livery.a2ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import ee.schimke.livery.instantiate.TemplateInstantiator
import ee.schimke.livery.protocol.TemplateEvent
import java.io.File
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class HeightA2uiTest : InlineSurfaceTest(null) {
  @Test fun unboundedCatalogUsesAuthoredHeight() = runComposeUiTest {
    send(create, components("""{"sha256":"${java.security.MessageDigest.getInstance("SHA-256").digest(InlineFixtures.banner).joinToString("") { "%02x".format(it) }}"}"""))
    setContent {
      MaterialTheme {
        Box(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
          A2uiSurface(surfaceModel = surface(), transitionSpec = null)
        }
      }
    }
    waitUntil(timeoutMillis = 10_000) { events.any { it.type == TemplateEvent.Type.Impression } }
    onNodeWithTag("livery:promo/banner@1:banner-classic").assertHeightIsEqualTo(TemplateInstantiator.authoredHeightDp(InlineFixtures.banner)!!.dp)
  }
}

@OptIn(ExperimentalTestApi::class)
class MinimumHeightA2uiTest : InlineSurfaceTest(null) {
  override fun prepare(tree: File) {
    val sidecar = File(tree, "designs/banner-classic/in-app.json")
    sidecar.writeText(sidecar.readText().replace("\"minWidthDp\": 280", "\"minHeightDp\": 123, \"minWidthDp\": 280"))
  }

  private val banner = """{"version":"v0.9","updateComponents":{"surfaceId":"$SURFACE","components":[{"id":"root","component":"PromoBanner","headline":"Hello","cta":"Go"}]}}"""

  @Test fun unboundedCatalogUsesTargetMinimum() = runComposeUiTest {
    send(create, banner)
    setContent {
      MaterialTheme {
        Box(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
          A2uiSurface(surfaceModel = surface(), transitionSpec = null)
        }
      }
    }
    waitUntil(timeoutMillis = 10_000) { events.any { it.type == TemplateEvent.Type.Impression } }
    onNodeWithTag("livery:promo/banner@1:banner-classic").assertHeightIsEqualTo(123.dp)
  }

  @Test fun boundedHeightIsNotReplacedWithTheTargetMinimum() = runComposeUiTest {
    send(create, banner)
    setContent {
      MaterialTheme {
        Box(Modifier.width(360.dp).height(200.dp)) {
          A2uiSurface(surfaceModel = surface(), transitionSpec = null)
        }
      }
    }
    waitUntil(timeoutMillis = 10_000) { events.any { it.type == TemplateEvent.Type.Impression } }
    onNodeWithTag("livery:promo/banner@1:banner-classic").assertHeightIsAtLeast(124.dp)
  }
}
