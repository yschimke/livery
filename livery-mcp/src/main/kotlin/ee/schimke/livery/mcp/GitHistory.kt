package ee.schimke.livery.mcp

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A catalog's version history, when its source tree is a git checkout (the repository + CI
 * workflow): each commit that changed a template, and the template as it was then. Git is the
 * record; nothing here writes to it.
 */
class GitHistory(private val root: File) {
  data class Change(val commit: String, val dateEpochSeconds: Long, val author: String, val subject: String)

  /** Whether [root] is inside a git work tree. */
  val available: Boolean by lazy { git("rev-parse", "--is-inside-work-tree")?.trim() == "true" }

  /** Commits that changed [file] (newest first), following renames. */
  fun log(file: File, limit: Int = 20): List<Change> =
    git("log", "--follow", "-n", limit.coerceIn(1, 100).toString(), "--format=%H%x1f%ct%x1f%an%x1f%s", "--", ":(top)${rel(file)}")
      ?.lines()?.filter { it.isNotBlank() }?.mapNotNull { line ->
        val p = line.split('\u001f')
        if (p.size < 4) null else Change(p[0], p[1].toLongOrNull() ?: 0, p[2], p[3])
      }.orEmpty()

  /** [file]'s bytes at [revision], or null when it didn't exist then. */
  fun show(file: File, revision: String): ByteArray? {
    require(REVISION.matches(revision)) { "bad revision '$revision'" }
    return gitBytes("show", "$revision:${rel(file)}")
  }

  /**
   * [file]'s path from the repository's top: what `git show <rev>:<path>` takes, and what a
   * `:(top)` pathspec anchors there whatever directory git runs in (the catalog may be a
   * subdirectory of the checkout).
   */
  private fun rel(file: File): String {
    val top = git("rev-parse", "--show-toplevel")?.trim()?.let(::File) ?: root
    return file.canonicalFile.relativeTo(top.canonicalFile).path
  }

  private fun git(vararg args: String): String? = gitBytes(*args)?.decodeToString()

  private fun gitBytes(vararg args: String): ByteArray? = runCatching {
    val p = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val out = p.inputStream.readBytes()
    if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
    out.takeIf { p.exitValue() == 0 }
  }.getOrNull()

  companion object {
    /** A commit hash or a short ref relative to HEAD: never an option or a path. */
    private val REVISION = Regex("[0-9a-fA-F]{4,40}|HEAD(~[0-9]{1,4})?")
  }
}
