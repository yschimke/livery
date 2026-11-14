package ee.schimke.livery.mcp

import okio.ByteString.Companion.toByteString
import okio.ByteString
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import java.util.concurrent.TimeUnit

/**
 * A catalog's version history, when its source tree is a git checkout (the repository + CI
 * workflow): each commit that changed a template, and the template as it was then. Git is the
 * record; nothing here writes to it.
 */
class GitHistory(private val root: File) : CatalogHistory {

  /** Whether [root] is inside a git work tree. */
  val available: Boolean by lazy { git("rev-parse", "--is-inside-work-tree")?.trim() == "true" }

  override suspend fun log(file: Path, limit: Int): List<CatalogHistory.Change> = withContext(Dispatchers.IO) {
    git("log", "--follow", "-n", limit.coerceIn(1, 100).toString(), "--format=%H%x1f%ct%x1f%an%x1f%s", "--", ":(top)${rel(file)}")
      ?.lines()?.filter { it.isNotBlank() }?.mapNotNull { line ->
        val p = line.split('\u001f')
        if (p.size < 4) null else CatalogHistory.Change(p[0], p[1].toLongOrNull() ?: 0, p[2], p[3])
      }.orEmpty()
  }

  /**
   * [file]'s bytes at [revision], or null when it didn't exist then. A commit [log] listed from
   * before a rename is read under the name the file had then.
   */
  override suspend fun show(file: Path, revision: String): ByteString? = withContext(Dispatchers.IO) { showBlocking(file, revision) }

  private fun showBlocking(file: Path, revision: String): ByteString? {
    require(REVISION.matches(revision)) { "bad revision '$revision'" }
    val commit = git("rev-parse", "--verify", "--quiet", "$revision^{commit}")?.trim() ?: return null
    return gitBytes("show", "$commit:${pathAt(file, commit) ?: rel(file)}")?.toByteString()
  }

  /**
   * The path [file] had at [commit], following renames back from today's name: the name in the
   * newest change to it (newest first, as git lists them) that [commit] descends from.
   */
  private fun pathAt(file: Path, commit: String): String? {
    val changes = mutableListOf<Pair<String, String>>()
    var hash: String? = null
    val lines = git("log", "--follow", "-n", "200", "--format=%x1e%H", "--name-only", "--", ":(top)${rel(file)}")?.lines().orEmpty()
    // Only whitespace is trimmed: trim() would also take the record separator that marks a commit.
    for (line in lines.map { it.trim(' ', '\t', '\r') }.filter { it.isNotEmpty() }) {
      if (line.startsWith("\u001e")) hash = line.removePrefix("\u001e")
      else hash?.let { changes += it to line; hash = null }
    }
    return changes.firstOrNull { (h, _) -> h == commit || isAncestor(h, commit) }?.second
  }

  private fun isAncestor(ancestor: String, of: String): Boolean = runCatching {
    val p = ProcessBuilder("git", "-C", root.path, "merge-base", "--is-ancestor", ancestor, of)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
  }.getOrDefault(false)

  /**
   * [file]'s path from the repository's top: what `git show <rev>:<path>` takes, and what a
   * `:(top)` pathspec anchors there whatever directory git runs in (the catalog may be a
   * subdirectory of the checkout).
   */
  private fun rel(file: Path): String {
    val top = git("rev-parse", "--show-toplevel")?.trim()?.let(::File) ?: root
    return file.toFile().canonicalFile.relativeTo(top.canonicalFile).path
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
