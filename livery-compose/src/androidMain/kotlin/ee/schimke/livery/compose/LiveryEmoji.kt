package ee.schimke.livery.compose

import android.content.Context
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Emoji, loaded when the app chooses rather than at process start.
 *
 * Compose's text uses EmojiCompat to draw emoji newer than the device's own font, and
 * androidx.emoji2 starts it with androidx.startup as the process starts: a package-manager lookup
 * for the downloadable emoji font on the main thread, before the first frame. Livery's manifest
 * turns that initializer off, so a cold start that plays templates doesn't pay for it. Call
 * [load] once the first screen is up instead:
 *
 * ```kotlin
 * val startup = host.resources.startup(lifecycleScope) { prepareSlot("home.top", data) { plan() } }
 * lifecycleScope.launch {
 *   startup.await()
 *   LiveryEmoji.load(applicationContext)
 * }
 * ```
 *
 * Text composed before the font loads draws with the platform's emoji; text composed after it
 * uses EmojiCompat's. An app whose manifest restores the initializer, or that sets EmojiCompat up
 * itself, keeps its own: [load] does nothing once EmojiCompat is configured.
 */
object LiveryEmoji {
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
