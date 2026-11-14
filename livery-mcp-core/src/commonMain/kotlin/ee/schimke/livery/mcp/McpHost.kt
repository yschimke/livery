package ee.schimke.livery.mcp

import okio.ByteString
import ee.schimke.livery.client.Bindings
import ee.schimke.livery.publisher.CompatibilityCheck
import ee.schimke.livery.publisher.targetMetadataOrNull
import okio.Path
import okio.Path.Companion.toPath

/**
 * What the platform running [LiveryMcp] adds to its tools: what only some hosts can do. Every
 * default is "this host can't": the tools say so to the agent rather than fail. `livery-mcp`'s
 * JVM host renders with Skia and reads git; a browser's might render in a worker or read a
 * repository's history over an API, hence `suspend`.
 */
interface McpHost {
  /** [template] with [bindings] as a PNG; null, or [HostUnavailable] with a reason, when this host can't render. */
  suspend fun render(template: ByteString, bindings: Bindings, spec: PreviewSpec): ByteString? = null

  /** [tree]'s version history, or null when it has none (not a git checkout, or no git here). */
  fun history(tree: SourceTree): CatalogHistory? = null

  /**
   * [tree] against [against]: another source tree on the same file system by default; a host
   * with git also takes a revision of the tree's repository.
   */
  suspend fun check(tree: SourceTree, against: String, inspect: Boolean): CompatibilityCheck.Report {
    val other = against.toPath()
    require(tree.fileSystem.targetMetadataOrNull(other)?.isDirectory == true) {
      "'$against' is not a source tree, and this host has no git history to read a revision from"
    }
    return CompatibilityCheck.compare(tree.fileSystem, tree.root, other, against, inspect)
  }

  /** A host that adds nothing: every tool that only reads or writes the tree. */
  companion object None : McpHost
}

/**
 * What a host throws when it can't do what a tool asked, with a reason the agent can pass on (a
 * native library that didn't load, no git): the tool's result says it, as its error.
 */
class HostUnavailable(message: String) : Exception(message)

/** A render's size and theme: what `render_preview` takes. */
data class PreviewSpec(val widthDp: Int, val heightDp: Int, val density: Float = 2f, val dark: Boolean = false) {
  init { require(widthDp in 1..4096 && heightDp in 1..4096 && density in 0.5f..4f) { "render size out of range" } }
}

/** A catalog's version history: each commit that changed a template, and the template as it was then. */
interface CatalogHistory {
  data class Change(val commit: String, val dateEpochSeconds: Long, val author: String, val subject: String)

  /** Commits that changed [file] (newest first), following renames. */
  suspend fun log(file: Path, limit: Int = 20): List<Change>

  /** [file]'s bytes at [revision], or null when it didn't exist then. */
  suspend fun show(file: Path, revision: String): ByteString?
}
