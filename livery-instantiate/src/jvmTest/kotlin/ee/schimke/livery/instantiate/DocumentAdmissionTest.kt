package ee.schimke.livery.instantiate

import okio.ByteString.Companion.toByteString
import okio.ByteString
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcShaderData
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.TemplateSignatures
import ee.schimke.livery.client.TrustedKey
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.ManifestSignature
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * PROTOCOL.md §3.4–3.5: documents that arrive with a message. Against the sample catalog's real
 * `live/status-tile@1` in-app document, and copies of it with a shader or an embedded image added.
 */
class DocumentAdmissionTest {
  private val samples = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" })
  private val contract = Json { ignoreUnknownKeys = true }.decodeFromString(Contract.serializer(), File(samples, "contracts/status-tile.json").readText())
  private val tile = File(samples, "designs/tile-default/in-app.rc").readBytes().toByteString()

  private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
  private val key = TrustedKey("app", pair.public.encoded.takeLast(32).toByteArray().toByteString())

  private fun sha(bytes: ByteString) = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }

  private fun sign(bytes: ByteString, ref: String = contract.ref, keyId: String = "app") = ManifestSignature(
    keyId = keyId,
    signature = Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(pair.private); update(TemplateSignatures.message(ref, sha(bytes)).toByteArray()); sign() }),
  )

  /** [tile] with a runtime shader defined in it: a GPU program, unused or not. */
  private val withShader: ByteString = RcDocumentCodec.decode(tile.toByteArray(), RcWireLimits()).let { d ->
    RcDocumentCodec.encode(d.copy(operations = listOf(RcTextData(9001, "half4 main(float2 p) { return half4(1); }"), RcShaderData(9002, 9001, emptyMap(), emptyMap(), emptyMap())) + d.operations)).toByteString()
  }

  /** [tile] with image bytes in it, which the client's image rules never see. */
  private val withPixels: ByteString = RcDocumentCodec.decode(tile.toByteArray(), RcWireLimits()).let { d ->
    RcDocumentCodec.encode(d.copy(operations = listOf(RcBitmapData(9003, 1, 1, RcBitmapData.TYPE_RAW8888, RcBitmapData.ENCODING_INLINE, byteArrayOf(0, 0, 0, 0))) + d.operations)).toByteString()
  }

  private fun admission(signedFeatures: Set<RiskyFeature> = emptySet(), agent: AgentBytesPolicy? = null, keys: List<TrustedKey> = listOf(key)) =
    DocumentAdmission(JavaCrypto, keys, signedFeatures, agent)

  private fun refused(a: Admission) = assertIs<Admission.Refused>(a).reason

  @Test fun featuresAreReadFromTheDocumentsOperations() {
    assertEquals(emptySet(), TemplateInspector.facts(tile)!!.features)
    assertEquals(setOf(RiskyFeature.Shaders), TemplateInspector.facts(withShader)!!.features)
    assertEquals(setOf(RiskyFeature.EmbeddedImages), TemplateInspector.facts(withPixels)!!.features)
  }

  @Test fun unsignedBytesNeedTheAppsPolicy() = runBlocking {
    assertEquals("unsigned documents are not allowed", refused(admission().admit(tile, contract, null)))
    assertEquals("unsigned documents may not play as live/status-tile@1", refused(admission(agent = AgentBytesPolicy(setOf("promo/"))).admit(tile, contract, null)))
    val ok = assertIs<Admission.Admitted>(admission(agent = AgentBytesPolicy(setOf("live/"))).admit(tile, contract, null))
    assertNull(ok.signedBy)
    assertEquals(sha(tile), ok.sha256)
  }

  @Test fun unsignedBytesMayUseNoRiskyFeatureWhateverTheApp() = runBlocking {
    val anything = admission(signedFeatures = RiskyFeature.entries.toSet(), agent = AgentBytesPolicy(setOf("live/")))
    assertEquals("an unsigned document may not use [shaders]", refused(anything.admit(withShader, contract, null)))
    assertEquals("an unsigned document may not use [embedded-images]", refused(anything.admit(withPixels, contract, null)))
  }

  @Test fun unsignedBytesAreSmallAndStayInTheirSlot() = runBlocking {
    assertEquals("${tile.size} bytes is over the unsigned limit of 100", refused(admission(agent = AgentBytesPolicy(setOf("live/"), maxBytes = 100)).admit(tile, contract, null)))
    // The document's `toggle` as open-url: it would leave the slot, so the app must name the kind.
    val leaving = contract.copy(actions = contract.actions + ("toggle" to contract.actions.getValue("toggle").copy(kind = "open-url")))
    assertEquals("an unsigned document may not emit [toggle] ([open-url])", refused(admission(agent = AgentBytesPolicy(setOf("live/"))).admit(tile, leaving, null)))
    assertIs<Admission.Admitted>(admission(agent = AgentBytesPolicy(setOf("live/"), allowedKinds = setOf("open-url"))).admit(tile, leaving, null))
    Unit
  }

  @Test fun signedBytesMayUseTheRiskyFeaturesTheAppOptsInto() = runBlocking {
    assertEquals("app", assertIs<Admission.Admitted>(admission().admit(tile, contract, sign(tile))).signedBy)
    assertEquals("uses [shaders], which the app hasn't allowed for signed documents", refused(admission().admit(withShader, contract, sign(withShader))))
    val shaders = assertIs<Admission.Admitted>(admission(signedFeatures = setOf(RiskyFeature.Shaders)).admit(withShader, contract, sign(withShader)))
    assertEquals(setOf(RiskyFeature.Shaders), shaders.features)
    assertEquals("uses [embedded-images], which the app hasn't allowed for signed documents", refused(admission(signedFeatures = setOf(RiskyFeature.Shaders)).admit(withPixels, contract, sign(withPixels))))
  }

  @Test fun aSignatureCoversTheseBytesAsThisContract() = runBlocking {
    assertEquals("signature: bad signature", refused(admission().admit(withShader, contract, sign(tile))), "other bytes")
    assertEquals("signature: bad signature", refused(admission().admit(tile, contract, sign(tile, ref = "live/status-tile@2"))), "another contract")
    assertEquals("signature: untrusted key other", refused(admission().admit(tile, contract, sign(tile, keyId = "other"))))
    assertEquals("signature: key app may not sign live/status-tile@1", refused(admission(keys = listOf(key.copy(contractPrefixes = setOf("ads/")))).admit(tile, contract, sign(tile))))
  }

  @Test fun theDocumentIsHeldToItsContract() = runBlocking {
    val policy = AgentBytesPolicy(setOf("live/", "promo/"))
    assertEquals("not a Remote Compose document", refused(admission(agent = policy).admit(byteArrayOf(1, 2, 3).toByteString(), contract, null)))
    val banner = Json { ignoreUnknownKeys = true }.decodeFromString(Contract.serializer(), File(samples, "contracts/promo-banner.json").readText())
    val reason = refused(admission(agent = policy).admit(tile, banner, null))
    assertEquals(true, reason.startsWith("reads [") && reason.endsWith("which promo/banner@1 doesn't declare"), reason)
  }
}
