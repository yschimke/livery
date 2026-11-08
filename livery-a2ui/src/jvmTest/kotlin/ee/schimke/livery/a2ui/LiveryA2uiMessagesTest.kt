package ee.schimke.livery.a2ui

import ee.schimke.livery.protocol.Contract
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

class LiveryA2uiMessagesTest {
  private val root = File(System.getProperty("user.dir")).resolve("..").canonicalFile
  private val read = Json { ignoreUnknownKeys = true }
  private val contracts = listOf("samples/catalog/contracts/promo-banner.json", "samples/two-way/contracts/quantity.json")
    .map { read.decodeFromString(Contract.serializer(), File(root, it).readText()) }
  private val promo = contracts.first { it.id == "promo/banner" }

  private fun message(vararg components: String) = Json.parseToJsonElement(
    """{"version":"v0.9","updateComponents":{"surfaceId":"s","components":[${components.joinToString(",")}]}}""",
  )

  private fun problems(m: JsonElement, data: String? = null, designs: Map<String, Set<String>>? = null) =
    LiveryA2uiMessages.validate(m, contracts, data?.let(Json::parseToJsonElement), designs)

  @Test fun queryAndNamedFormsAreValidatedBeforeRendering() {
    for (template in listOf("""{"query":{"offer":"sale"}}""", """{"sha256":"${"a".repeat(64)}"}""")) {
      assertEquals(emptyList(), problems(message("""{"id":"promo","component":"PromoBanner","headline":"Hi","cta":"Go","template":$template}""")))
    }
    for (template in listOf("""{"query":42}""", """{"query":{},"url":"https://agent.invalid/"}""", """{"sha256":"bad"}""", """{"query":{},"inline":"eA=="}""")) {
      assertTrue(problems(message("""{"id":"promo","component":"PromoBanner","headline":"Hi","cta":"Go","template":$template}""")).isNotEmpty())
    }
  }

  @Test fun aGoodComponentHasNoProblems() {
    val m = message("""{"id":"root","component":"Column","children":["promo"],"anything":1}""",
      """{"id":"promo","component":"PromoBanner","headline":{"path":"/t"},"cta":"Shop","actions":{"cta":{"event":{"name":"go"}}},"design":"banner-bold"}""")
    assertEquals(emptyList(), problems(m), "a basic component is A2UI's to check")
  }

  @Test fun reportsWhatAClientWouldRefuse() {
    val p = problems(message("""{"id":"promo","component":"PromoBanner","headline":"${"x".repeat(200)}","colour":"red","actions":{"nope":{"event":{"name":"x"}},"cta":"go"},"design":3,"template":{"inline":"!!"}}"""))
      .map { it.property to it.message }
    val properties = p.map { it.first }.toSet()
    assertTrue(setOf("colour", "cta", "actions.nope", "actions.cta", "design", "template", "headline").all { it in properties }, p.joinToString("\n"))
    assertTrue(p.all { it.second.isNotBlank() })
  }

  @Test fun bindingsAreCheckedAgainstTheDataModelWhenGiven() {
    val m = message("""{"id":"promo","component":"PromoBanner","headline":{"path":"/offer/title"},"cta":"Go"}""")
    assertEquals(emptyList(), problems(m), "without the data model a binding isn't judged")
    assertEquals(emptyList(), problems(m, """{"offer":{"title":"Spring sale"}}"""))
    assertEquals(listOf("headline"), problems(m, """{"offer":{"title":"${"x".repeat(200)}"}}""").map { it.property })
    assertTrue(problems(m, """{"offer":{}}""").single().message.contains("nothing in the data model"))
  }

  @Test fun designsAreCheckedWhenTheCatalogIsKnown() {
    val m = message("""{"id":"promo","component":"PromoBanner","headline":"Hi","cta":"Go","design":"banner-nope"}""")
    assertEquals(listOf("design"), problems(m, designs = mapOf("promo/banner@1" to setOf("banner-bold"))).map { it.property })
  }

  @Test fun aContractWithNoDesignsInAKnownCatalogTakesNone() {
    val m = message("""{"id":"promo","component":"PromoBanner","headline":"Hi","cta":"Go","design":"feed-card"}""")
    assertEquals(listOf("design"), problems(m, designs = mapOf("feed/article@1" to setOf("feed-card"))).map { it.property })
  }

  @Test fun aBindingPathIsAString() {
    for (path in listOf("123", "false", "null")) {
      val m = message("""{"id":"promo","component":"PromoBanner","headline":{"path":$path},"cta":"Go"}""")
      assertEquals(listOf("headline"), problems(m).map { it.property }, path)
    }
  }

  @Test fun aTemplateMayNameItsDocumentByHash() {
    fun named(template: String) = problems(message("""{"id":"promo","component":"PromoBanner","headline":"Hi","cta":"Go","template":$template}""")).map { it.property }
    val sha = "ab".repeat(32)
    assertEquals(emptyList(), named("""{"sha256":"$sha"}"""))
    assertEquals(emptyList(), named("""{"sha256":"$sha","url":"https://catalog.example/d.rc","signature":{"keyId":"k","signature":"c2ln"}}"""))
    assertEquals(listOf("template"), named("""{"sha256":"abc"}"""))
    assertEquals(listOf("template"), named("""{"sha256":"$sha","url":"http://catalog.example/d.rc"}"""))
  }

  @Test fun aSignatureNeedsStringFields() {
    val doc = kotlin.io.encoding.Base64.encode(byteArrayOf(1, 2, 3))
    fun signed(signature: String) = problems(message(
      """{"id":"promo","component":"PromoBanner","headline":"Hi","cta":"Go","template":{"inline":"$doc","signature":$signature}}"""))
      .map { it.property }
    assertEquals(listOf("template"), signed("""{"keyId":123,"signature":false}"""))
    assertEquals(listOf("template"), signed("""{"keyId":"main","signature":"c2ln","alg":42}"""))
    assertEquals(emptyList(), signed("""{"keyId":"main","signature":"c2ln","alg":"Ed25519"}"""))
  }

  @Test fun theBuilderWritesWhatTheValidatorAccepts() {
    val c = LiveryA2ui.componentJson("promo", promo) {
      path("headline", "/offer/title"); value("cta", "Shop now"); design("banner-bold")
      event("cta", "startTrial", context = mapOf("from" to "chat"))
    }
    assertEquals("PromoBanner", c["component"].toString().trim('"'))
    assertEquals(emptyList(), problems(message(c.toString()), """{"offer":{"title":"Spring sale"}}"""))
    assertFailsWith<IllegalArgumentException> { LiveryA2ui.componentJson("x", promo) { value("colour", "red") } }
    assertFailsWith<IllegalArgumentException> { LiveryA2ui.componentJson("x", promo) { event("nope", "x") } }
    // An array of messages is checked message by message.
    assertEquals(1, problems(JsonArray(listOf(message(), message("""{"id":"p","component":"PromoBanner","cta":"Go"}""")))).size)
  }
}
