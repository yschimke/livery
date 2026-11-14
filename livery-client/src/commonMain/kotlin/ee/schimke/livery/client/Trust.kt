package ee.schimke.livery.client

import okio.ByteString.Companion.encodeUtf8
import okio.ByteString
import okio.ByteString.Companion.toByteString
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.ManifestSignature
import kotlin.io.encoding.Base64

/**
 * A publisher key the app trusts, optionally limited to some contracts: an ads partner's key may
 * sign `ads/` templates and nothing else, while the app's own key signs everything.
 */
data class TrustedKey(
  val keyId: String,
  val publicKey: ByteString,
  val contractPrefixes: Set<String> = emptySet(),
) {
  fun allows(contractRef: String): Boolean = contractPrefixes.isEmpty() || contractPrefixes.any { contractRef.startsWith(it) }
}

sealed interface TrustPolicy {
  /** Development and preview channels only: never in a release build's production slots. */
  data object AllowUnsigned : TrustPolicy
  data class Signed(val keys: List<TrustedKey>) : TrustPolicy
}

sealed interface Verification {
  data class Accepted(val manifest: Manifest, val key: TrustedKey?) : Verification
  data class Rejected(val reason: String) : Verification
}

class ManifestVerifier(private val crypto: Crypto, private val policy: TrustPolicy) {
  suspend fun verify(manifestBytes: ByteString, signature: ManifestSignature?, parse: (ByteString) -> Manifest): Verification {
    val key: TrustedKey? = when (policy) {
      TrustPolicy.AllowUnsigned -> null
      is TrustPolicy.Signed -> {
        signature ?: return Verification.Rejected("manifest is unsigned")
        if (signature.alg != "Ed25519") return Verification.Rejected("unsupported alg ${signature.alg}")
        val k = policy.keys.firstOrNull { it.keyId == signature.keyId }
          ?: return Verification.Rejected("untrusted key ${signature.keyId}")
        val sig = runCatching { Base64.decode(signature.signature).toByteString() }.getOrNull()
          ?: return Verification.Rejected("signature is not base64")
        if (!crypto.verifyEd25519(k.publicKey, manifestBytes, sig)) return Verification.Rejected("bad signature")
        k
      }
    }
    val manifest = runCatching { parse(manifestBytes) }.getOrElse { return Verification.Rejected("unparseable: ${it.message}") }
    if (key != null) {
      val outside = (manifest.templates.map { it.contract } + manifest.designs.map { it.contract }).filterNot(key::allows)
      if (outside.isNotEmpty()) return Verification.Rejected("key ${key.keyId} may not sign ${outside.distinct()}")
    }
    return Verification.Accepted(manifest, key)
  }
}

/**
 * PROTOCOL.md §3.4: a signature over one document rather than a manifest, for a document that
 * arrives with a message instead of through a catalog. It is Ed25519 over
 * `livery-template:<contract id@major>:<sha256>` ([message]), by the same trusted keys, held to the
 * same contract prefixes, so a key that may sign `ads/` manifests may sign `ads/` documents and
 * nothing else. The hash binds the bytes and the contract binds what they may be played as.
 */
object TemplateSignatures {
  fun message(contractRef: String, sha256: String): ByteString = "livery-template:$contractRef:$sha256".encodeUtf8()

  /** The key that signed [sha256] for [contractRef], or why the signature doesn't count. */
  suspend fun verify(crypto: Crypto, keys: List<TrustedKey>, contractRef: String, sha256: String, signature: ManifestSignature): TemplateVerification {
    if (signature.alg != "Ed25519") return TemplateVerification.Rejected("unsupported alg ${signature.alg}")
    val key = keys.firstOrNull { it.keyId == signature.keyId } ?: return TemplateVerification.Rejected("untrusted key ${signature.keyId}")
    val sig = runCatching { Base64.decode(signature.signature).toByteString() }.getOrNull() ?: return TemplateVerification.Rejected("signature is not base64")
    if (!crypto.verifyEd25519(key.publicKey, message(contractRef, sha256), sig)) return TemplateVerification.Rejected("bad signature")
    if (!key.allows(contractRef)) return TemplateVerification.Rejected("key ${key.keyId} may not sign $contractRef")
    return TemplateVerification.Accepted(key)
  }
}

sealed interface TemplateVerification {
  data class Accepted(val key: TrustedKey) : TemplateVerification
  data class Rejected(val reason: String) : TemplateVerification
}
