package ee.schimke.livery.verify

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import ee.schimke.composeai.rcwidget.launcher.RcLauncherWidgets
import ee.schimke.composeai.rcwidget.launcher.platformWidgetDocument
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException

/**
 * Android 16's own launcher widget player (`RemoteViews.DrawInstructions`), run on a document the
 * way a launcher runs it: rc-widget adapts it to the platform's profile, the platform inflates it
 * and draws it.
 *
 * The platform player never lets a document's exception out. It catches it while drawing, sets its
 * private `mDisable` and draws a red warning sign from then on: what a person sees on their home
 * screen, and nothing a test would notice. So after drawing, this reads the player's state by
 * reflection, and when it is disabled, paints the document again to recover the exception.
 *
 * Needs Android 16 (API 36) or later: under Robolectric, `@Config(sdk = [36])`.
 */
object PlatformWidgetPlayer {
  sealed interface Outcome {
    /** The document drew without error. */
    data class Played(val image: Bitmap) : Outcome

    /** What a device would show instead of the widget, and why. */
    data class Failed(val reason: String) : Outcome
  }

  /** The platform player's class: the view a draw-instructions RemoteViews inflates. */
  const val PLAYER_CLASS: String = "com.android.internal.widget.remotecompose.player.RemoteComposePlayer"

  /**
   * Plays [document] at [widthPx] × [heightPx] in [activity]'s window and reports how it went.
   *
   * By default the document goes through `RcLauncherWidgets.remoteViews`, as a widget provider's
   * does, which first recovers what the player can't read (a spacing that is a variable, and on
   * Android 16 what its level 6 player needs as literals or lacks). [recover] false hands the
   * player the document as it is, for what a host that builds its own `RemoteViews` would show, or
   * to find a template that only plays because it is recovered.
   */
  fun play(activity: Activity, document: ByteArray, widthPx: Int, heightPx: Int, recover: Boolean = true): Outcome {
    if (Build.VERSION.SDK_INT < RcLauncherWidgets.MIN_HOST_API) {
      return Outcome.Failed("the platform widget player needs API ${RcLauncherWidgets.MIN_HOST_API}; this runs ${Build.VERSION.SDK_INT}")
    }
    val views = try {
      if (recover) RcLauncherWidgets.remoteViews(document)
      else RemoteViews(RemoteViews.DrawInstructions.Builder(listOf(platformWidgetDocument(document, RcLauncherWidgets.targetFor()))).build())
    } catch (e: Throwable) {
      return Outcome.Failed("couldn't be adapted for the platform player: ${chain(e)}")
    }
    val parent = FrameLayout(activity)
    activity.setContentView(parent, ViewGroup.LayoutParams(widthPx, heightPx))
    val player = try {
      views.apply(activity, parent)
    } catch (e: Throwable) {
      return Outcome.Failed("the platform player refused it: ${chain(e)}")
    }
    if (player.javaClass.name != PLAYER_CLASS) return Outcome.Failed("inflated ${player.javaClass.name}, not the platform player")
    parent.addView(player, FrameLayout.LayoutParams(widthPx, heightPx))
    val image = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    try {
      parent.measure(exactly(widthPx), exactly(heightPx))
      parent.layout(0, 0, widthPx, heightPx)
      // The first frame lays out and paints; the second draws what the first asked to repaint.
      repeat(2) { parent.draw(Canvas(image)) }
    } catch (e: Throwable) {
      return Outcome.Failed("drawing threw: ${chain(e)}")
    }
    val canvas = field(player, "mInner") ?: return Outcome.Failed(INTERNALS)
    val state = fields(canvas)
    if (!state.containsKey("mDisable") || !state.containsKey("mDocument")) return Outcome.Failed(INTERNALS)
    val doc = state["mDocument"] ?: return Outcome.Failed("the platform player refused the document (an unsupported version or profile)")
    if (state["mDisable"] == true) return Outcome.Failed("crashed while drawing: ${repaint(doc, state)}")
    return Outcome.Played(image)
  }

  /** The exception the disabled player swallowed, by painting the document once more. */
  private fun repaint(doc: Any, state: Map<String, Any?>): String {
    val context = state["mARContext"] ?: return "the player disabled itself (its context isn't readable)"
    return try {
      context.javaClass.methods.firstOrNull { it.name == "useCanvas" && it.parameterCount == 1 }
        ?.invoke(context, Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
      val paint = doc.javaClass.methods.first { it.name == "paint" && it.parameterCount == 2 }
      paint.invoke(doc, context, state["mTheme"] ?: -1)
      "the player disabled itself, and painting again didn't throw"
    } catch (e: InvocationTargetException) {
      chain(e.targetException)
    } catch (e: Throwable) {
      "the player disabled itself (${chain(e)})"
    }
  }

  private const val INTERNALS =
    "can't read the platform player's state (mInner, mDocument, mDisable): this Android version's player differs from the one this check knows"

  private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)

  private fun chain(e: Throwable): String =
    generateSequence(e) { it.cause }.take(4).joinToString(", caused by ") { "${it.javaClass.simpleName}: ${it.message}" }

  private fun allFields(o: Any): Sequence<Field> =
    generateSequence(o.javaClass as Class<*>?) { it.superclass }.flatMap { it.declaredFields.asSequence() }

  private fun field(o: Any, name: String): Any? =
    allFields(o).firstOrNull { it.name == name }?.let { runCatching { it.isAccessible = true; it.get(o) }.getOrNull() }

  private fun fields(o: Any): Map<String, Any?> =
    allFields(o).associate { f -> f.name to runCatching { f.isAccessible = true; f.get(o) }.getOrNull() }
}
