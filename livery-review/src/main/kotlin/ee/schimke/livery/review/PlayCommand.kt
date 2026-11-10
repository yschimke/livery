package ee.schimke.livery.review

import ee.schimke.livery.publisher.build
import ee.schimke.livery.publisher.load
import ee.schimke.livery.client.BindResult
import ee.schimke.livery.client.ContractBinder
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.publisher.CatalogBuilder
import ee.schimke.livery.publisher.CatalogProblems
import ee.schimke.livery.publisher.KeptExamples
import ee.schimke.livery.publisher.Layout
import ee.schimke.livery.publisher.LiveryCommand
import ee.schimke.livery.render.RenderSpec
import ee.schimke.livery.render.TemplateRenderer
import java.io.File
import java.net.URI
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * `livery play`: one template, with one set of data, played the way this release of Livery plays it,
 * as a PNG and a verdict. Every Livery release ships it, so the `livery` CLI of a release is how
 * a template is tried as an app on that release would play it: download that release's CLI and run.
 *
 * - `compose`: rc-players' Compose player, in this process (`livery-render`).
 * - `launcher-widget`: written for a widget as the provider writes it, and played on Android 16's
 *   own widget player under Robolectric, in a Java 21 JVM with this release's Android classes
 *   (`lib/android-play`). Fails when the player disables itself, as a home screen would show it.
 */
class PlayCommand : LiveryCommand {
  /** The environment `run` was given: LIVERY_JAVA, JAVA_HOME, LIVERY_CACHE, LIVERY_ANDROID_PLAY. */
  private var env: Map<String, String> = System.getenv()

  override val name: String = "play"

  override val usage: String = """
  livery play <template.rc> --contract <contract.json> (--data <data.json> | --example <name>)
      [--surface compose|launcher-widget] [--size <w>x<h>] [--density <d>] [--out <png>]
      [--catalog <src>] [--as-is]       plays a template as this release of Livery plays it: a PNG,
                                        exit 1 if it crashes or is refused. --example also names
                                        a kept example ("on (kept 1a2b3c4d)") with --catalog.
                                        --as-is (launcher-widget) hands the platform player the
                                        document without the recovery a widget provider's
                                        publishing applies, to find a template that needs it.
                                        launcher-widget needs Java 21 (LIVERY_JAVA, or JAVA_HOME)
                                        and downloads Android 16 for Robolectric once (cached in
                                        LIVERY_CACHE, default ~/.cache/livery)
"""

