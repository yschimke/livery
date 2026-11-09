package ee.schimke.livery.client

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.protocol.TemplateEvent
import ee.schimke.livery.protocol.TemplateRef
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Keeps the experimental shared fixture tied to real capture bytes and Livery's existing gates. */
class ComponentPreviewFixtureTest {
  private val samples = File("../samples")
  private val json = Json { ignoreUnknownKeys = true }
  private val descriptor = read("component-preview/preview.json")
  private val cases = read("component-preview/cases.json")
  private val contract = json.decodeFromJsonElement(Contract.serializer(), read("two-way/contracts/quantity.json"))
  private val ref = json.decodeFromJsonElement(TemplateRef.serializer(), descriptor.getValue("template"))
  private val data = descriptor.getValue("data").jsonObject
  private val bytes = File(samples, "two-way/designs/quantity-stepper/in-app.rc").readBytes()
  private val target = json.decodeFromJsonElement(Target.serializer(), read("two-way/designs/quantity-stepper/in-app.json").getValue("target"))
  private val entry = TemplateEntry(ref.sha256!!, bytes.size.toLong(), ref.designId!!, contract.ref, target)
  private val design = Design(ref.designId!!, contract.ref, "Quantity stepper")

  private fun read(path: String): JsonObject = json.parseToJsonElement(File(samples, path).readText()).jsonObject

  @Test fun descriptorPinsTheRealEligibleArtifactAndBindsItsData() {
    val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    assertEquals(actualHash, ref.sha256, "recapturing requires an intentional preview fixture revision")
    assertEquals(contract.ref, ref.contract)
    assertIs<BindResult.Ok>(ContractBinder.bind(contract, data))
    val manifest = Manifest(catalog = "two-way", serial = 1, generatedAtEpochMs = 0, expiresAtEpochMs = 1,
      contracts = listOf(contract), designs = listOf(design), templates = listOf(entry))
    val caps = PlayerCapabilities("in-app", 7, setOf("androidx"))
    val query = TemplateQuery(contract.ref, caps, widthDp = 360, designId = ref.designId)
    assertEquals(entry, TemplateSelector.select(manifest, query))
    assertNull(TemplateSelector.select(manifest, query.copy(caps = caps.copy(maxRcApiLevel = 6))))
    assertNull(TemplateSelector.select(manifest, query.copy(widthDp = 100)))
  }

  @Test fun fixtureDataUsesTheExistingBindingGate() {
    for (element in cases.getValue("dataCases").jsonArray) {
      val case = element.jsonObject
      assertEquals(case.getValue("accepted").jsonPrimitive.boolean,
        ContractBinder.bind(contract, case.getValue("data").jsonObject) is BindResult.Ok,
        case.getValue("name").jsonPrimitive.content)
    }
  }

  @Test fun actionTraceRejectsInvalidWritesAndKeepsPreviewsIndependent() = runTest {
    val states = mutableMapOf("a" to JsonObject(data.toMap()), "b" to JsonObject(data.toMap()))
    val events = mutableListOf<TemplateEvent>()
    for (element in cases.getValue("actionTrace").jsonArray) {
      val step = element.jsonObject
      val session = step.getValue("session").jsonPrimitive.content
      val before = states.getValue(session)
      val bindings = assertIs<BindResult.Ok>(ContractBinder.bind(contract, before)).bindings
      val plan = RenderPlan(LoadedTemplate(entry, design, bytes, LoadedTemplate.Origin.Bundled, contract), contract, bindings)
      val router = ActionRouter(
        handlers = mapOf("set" to { _, action, args ->
          val input = assertNotNull(contract.actions.getValue(action).input)
          states[session] = JsonObject(states.getValue(session) + (input to JsonPrimitive(args.getValue("value").toInt())))
        }),
        urlAllowlist = { false }, events = EventSink { events += it }, clock = { 1 },
      )
      router.dispatch(plan, step.getValue("action").jsonPrimitive.content,
        step.getValue("args").jsonObject.mapValues { it.value.jsonPrimitive.content })
      assertEquals(if (step.getValue("accepted").jsonPrimitive.boolean) TemplateEvent.Type.Action else TemplateEvent.Type.Error,
        events.last().type, step.toString())
      val quantities = states.mapValues { it.value.getValue("quantity").jsonPrimitive.int }
      assertEquals(step.getValue("quantities").jsonObject.mapValues { it.value.jsonPrimitive.int }, quantities, step.toString())
      states.values.forEach { assertIs<BindResult.Ok>(ContractBinder.bind(contract, it)) }
    }
  }
}
