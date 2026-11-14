package ee.schimke.livery.client

import android.content.Context
import ee.schimke.livery.protocol.Manifest
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

/**
 * The catalog an app was built with, from its assets: what the `ee.schimke.composeai.livery.catalog` Gradle
 * plugin writes (`manifest.json`, `blobs/`, `assets/` under [path]). Pass it to
 * `TemplateRepository(..., bundled = AndroidBundled.fromAssets(context))` so a fresh install
 * plays templates before it has downloaded anything, and offline after that.
 *
 * The APK is trusted as the app's own code is: a bundled catalog isn't signature-checked, and is
 * never too old to play. Anything the repository downloads replaces it.
 */
object AndroidBundled {
  /**
   * Null when the app ships no catalog at [path]. The manifest is decoded when the repository first needs it,
   * not here: an app builds its repository on the main thread at start, and resolves its first template on a
   * worker ([ee.schimke.livery.client.TemplateRepository.Bundled.manifest]).
   */
  fun fromAssets(context: Context, path: String = "livery"): TemplateRepository.Bundled? {
    val assets = context.applicationContext.assets
    val base = path.trim('/')
    // Whether the app ships one at all: opening it is a lookup in the APK's index, and reads nothing.
    try {
      assets.open("$base/${Manifest.FILE}").close()
    } catch (_: FileNotFoundException) {
      return null
    }
    val manifest = lazy {
      LiveryTrace.section({ "livery:manifest-decode" }) {
        assets.open("$base/${Manifest.FILE}").use { TemplateRepository.DefaultJson.decodeFromString(Manifest.serializer(), it.readBytes().decodeToString()) }
      }
    }
    // Every file the catalog holds, by hash: templates under its blob base, assets by file name.
    val files by lazy {
      val m = manifest.value
      buildMap {
        m.templates.forEach { put(it.sha256, "$base/${m.blobBase}${it.sha256}.rc") }
        m.assets.forEach { put(it.sha256, "$base/${m.assetBase}${it.fileName}") }
      }
    }
    return TemplateRepository.Bundled({ manifest.value }) { sha256 ->
      withContext(Dispatchers.IO) {
        val file = files[sha256] ?: return@withContext null
        LiveryTrace.section({ "livery:asset-read:${sha256.take(8)}" }) { runCatching { assets.open(file).source().buffer().use { it.readByteString() } }.getOrNull() }
      }
    }
  }
}
