package ee.schimke.livery.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Parcel
import android.widget.RemoteViews
import androidx.test.platform.app.InstrumentationRegistry
import ee.schimke.composeai.rcwidget.launcher.RcLauncherWidgets
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A Remote Compose body, as [LiveryNotifications] hands it over, on a device: it posts, SystemUI
 * keeps it (on Android 17 SystemUI nests it in its template, and drops one it can't inflate a moment
 * after it posts), and the notification survives the parcel a foreground service's does.
 *
 * Run with `./gradlew :livery-notifications:connectedDebugAndroidTest`, on an API 31+ device (set
 * `ANDROID_SERIAL` with more than one attached).
 */
class NotificationBodyTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context = instrumentation.targetContext
  private val manager = context.getSystemService(NotificationManager::class.java)
  private val document: ByteArray = instrumentation.context.assets.open("demo-tile.rc").readBytes()

  init {
    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
  }

  /** The body of [LiveryNotifications.render]: `RcLauncherWidgets.notificationRemoteViews`. */
  private fun body(): RemoteViews {
    assumeTrue(Build.VERSION.SDK_INT >= RcLauncherWidgets.MIN_HOST_API)
    return RcLauncherWidgets.notificationRemoteViews(document, emptyMap())
  }

  private fun build(views: RemoteViews, id: Int): Notification {
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Body test", NotificationManager.IMPORTANCE_LOW))
    return Notification.Builder(context, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("body $id")
      .setCustomBigContentView(views)
      .build()
  }

  @Test fun theBodyPostsAndStays() {
    val n = build(body(), ID)
    manager.notify(ID, n)
    try {
      // SystemUI cancels a notification it can't inflate within a second or so.
      Thread.sleep(SETTLE_MILLIS)
      assertTrue("SystemUI dropped the notification", manager.activeNotifications.any { it.id == ID })
    } finally {
      manager.cancel(ID)
    }
  }

  /** WorkManager's `SystemForegroundService` parcels the notification into an `Intent`. */
  @Test fun theNotificationSurvivesAParcel() {
    val n = build(body(), ID + 1)
    val p = Parcel.obtain()
    try {
      n.writeToParcel(p, 0)
      p.setDataPosition(0)
      Notification.CREATOR.createFromParcel(p)
    } finally {
      p.recycle()
    }
  }

  private companion object {
    const val CHANNEL = "body-test"
    const val ID = 4101
    const val SETTLE_MILLIS = 4_000L
  }
}