  override fun run(args: List<String>, env: Map<String, String>): Int {
    this.env = env
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    fun fail(message: String): Int { System.err.println(message); return 64 }
    val template = File(args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: return fail(usage))
    val contractFile = File(opt("--contract") ?: return fail(usage))
    val contract = JSON.decodeFromString(Contract.serializer(), contractFile.readText())
    val catalog = opt("--catalog")?.let(::File)
    val data: JsonObject = opt("--data")?.let { JSON.parseToJsonElement(File(it).readText()).jsonObject }
      ?: opt("--example")?.let { name ->
        contract.examples[name] ?: catalog?.let { KeptExamples.load(it)[contract.ref]?.get(name) }
          ?: return fail("${contract.ref} has no example '$name': ${contract.examples.keys}")
      }
      ?: contract.examples.values.firstOrNull() ?: JsonObject(emptyMap())
    val (w, h) = (opt("--size") ?: "360x200").split('x').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }
      ?.let { it[0] to it[1] } ?: return fail("--size is <w>x<h> in dp")
    val density = opt("--density")?.toFloatOrNull() ?: 2f
    val out = File(opt("--out") ?: "${template.nameWithoutExtension}.png")
    val verdict = when (val surface = opt("--surface") ?: "compose") {
      "compose" -> compose(template, contract, data, RenderSpec(w, h, density), out, catalog)
      "launcher-widget" -> widget(template, contractFile, data, w, h, density, out, catalog, recover = "--as-is" !in args)
      else -> return fail("--surface is compose or launcher-widget, not $surface")
    }
    println(verdict)
    return when {
      verdict.startsWith("played") -> 0
      verdict.startsWith("failed") -> 1
      else -> 2
    }
  }

  private fun compose(template: File, contract: Contract, data: JsonObject, spec: RenderSpec, out: File, catalog: File?): String {
    val bindings = when (val b = ContractBinder.bind(contract, data)) {
      is BindResult.Ok -> b.bindings
      is BindResult.Invalid -> return "failed: the data doesn't bind to ${contract.ref}: ${b.problems.map { "${it.name}: ${it.message}" }}"
    }
    val bytes = template.readBytes()
    // With --catalog, the catalog's images and the template's fonts, as the app loads them from a
    // downloaded catalog; without, the player's defaults (placeholders, system fonts).
    val built = catalog?.let {
      runCatching { CatalogBuilder(now = { 0 }).build(it, Layout.Static, serial = 0) }
        .getOrElse { e -> return "error: --catalog $it doesn't build: ${(e as? CatalogProblems)?.problems ?: e.message}" }
    }
    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val entry = built?.manifest?.templates?.firstOrNull { it.sha256 == sha }
    return try {
      val images = built?.let { CatalogImages(it, bindings) } ?: ee.schimke.composeai.rcplayer.compose.RcImageLoader.Default
      val fonts = if (built != null && entry != null) CatalogFonts.forTemplate(built, entry) else ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader.Default
      out.writeBytes(TemplateRenderer.png(bytes, bindings, spec, images, fonts))
      "played: $out"
    } catch (e: Throwable) {
      "failed: ${generateSequence(e) { it.cause }.take(3).joinToString(", caused by ") { "${it.javaClass.simpleName}: ${it.message}" }}"
    }
  }

  private fun widget(template: File, contract: File, data: JsonObject, w: Int, h: Int, density: Float, out: File, catalog: File?, recover: Boolean): String {
    val lib = androidPlayDir() ?: return "error: this livery has no lib/android-play (a build without the widget player)"
    val sdkDir = robolectricSdk() ?: return "error: couldn't download Robolectric's Android 16 SDK; put ${SDK_FILE} in ${cacheDir()}"
    val downloads = downloads(lib) ?: return "error: couldn't download the widget player's libraries; see ${cacheDir()}"
    val java = javaExecutable()
    val dataFile = File.createTempFile("livery-play", ".json").apply { writeText(data.toString()); deleteOnExit() }
    val result = File.createTempFile("livery-play", ".txt").apply { delete(); deleteOnExit() }
    val command = listOf(
      java, "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
      "-cp", (listOf("${lib.absolutePath}${File.separator}*") + downloads.map { it.absolutePath }).joinToString(File.pathSeparator),
      "-Drobolectric.offline=true", "-Drobolectric.dependency.dir=${sdkDir.absolutePath}",
      "-Dlivery.play.template=${template.absolutePath}", "-Dlivery.play.contract=${contract.absolutePath}",
      "-Dlivery.play.data=${dataFile.absolutePath}", "-Dlivery.play.width=$w", "-Dlivery.play.height=$h",
      "-Dlivery.play.density=$density", "-Dlivery.play.out=${out.absolutePath}", "-Dlivery.play.result=${result.absolutePath}",
      "-Dlivery.play.recover=$recover",
    ) + listOfNotNull(catalog?.let { "-Dlivery.play.catalog=${it.absolutePath}" }) + "ee.schimke.livery.verify.WidgetPlay"
    // The child's output goes to a file, so a hung player can't block the timed wait on a pipe.
    val logFile = File.createTempFile("livery-play", ".log").apply { deleteOnExit() }
    val process = try {
      ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile).start()
    } catch (e: java.io.IOException) {
      // Not the template's failure: exit 2, never the 1 a crash gets.
      return "error: couldn't start the widget player's Java ($java): ${e.message}"
    }
    if (!process.waitFor(10, TimeUnit.MINUTES)) {
      process.destroyForcibly().waitFor(30, TimeUnit.SECONDS)
      return "error: the widget player didn't finish in 10 minutes"
    }
    val log = logFile.readText()
    return when {
      result.isFile && result.readText().startsWith("played") -> "played: $out"
      result.isFile -> result.readText().trim()
      "requires Java 21" in log -> "error: the widget player needs Java 21: set LIVERY_JAVA to a Java 21 java ($java isn't)"
      else -> "error: the widget player didn't run:\n" + log.lines().filterNot { it.startsWith("Picked up JAVA_TOOL_OPTIONS") }.takeLast(20).joinToString("\n")
    }
  }

  /** `lib/android-play` beside the jar this class is in, or LIVERY_ANDROID_PLAY. */
  private fun androidPlayDir(): File? =
    env["LIVERY_ANDROID_PLAY"]?.let(::File)?.takeIf { it.isDirectory } ?: runCatching { File(PlayCommand::class.java.protectionDomain.codeSource.location.toURI()).parentFile.resolve("android-play") }
      .getOrNull()?.takeIf { it.isDirectory }

  private fun javaExecutable(): String =
    env["LIVERY_JAVA"] ?: env["JAVA_HOME"]?.let { "$it${File.separator}bin${File.separator}java" } ?: "java"

  private fun cacheDir(): File = File(env["LIVERY_CACHE"] ?: "${System.getProperty("user.home")}/.cache/livery", "robolectric")

  /** The directory holding Robolectric's Android 16 jar, downloaded from Maven Central once. */
  private fun robolectricSdk(): File? =
    mavenJar("org.robolectric", "android-all-instrumented", SDK_VERSION, "Android 16 for Robolectric")?.parentFile

  /** The libraries this release left out of `lib/android-play` (`downloads.properties`), cached. */
  private fun downloads(lib: File): List<File>? {
    val listed = Properties().apply { File(lib, "downloads.properties").takeIf { it.isFile }?.inputStream()?.use(::load) }
    return listed.stringPropertyNames().sorted().map { coordinate ->
      val (group, artifact) = coordinate.split(':')
      mavenJar(group, artifact, listed.getProperty(coordinate), coordinate) ?: return null
    }
  }

  /** A jar from Maven Central, verified against its published SHA-1 and kept in the cache. */
  private fun mavenJar(group: String, artifact: String, version: String, what: String): File? {
    val dir = cacheDir().apply { mkdirs() }
    val file = File(dir, "$artifact-$version.jar")
    if (file.isFile) return file
    val url = "https://repo1.maven.org/maven2/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.jar"
    System.err.println("downloading $what (once): $url")
    return runCatching {
      val part = File(dir, "${file.name}.part")
      URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
      val expected = URI("$url.sha1").toURL().readText().trim().substringBefore(' ')
      val actual = java.security.MessageDigest.getInstance("SHA-1").digest(part.readBytes()).joinToString("") { "%02x".format(it) }
      check(expected.equals(actual, ignoreCase = true)) { "$url: SHA-1 $actual, expected $expected" }
      check(part.renameTo(file))
      file
    }.onFailure { System.err.println("couldn't download $url: ${it.message}") }.getOrNull()
  }

  private companion object {
    val SDK_VERSION: String = Properties().apply {
      PlayCommand::class.java.getResourceAsStream("/livery-play.properties")?.use(::load)
    }.getProperty("robolectric.sdk36") ?: "16-robolectric-13921718-i7"
    val SDK_FILE = "android-all-instrumented-$SDK_VERSION.jar" // its name in the cache, as Robolectric looks for it
    val JSON = Json { ignoreUnknownKeys = true }
  }
}
