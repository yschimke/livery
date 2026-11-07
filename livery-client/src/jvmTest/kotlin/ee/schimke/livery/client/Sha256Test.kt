package ee.schimke.livery.client

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The plain-Kotlin SHA-256 against the JVM's, across every padding boundary and a large input. */
class Sha256Test {
  private fun jvm(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  @Test fun knownVectors() {
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
  }

  @Test fun matchesTheJvmAtEveryLengthAroundABlock() {
    val r = Random(7)
    for (n in (0..200) + listOf(1_000, 65_536, 1_000_003)) {
      val b = r.nextBytes(n)
      assertEquals(jvm(b), Sha256.hex(b), "length $n")
    }
  }
}
