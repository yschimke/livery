package ee.schimke.livery.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The host's feed of sample cards (`FeedActivity`), flung top to bottom, drawn three ways: Livery
 * slots, the Remote Compose player alone, and the same designs written in Compose UI; and Livery and
 * Compose UI again with each of Compose's prefetch options (a cache window, pausable composition). Both are compiled with the shipped baseline
 * profiles, as an app installed from a store would be. FrameTimingMetric's frame durations and
 * overruns for the two tests are the comparison; on an emulator they are only a rough one.
 */
@RunWith(AndroidJUnit4::class)
class FeedScrollBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  /** Livery slots: planning, binding, events and the player, as an app's feed runs them. */
  @Test @Spine fun livery() = fling("livery", tier = Tier.Spine)

  @Test @Completion fun liveryView() = fling("livery-view", tier = Tier.Completion)
  @Test @Completion fun liveryEmbedded() = fling("livery-embedded", tier = Tier.Completion)
  /** Livery with the lookahead pass deferred (off by default): it changes nothing for the default catalog's templates, which have no layout animations. */
  /** Livery with one composable per component: what flat layout saves. */
  @Test @Completion fun liveryNoFlat() = fling("livery-noflat", tier = Tier.Completion)

  /** The same prepared documents and values, played by the player with nothing of Livery around it. */
  @Test @Completion fun rcPlayer() = fling("rc", tier = Tier.Completion)

  /** The same designs written in Compose UI, drawing the same pixels (TwinRenderParityTest). */
  @Test @Spine fun composeUi() = fling("compose", tier = Tier.Spine)

  /**
   * Compose's own prefetch options, each for Livery and for the twin, so it is measured as a
   * difference to each (FeedActivity): a cache window of one viewport ahead and half behind, and
   * pausable composition in prefetch.
   */
  @Test @Completion fun liveryCacheWindow() = fling("livery", Options.CacheWindow, tier = Tier.Completion)

  @Test fun composeUiCacheWindow() = fling("compose", Options.CacheWindow, tier = Tier.Completion)

  @Test @Completion fun liveryPausable() = fling("livery", Options.Pausable, tier = Tier.Completion)

  @Test fun composeUiPausable() = fling("compose", Options.Pausable, tier = Tier.Completion)

  @Test fun liveryEmbeddedCacheWindow() = fling("livery-embedded", Options.CacheWindow, tier = Tier.Completion)

  @Test fun liveryEmbeddedPausable() = fling("livery-embedded", Options.Pausable, tier = Tier.Completion)

  /**
   * Lazy: nothing prepared up front, each slot plans as it composes (planning made to take 20 ms, as an app's own
   * work does). `liveryLazyNoAhead` has the slots alone; `liveryLazyAhead` adds LiveryPrepareAhead with 8 cards ahead
   * of the scroll; `liveryLazyAheadCacheWindow` also gives the list a cache window.
   */
  @Test @Completion fun liveryLazyNoAhead() = fling("livery", Options.LazyNoAhead, tier = Tier.Completion)

  @Test @Spine fun liveryLazyAhead() = fling("livery", Options.LazyAhead, tier = Tier.Spine)

  @Test fun liveryLazyAheadCacheWindow() = fling("livery", Options.LazyAheadCacheWindow, tier = Tier.Completion)

  /** An infinite feed from androidx Paging (PagingActivity), pages of 20 taking 300 ms, planning 20 ms a card. */
  @Test @Completion fun pagingNoAhead() = fling("livery", Options.PagingNoAhead, tier = Tier.Completion)

  @Test @Spine fun pagingAhead() = fling("livery", Options.PagingAhead, tier = Tier.Spine)

  /** The window's size and the planning cost: 2, 4 and 16 cards ahead (8 is `liveryLazyAhead`), and planning at 60 ms. */
  @Test fun liveryLazyAhead2() = fling("livery", Options.Ahead2, tier = Tier.Completion)

  @Test fun liveryLazyAhead4() = fling("livery", Options.Ahead4, tier = Tier.Completion)

  @Test fun liveryLazyAhead16() = fling("livery", Options.Ahead16, tier = Tier.Completion)

  @Test fun liveryLazyNoAheadSlow() = fling("livery", Options.NoAheadSlow, tier = Tier.Completion)

  @Test fun liveryLazyAheadSlow() = fling("livery", Options.AheadSlow, tier = Tier.Completion)

  /** The visible polls' numbers change every second while the list flings, for Livery (lazy, 8 ahead), its embedded player and the twin. */
  @Test @Spine fun liveryAnimated() = fling("livery", Options.AnimatedLazy, tier = Tier.Spine)

  @Test @Completion fun liveryEmbeddedAnimated() = fling("livery-embedded", Options.AnimatedLazy, tier = Tier.Completion)

  @Test @Spine fun composeUiAnimated() = fling("compose", Options.Animated, tier = Tier.Spine)

  /**
   * Animated, like for like. Since the catalog build turns a template's easing off unless its author asked for it, `liveryAnimated`
   * lands a bar at once while `composeUiAnimated`'s bars ease: not the same work. `composeUiAnimatedNoEase` is its pair, and
   * `liveryAnimatedEased` (the catalog built with `--default-animations`) is the pair of `composeUiAnimated`;
   */
  @Test @Spine fun composeUiAnimatedNoEase() = fling("compose", Options.AnimatedNoEase, tier = Tier.Spine)

  @Test @Spine fun liveryAnimatedEased() = fling("livery", Options.AnimatedLazyEased, tier = Tier.Spine)

  private enum class Options(val activity: String = "FeedActivity", val apply: Intent.() -> Unit) {
    AnimatedNoEase(apply = { putExtra("animateMs", 1000L).putExtra("twinEases", false) }),
    AnimatedLazyEased(apply = { putExtra("ahead", 8).putExtra("planDelayMs", 20L).putExtra("animateMs", 1000L).putExtra("eased", true) }),
    Ahead2(apply = { putExtra("ahead", 2).putExtra("planDelayMs", 20L) }),
    Ahead4(apply = { putExtra("ahead", 4).putExtra("planDelayMs", 20L) }),
    Ahead16(apply = { putExtra("ahead", 16).putExtra("planDelayMs", 20L) }),
    NoAheadSlow(apply = { putExtra("ahead", -1).putExtra("planDelayMs", 60L) }),
    AheadSlow(apply = { putExtra("ahead", 8).putExtra("planDelayMs", 60L) }),
    AnimatedLazy(apply = { putExtra("ahead", 8).putExtra("planDelayMs", 20L).putExtra("animateMs", 1000L) }),
    Animated(apply = { putExtra("animateMs", 1000L) }),
    PagingNoAhead("PagingActivity", { putExtra("ahead", -1).putExtra("planDelayMs", 20L) }),
    PagingAhead("PagingActivity", { putExtra("ahead", 8).putExtra("planDelayMs", 20L) }),
    LazyNoAhead(apply = { putExtra("ahead", -1).putExtra("planDelayMs", 20L) }),
    LazyAhead(apply = { putExtra("ahead", 8).putExtra("planDelayMs", 20L) }),
    LazyAheadCacheWindow(apply = { putExtra("ahead", 8).putExtra("planDelayMs", 20L).putExtra("cacheAhead", 1f).putExtra("cacheBehind", 0.5f) }),
    None(apply = {}),
    CacheWindow(apply = { putExtra("cacheAhead", 1f).putExtra("cacheBehind", 0.5f) }),
    Pausable(apply = { putExtra("pausable", true) }),
  }

  @OptIn(ExperimentalMetricApi::class)
  private fun fling(mode: String, options: Options = Options.None, tier: Tier = Tier.Completion) =
    rule.measureRepeated(
      packageName = PACKAGE,
      // Placeholders composed during the fling: cards that reached the screen before they were ready.
      metrics = listOf(FrameTimingMetric(), TraceSectionMetric("host:placeholder", TraceSectionMetric.Mode.Sum)),
      compilationMode = CompilationMode.Partial(),
      startupMode = StartupMode.WARM,
      iterations = tier.iterations,
      setupBlock = {
        pressHome()
        startFeed(mode, options)
      },
    ) {
      val feed = device.findObject(By.res("feed"))
      feed.setGestureMargin(device.displayWidth / 5)
      repeat(tier.flings) {
        feed.fling(Direction.DOWN)
        device.waitForIdle()
      }
    }

  private fun MacrobenchmarkScope.startFeed(mode: String, options: Options) {
    startActivityAndWait(
      Intent().setClassName(PACKAGE, "$PACKAGE.${options.activity}").putExtra("mode", mode).apply(options.apply)
    )
    device.wait(Until.hasObject(By.res("feed")), 15_000)
  }

  private companion object {
    const val PACKAGE = "ee.schimke.livery.baselineprofile.host"
  }
}
