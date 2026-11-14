package ee.schimke.livery.client

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import ee.schimke.livery.protocol.ActionSpec
import ee.schimke.livery.protocol.Arm
import ee.schimke.livery.protocol.Conditions
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Experiment
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
import ee.schimke.livery.protocol.Presentation
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

  @Test fun decisionRequest() {
    val request = DecisionRequest(TemplateQuery("promo/banner@1", PlayerCapabilities(Surfaces.IN_APP, 7, setOf("androidx")), widthDp = 360, theme = "dark", minor = 1),
      buildJsonObject { put("offer", "summer") }, DecisionContext(1_000, "test", "en-GB", 4, "app-scoped"))
    check("decision-request.json", json.encodeToJsonElement(DecisionRequest.serializer(), request))
  }

  @Test fun exactBundledDocuments() = runBlocking {
    val m = Fx.manifest()
    val names = listOf("default-v7", "default-v8", "default-v9", "default-launcher", "default-wear-small", "retro-v7", "retro-launcher")
    val blobs = names.associate { FakeCrypto.sha(Fx.blob(it)) to Fx.blob(it).utf8() }
    val repository = TemplateRepository(StaticSource("https://catalog.invalid/"), Fetcher { _, _ -> error("no network") }, FakeCrypto,
      TrustPolicy.AllowUnsigned, MemoryBlobStore(), MemoryKeyValueStore(), Clock { 1_000 }, listOf(Fx.inApp),
      TemplateRepository.Bundled(m) { blobs[it]?.encodeUtf8() })
    val q = TemplateQuery(Fx.tile.ref, Fx.inApp)
    val cases = listOf(
      "installed exact" to ee.schimke.livery.protocol.TemplateRef(sha256 = m.templates[0].sha256),
      "on request exact" to ee.schimke.livery.protocol.TemplateRef(sha256 = m.templates[5].sha256),
      "too new" to ee.schimke.livery.protocol.TemplateRef(sha256 = m.templates[2].sha256),
      "unknown hash" to ee.schimke.livery.protocol.TemplateRef(sha256 = "0".repeat(64)),
      "wrong design" to ee.schimke.livery.protocol.TemplateRef(sha256 = m.templates[0].sha256, designId = "tile-retro"),
    )
    check("exact-bundled.json", buildJsonObject {
      put("manifest", json.encodeToJsonElement(Manifest.serializer(), m))
      put("blobs", buildJsonObject { blobs.forEach { (key, value) -> put(key, value) } })
      put("query", json.encodeToJsonElement(TemplateQuery.serializer(), q))
      put("cases", kotlinx.serialization.json.JsonArray(cases.map { (name, ref) ->
        val loaded = repository.ensure(ref, q)
        buildJsonObject {
          put("name", name); put("ref", json.encodeToJsonElement(ee.schimke.livery.protocol.TemplateRef.serializer(), ref))
          put("expect", loaded?.entry?.sha256?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        }
      }))
    })
  }

  // --- selection ------------------------------------------------------------------------------

  /** With [fallbacks], the first of [query] and then each of them that has a template decides (§5.4). */
  @Serializable data class SelectionCase(
    val name: String, val query: Query, val fallbacks: List<Query> = emptyList(), val installed: List<String> = emptyList(), val expect: String?,
  )
  @Serializable data class Query(
    val contract: String, val caps: PlayerCapabilities, val widthDp: Int? = null, val heightDp: Int? = null,
    val designId: String? = null, val variant: String? = null, val theme: String? = null, val tags: Map<String, String> = emptyMap(),
    val minor: Int? = null,
  )
  @Serializable data class SelectionFixture(val manifest: Manifest, val cases: List<SelectionCase>)

  private fun t(sha: Char, design: String, target: Target, bytes: Long = 100, revoked: Boolean = false, tags: Map<String, String> = emptyMap(), requiresMinor: Int = 0, features: List<String> = emptyList()) =
    TemplateEntry(sha.toString().repeat(64), bytes, design, "c/tile@1", target, tags = tags, revoked = revoked, requiresMinor = requiresMinor, features = features)

  @Test fun selection() {
    val inApp = { level: Int -> Target(Surfaces.IN_APP, level, listOf("androidx")) }
    val m = Manifest(
      catalog = "conformance", serial = 1, generatedAtEpochMs = 0, expiresAtEpochMs = 1,
      contracts = listOf(Contract("c/tile", 1, minor = 2)),
      designs = listOf(
        Design("builtin", "c/tile@1", "Built-in", install = InstallPolicy.Always),
        Design("compact", "c/tile@1", "Compact", variant = "compact", install = InstallPolicy.Always, tags = mapOf("style" to "flat")),
        Design("skin", "c/tile@1", "Skin", install = InstallPolicy.OnRequest),
        Design("gone", "c/tile@1", "Gone", revoked = true),
        // Shipped to everyone, but drawn only when named or picked by an arm (a season).
        Design("winter", "c/tile@1", "Winter", variant = "winter", install = InstallPolicy.Always, automatic = false),
        // Its only template uses a shader (§5.1): it plays only where the player allows shaders.
        Design("fx", "c/tile@1", "Effects", variant = "fx", install = InstallPolicy.Always),
        // Tagged for one item (a talk's track), with a variant of its own so the plain query's
        // default still wins everywhere else (§5.4).
        Design("android", "c/tile@1", "Android track", variant = "track", install = InstallPolicy.Always, tags = mapOf("track" to "android")),
      ),
      templates = listOf(
        t('1', "builtin", inApp(7)),
        t('2', "builtin", inApp(8)),
        t('3', "builtin", inApp(9)),
        t('4', "builtin", Target(Surfaces.LAUNCHER_WIDGET, 7, listOf("widgets"), SizeRange(110, 400, 40, 400))),
        t('5', "builtin", Target(Surfaces.LAUNCHER_WIDGET, 7, listOf("widgets"), SizeRange(110, 200, 40, 200))),
        t('6', "builtin", Target(Surfaces.WEAR_WIDGET, 7, listOf("glance-wear"), theme = "baked:dark", container = "wear:small")),
        t('7', "compact", inApp(7)),
        // The compact design's newer version reads an input contract minor 2 added.
        t('a', "compact", inApp(8), requiresMinor = 2),
        t('8', "skin", inApp(9)),
        t('9', "gone", inApp(8)),
        // The smallest template, and the only one at level 9 besides the skin's.
        t('b', "winter", inApp(9), bytes = 50),
        // Sorts first on a tie, so only the byte budget keeps it out.
        t('0', "builtin", inApp(8), bytes = 900_000),
        t('f', "fx", inApp(8), features = listOf("shaders")),
        t('c', "android", inApp(8)),
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
      SelectionCase("a design that isn't automatic is never picked unasked", q(app8.copy(maxRcApiLevel = 9)).copy(variant = "winter"), expect = null),
      SelectionCase("not even when it is all that fits", q(app8.copy(maxRcApiLevel = 9, maxBytes = 80)), expect = null),
      SelectionCase("a design that isn't automatic draws when named", q(app8.copy(maxRcApiLevel = 9)).copy(designId = "winter"), expect = null),
      SelectionCase("tightest launcher fit", q(launcher).copy(widthDp = 150, heightDp = 100), expect = null),
      SelectionCase("launcher size not covered", q(launcher).copy(widthDp = 500, heightDp = 100), expect = null),
      SelectionCase("wear container and baked theme", q(wear), expect = null),
      SelectionCase("wrong wear container", q(wear.copy(container = "wear:large")), expect = null),
      SelectionCase("byte budget", q(app8.copy(maxBytes = 150)), expect = null),
      SelectionCase("tags", q(app8).copy(tags = mapOf("style" to "flat")), expect = null),
      SelectionCase("other contract major", Query("c/tile@2", app8), expect = null),
      SelectionCase("app on the contract's minor gets the newer template", q(app8).copy(variant = "compact", minor = 2), expect = null),
      SelectionCase("app on an older minor gets the older template", q(app8).copy(variant = "compact", minor = 1), expect = null),
      SelectionCase("no minor accepts any", q(app8).copy(variant = "compact"), expect = null),
      SelectionCase("a risky feature the player doesn't allow: the design falls back", q(app8).copy(designId = "fx"), expect = null),
      SelectionCase("the player allows that feature", q(app8.copy(features = setOf("shaders"))).copy(designId = "fx"), expect = null),
      SelectionCase("allowing another feature isn't enough", q(app8.copy(features = setOf("haptics"))).copy(designId = "fx"), expect = null),
      SelectionCase("the most specific query with a template decides", q(app8).copy(tags = mapOf("track" to "android")), fallbacks = listOf(q(app8)), expect = null),
      SelectionCase("one without falls back to the next", q(app8).copy(tags = mapOf("track" to "ios")), fallbacks = listOf(q(app8)), expect = null),
      SelectionCase("the plain query still draws the default, not the tagged design", q(app8).copy(tags = mapOf("track" to "ios")), fallbacks = listOf(q(app8).copy(tags = mapOf("style" to "round")), q(app8)), expect = null),
      SelectionCase("no query has a template", q(app8).copy(tags = mapOf("track" to "ios")), fallbacks = listOf(q(app8).copy(tags = mapOf("style" to "round"))), expect = null),
    ).map { c ->
      val auto = { d: Design -> d.install == InstallPolicy.Always || d.id in c.installed }
      val queries = (listOf(c.query) + c.fallbacks).map { qq -> TemplateQuery(qq.contract, qq.caps, qq.widthDp, qq.heightDp, qq.designId, qq.variant, qq.theme, qq.tags, qq.minor) }
      val got = if (c.fallbacks.isEmpty()) TemplateSelector.select(m, queries.single(), automatic = auto) else TemplateSelector.selectFirst(m, queries, automatic = auto)?.value
      c.copy(expect = got?.sha256)
    }
    check("selection.json", json.encodeToJsonElement(SelectionFixture.serializer(), SelectionFixture(m, cases)))
  }

  // --- experiments ----------------------------------------------------------------------------

  @Serializable data class ExperimentCase(
    val name: String, val experiment: String, val bucketingId: String,
    val nowEpochMs: Long = 1_000, val segments: Set<String> = emptySet(), val expect: String?,
  )
  @Serializable data class ExperimentFixture(val experiments: List<Experiment>, val cases: List<ExperimentCase>)

  @Test fun experiments() {
    val experiments = listOf(
      Experiment("even", listOf(Arm("a"), Arm("b"))),
      Experiment("weighted", listOf(Arm("control", 3), Arm("treatment", 1), Arm("off", 0))),
      Experiment("tenth", listOf(Arm("a"), Arm("b")), allocationBasisPoints = 1_000),
      Experiment("scheduled", listOf(Arm("a")), startEpochMs = 500, endEpochMs = 2_000),
      Experiment("beta", listOf(Arm("a"), Arm("b")), conditions = Conditions(segments = setOf("beta"))),
    )
    val cases = buildList {
      for (e in listOf("even", "weighted", "tenth")) for (i in 0 until 12) add(ExperimentCase("$e user-$i", e, "user-$i", expect = null))
      add(ExperimentCase("before the start", "scheduled", "user-1", nowEpochMs = 400, expect = null))
      add(ExperimentCase("running", "scheduled", "user-1", expect = null))
      add(ExperimentCase("at the end", "scheduled", "user-1", nowEpochMs = 2_000, expect = null))
      add(ExperimentCase("outside the segment", "beta", "user-1", expect = null))
      add(ExperimentCase("in the segment", "beta", "user-1", segments = setOf("beta"), expect = null))
    }.map { c ->
      val e = experiments.single { it.id == c.experiment }
      c.copy(expect = LocalBucketing.assignNow(e, DecisionContext(c.nowEpochMs, "test", "en", 1, c.bucketingId, c.segments)))
    }
    check("experiments.json", json.encodeToJsonElement(ExperimentFixture.serializer(), ExperimentFixture(experiments, cases)))
  }

  @Serializable data class ActionExpect(val args: Map<String, String> = emptyMap(), val problem: String? = null)
  @Serializable data class ActionCase(val name: String, val action: String, val args: Map<String, String>, val expect: ActionExpect?)
  @Serializable data class ActionFixture(val contract: Contract, val cases: List<ActionCase>)

  /** PROTOCOL.md §7: what an emitted action's event carries, or why it is dropped. */
  @Test fun actions() {
    val c = Contract(
      "c/card", 1,
      // The inputs a `set` changes (§7.1): its `value` is checked as the input is.
      inputs = mapOf(
        "qty" to InputSpec(ValueType.Int, min = 0.0, max = 9.0),
        "liked" to InputSpec(ValueType.Bool),
        "note" to InputSpec(ValueType.String, maxLength = 10),
      ),
      actions = mapOf(
        "open" to ActionSpec("navigate", args = mapOf("route" to InputSpec(ValueType.String, required = true, maxLength = 20))),
        "expand" to ActionSpec("track", args = mapOf("section" to InputSpec(ValueType.String), "index" to InputSpec(ValueType.Int, min = 0.0, max = 9.0))),
        "rate" to ActionSpec("app:rate", args = mapOf("stars" to InputSpec(ValueType.Int, required = true, min = 1.0, max = 5.0), "liked" to InputSpec(ValueType.Bool), "at" to InputSpec(ValueType.Long), "score" to InputSpec(ValueType.Float, max = 1.0))),
        "close" to ActionSpec("dismiss"),
        "setQty" to ActionSpec("set", input = "qty"),
        "setLiked" to ActionSpec("set", input = "liked"),
        "setNote" to ActionSpec("set", input = "note"),
      ),
    )
    val cases = listOf(
      ActionCase("declared arguments only", "open", mapOf("route" to "home", "secret" to "x"), null),
      ActionCase("required argument missing", "open", emptyMap(), null),
      ActionCase("too long", "open", mapOf("route" to "x".repeat(21)), null),
      ActionCase("optional arguments left out", "expand", emptyMap(), null),
      ActionCase("int in range", "expand", mapOf("index" to "9", "section" to "specs"), null),
      ActionCase("int out of range", "expand", mapOf("index" to "10"), null),
      ActionCase("not an int", "expand", mapOf("index" to "2.5"), null),
      ActionCase("bool, long and float", "rate", mapOf("stars" to "5", "liked" to "true", "at" to "1793000000000", "score" to "0.5"), null),
      ActionCase("not a bool", "rate", mapOf("stars" to "5", "liked" to "yes"), null),
      ActionCase("float above max", "rate", mapOf("stars" to "5", "score" to "1.5"), null),
      ActionCase("no arguments declared", "close", mapOf("value" to "x"), null),
      ActionCase("undeclared action", "share", emptyMap(), null),
      ActionCase("set: a value its input allows", "setQty", mapOf("value" to "3"), null),
      ActionCase("set: above its input's max", "setQty", mapOf("value" to "10"), null),
      ActionCase("set: below its input's min", "setQty", mapOf("value" to "-1"), null),
      ActionCase("set: not its input's type", "setQty", mapOf("value" to "2.5"), null),
      ActionCase("set: no value", "setQty", emptyMap(), null),
      ActionCase("set: only value is an argument", "setQty", mapOf("value" to "2", "other" to "x"), null),
      ActionCase("set: a document's boolean, as 1", "setLiked", mapOf("value" to "1"), null),
      ActionCase("set: a bool, as false", "setLiked", mapOf("value" to "false"), null),
      ActionCase("set: not a bool", "setLiked", mapOf("value" to "2"), null),
      ActionCase("set: longer than its input's maxLength", "setNote", mapOf("value" to "x".repeat(11)), null),
    ).map { k -> ActionGate.check(c, k.action, k.args).let { k.copy(expect = ActionExpect(it.args, it.problem)) } }
    check("actions.json", json.encodeToJsonElement(ActionFixture.serializer(), ActionFixture(c, cases)))
  }

  @Serializable data class ScreenPlacementJson(val placement: String, val contract: String, val allows: List<Presentation>, val default: Presentation)
  @Serializable data class ScreenCase(val name: String, val placement: String, val requested: Presentation, val depth: Int, val expect: ScreenExpect?)
  @Serializable data class ScreenExpect(val presentation: Presentation? = null, val problem: String? = null)
  @Serializable data class ScreenFixture(val placements: List<ScreenPlacementJson>, val cases: List<ScreenCase>)

  /** PROTOCOL.md §8.2: how a screen shows, or why it doesn't. */
  @Test fun screens() {
    val placements = listOf(
      ScreenPlacement("app.launch", "c/promo@1", setOf(Presentation.Fullscreen)),
      ScreenPlacement("promo.details", "c/details@1", setOf(Presentation.Sheet, Presentation.Fullscreen)),
      ScreenPlacement("help", "c/help@1", setOf(Presentation.Dialog, Presentation.Sheet), default = Presentation.Dialog),
    )
    val cases = listOf(
      ScreenCase("none asked takes the default", "promo.details", Presentation.Inline, 1, null),
      ScreenCase("an allowed presentation", "promo.details", Presentation.Fullscreen, 1, null),
      ScreenCase("one not allowed", "app.launch", Presentation.Sheet, 1, null),
      ScreenCase("a dialog default", "help", Presentation.Inline, 1, null),
      ScreenCase("one level deeper", "help", Presentation.Sheet, 2, null),
      ScreenCase("no deeper", "help", Presentation.Sheet, 3, null),
      ScreenCase("not a screen placement", "home.top", Presentation.Sheet, 1, null),
    ).map { c ->
      val o = ScreenRules.presentation(placements.firstOrNull { it.placement == c.placement }, c.placement, c.requested, c.depth)
      c.copy(expect = ScreenExpect(o.presentation, o.problem))
    }
    val json = placements.map { ScreenPlacementJson(it.placement, it.contract, it.allows.toList(), it.default) }
    check("screens.json", this.json.encodeToJsonElement(ScreenFixture.serializer(), ScreenFixture(json, cases)))
  }

  @Serializable data class DesignArmExpect(val experiment: String, val arm: String, val designId: String? = null)
  @Serializable data class DesignArmCase(val name: String, val contract: String, val bucketingId: String, val expect: DesignArmExpect?)
  @Serializable data class DesignArmFixture(val experiments: List<Experiment>, val cases: List<DesignArmCase>)

  @Test fun designArms() {
    val experiments = listOf(
      // Names no design: never a design arm, though it comes first.
      Experiment("copy", listOf(Arm("a"), Arm("b"))),
      Experiment("tile_look", listOf(Arm("control"), Arm("retro", designs = mapOf("c/tile@1" to "tile-retro"))), allocationBasisPoints = 5_000),
      Experiment("banner_look", listOf(Arm("bold", designs = mapOf("c/banner@1" to "banner-bold")), Arm("classic", designs = mapOf("c/banner@1" to "banner-classic"))), allocationBasisPoints = 5_000),
      // A later experiment over the tile decides only for users tile_look leaves out.
      Experiment("tile_late", listOf(Arm("late", designs = mapOf("c/tile@1" to "tile-late")))),
    )
    val cases = buildList {
      for (c in listOf("c/tile@1", "c/banner@1")) for (i in 0 until 24) add(DesignArmCase("$c user-$i", c, "user-$i", null))
      add(DesignArmCase("no experiment over the contract", "c/feed@1", "user-0", null))
    }.map { c ->
      val ctx = DecisionContext(1_000, "test", "en", 1, c.bucketingId)
      val got = kotlinx.coroutines.runBlocking { designArm(experiments, c.contract, ctx) { LocalBucketing.assignNow(it, ctx) } }
      c.copy(expect = got?.let { DesignArmExpect(it.experiment, it.arm, it.designId) })
    }
    check("design-arms.json", json.encodeToJsonElement(DesignArmFixture.serializer(), DesignArmFixture(experiments, cases)))
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
      // A whole-number value is an int however it is spelled; a fraction is not.
      "integral spellings" to """{"title":"Hi","count":3.0,"at":1e3}""",
      "fractional int" to """{"title":"Hi","count":2.5}""",
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
    val pub = Base64.getDecoder().decode(publicKey).toByteString()
    fun sign(bytes: ByteString) = Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(priv); update(bytes.toByteArray()); sign() })

    val manifest = """{"catalog":"conformance","serial":7,"generatedAtEpochMs":0,"expiresAtEpochMs":1,"designs":[{"id":"d","contract":"ads/banner@1","title":"D"}]}""".encodeUtf8()
    val good = sign(manifest)
    val tampered = manifest.utf8().replace("\"serial\":7", "\"serial\":8").encodeUtf8()
    val keys = listOf(TrustedKey("k1", pub))
    val scoped = listOf(TrustedKey("k1", pub, setOf("promo/")))
    data class Case(val name: String, val bytes: ByteString, val sig: ManifestSignature?, val trust: List<TrustedKey>)
    val cases = listOf(
      Case("valid", manifest, ManifestSignature(keyId = "k1", signature = good), keys),
      Case("tampered body", tampered, ManifestSignature(keyId = "k1", signature = good), keys),
      Case("unknown key id", manifest, ManifestSignature(keyId = "k2", signature = good), keys),
      Case("wrong algorithm", manifest, ManifestSignature(alg = "RS256", keyId = "k1", signature = good), keys),
      Case("not base64", manifest, ManifestSignature(keyId = "k1", signature = "%%%"), keys),
      Case("unsigned", manifest, null, keys),
      Case("key outside its contract scope", manifest, ManifestSignature(keyId = "k1", signature = good), scoped),
    ).map { c ->
      val v = ManifestVerifier(JavaCrypto, TrustPolicy.Signed(c.trust)).verify(c.bytes, c.sig) { Json { ignoreUnknownKeys = true }.decodeFromString(Manifest.serializer(), it.utf8()) }
      buildJsonObject {
        put("name", c.name)
        put("manifestBase64", Base64.getEncoder().encodeToString(c.bytes.toByteArray()))
        c.sig?.let { put("signature", json.encodeToJsonElement(ManifestSignature.serializer(), it)) }
        put("trust", kotlinx.serialization.json.JsonArray(c.trust.map { k ->
          buildJsonObject {
            put("keyId", k.keyId); put("publicKey", Base64.getEncoder().encodeToString(k.publicKey.toByteArray()))
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

  /**
   * PROTOCOL.md §3.4: template signatures, by the same test key as `signing.json` (which must exist
   * first, so the key never churns): accepted, or rejected with the reason every port gives.
   */
  @Test fun templateSigning() = runBlocking {
    val keys = Json.parseToJsonElement(File(dir, "signing.json").readText()).jsonObject
    fun str(key: String) = (keys[key] as kotlinx.serialization.json.JsonPrimitive).content
    val priv = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(str("testPrivateKeyPkcs8"))))
    val pub = Base64.getDecoder().decode(str("testPublicKey")).toByteString()
    fun sign(contract: String, sha: String) = Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(priv); update(TemplateSignatures.message(contract, sha).toByteArray()); sign() })
    val sha = "ab".repeat(32)
    val other = "cd".repeat(32)
    val good = sign("promo/banner@1", sha)
    val all = listOf(TrustedKey("k1", pub))
    val ads = listOf(TrustedKey("k1", pub, setOf("ads/")))
    data class Case(val name: String, val contract: String, val sha256: String, val sig: ManifestSignature, val trust: List<TrustedKey>)
    val cases = listOf(
      Case("valid", "promo/banner@1", sha, ManifestSignature(keyId = "k1", signature = good), all),
      Case("other bytes", "promo/banner@1", other, ManifestSignature(keyId = "k1", signature = good), all),
      Case("other contract", "promo/banner@2", sha, ManifestSignature(keyId = "k1", signature = good), all),
      Case("unknown key id", "promo/banner@1", sha, ManifestSignature(keyId = "k2", signature = good), all),
      Case("wrong algorithm", "promo/banner@1", sha, ManifestSignature(alg = "RS256", keyId = "k1", signature = good), all),
      Case("not base64", "promo/banner@1", sha, ManifestSignature(keyId = "k1", signature = "%%%"), all),
      Case("key outside its contract scope", "promo/banner@1", sha, ManifestSignature(keyId = "k1", signature = good), ads),
    ).map { c ->
      val v = TemplateSignatures.verify(JavaCrypto, c.trust, c.contract, c.sha256, c.sig)
      buildJsonObject {
        put("name", c.name)
        put("contract", c.contract)
        put("sha256", c.sha256)
        put("signature", json.encodeToJsonElement(ManifestSignature.serializer(), c.sig))
        put("trust", kotlinx.serialization.json.JsonArray(c.trust.map { k ->
          buildJsonObject {
            put("keyId", k.keyId); put("publicKey", Base64.getEncoder().encodeToString(k.publicKey.toByteArray()))
            if (k.contractPrefixes.isNotEmpty()) put("contractPrefixes", kotlinx.serialization.json.JsonArray(k.contractPrefixes.map { kotlinx.serialization.json.JsonPrimitive(it) }))
          }
        }))
        when (v) {
          is TemplateVerification.Accepted -> put("expect", "accepted")
          is TemplateVerification.Rejected -> { put("expect", "rejected"); put("reason", v.reason) }
        }
      }
    }
    check("template-signing.json", buildJsonObject {
      put("note", "TEST KEY ONLY (signing.json's). Ed25519 over livery-template:<contract>:<sha256>; signature is standard padded base64.")
      put("cases", kotlinx.serialization.json.JsonArray(cases))
    })
  }
}
