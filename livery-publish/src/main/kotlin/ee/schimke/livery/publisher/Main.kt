package ee.schimke.livery.publisher

import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.ManifestVerifier
import ee.schimke.livery.client.TrustPolicy
import ee.schimke.livery.client.TrustedKey
import ee.schimke.livery.client.Verification
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.ManifestSignature
import java.io.File
import java.util.Base64
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private const val USAGE = """livery — build, sign and check a template catalog

  livery keygen <name>                 writes <name>.key (private) and <name>.pub (raw public, base64)
  livery build <src> <out> [options]   validates <src> and writes a catalog to <out>
      --key <file> --key-id <id>           sign manifest.json (writes manifest.sig.json)
      --serial <n>                         default: now in ms (must only grow per channel)
      --inspect                            decode every .rc and check it reads and emits only what
                                           its sidecar declares
      --github <owner/repo> --tag <tag>    flat layout addressed by that release's download URLs;
                                           upload with: gh release upload <tag> <out>/*
  livery verify <out> --pub <file> --key-id <id>
  livery preview <doc.rc> <out> --src <catalog> --contract <id@major> [--target <json>] [--title <t>]
                                       one document as an unsigned catalog in <out>/preview-<hash>/,
                                       checked against the contract, for a debug build to play
"""

fun main(args: Array<String>) {
  val a = args.toList()
  fun opt(name: String) = a.indexOf(name).takeIf { it >= 0 }?.let { a.getOrNull(it + 1) }
  try {
    when (a.firstOrNull()) {
      "keygen" -> {
        val name = a.getOrNull(1) ?: usage()
        val k = Signing.generate()
        File("$name.key").writeText(k.privatePkcs8Base64 + "\n")
        File("$name.pub").writeText(k.publicRawBase64 + "\n")
        println("wrote $name.key (keep secret) and $name.pub (embed in apps)")
      }
      "build" -> {
        val src = File(a.getOrNull(1) ?: usage())
        val out = File(a.getOrNull(2) ?: usage())
        val layout = opt("--github")?.let {
          Layout.GitHubRelease(it.substringBefore('/'), it.substringAfter('/'), opt("--tag") ?: error("--github needs --tag"))
        } ?: Layout.Static
        val builder = if ("--inspect" in a) CatalogBuilder(inspect = CatalogBuilder.INSPECT) else CatalogBuilder()
        val result = builder.build(src, layout, opt("--serial")?.toLong())
        out.mkdirs()
        result.files.forEach { (path, bytes) -> File(out, path).apply { parentFile.mkdirs() }.writeBytes(bytes) }
        File(out, Manifest.FILE).writeBytes(result.manifestBytes)
        opt("--key")?.let { keyFile ->
          val sig = Signing.sign(result.manifestBytes, File(keyFile).readText(), opt("--key-id") ?: error("--key needs --key-id"))
          File(out, Manifest.SIGNATURE_FILE).writeText(Json.encodeToString(ManifestSignature.serializer(), sig))
        }
        val m = result.manifest
        println("${m.catalog}/${m.channel} serial ${m.serial}: ${m.designs.size} designs, ${m.templates.size} templates, ${m.contracts.size} contracts, ${m.placements.size} placements -> $out")
      }
      "verify" -> {
        val out = File(a.getOrNull(1) ?: usage())
        val pub = Base64.getDecoder().decode(File(opt("--pub") ?: usage()).readText().trim())
        val sig = Json.decodeFromString(ManifestSignature.serializer(), File(out, Manifest.SIGNATURE_FILE).readText())
        val bytes = File(out, Manifest.FILE).readBytes()
        val v = runBlocking { ManifestVerifier(JavaCrypto, TrustPolicy.Signed(listOf(TrustedKey(opt("--key-id") ?: usage(), pub))))
          .verify(bytes, sig) { Json { ignoreUnknownKeys = true }.decodeFromString(Manifest.serializer(), it.decodeToString()) } }
        when (v) {
          is Verification.Accepted -> println("ok: ${v.manifest.catalog}/${v.manifest.channel} serial ${v.manifest.serial}")
          is Verification.Rejected -> { System.err.println("rejected: ${v.reason}"); exitProcess(1) }
        }
      }
      "preview" -> {
        val doc = File(a.getOrNull(1) ?: usage())
        val out = File(a.getOrNull(2) ?: usage())
        val ref = opt("--contract") ?: usage()
        val json = Json { ignoreUnknownKeys = false }
        val contract = File(opt("--src") ?: usage(), "contracts").listFiles().orEmpty().filter { it.extension == "json" }
          .map { json.decodeFromString(ee.schimke.livery.protocol.Contract.serializer(), it.readText()) }
          .filter { it.ref == ref }.maxByOrNull { it.minor } ?: error("no contract $ref in ${opt("--src")}/contracts")
        val target = opt("--target")?.let { json.decodeFromString(ee.schimke.livery.protocol.Target.serializer(), it) }
        val result = if (target != null) PreviewCatalog.build(contract, doc.readBytes(), target, opt("--title"))
          else PreviewCatalog.build(contract, doc.readBytes(), title = opt("--title"))
        val dir = PreviewCatalog.write(result, out)
        println("${result.channel}: unsigned preview of ${doc.name} for $ref -> $dir")
      }
      else -> usage()
    }
  } catch (e: CatalogProblems) {
    System.err.println(e.message); exitProcess(2)
  }
}

private fun usage(): Nothing { System.err.println(USAGE); exitProcess(64) }
