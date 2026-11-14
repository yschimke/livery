package ee.schimke.livery.client

import android.content.Context
import ee.schimke.livery.protocol.Manifest
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
  /** Null when the app ships no catalog at [path]. */
  fun fromAssets(context: Context, path: String = "livery"): TemplateRepository.Bundled? {
    val assets = context.applicationContext.assets
    val base = path.trim('/')
    val manifest = try {
      LiveryTrace.section({ "livery:manifest-decode" }) {
        assets.open("$base/${Manifest.FILE}").use { TemplateRepository.DefaultJson.decodeFromString(Manifest.serializer(), it.readBytes().decodeToString()) }
      }
    } catch (_: FileNotFoundException) {
      return null
    }
    // Every file the catalog holds, by hash: templates under its blob base, assets by file name.
    val files = buildMap {
      manifest.templates.forEach { put(it.sha256, "$base/${manifest.blobBase}${it.sha256}.rc") }
      manifest.assets.forEach { put(it.sha256, "$base/${manifest.assetBase}${it.fileName}") }
    }
    return TemplateRepository.Bundled(manifest) { sha256 ->
      val file = files[sha256] ?: return@Bundled null
      withContext(Dispatchers.IO) { LiveryTrace.section({ "livery:asset-read:${sha256.take(8)}" }) { runCatching { assets.open(file).use { it.readBytes() } }.getOrNull() } }
    }
  }
}
