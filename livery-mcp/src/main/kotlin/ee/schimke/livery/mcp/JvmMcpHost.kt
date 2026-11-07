package ee.schimke.livery.mcp

import ee.schimke.livery.client.Bindings
import ee.schimke.livery.publisher.CompatibilityCheck
import ee.schimke.livery.publisher.check
import ee.schimke.livery.render.RenderSpec
import ee.schimke.livery.render.TemplateRenderer
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * The JVM's [McpHost]: renders through rc-players' Compose player on Skia ([TemplateRenderer]),
 * and reads the catalog's git history and earlier revisions with the `git` command.
 */
object JvmMcpHost : McpHost {
  override fun render(template: ByteArray, bindings: Bindings, spec: PreviewSpec): ByteArray = try {
    TemplateRenderer.png(template, bindings, RenderSpec(spec.widthDp, spec.heightDp, spec.density, spec.dark))
  } catch (e: Throwable) {
    // Skia: on a headless host its native library can fail to load (on Linux it links libGL,
    // libfontconfig and libX11). Say so, so an agent can tell its user what to fix instead of retrying.
    val link = generateSequence(e) { it.cause }.firstOrNull { it is UnsatisfiedLinkError } ?: throw e
    throw HostUnavailable("can't render on this host: Skia's native library didn't load (${link.message}). " +
      "On Linux, install libGL and fontconfig (e.g. apt-get install libgl1 libfontconfig1) or put them on LD_LIBRARY_PATH.")
  }

  override fun history(tree: SourceTree): CatalogHistory? =
    tree.takeIf { it.fileSystem === FileSystem.SYSTEM }?.let { GitHistory(it.root.toFile()) }?.takeIf { it.available }

  override fun check(tree: SourceTree, against: String, inspect: Boolean): CompatibilityCheck.Report {
    // Another tree on disk, or a revision of the catalog's repository (`livery check` does both).
    if (!File(against).isDirectory && history(tree) == null) throw HostUnavailable("the catalog isn't a git checkout: pass another source tree as 'against'")
    return CompatibilityCheck.check(tree.root.toFile(), against, inspect)
  }
}

/** The catalog source tree at [root] on disk. */
fun SourceTree(root: File): SourceTree = SourceTree(FileSystem.SYSTEM, root.toOkioPath())

/** The server over the source tree at [src] on disk, rendering and reading git as [JvmMcpHost] does. */
fun LiveryMcp.Companion.local(src: File, writable: Boolean = false, previewOut: File? = null, extensions: List<McpExtension> = emptyList()): LiveryMcp =
  LiveryMcp(SourceTree(src), writable, previewOut?.toOkioPath(), extensions, JvmMcpHost)

/** The extensions on the class path ([McpExtension.Factory] services) configured by [args] and [env]. */
fun McpExtension.Companion.load(args: List<String>, env: Map<String, String>): List<McpExtension> =
  java.util.ServiceLoader.load(McpExtension.Factory::class.java).mapNotNull { it.create(args, env) }
