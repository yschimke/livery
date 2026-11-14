package ee.schimke.livery.capture

import okio.Path.Companion.toOkioPath
import okio.FileSystem
import java.io.File

/**
 * A design's folder in a catalog source tree (`designs/<id>/`): the templates a capture produced,
 * written or checked. Keep a unit test that [check]s it, and run it with your regenerate switch to
 * [write] instead after changing a composable:
 *
 * ```
 * val folder = DesignFolder(File(catalog, "designs/tile-default"))
 * if (regenerate) folder.write(templates) else assertEquals(emptyList(), folder.check(templates))
 * ```
 */
class DesignFolder(val dir: File) {
  /** Writes `<name>.rc` and `<name>.json` for each template, and removes templates no longer captured. */
  fun write(templates: Map<String, CapturedTemplate>) {
    requireNames(templates)
    dir.mkdirs()
    stale(templates).forEach { File(dir, it).delete() }
    for ((name, t) in templates) {
      FileSystem.SYSTEM.write(File(dir, "$name.rc").toOkioPath()) { write(t.bytes) }
      File(dir, "$name.json").writeText(t.sidecar())
    }
  }

  /** What differs from the committed files; empty when they are current. */
  fun check(templates: Map<String, CapturedTemplate>): List<String> = buildList {
    requireNames(templates)
    for ((name, t) in templates) {
      val rc = File(dir, "$name.rc")
      val json = File(dir, "$name.json")
      when {
        !rc.isFile -> add("$name.rc is missing")
        FileSystem.SYSTEM.read(rc.toOkioPath()) { readByteString() } != t.bytes -> add("$name.rc differs from the capture")
      }
      when {
        !json.isFile -> add("$name.json is missing")
        json.readText() != t.sidecar() -> add("$name.json differs from the capture")
      }
    }
    stale(templates).forEach { add("$it is no longer captured") }
  }

  private fun requireNames(templates: Map<String, CapturedTemplate>) {
    for (name in templates.keys) {
      require(NAME.matches(name)) { "template names are ${NAME.pattern}: '$name'" }
      require(name !in RESERVED) { "'$name' is reserved in a design's folder (design.json, previews/, assets/)" }
    }
  }

  private companion object {
    val NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    val RESERVED = setOf("design", "previews", "assets")
  }

  private fun stale(templates: Map<String, CapturedTemplate>): List<String> =
    dir.listFiles().orEmpty().filter { (it.extension == "rc" || (it.extension == "json" && it.name != "design.json")) && it.nameWithoutExtension !in templates }.map { it.name }.sorted()
}
