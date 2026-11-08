@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class, kotlin.js.ExperimentalJsExport::class)

package ee.schimke.livery.web.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.composeai.rcplayer.compose.*
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcOperationProfiles
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcHostActionValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import kotlin.io.encoding.Base64
import kotlin.js.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import kotlinx.serialization.json.*
import org.w3c.dom.HTMLElement
import kotlinx.browser.window

private val players = mutableMapOf<Int, Player>()
private var nextId = 1
private val scope = MainScope()
private var fonts: RcTypefaceLoader? = null
private var symbols: FontFamily? = null

/** Called during catalog preparation, before any viewport or render is created. */
@JsExport
fun liveryCmpPrepare(roboto: String, symbolFont: String) {
  if (fonts != null) return
  disableFontDownloads()
  val bytes = Base64.decode(roboto)
  val faces = RcFontFaces((100..900 step 100).map { RcFontFace("livery/RobotoFlex", bytes, it) })
  fonts = RcBundledTypefaceLoader(mapOf("default" to faces, "roboto flex" to faces, "sans-serif" to faces))
  symbols = FontFamily(Font("livery/NotoSansSymbols2", Base64.decode(symbolFont)))
}

private class Player(val element: HTMLElement, val action: (String, String, String?) -> Unit) {
  var document by mutableStateOf<RcDocument?>(null)
  var density by mutableStateOf(1f)
  var revision by mutableStateOf(0)
  var key: String? = null
  var declared = emptyMap<String, RcNamedVariable>()
  val values = mutableStateMapOf<String, RcNamedValue>()
  var drawn = CompletableDeferred<Boolean>()
  var closeWindow: (() -> Unit)? = null
  var disposed = false
  var loads = 0
  var sets = 0
}

@JsExport
fun liveryCmpMount(element: JsAny, onAction: (String, String, String?) -> Unit): Int {
  check(fonts != null) { "Prepare CMP fonts before mounting" }
  val p = Player(element.unsafeCast<HTMLElement>(), onAction)
  val id = nextId++
  players[id] = p
  ComposeViewport(p.element) {
    val close = viewportDisposer()
    SideEffect {
      p.closeWindow = close
      // A detached element may be disposed before Skiko calls this composition.
      if (p.disposed) window.setTimeout({ close(); p.element.textContent = ""; null }, 0)
    }
    if (!p.disposed) {
      // Register the bundled symbol face in this viewport's Skia fallback provider before text
      // resolves. Browsers have no system fonts available to Skia's Wasm font manager.
      LocalFontFamilyResolver.current.resolve(symbols!!)
      val doc = p.document
      val revision = p.revision
      val frame = p.drawn
      CompositionLocalProvider(LocalDensity provides Density(p.density)) {
        if (doc != null) key(p.key) {
          RcComposePlayer(
            document = doc,
            namedValues = p.values,
            theme = RcPlayerTheme.Light,
            typefaces = fonts!!,
            modifier = Modifier.fillMaxSize().drawWithContent {
              drawContent()
              if (revision == p.revision) frame.complete(true)
            },
            onEvent = { event -> if (!p.disposed) forward(event, p.action) },
          )
        }
      }
    }
  }
  return id
}

/** Decode only a changed key. The state map and decoded document survive every value update. */
@JsExport
fun liveryCmpShow(id: Int, key: String, bytes: String, values: String, density: Double): Promise<JsBoolean> {
  val p = players.getValue(id)
  val doc = if (key != p.key) RcDocumentCodec.decode(Base64.decode(bytes)) else p.document!!
  if (key != p.key) doc.composeSupportReport(
    RcOperationProfiles.CMP_WASM_ALPHA18,
    availableFontFamilies = fonts!!.families,
  ).requireRenderable(lenient = false)
  val declared = if (key != p.key) doc.operations.filterIsInstance<RcNamedVariable>().associateBy { it.name } else p.declared
  val pushed = Json.parseToJsonElement(values).jsonObject.mapNotNull { (name, raw) ->
    val variable = declared["USER:$name"] ?: declared[name] ?: return@mapNotNull null
    val value = raw.jsonPrimitive
    val typed = when (variable.type) {
      RcNamedVariable.STRING_TYPE -> RcNamedValue.Text(value.content)
      RcNamedVariable.FLOAT_TYPE -> RcNamedValue.FloatValue(value.float)
      RcNamedVariable.INT_TYPE -> RcNamedValue.Integer(value.int)
      RcNamedVariable.COLOR_TYPE -> RcNamedValue.Color(value.content.removePrefix("#").toLong(16).toInt())
      else -> error("Unsupported live value type ${variable.type}")
    }
    variable.name to typed
  }.toMap()
  p.drawn.complete(false)
  p.drawn = CompletableDeferred()
  if (key != p.key) {
    p.values.clear()
    p.document = doc
    p.declared = declared
    p.key = key
    p.loads++
  }
  p.values.keys.toList().filter { it !in pushed }.forEach(p.values::remove)
  pushed.forEach { (name, value) -> if (p.values[name] != value) { p.values[name] = value; p.sets++ } }
  p.density = density.toFloat()
  p.revision++
  val frame = p.drawn
  return scope.promise { frame.await().toJsBoolean() }
}

@JsExport
fun liveryCmpResize(id: Int, density: Double) { players[id]?.density = density.toFloat() }

@JsExport
fun liveryCmpStats(id: Int): String = players[id]?.let { "{\"loads\":${it.loads},\"sets\":${it.sets}}" } ?: "{\"loads\":0,\"sets\":0}"

@JsExport
fun liveryCmpForget(id: Int) {
  players[id]?.let { it.key = null; it.document = null; it.values.clear(); it.drawn.complete(false) }
}

@JsExport
fun liveryCmpDispose(id: Int) {
  val p = players.remove(id) ?: return
  p.disposed = true
  p.drawn.complete(false)
  p.closeWindow?.invoke()
  p.element.textContent = ""
}

private fun forward(event: RcPlayerEvent, action: (String, String, String?) -> Unit) {
  if (event !is RcPlayerEvent.HostNamedAction) return
  val name = event.name.removePrefix("USER:")
  when (val v = event.value) {
    RcHostActionValue.None -> action(name, "none", null)
    is RcHostActionValue.TextValue -> action(name, "string", v.value)
    is RcHostActionValue.IntegerValue -> action(name, "int", v.value.toString())
    is RcHostActionValue.FloatValue -> action(name, "float", v.value.toString())
    else -> Unit // No list-valued actions in the web action contract.
  }
}
