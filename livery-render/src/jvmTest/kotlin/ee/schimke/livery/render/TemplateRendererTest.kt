package ee.schimke.livery.render

import okio.ByteString.Companion.toByteString
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.protocol.NamedValue
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TemplateRendererTest {
  private val tile = javaClass.getResource("/demo-tile.rc")!!.readBytes().toByteString()

  private fun render(state: String, on: Boolean) = TemplateRenderer.png(
    tile,
    Bindings(mapOf("light.kitchen.state" to NamedValue.Str(state), "light.kitchen.is_on" to NamedValue.B(on))),
    RenderSpec(widthDp = 200, heightDp = 100, density = 2f, dark = true),
  )

  @Test fun rendersAPngAtTheRequestedSize() {
    val png = render("On", true)
    val image = ImageIO.read(png.toByteArray().inputStream())
    assertEquals(400, image.width); assertEquals(200, image.height)
    val colours = (0 until image.width step 8).flatMap { x -> (0 until image.height step 8).map { y -> image.getRGB(x, y) } }.toSet()
    assertTrue(colours.size > 1, "not a blank image")
    System.getProperty("livery.render.out")?.let { File(it).apply { mkdirs() }.resolve("tile-on.png").writeBytes(png.toByteArray()) }
  }

  @Test fun theValuesAreInTheFirstImage() {
    assertNotEquals(render("On", true), render("Off", false), "different values draw differently")
  }
}
