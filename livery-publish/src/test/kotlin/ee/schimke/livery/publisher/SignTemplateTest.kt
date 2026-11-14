package ee.schimke.livery.publisher

import okio.ByteString.Companion.toByteString
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.TemplateSignatures
import ee.schimke.livery.client.TemplateVerification
import ee.schimke.livery.client.TrustedKey
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/** PROTOCOL.md §3.4: a publisher signs one document for one contract, and a client verifies it. */
class SignTemplateTest {
  @Test fun aSignedDocumentVerifiesForItsContractOnly() = runBlocking {
    val keys = Signing.generate()
    val doc = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" }, "designs/banner-classic/in-app.rc").readBytes().toByteString()
    val sig = Signing.signTemplate(doc, "promo/banner@1", keys.privatePkcs8Base64, "app")
    val trusted = listOf(TrustedKey("app", Base64.getDecoder().decode(keys.publicRawBase64).toByteString()))
    val sha = MessageDigest.getInstance("SHA-256").digest(doc.toByteArray()).joinToString("") { "%02x".format(it) }
    assertEquals("app", assertIs<TemplateVerification.Accepted>(TemplateSignatures.verify(JavaCrypto, trusted, "promo/banner@1", sha, sig)).key.keyId)
    assertEquals(TemplateVerification.Rejected("bad signature"), TemplateSignatures.verify(JavaCrypto, trusted, "promo/banner@2", sha, sig))
  }
}
