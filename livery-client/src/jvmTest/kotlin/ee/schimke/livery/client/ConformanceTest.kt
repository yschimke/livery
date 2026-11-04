package ee.schimke.livery.client

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.ImageProxy
import ee.schimke.livery.protocol.ImageRef
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.InstallPolicy
import ee.schimke.livery.protocol.ListSpec
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.ManifestSignature
import ee.schimke.livery.protocol.NamedValue
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.SizeRange
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.protocol.ValueType
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The conformance fixtures in `conformance/`: inputs and the answers this Kotlin client gives.
 * Every port (the Swift package, the TypeScript client) runs the same files and must agree.
 *
 * Run `./gradlew :livery-client:jvmTest -Plivery.conformance.update=true` to rewrite them after a
 * deliberate behaviour change; otherwise this test fails when Kotlin's answers drift from them.
 */
class ConformanceTest {
  private val dir = File(System.getProperty("user.dir")).resolve("../conformance").canonicalFile
  private val update = System.getProperty("livery.conformance.update") == "true"
  private val json = Json { prettyPrint = true; encodeDefaults = false; prettyPrintIndent = "  " }

  private fun check(name: String, actual: JsonElement) {
    val file = File(dir, name)
    val text = json.encodeToString(JsonElement.serializer(), actual) + "\n"
    if (update || !file.isFile) { dir.mkdirs(); file.writeText(text); return }
    assertEquals(file.readText(), text, "conformance/$name drifted: rerun with -Plivery.conformance.update=true if intended")
  }

  // --- selection ------------------------------------------------------------------------------

  @Serializable data class SelectionCase(val name: String, val query: Query, val installed: List<String> = emptyList(), val expect: String?)
  @Serializable data class Query(
    val contract: String, val caps: PlayerCapabilities, val widthDp: Int? = null, val heightDp: Int? = null,
    val designId: String? = null, val variant: String? = null, val theme: String? = null, val tags: Map<String, String> = emptyMap(),
  )
  @Serializable data class SelectionFixture(val manifest: Manifest, val cases: List<SelectionCase>)

  private fun t(sha: Char, design: String, target: Target, bytes: Long = 100, revoked: Boolean = false, tags: Map<String, String> = emptyMap()) =
    TemplateEntry(sha.toString().repeat(64), bytes, design, "c/tile@1", target, tags = tags, revoked = revoked)

