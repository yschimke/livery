package ee.schimke.livery.client

import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.ManifestSignature
import kotlin.io.encoding.Base64

/**
 * A publisher key the app trusts, optionally limited to some contracts: an ads partner's key may
 * sign `ads/` templates and nothing else, while the app's own key signs everything.
 */
data class TrustedKey(
  val keyId: String,
  val publicKey: ByteArray,
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
  fun verify(manifestBytes: ByteArray, signature: ManifestSignature?, parse: (ByteArray) -> Manifest): Verification {
    val key: TrustedKey? = when (policy) {
      TrustPolicy.AllowUnsigned -> null
      is TrustPolicy.Signed -> {
        signature ?: return Verification.Rejected("manifest is unsigned")
        if (signature.alg != "Ed25519") return Verification.Rejected("unsupported alg ${signature.alg}")
        val k = policy.keys.firstOrNull { it.keyId == signature.keyId }
          ?: return Verification.Rejected("untrusted key ${signature.keyId}")
        val sig = runCatching { Base64.decode(signature.signature) }.getOrNull()
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
