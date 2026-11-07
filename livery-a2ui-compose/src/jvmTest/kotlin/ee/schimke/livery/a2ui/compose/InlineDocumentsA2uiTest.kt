package ee.schimke.livery.a2ui.compose

import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcShaderData
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.TemplateSignatures
import ee.schimke.livery.client.TrustedKey
import ee.schimke.livery.instantiate.AgentBytesPolicy
import ee.schimke.livery.instantiate.DocumentAdmission
import ee.schimke.livery.instantiate.RiskyFeature
import ee.schimke.livery.protocol.TemplateEvent
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The document itself in the A2UI message: `template: {inline, signature}` (PROTOCOL.md §3.4–3.5). */
object InlineFixtures {
  private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
  val key = TrustedKey("app", pair.public.encoded.takeLast(32).toByteArray())

  /** The sample's real promo banner document. */
  val banner: ByteArray = File(System.getProperty("livery.samples") ?: "../samples/catalog", "designs/banner-classic/in-app.rc").readBytes()

  /** The banner with a runtime shader defined in it. */
  val bannerWithShader: ByteArray = RcDocumentCodec.decode(banner, RcWireLimits()).let { d ->
    RcDocumentCodec.encode(d.copy(operations = listOf(RcTextData(9001, "half4 main(float2 p) { return half4(1); }"), RcShaderData(9002, 9001, emptyMap(), emptyMap(), emptyMap())) + d.operations))
  }

  /** The banner with image bytes in it. */
  val bannerWithPixels: ByteArray = RcDocumentCodec.decode(banner, RcWireLimits()).let { d ->
    RcDocumentCodec.encode(d.copy(operations = listOf(RcBitmapData(9003, 1, 1, RcBitmapData.TYPE_RAW8888, RcBitmapData.ENCODING_INLINE, byteArrayOf(0, 0, 0, 0))) + d.operations))
  }

  fun signature(bytes: ByteArray, contract: String = "promo/banner@1"): String {
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update(TemplateSignatures.message(contract, sha)); sign() }
    return """{"keyId":"app","signature":"${Base64.getEncoder().encodeToString(sig)}"}"""
  }
}

/** Shared: a banner whose document is the [template] JSON the agent sent. */
@OptIn(ExperimentalTestApi::class)
abstract class InlineSurfaceTest(documents: DocumentAdmission?) : LiveryA2uiSurfaceTest(documents) {
  protected fun components(template: String) =
    """
    {"version":"v0.9","updateComponents":{"surfaceId":"$SURFACE","components":[
      {"id":"root","component":"PromoBanner","headline":"Try Premium free","cta":"Start trial","template":$template}
    ]}}
    """.trimIndent()

  protected fun inline(bytes: ByteArray, signature: String? = null) =
    """{"inline":"${Base64.getEncoder().encodeToString(bytes)}"${signature?.let { ""","signature":$it""" } ?: ""}}"""

  /** Plays it, and returns the design id its impression names. */
  protected fun ComposeUiTest.plays(template: String): String? {
    send(create, components(template))
    show()
    return events.first { it.type == TemplateEvent.Type.Impression }.designId
  }

  /** Refuses it: the agent is told why, and nothing draws in its place. */
  protected fun ComposeUiTest.refuses(template: String): String {
    send(create, components(template))
    setContent { MaterialTheme { A2uiSurface(surfaceModel = surface(), transitionSpec = null) } }
    waitUntil(timeoutMillis = 10_000) { outbound.any { it is A2uiClientErrorMessage } }
    assertTrue(events.none { it.type == TemplateEvent.Type.Impression }, "no other template draws instead (§5.3)")
    return outbound.filterIsInstance<A2uiClientErrorMessage>().first().message
  }
}

/** An app that allows unsigned promo documents, and shaders in signed ones. */
@OptIn(ExperimentalTestApi::class)
class InlineDocumentsA2uiTest : InlineSurfaceTest(
  DocumentAdmission(JavaCrypto, listOf(InlineFixtures.key), signedFeatures = setOf(RiskyFeature.Shaders), agentBytes = AgentBytesPolicy(setOf("promo/"))),
) {
  @Test fun unsignedBytesPlayUnderTheAppsPolicy() = runComposeUiTest {
    assertEquals("a2ui:unsigned", plays(inline(InlineFixtures.banner)), "its events say they were unsigned")
  }

  @Test fun unsignedBytesNeverUseAShader() = runComposeUiTest {
    val why = refuses(inline(InlineFixtures.bannerWithShader))
    assertTrue("an unsigned document may not use [shaders]" in why, why)
  }

  @Test fun signedBytesUseTheShaderTheAppOptedInto() = runComposeUiTest {
    assertEquals("a2ui:signed:app", plays(inline(InlineFixtures.bannerWithShader, InlineFixtures.signature(InlineFixtures.bannerWithShader))))
  }

  @Test fun signedBytesStillUseOnlyWhatTheAppOptedInto() = runComposeUiTest {
    val why = refuses(inline(InlineFixtures.bannerWithPixels, InlineFixtures.signature(InlineFixtures.bannerWithPixels)))
    assertTrue("uses [embedded-images], which the app hasn't allowed for signed documents" in why, why)
  }

  @Test fun aSignatureForOtherBytesIsRefused() = runComposeUiTest {
    val why = refuses(inline(InlineFixtures.bannerWithShader, InlineFixtures.signature(InlineFixtures.banner)))
    assertTrue("signature: bad signature" in why, why)
  }

  @Test fun theAgentsDataStillPassesTheContract() = runComposeUiTest {
    send(create, components(inline(InlineFixtures.banner)).replace("\"Try Premium free\"", "\"" + "x".repeat(41) + "\""))
    setContent { MaterialTheme { A2uiSurface(surfaceModel = surface(), transitionSpec = null) } }
    waitUntil(timeoutMillis = 10_000) { outbound.any { it is A2uiClientErrorMessage } }
    assertTrue(outbound.filterIsInstance<A2uiClientErrorMessage>().any { "headline" in it.message })
  }
}

/** An app with no document policy: nothing the agent sends plays, signed or not. */
@OptIn(ExperimentalTestApi::class)
class InlineDocumentsOffA2uiTest : InlineSurfaceTest(null) {
  @Test fun noDocumentPlaysWithoutThePolicy() = runComposeUiTest {
    val why = refuses(inline(InlineFixtures.banner, InlineFixtures.signature(InlineFixtures.banner)))
    assertTrue("the app accepts no documents sent with a message" in why, why)
  }
}

/** Signed documents are trusted, but with no risky features opted into. */
@OptIn(ExperimentalTestApi::class)
class InlineDocumentsSignedOnlyA2uiTest : InlineSurfaceTest(DocumentAdmission(JavaCrypto, listOf(InlineFixtures.key))) {
  @Test fun unsignedBytesAreRefusedWithoutAnAgentPolicy() = runComposeUiTest {
    assertTrue("unsigned documents are not allowed" in refuses(inline(InlineFixtures.banner)))
  }

  @Test fun aShaderNeedsTheAppsOptInEvenWhenSigned() = runComposeUiTest {
    val why = refuses(inline(InlineFixtures.bannerWithShader, InlineFixtures.signature(InlineFixtures.bannerWithShader)))
    assertTrue("uses [shaders], which the app hasn't allowed for signed documents" in why, why)
  }

  @Test fun aPlainSignedDocumentPlays() = runComposeUiTest {
    assertEquals("a2ui:signed:app", plays(inline(InlineFixtures.banner, InlineFixtures.signature(InlineFixtures.banner))))
  }
}
