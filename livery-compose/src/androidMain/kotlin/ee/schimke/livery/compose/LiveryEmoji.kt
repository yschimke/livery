package ee.schimke.livery.compose

import android.content.Context
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext

/**
 * Emoji, loaded after the app's first template has drawn rather than at process start.
 *
 * Compose's text uses EmojiCompat to draw emoji newer than the device's own font, and
 * androidx.emoji2 starts it with androidx.startup as the process starts: a package-manager lookup
 * for the downloadable emoji font on the main thread, before the first frame. Livery's manifest
 * turns that initializer off, and the first [LiveryTemplate] an app composes loads emoji instead,
 * two frames after it, off the main thread: an app does nothing. Text composed before the font
 * loads draws with the platform's emoji; text composed after it uses EmojiCompat's.
 *
 * [load] is there for an app that wants emoji sooner, or on a screen with no template. An app
 * whose manifest restores the initializer, or that sets EmojiCompat up itself, keeps its own:
 * loading does nothing once EmojiCompat is configured.
 */
object LiveryEmoji {
  /** Whether a template has asked for emoji yet, in this process. */
  @Volatile internal var requested: Boolean = false

  /**
   * Sets EmojiCompat up with the device's downloadable emoji font, and loads it, off the main
   * thread. Returns whether EmojiCompat is configured afterwards: false on a device without an
   * emoji font provider. Safe to call more than once.
   */
  suspend fun load(context: Context): Boolean =
    withContext(Dispatchers.IO) {
      if (EmojiCompat.isConfigured()) return@withContext true
      val config =
        DefaultEmojiCompatConfig.create(context.applicationContext) ?: return@withContext false
      config.setMetadataLoadStrategy(EmojiCompat.LOAD_STRATEGY_MANUAL)
      // `init` keeps the first instance, so a racing caller or an app's own setup wins harmlessly.
      EmojiCompat.init(config).load()
      true
    }
}

@Composable
internal actual fun AfterFirstTemplateFrame() {
  // Every template after the first reads this flag and composes nothing more.
  if (LiveryEmoji.requested) return
  val context = LocalContext.current.applicationContext
  LaunchedEffect(Unit) {
    if (LiveryEmoji.requested) return@LaunchedEffect
    LiveryEmoji.requested = true
    // The first frame, and the one after it: the first screen draws before emoji load.
    withFrameNanos {}
    withFrameNanos {}
    LiveryEmoji.load(context)
  }
}
