package ee.schimke.livery.publisher

import ee.schimke.livery.client.ContractChange
import ee.schimke.livery.client.ContractEvolution
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.TemplateEntry
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

/**
 * `livery check`: whether a catalog source tree is compatible with an earlier version of itself
 * (a directory, or the same tree at a git revision) and with the apps already installed
 * (PROTOCOL.md §4.3). The publisher, CI and agents (`check_compatibility`) run the same check.
 *
 * It fails on what would break someone: a contract change that isn't a minor change, a template
 * that reads a value as a different type than its contract declares, or a tree that doesn't build.
 * Everything else (templates added, removed or changed, a template now needing a newer minor) is
 * reported for review.
 */
object CompatibilityCheck {
  @Serializable
  data class Report(
    val against: String,
    val contracts: List<ContractReport>,
    val templates: List<TemplateReport>,
    /** What fails the check. */
    val problems: List<String>,
    /** What a reviewer should look at, but doesn't fail it. */
    val warnings: List<String>,
  ) {
    val ok: Boolean get() = problems.isEmpty()
  }

  @Serializable
  data class ContractReport(val contract: String, val fromMinor: Int?, val toMinor: Int?, val changes: List<String>, val breaking: Boolean)

  @Serializable
  data class TemplateReport(
    val design: String,
    /** The template's target, as `surface[/container][ size]`. */
    val target: String,
    /** `added`, `removed`, `changed` or `unchanged`. */
    val status: String,
    val readsAdded: List<String> = emptyList(),
    val readsRemoved: List<String> = emptyList(),
    val emitsAdded: List<String> = emptyList(),
    val emitsRemoved: List<String> = emptyList(),
    val requiresMinor: Int = 0,
    val previousRequiresMinor: Int? = null,
    val bytes: Long = 0,
    val previousBytes: Long? = null,
    /**
     * Each value it reads, by who guarantees it: `required` (every producer sends it), `default`
     * (the contract fills it), `document` (only the template's own default draws when it is
     * missing), `list` (a list's count, bound when the list is sent) or `theme` (a theme role the
     * host fills).
     */
    val values: Map<String, String> = emptyMap(),
  )

  /**
   * Compares the tree at [src] with [against]: a directory, or a git revision of the repository [src]
   * is in. [inspect] decodes every document too (`livery build --inspect`): its reads, emits and types.
   */
  fun check(src: File, against: String, inspect: Boolean = false): Report {
    File(against).takeIf { it.isDirectory }?.let { return compare(src, it, against, inspect) }
    val (temp, old) = atRevision(src, against)
    return try { compare(src, old, against, inspect) } finally { temp.deleteRecursively() }
  }

  /** Compares two source trees; [old] may be missing (nothing published yet) or not build. */
  fun compare(src: File, old: File?, against: String = old?.path ?: "nothing", inspect: Boolean = false): Report {
    val problems = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val new = try {
      (if (inspect) CatalogBuilder(now = { 0 }, inspect = CatalogBuilder.INSPECT) else CatalogBuilder(now = { 0 })).build(src, Layout.Static, serial = 0)
    } catch (e: CatalogProblems) {
      return Report(against, emptyList(), emptyList(), e.problems, emptyList())
    }
    val before = old?.takeIf { File(it, "catalog.json").isFile }?.let {
      runCatching { CatalogBuilder(now = { 0 }).build(it, Layout.Static, serial = 0) }
        .onFailure { e -> warnings += "$against doesn't build, so only this tree is checked: ${(e as? CatalogProblems)?.problems ?: e.message}" }
        .getOrNull()
    }
    if (before == null && old != null && !File(old, "catalog.json").isFile) warnings += "$against has no catalog: everything is new"

    val contracts = contractReports(before?.manifest, new.manifest)
    contracts.filter { it.breaking }.forEach { r ->
      problems += "contract ${r.contract}: ${r.fromMinor} -> ${r.toMinor} is not a minor change: ${r.changes.filter { it.startsWith("breaking") }}"
    }

    val templates = templateReports(before, new, warnings)
    return Report(against, contracts, templates, problems, warnings)
  }

  private fun contractReports(old: Manifest?, new: Manifest): List<ContractReport> {
    fun latest(m: Manifest?) = m?.contracts.orEmpty().groupBy { it.ref }.mapValues { (_, v) -> v.maxBy { it.minor } }
    val o = latest(old)
    val n = latest(new)
    return (o.keys + n.keys).sorted().map { ref ->
      val a = o[ref]
      val b = n[ref]
      when {
        a == null -> ContractReport(ref, null, b!!.minor, listOf("added"), breaking = false)
        b == null -> ContractReport(ref, a.minor, null, listOf("removed: its templates are gone too"), breaking = false)
        else -> {
          val changes = ContractEvolution.compare(a, b)
          ContractReport(ref, a.minor, b.minor, changes.map(ContractChange::toString), changes.any { it.breaking })
        }
      }
    }
  }

