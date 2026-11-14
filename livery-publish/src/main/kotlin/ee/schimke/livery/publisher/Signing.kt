package ee.schimke.livery.publisher

import okio.ByteString
import ee.schimke.livery.client.TemplateSignatures
import ee.schimke.livery.protocol.ManifestSignature
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Ed25519 publisher keys: private as PKCS#8, public as the raw 32 bytes clients embed. */
object Signing {
  data class KeyPairText(val privatePkcs8Base64: String, val publicRawBase64: String)

  fun generate(): KeyPairText {
    val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val raw = kp.public.encoded.takeLast(32).toByteArray()
    return KeyPairText(Base64.getEncoder().encodeToString(kp.private.encoded), Base64.getEncoder().encodeToString(raw))
  }

  fun sign(manifestBytes: ByteString, privatePkcs8Base64: String, keyId: String): ManifestSignature {
    val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privatePkcs8Base64.trim())))
    val sig = Signature.getInstance("Ed25519").run { initSign(key); update(manifestBytes.toByteArray()); sign() }
    return ManifestSignature(keyId = keyId, signature = Base64.getEncoder().encodeToString(sig))
  }

  /**
   * A template signature (PROTOCOL.md §3.4): one [document] signed to play as [contractRef]
   * (`id@major`), for sending with a message instead of through a catalog.
   */
  fun signTemplate(document: ByteString, contractRef: String, privatePkcs8Base64: String, keyId: String): ManifestSignature {
    val sha = document.sha256().hex()
    return sign(TemplateSignatures.message(contractRef, sha), privatePkcs8Base64, keyId)
  }
}