  @Test fun selection() {
    val inApp = { level: Int -> Target(Surfaces.IN_APP, level, listOf("androidx")) }
    val m = Manifest(
      catalog = "conformance", serial = 1, generatedAtEpochMs = 0, expiresAtEpochMs = 1,
      contracts = listOf(Contract("c/tile", 1)),
      designs = listOf(
        Design("builtin", "c/tile@1", "Built-in", install = InstallPolicy.Always),
        Design("compact", "c/tile@1", "Compact", variant = "compact", install = InstallPolicy.Always, tags = mapOf("style" to "flat")),
        Design("skin", "c/tile@1", "Skin", install = InstallPolicy.OnRequest),
        Design("gone", "c/tile@1", "Gone", revoked = true),
      ),
      templates = listOf(
        t('1', "builtin", inApp(7)),
        t('2', "builtin", inApp(8)),
        t('3', "builtin", inApp(9)),
        t('4', "builtin", Target(Surfaces.LAUNCHER_WIDGET, 7, listOf("widgets"), SizeRange(110, 400, 40, 400))),
        t('5', "builtin", Target(Surfaces.LAUNCHER_WIDGET, 7, listOf("widgets"), SizeRange(110, 200, 40, 200))),
        t('6', "builtin", Target(Surfaces.WEAR_WIDGET, 7, listOf("glance-wear"), theme = "baked:dark", container = "wear:small")),
        t('7', "compact", inApp(7)),
        t('8', "skin", inApp(9)),
        t('9', "gone", inApp(8)),
        // Sorts first on a tie, so only the byte budget keeps it out.
        t('0', "builtin", inApp(8), bytes = 900_000),
      ),
    )
    val app8 = PlayerCapabilities(Surfaces.IN_APP, 8, setOf("androidx"))
    val launcher = PlayerCapabilities(Surfaces.LAUNCHER_WIDGET, 7, setOf("widgets"), livePush = false, maxBytes = 50_000)
    val wear = PlayerCapabilities(Surfaces.WEAR_WIDGET, 7, setOf("glance-wear"), livePush = false, themes = setOf("baked:dark"), container = "wear:small")
    val q = { caps: PlayerCapabilities -> Query("c/tile@1", caps) }
    val cases = listOf(
      SelectionCase("highest level the player draws", q(app8), expect = null),
      SelectionCase("chosen design", q(app8).copy(designId = "compact"), expect = null),
      SelectionCase("preferred variant", q(app8).copy(variant = "compact"), expect = null),
      SelectionCase("uninstalled skin is never automatic", q(app8.copy(maxRcApiLevel = 9)), expect = null),
      SelectionCase("installed skin is eligible; the built-in still wins a tie (choosing applies it)", q(app8.copy(maxRcApiLevel = 9)), installed = listOf("skin"), expect = null),
      SelectionCase("skin chosen explicitly", q(app8.copy(maxRcApiLevel = 9)).copy(designId = "skin"), expect = null),
      SelectionCase("revoked design falls back", q(app8).copy(designId = "gone"), expect = null),
      SelectionCase("tightest launcher fit", q(launcher).copy(widthDp = 150, heightDp = 100), expect = null),
      SelectionCase("launcher size not covered", q(launcher).copy(widthDp = 500, heightDp = 100), expect = null),
      SelectionCase("wear container and baked theme", q(wear), expect = null),
      SelectionCase("wrong wear container", q(wear.copy(container = "wear:large")), expect = null),
      SelectionCase("byte budget", q(app8.copy(maxBytes = 150)), expect = null),
      SelectionCase("tags", q(app8).copy(tags = mapOf("style" to "flat")), expect = null),
      SelectionCase("other contract major", Query("c/tile@2", app8), expect = null),
    ).map { c ->
      val auto = { d: Design -> d.install == InstallPolicy.Always || d.id in c.installed }
      val qq = c.query
      val got = TemplateSelector.select(m, TemplateQuery(qq.contract, qq.caps, qq.widthDp, qq.heightDp, qq.designId, qq.variant, qq.theme, qq.tags), automatic = auto)
      c.copy(expect = got?.sha256)
    }
    check("selection.json", json.encodeToJsonElement(SelectionFixture.serializer(), SelectionFixture(m, cases)))
  }

  // --- binding --------------------------------------------------------------------------------