  private fun templateReports(old: CatalogBuilder.Output?, new: CatalogBuilder.Output, warnings: MutableList<String>): List<TemplateReport> {
    // The whole target identifies a template (the build allows one per design and target); its
    // description is only for people, and two targets can read the same (profiles aren't shown).
    fun key(t: TemplateEntry) = t.designId to t.target
    val before = old?.manifest?.templates.orEmpty().associateBy(::key)
    val after = new.manifest.templates.associateBy(::key)
    val contracts = new.manifest.contracts.groupBy { it.ref }.mapValues { (_, v) -> v.maxBy { it.minor } }
    return (before.keys + after.keys).map { (design, target) -> Triple(design, target, (after[design to target] ?: before[design to target])!!) }
      .sortedWith(compareBy({ it.first }, { describe(it.third) }, { it.third.target.toString() })).map { (design, target, any) ->
      val k = design to describe(any)
      val a = before[design to target]
      val b = after[design to target]
      if (b == null) {
        warnings += "${k.first} ${k.second}: removed; that slot falls back to another design"
        return@map TemplateReport(k.first, k.second, "removed", previousRequiresMinor = a!!.requiresMinor, previousBytes = a.bytes)
      }
      val contract = contracts[b.contract]
      val values = contract?.let { valueGuarantees(it, b, new.files["blobs/${b.sha256}.rc"]) }.orEmpty()
      val status = when {
        a == null -> "added"
        a.sha256 == b.sha256 && a.reads == b.reads && a.emits == b.emits && a.requiresMinor == b.requiresMinor -> "unchanged"
        else -> "changed"
      }
      // Only what this change brings in: an unchanged template's defaults were reviewed before.
      values.filterValues { it == "document" }.keys.takeIf { it.isNotEmpty() && status != "unchanged" }?.let {
        warnings += "${k.first} ${k.second}: ${it.sorted()} are optional with no contract default; only the template's own default draws when they're missing"
      }
      if (a != null && b.requiresMinor > a.requiresMinor) {
        warnings += "${k.first} ${k.second}: now requires minor ${b.requiresMinor} (was ${a.requiresMinor}); apps on an older minor no longer get this design here"
      }
      TemplateReport(
        k.first, k.second,
        status = status,
        readsAdded = (b.reads - a?.reads.orEmpty().toSet()).sorted(),
        readsRemoved = (a?.reads.orEmpty() - b.reads.toSet()).sorted(),
        emitsAdded = (b.emits - a?.emits.orEmpty().toSet()).sorted(),
        emitsRemoved = (a?.emits.orEmpty() - b.emits.toSet()).sorted(),
        requiresMinor = b.requiresMinor, previousRequiresMinor = a?.requiresMinor,
        bytes = b.bytes, previousBytes = a?.bytes,
        values = values,
      )
    }
  }

  /** Who guarantees each value [t] reads (see [TemplateReport.values]). */
  private fun valueGuarantees(c: Contract, t: TemplateEntry, bytes: ByteArray?): Map<String, String> {
    val reads = (t.reads + (bytes?.let { TemplateInspector.facts(it)?.declaredReads }.orEmpty())).toSortedSet()
    return reads.associateWith { name ->
      val spec = ContractEvolution.specOf(c, name)
      when {
        spec == null -> when {
          name.startsWith("theme.") -> "theme"
          // Bound with its list, so it reads the document's default (normally 0) when the list is missing.
          c.lists.keys.any { name == "$it.count" } -> "list"
          else -> "unknown"
        }
        spec.role != null -> "theme"
        spec.required -> "required"
        spec.default != null -> "default"
        else -> "document"
      }
    }
  }

  private fun describe(t: TemplateEntry): String = buildString {
    append(t.target.surface)
    if (t.target.profiles.isNotEmpty()) append(" [").append(t.target.profiles.joinToString(",")).append(']')
    t.target.container?.let { append('/').append(it) }
    val s = t.target.size
    if (listOf(s.minWidthDp, s.maxWidthDp, s.minHeightDp, s.maxHeightDp).any { it != null }) {
      append(" ${s.minWidthDp ?: 0}-${s.maxWidthDp ?: "max"}x${s.minHeightDp ?: 0}-${s.maxHeightDp ?: "max"}dp")
    }
    if (t.target.theme != "host") append(" ").append(t.target.theme)
    append(" L").append(t.target.rcApiLevel)
  }

