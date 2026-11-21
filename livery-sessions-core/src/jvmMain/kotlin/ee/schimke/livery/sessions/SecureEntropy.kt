package ee.schimke.livery.sessions

import java.security.SecureRandom
import java.util.Base64

/** [Entropy] from the platform's [SecureRandom]: what a JVM session server uses. */
class SecureEntropy(private val random: SecureRandom = SecureRandom()) : Entropy {
  override fun nextInt(bound: Int): Int = random.nextInt(bound)

  override fun token(): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}