  @Test fun binding() {
    val c = Contract(
      "c/card", 1,
      inputs = mapOf(
        "title" to InputSpec(ValueType.String, required = true, maxLength = 10),
        "count" to InputSpec(ValueType.Int, min = 0.0, max = 99.0),
        "at" to InputSpec(ValueType.Long),
        "level" to InputSpec(ValueType.Float, min = 0.0, max = 1.0),
        "on" to InputSpec(ValueType.Bool, default = Json.parseToJsonElement("false")),
        "accent" to InputSpec(ValueType.Color, default = Json.parseToJsonElement("\"#6750A4\"")),
        "hero" to InputSpec(ValueType.Image),
      ),
      lists = mapOf("tags" to ListSpec(mapOf("label" to InputSpec(ValueType.String, required = true)), max = 2)),
    )
    val datasets = listOf(
      "minimal" to """{"title":"Hi"}""",
      "everything" to """{"title":"Hi","count":3,"at":1793000000,"level":0.25,"on":true,"accent":"#80112233","hero":"asset:${"b".repeat(64)}","tags":[{"label":"a"},{"label":"b"}]}""",
      "missing required" to """{}""",
      "unknown key" to """{"title":"Hi","typo":1}""",
      "too long" to """{"title":"Far too long a title"}""",
      "out of range" to """{"title":"Hi","count":100,"level":-0.1}""",
      "wrong types" to """{"title":5,"count":"3","on":"yes","accent":"red"}""",
      "bad image" to """{"title":"Hi","hero":"http://insecure.example/x.png"}""",
      "list too long" to """{"title":"Hi","tags":[{"label":"a"},{"label":"b"},{"label":"c"}]}""",
      "list item problems" to """{"title":"Hi","tags":[{"typo":"a"}]}""",
      "null uses default" to """{"title":"Hi","on":null}""",
    )
    val cases = datasets.map { (name, data) ->
      val result = when (val r = ContractBinder.bind(c, Json.parseToJsonElement(data).jsonObject)) {
        is BindResult.Ok -> buildJsonObject {
          put("ok", JsonObject(r.bindings.values.toSortedMap().mapValues { (_, v) -> json.encodeToJsonElement(NamedValue.serializer(), v) }))
        }
        is BindResult.Invalid -> buildJsonObject {
          put("problems", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(String.serializer()), r.problems.map { it.name }.distinct().sorted()))
        }
      }
      buildJsonObject { put("name", name); put("data", Json.parseToJsonElement(data)); put("expect", result) }
    }
    check("binding.json", buildJsonObject {
      put("contract", json.encodeToJsonElement(Contract.serializer(), c))
      put("cases", kotlinx.serialization.json.JsonArray(cases))
    })
  }

  // --- image refs, proxy URLs, URL resolution -------------------------------------------------

  @Test fun resources() {
    val refs = listOf("asset:" + "c".repeat(64), "asset:abc", "https://cdn.example/a.png", "https://", "http://cdn.example/a.png", "app:avatar", "app:", "data:image/png;base64,AA", "file:///x")
    val proxy = ImageProxy("https://img.example/{width}x{height}/{format}?u={url}", sourceHosts = listOf("cdn.example"), maxWidthPx = 1000, maxHeightPx = 1000)
    val remotes = listOf(
      Triple("https://cdn.example/a b.png?x=1&y=ü", 400, 300),
      Triple("https://sub.cdn.example/a.png", 4000, 4000),
      Triple("https://cdn.example.evil/a.png", 10, 10),
      Triple("https://other.example/a.png", 10, 10),
      // The authority's real host, not what precedes "@" or follows a backslash.
      Triple("https://cdn.example:443@attacker.example/a.png", 10, 10),
      Triple("https://cdn.example@attacker.example/a.png", 10, 10),
      Triple("https://attacker.example\\@cdn.example/a.png", 10, 10),
      Triple("https://attacker.example#@cdn.example/a.png", 10, 10),
      Triple("https://CDN.Example:8443/a.png", 10, 10),
      Triple("https://[::1]/a.png", 10, 10),
    )
    val urls = listOf(
      "https://h.example/c/main/manifest.json" to "blobs/x.rc",
      "https://h.example/c/main/manifest.json" to "/root.rc",
      "https://h.example/c/main/manifest.json" to "https://other.example/y.rc",
      "https://github.com/o/r/releases/latest/download/manifest.json" to "https://github.com/o/r/releases/download/t/z.rc",
      "file:/tmp/cat/manifest.json" to "assets/f.ttf",
    )
    check("resources.json", buildJsonObject {
      put("imageRefs", kotlinx.serialization.json.JsonArray(refs.map { r ->
        buildJsonObject {
          put("value", r)
          put("kind", when (ImageRef.parse(r)) { is ImageRef.Asset -> "asset"; is ImageRef.Remote -> "remote"; is ImageRef.App -> "app"; null -> "invalid" })
        }
      }))
      put("proxy", json.encodeToJsonElement(ImageProxy.serializer(), proxy))
      put("proxied", kotlinx.serialization.json.JsonArray(remotes.map { (u, w, h) ->
        buildJsonObject { put("url", u); put("widthPx", w); put("heightPx", h); put("expect", ImageResolver.remoteUrl(proxy, emptySet(), u, w, h)) }
      }))
      put("direct", kotlinx.serialization.json.JsonArray(remotes.map { (u, w, h) ->
        buildJsonObject { put("url", u); put("allow", "cdn.example"); put("expect", ImageResolver.remoteUrl(null, setOf("cdn.example"), u, w, h)) }
      }))
      put("urls", kotlinx.serialization.json.JsonArray(urls.map { (base, ref) ->
        buildJsonObject { put("base", base); put("ref", ref); put("expect", resolveUrl(base, ref)) }
      }))
    })
  }

  // --- signing --------------------------------------------------------------------------------

  /**
   * A fixed test key signs fixed manifests. The private key lives in the fixture so the cases can be
   * regenerated; it is a TEST KEY and signs nothing real.
   */
  @Test fun signing() = runBlocking {
    val file = File(dir, "signing.json")
    // The test key is kept across updates, so regenerating never churns the signing cases.
    val existing = file.takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()).jsonObject }
    fun str(key: String) = (existing?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
    val (privateKey, publicKey) = str("testPrivateKeyPkcs8")?.let { it to str("testPublicKey")!! } ?: KeyPairGenerator.getInstance("Ed25519").generateKeyPair().let { kp ->
      Base64.getEncoder().encodeToString(kp.private.encoded) to Base64.getEncoder().encodeToString(kp.public.encoded.takeLast(32).toByteArray())
    }
    val priv = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)))
    val pub = Base64.getDecoder().decode(publicKey)
    fun sign(bytes: ByteArray) = Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(priv); update(bytes); sign() })

    val manifest = """{"catalog":"conformance","serial":7,"generatedAtEpochMs":0,"expiresAtEpochMs":1,"designs":[{"id":"d","contract":"ads/banner@1","title":"D"}]}""".encodeToByteArray()
    val good = sign(manifest)
    val tampered = manifest.decodeToString().replace("\"serial\":7", "\"serial\":8").encodeToByteArray()
    val keys = listOf(TrustedKey("k1", pub))
    val scoped = listOf(TrustedKey("k1", pub, setOf("promo/")))
    data class Case(val name: String, val bytes: ByteArray, val sig: ManifestSignature?, val trust: List<TrustedKey>)
    val cases = listOf(
      Case("valid", manifest, ManifestSignature(keyId = "k1", signature = good), keys),
      Case("tampered body", tampered, ManifestSignature(keyId = "k1", signature = good), keys),
      Case("unknown key id", manifest, ManifestSignature(keyId = "k2", signature = good), keys),
      Case("wrong algorithm", manifest, ManifestSignature(alg = "RS256", keyId = "k1", signature = good), keys),
      Case("not base64", manifest, ManifestSignature(keyId = "k1", signature = "%%%"), keys),
      Case("unsigned", manifest, null, keys),
      Case("key outside its contract scope", manifest, ManifestSignature(keyId = "k1", signature = good), scoped),
    ).map { c ->
      val v = ManifestVerifier(JavaCrypto, TrustPolicy.Signed(c.trust)).verify(c.bytes, c.sig) { Json { ignoreUnknownKeys = true }.decodeFromString(Manifest.serializer(), it.decodeToString()) }
      buildJsonObject {
        put("name", c.name)
        put("manifestBase64", Base64.getEncoder().encodeToString(c.bytes))
        c.sig?.let { put("signature", json.encodeToJsonElement(ManifestSignature.serializer(), it)) }
        put("trust", kotlinx.serialization.json.JsonArray(c.trust.map { k ->
          buildJsonObject {
            put("keyId", k.keyId); put("publicKey", Base64.getEncoder().encodeToString(k.publicKey))
            if (k.contractPrefixes.isNotEmpty()) put("contractPrefixes", kotlinx.serialization.json.JsonArray(k.contractPrefixes.map { kotlinx.serialization.json.JsonPrimitive(it) }))
          }
        }))
        put("expect", if (v is Verification.Accepted) "accepted" else "rejected")
      }
    }
    check("signing.json", buildJsonObject {
      put("note", "TEST KEY ONLY. Ed25519 over the exact manifest bytes; signature is standard padded base64.")
      put("testPrivateKeyPkcs8", privateKey)
      put("testPublicKey", publicKey)
      put("cases", kotlinx.serialization.json.JsonArray(cases))
    })
  }
}