  /** A git revision: a ref, tag or hash with `~`/`^` suffixes; never an option or a path outside git. */
  private val REVISION = Regex("[A-Za-z0-9][A-Za-z0-9._/~^-]{0,199}")

  /**
   * [src] as it was at [revision], extracted to a temporary directory: the same path in the same
   * repository. Missing at that revision gives an empty directory (nothing published yet). Returns
   * the temporary directory (delete it when done) and the tree inside it.
   */
  fun atRevision(src: File, revision: String): Pair<File, File> {
    require(REVISION.matches(revision) && ".." !in revision) { "'$revision' is neither a directory nor a git revision" }
    fun git(vararg args: String, out: File? = null, at: File = src): String {
      val pb = ProcessBuilder(listOf("git", "-C", at.path) + args).redirectError(ProcessBuilder.Redirect.PIPE)
      if (out != null) pb.redirectOutput(out)
      val p = pb.start()
      val text = if (out == null) p.inputStream.readBytes().decodeToString() else ""
      val err = p.errorStream.readBytes().decodeToString()
      if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); error("git ${args.first()} timed out") }
      require(p.exitValue() == 0) { "git ${args.joinToString(" ")}: ${err.trim()}" }
      return text.trim()
    }
    val prefix = git("rev-parse", "--show-prefix")
    // Pathspecs are relative to where git runs, so the tree is listed and archived from the top.
    val top = File(git("rev-parse", "--show-toplevel"))
    val commit = git("rev-parse", "--verify", "--quiet", "$revision^{commit}")
    val dir = Files.createTempDirectory("livery-check").toFile()
    val tar = File(dir, "tree.tar")
    val listed = git("ls-tree", "-d", "--name-only", commit, "--", prefix.trimEnd('/').ifEmpty { "." }, at = top)
    if (prefix.isNotEmpty() && listed.isEmpty()) return dir to File(dir, "tree").apply { mkdirs() }
    git("archive", "--format=tar", "-o", tar.path, commit, "--", prefix.ifEmpty { "." }, at = top)
    val x = ProcessBuilder("tar", "-xf", tar.path, "-C", dir.path).redirectErrorStream(true).start()
    x.inputStream.readBytes()
    require(x.waitFor(60, TimeUnit.SECONDS) && x.exitValue() == 0) { "couldn't extract $revision" }
    tar.delete()
    return dir to (if (prefix.isEmpty()) dir else File(dir, prefix))
  }

  /** The report as people read it in a terminal or a CI log. */
  fun text(r: Report): String = buildString {
    appendLine("livery check against ${r.against}: ${if (r.ok) "compatible" else "NOT compatible"}")
    if (r.contracts.isNotEmpty()) appendLine("\ncontracts")
    for (c in r.contracts) {
      appendLine("  ${c.contract} ${c.fromMinor ?: "-"} -> ${c.toMinor ?: "-"}${if (c.breaking) "  BREAKING" else ""}")
      c.changes.forEach { appendLine("    $it") }
    }
    val changed = r.templates.filter { it.status != "unchanged" }
    if (changed.isNotEmpty()) appendLine("\ntemplates")
    for (t in changed) {
      appendLine("  ${t.status.padEnd(9)} ${t.design} ${t.target}")
      fun line(label: String, v: List<String>) { if (v.isNotEmpty()) appendLine("      $label ${v.joinToString(", ")}") }
      line("reads +", t.readsAdded); line("reads -", t.readsRemoved); line("emits +", t.emitsAdded); line("emits -", t.emitsRemoved)
      if (t.status != "removed") {
        appendLine("      requires minor ${t.requiresMinor}${t.previousRequiresMinor?.takeIf { it != t.requiresMinor }?.let { " (was $it)" } ?: ""}, ${t.bytes} bytes${t.previousBytes?.takeIf { it != t.bytes }?.let { " (was $it)" } ?: ""}")
        t.values.entries.groupBy({ it.value }, { it.key }).toSortedMap().forEach { (k, v) -> appendLine("      $k: ${v.joinToString(", ")}") }
      }
    }
    val same = r.templates.count { it.status == "unchanged" }
    if (same > 0) appendLine("\n  $same template(s) unchanged")
    if (r.warnings.isNotEmpty()) { appendLine("\nwarnings"); r.warnings.forEach { appendLine("  - $it") } }
    if (r.problems.isNotEmpty()) { appendLine("\nproblems"); r.problems.forEach { appendLine("  - $it") } }
  }
}
