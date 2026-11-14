package ee.schimke.livery.client

// No platform trace here: sections are never recorded, and their names are never built.
internal actual fun traceEnabled(): Boolean = false

internal actual fun traceBegin(name: String) = Unit

internal actual fun traceEnd() = Unit

internal actual fun traceAsyncBegin(name: String): Int = -1

internal actual fun traceAsyncEnd(name: String, cookie: Int) = Unit
