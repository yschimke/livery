package ee.schimke.livery.sessions.templates

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcShaderData
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.livery.instantiate.RiskyFeature
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.sessions.server.DraftResult
import ee.schimke.livery.sessions.server.SessionFile
import ee.schimke.livery.sessions.server.SessionServer
import ee.schimke.livery.sessions.server.sessionRoutes
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Against the sample catalog's real `live/status-tile@1` and its in-app document. */
class TemplateKindTest {
  private val samples = File(requireNotNull(System.getProperty("livery.samples")) { "run through Gradle: gradle/sample-catalogs.gradle.kts" })
  private val json = Json { ignoreUnknownKeys = true }
  private val contract = json.decodeFromString(Contract.serializer(), File(samples, "contracts/status-tile.json").readText())
  private val target = Target(surface = "in-app", rcApiLevel = 7, profiles = listOf("androidx"))
  private val tile = File(samples, "designs/tile-default/in-app.rc").readBytes().toByteString()

  /** [tile] with a runtime shader in it: a feature no unsigned draft may use by default. */
  private val withShader: ByteString =
    RcDocumentCodec.decode(tile.toByteArray(), RcWireLimits()).let { d ->
      RcDocumentCodec.encode(d.copy(operations = listOf(RcTextData(9001, "half4 main(float2 p) { return half4(1); }"), RcShaderData(9002, 9001, emptyMap(), emptyMap(), emptyMap())) + d.operations)).toByteString()
    }

  private fun ByteString.base64() = Base64.getEncoder().encodeToString(toByteArray())

  private fun draft(bytes: ByteString) = buildJsonObject { put("documentBase64", bytes.base64()) }

  private fun refusal(result: DraftResult) = assertIs<DraftResult.Refused>(result).reason

  @Test
  fun theGateIsTheOneForUnsignedDocuments() {
    val kind = TemplateKind()
    SessionServer(listOf(kind))
    val created = kind.create(contract, target, "Tile")
    // A contract without the tile's inputs: the same document now reads what it doesn't declare.
    val bare = kind.create(contract.copy(inputs = emptyMap(), lists = emptyMap()), target, "Bare")
    assertTrue("doesn't declare" in refusal(kind.submit(bare.agent, 0, draft(tile))))
    assertEquals("It uses [shaders], which drafts here may not.", refusal(kind.submit(created.agent, 0, draft(withShader))))
    assertEquals("That isn't a Remote Compose document.", refusal(kind.submit(created.agent, 0, draft(ByteString.of(1, 2, 3)))))
    assertTrue("over" in refusal(TemplateKind(maxBytes = 100).let { small -> SessionServer(listOf(small)); small.submit(small.create(contract, target, "x").agent, 0, draft(tile)) }))
    // The real tile passes, at the revision read, and not again at the same revision.
    assertIs<DraftResult.Accepted>(kind.submit(created.agent, 0, draft(tile)))
    assertTrue("revision 1" in refusal(kind.submit(created.agent, 0, draft(tile))))
    assertEquals(1, kind.draft(created.owner)!!.revision)
  }

  @Test
  fun anAppMayAllowAFeature() {
    val kind = TemplateKind(allowed = setOf(RiskyFeature.Shaders))
    SessionServer(listOf(kind))
    val created = kind.create(contract, target, "Tile")
    assertIs<DraftResult.Accepted>(kind.submit(created.agent, 0, draft(withShader)))
  }

  private suspend fun HttpClient.mcp(tool: String, arguments: JsonObject): JsonObject {
    val response =
      post("/api/agent/mcp") {
        setBody(
          buildJsonObject {
              put("jsonrpc", "2.0")
              put("id", 1)
              put("method", "tools/call")
              putJsonObject("params") {
                put("name", tool)
                put("arguments", arguments)
              }
            }
            .toString()
        )
      }
    return json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("result").jsonObject
  }

  @Test
  fun aPersonStartsASessionAndPlaysTheAgentsDraft() = testApplication {
    val kind = TemplateKind()
    val server = SessionServer(listOf(kind), maxWaitMs = 1_000)
    application {
      routing {
        sessionRoutes(server)
        templateSessionRoutes(server, kind)
      }
    }
    val started =
      client.post("/api/agent/templates") {
        setBody(
          buildJsonObject {
              put("contract", json.encodeToJsonElement(Contract.serializer(), contract))
              put("target", json.encodeToJsonElement(Target.serializer(), target))
              put("title", "A calmer status tile")
            }
            .toString()
        )
      }
    assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())
    val body = json.parseToJsonElement(started.bodyAsText()).jsonObject
    val owner = body.getValue("owner").jsonPrimitive.content
    // Nothing to play yet.
    assertEquals(HttpStatusCode.NoContent, client.get("/api/agent/templates/draft") { bearerAuth(owner) }.status)

    val session =
      client.mcp("connect", buildJsonObject { put("code", body.getValue("joinCode").jsonPrimitive.content) })
        .getValue("structuredContent").jsonObject.getValue("session").jsonPrimitive.content
    val read = client.mcp("get_session", buildJsonObject { put("session", session) }).getValue("structuredContent").jsonObject
    assertEquals("live/status-tile", read.getValue("contract").jsonObject.getValue("id").jsonPrimitive.content)
    assertEquals("template", read.getValue("kind").jsonPrimitive.content)

    // A dry run, then the draft.
    val checked = client.mcp("check_draft", buildJsonObject { put("session", session); put("documentBase64", withShader.base64()) })
    assertTrue(checked["isError"]!!.jsonPrimitive.boolean)
    val submitted = client.mcp("submit_draft", buildJsonObject { put("session", session); put("revision", 0); put("documentBase64", tile.base64()); put("summary", "Calmer") })
    assertEquals("submitted", submitted.getValue("structuredContent").jsonObject.getValue("status").jsonPrimitive.content)

    // The person plays exactly what the agent sent.
    val played = client.get("/api/agent/templates/draft") { bearerAuth(owner) }
    assertEquals("1", played.headers["X-Livery-Revision"])
    assertEquals("X-Livery-Revision, X-Livery-Sha256", played.headers["Access-Control-Expose-Headers"], "readable by a page on another origin")
    assertContentEquals(tile.toByteArray(), played.bodyAsBytes())
    val state = json.parseToJsonElement(client.get("/api/agent/session") { bearerAuth(owner) }.bodyAsText()).jsonObject
    assertEquals(1, state.getValue("revision").jsonPrimitive.long)
  }

  @Test
  fun aRestartKeepsTheDraft(): Unit = runBlocking {
    val file = Files.createTempDirectory("sessions").resolve("sessions.json").toFile()
    val kind = TemplateKind()
    val server = SessionServer(listOf(kind))
    val created = kind.create(contract, target, "Tile")
    server.start(TemplateKind.ID, created.owner, created.agent, "Tile")
    kind.submit(created.agent, 0, draft(tile))
    SessionFile(file, server).save()

    val later = TemplateKind()
    val restarted = SessionServer(listOf(later))
    SessionFile(file, restarted).load()
    val draft = assertNotNull(later.draft(created.owner))
    assertEquals(1, draft.revision)
    assertEquals(tile, draft.bytes)
    assertNotNull(restarted.relay.ownerState(created.owner))
  }
}
