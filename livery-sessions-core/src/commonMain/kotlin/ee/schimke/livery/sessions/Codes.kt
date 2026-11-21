package ee.schimke.livery.sessions

/**
 * Where a session's secrets come from: the short codes a person reads aloud or types from another
 * screen, and the long capabilities each side keeps. A server supplies a secure source
 * ([SecureEntropy] on the JVM); tests supply a fixed one.
 */
interface Entropy {
  /** [bound] > 0; uniformly distributed in `0 until bound`. */
  fun nextInt(bound: Int): Int

  /** An unguessable capability: at least 128 bits, URL-safe. */
  fun token(): String
}

/** The short codes both sides exchange (SESSIONS.md, Model). */
object Codes {
  /** No 0/O, 1/I/L: a code is read aloud or typed from another screen. */
  const val ALPHABET: String = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
  const val LENGTH: Int = 8

  /** A new code in its normalized form ([normalize]); [format] it for showing. */
  fun random(entropy: Entropy): String = buildString {
    repeat(LENGTH) { append(ALPHABET[entropy.nextInt(ALPHABET.length)]) }
  }

  /** `K7QM4MD2` as people see it: `K7QM-4MD2`. */
  fun format(code: String): String = code.take(4) + "-" + code.drop(4)

  /** Upper-cased, separators dropped; null unless it is [LENGTH] code characters. */
  fun normalize(code: String): String? =
    code
      .uppercase()
      .filter { it.isLetterOrDigit() }
      .takeIf { c -> c.length == LENGTH && c.all { it in ALPHABET } }

  /** An agent's name as the person sees it: plain, short, "Agent" when it gave none. */
  fun cleanName(name: String?): String =
    name?.filter { it.isLetterOrDigit() || it in " .-_()" }?.trim()?.take(40)?.ifEmpty { null }
      ?: "Agent"
}
