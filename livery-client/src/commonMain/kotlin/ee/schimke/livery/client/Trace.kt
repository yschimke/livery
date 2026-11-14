package ee.schimke.livery.client

/**
 * Named sections in the platform's trace, so a Perfetto trace of an app shows where Livery's time goes:
 * planning, binding, reading a template, preparing its document, loading its fonts and images. Every
 * section is named `livery:<phase>[:<design or contract>]`, so one template's work can be told from
 * another's and added up per phase.
 *
 * On Android a section is an `android.os.Trace` section (a synchronous one on its thread's track, an
 * [async] one on an async track, since a suspending phase can resume on another thread). Everywhere
 * else they are no-ops. With nothing recording a section costs one flag check, and its name, built by a
 * lambda, is never built.
 *
 * Sections never carry data: a name is a phase and a design id, nothing from a template's values.
 */
public object LiveryTrace {
  /** Runs [block] inside a section on the current thread. The section must not span a suspension. */
  public inline fun <T> section(name: () -> String, block: () -> T): T {
    if (!traceEnabled()) return block()
    traceBegin(name())
    try {
      return block()
    } finally {
      traceEnd()
    }
  }

  /**
   * Runs [block] inside a section that may suspend and resume on another thread, shown on an async
   * track (Android 10 and later; earlier it is not recorded).
   */
  public suspend inline fun <T> async(name: () -> String, block: suspend () -> T): T {
    if (!traceEnabled()) return block()
    val label = name()
    val cookie = traceAsyncBegin(label)
    try {
      return block()
    } finally {
      traceAsyncEnd(label, cookie)
    }
  }
}

@PublishedApi internal expect fun traceEnabled(): Boolean

@PublishedApi internal expect fun traceBegin(name: String)

@PublishedApi internal expect fun traceEnd()

/** Opens an async section and returns the cookie to close it with. */
@PublishedApi internal expect fun traceAsyncBegin(name: String): Int

@PublishedApi internal expect fun traceAsyncEnd(name: String, cookie: Int)
