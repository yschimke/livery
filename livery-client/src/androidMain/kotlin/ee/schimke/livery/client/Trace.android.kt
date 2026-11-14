package ee.schimke.livery.client

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

private val cookies = AtomicInteger()

// Trace labels are limited to 127 characters.
private fun String.label() = if (length <= 127) this else substring(0, 127)

internal actual fun traceEnabled(): Boolean = if (Build.VERSION.SDK_INT >= 29) Trace.isEnabled() else true

internal actual fun traceBegin(name: String) = Trace.beginSection(name.label())

internal actual fun traceEnd() = Trace.endSection()

internal actual fun traceAsyncBegin(name: String): Int {
  if (Build.VERSION.SDK_INT < 29) return -1
  val cookie = cookies.incrementAndGet()
  Trace.beginAsyncSection(name.label(), cookie)
  return cookie
}

internal actual fun traceAsyncEnd(name: String, cookie: Int) {
  if (cookie >= 0 && Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(name.label(), cookie)
}
