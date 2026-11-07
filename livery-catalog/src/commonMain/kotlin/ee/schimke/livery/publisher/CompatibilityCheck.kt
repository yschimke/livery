package ee.schimke.livery.publisher

import okio.FileSystem
import okio.Path
import ee.schimke.livery.client.ContractChange
import ee.schimke.livery.client.ContractEvolution
import ee.schimke.livery.instantiate.TemplateInspector
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.TemplateEntry
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
    /** The designs added, removed or changed (install policy, revocation, title…). */
    val designs: List<RuleReport> = emptyList(),
    /** The experiments and seasons added, removed or changed. */
    val experiments: List<RuleReport> = emptyList(),
    /** The placement rules added, removed or changed. */
    val placements: List<RuleReport> = emptyList(),
    /** What fails the check. */
    val problems: List<String>,
    /** What a reviewer should look at, but doesn't fail it. */
    val warnings: List<String>,
  ) {
    val ok: Boolean get() = problems.isEmpty()
  }

  /** An experiment, season or placement rule: what this change does to it. */
  @Serializable
  data class RuleReport(
    val id: String,
    /** `experiment`, `season` or a placement rule's placement. */
    val kind: String,
    /** `added`, `removed` or `changed`. */
    val status: String,
    /** One line per field that changed, or what an added one does. */
    val changes: List<String> = emptyList(),
  )

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
    /** What a reviewer should look at in this template; each is in [Report.warnings] too, prefixed. */
    val notes: List<String> = emptyList(),
    /** The risky features it uses (`shaders`, `haptics`, …; PROTOCOL.md §3.5): it plays only where an app allows them. */
    val features: List<String> = emptyList(),
    /** Of [features], those this change brings in: every one is a note, and `livery check --deny-new-features` fails on them. */
    val featuresAdded: List<String> = emptyList(),
  )

  /** A check, with the two trees it built: what [ReviewPreviews]-style tools render from. */
  class Comparison(
    val report: Report,
    /** The tree it was compared with, or null when that has no catalog or doesn't build. */
    val before: CatalogBuilder.Output?,
    /** This tree, or null when it doesn't build (the report says why). */
    val after: CatalogBuilder.Output?,
    /** This tree's kept examples ([KeptExamples]), by contract ref: drawn and played as well. */
    val kept: Map<String, Map<String, kotlinx.serialization.json.JsonObject>> = emptyMap(),
  )

  /** Compares two source trees; [old] may be missing (nothing published yet) or not build. */
  fun compare(fileSystem: FileSystem, src: Path, old: Path?, against: String = old?.toString() ?: "nothing", inspect: Boolean = false): Report =
    compareTrees(fileSystem, src, old, against, inspect).report

  /** [compare], keeping the built trees. */
  fun compareTrees(fileSystem: FileSystem, src: Path, old: Path?, against: String = old?.toString() ?: "nothing", inspect: Boolean = false): Comparison =
    compareTrees(Node(fileSystem, src), old?.let { Node(fileSystem, it) }, against, inspect)

  internal fun compareTrees(src: Node, old: Node?, against: String, inspect: Boolean): Comparison {
    val problems = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val new = try {
      (if (inspect) CatalogBuilder(now = { 0 }, inspect = CatalogBuilder.INSPECT) else CatalogBuilder(now = { 0 })).build(src, Layout.Static, serial = 0)
    } catch (e: CatalogProblems) {
      return Comparison(Report(against, emptyList(), emptyList(), problems = e.problems, warnings = emptyList()), null, null)
    }
    val before = old?.takeIf { it.resolve("catalog.json").isFile }?.let {
      runCatching { CatalogBuilder(now = { 0 }).build(it, Layout.Static, serial = 0) }
        .onFailure { e -> warnings += "$against doesn't build, so only this tree is checked: ${(e as? CatalogProblems)?.problems ?: e.message}" }
        .getOrNull()
    }
    if (before == null && old != null && !old.resolve("catalog.json").isFile) warnings += "$against has no catalog: everything is new"

    val contracts = contractReports(before?.manifest, new.manifest)
    contracts.filter { it.breaking }.forEach { r ->
      problems += "contract ${r.contract}: ${r.fromMinor} -> ${r.toMinor} is not a minor change: ${r.changes.filter { it.startsWith("breaking") }}"
    }

    val templates = templateReports(before, new, warnings)
    val experiments = experimentReports(before, new, warnings)
    val placements = placementReports(before?.manifest, new.manifest)
    val designs = designReports(before?.manifest, new.manifest)
    // Preview data the base had stays checked: kept when it changes or goes, and still binding.
    val kept = KeptExamples.load(src)
    if (old != null) KeptExamples.missing(src, old).forEach { m ->
      problems += "contract ${m.contract}: example '${m.name}' changed or was removed, and its old data isn't kept; " +
        "keep it with `livery keep-examples <src> --against $against`"
    }
    for ((ref, examples) in kept) {
      val c = new.manifest.contracts.filter { it.ref == ref }.maxByOrNull { it.minor }
        ?: run { warnings += "kept examples for $ref, which this tree no longer has"; continue }
      for ((label, data) in examples) {
        (ee.schimke.livery.client.ContractBinder.bind(c, data) as? ee.schimke.livery.client.BindResult.Invalid)?.let {
          problems += "contract $ref: kept example '$label' no longer binds: ${it.problems.map { p -> "${p.name}: ${p.message}" }}"
        }
      }
    }
    return Comparison(Report(against, contracts, templates, designs, experiments, placements, problems, warnings), before, new, kept)
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
      val notes = mutableListOf<String>()
      fun note(message: String) { notes += message; warnings += "${k.first} ${k.second}: $message" }
      // Only what this change brings in: an unchanged template's defaults were reviewed before.
      // One note per value, so a value missing defaults across many templates is said once.
      if (status != "unchanged") values.filterValues { it == "document" }.keys.sorted().forEach {
        note("`$it` is optional with no contract default; only the template's own default draws when it's missing")
      }
      // A risky feature is reviewed once, when it arrives: a new template that has one, or a changed one that gains one.
      val featuresAdded = (b.features - a?.features.orEmpty().toSet()).sorted()
      if (featuresAdded.isNotEmpty()) {
        note("now uses ${featuresAdded.joinToString()} (risky features); it plays only for apps that allow them, and draws another design for the rest")
      }
      if (a != null && b.requiresMinor > a.requiresMinor) {
        note("now requires minor ${b.requiresMinor} (was ${a.requiresMinor}); apps on an older minor no longer get this design here")
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
        notes = notes,
        features = b.features.sorted(),
        featuresAdded = featuresAdded,
      )
    }
  }

  private val JSON = kotlinx.serialization.json.Json { encodeDefaults = true }

  private fun time(ms: Long?): String = ms?.let { @OptIn(kotlin.time.ExperimentalTime::class) kotlin.time.Instant.fromEpochMilliseconds(it).toString() } ?: "open"

  /** The top-level fields that differ, as `field: old -> new`. */
  private fun <T> fieldChanges(serializer: kotlinx.serialization.KSerializer<T>, a: T, b: T, skip: Set<String> = emptySet()): List<String> {
    val x = JSON.encodeToJsonElement(serializer, a) as kotlinx.serialization.json.JsonObject
    val y = JSON.encodeToJsonElement(serializer, b) as kotlinx.serialization.json.JsonObject
    return (x.keys + y.keys).filter { it !in skip && x[it] != y[it] }.sorted().map { "$it: ${x[it] ?: "-"} -> ${y[it] ?: "-"}" }
  }

  private fun experimentReports(old: CatalogBuilder.Output?, new: CatalogBuilder.Output, warnings: MutableList<String>): List<RuleReport> {
    val before = old?.manifest?.experiments.orEmpty().associateBy { it.id }
    val after = new.manifest.experiments.associateBy { it.id }
    fun kind(id: String) = if (id in new.seasons || old?.seasons.orEmpty().contains(id)) "season" else "experiment"
    fun summary(e: ee.schimke.livery.protocol.Experiment): List<String> = buildList {
      add("runs ${time(e.startEpochMs)} to ${time(e.endEpochMs)}")
      if (kind(e.id) == "experiment") add("allocation ${e.allocationBasisPoints / 100.0}%")
      for (arm in e.arms) add("arm ${arm.id} (weight ${arm.weight})" + if (arm.designs.isEmpty()) "" else ": " + arm.designs.entries.joinToString { "${it.key} -> ${it.value}" })
      if (e.conditions != ee.schimke.livery.protocol.Conditions()) add("conditions ${JSON.encodeToJsonElement(ee.schimke.livery.protocol.Conditions.serializer(), e.conditions)}")
    }
    return (before.keys + after.keys).sorted().mapNotNull { id ->
      val a = before[id]
      val b = after[id]
      val k = kind(id)
      when {
        a == null -> RuleReport(id, k, "added", summary(b!!))
        b == null -> {
          warnings += "$k $id: removed; its users go back to the usual designs and rules"
          RuleReport(id, k, "removed")
        }
        a == b -> null
        else -> {
          if (k == "experiment") {
            if (b.allocationBasisPoints < a.allocationBasisPoints) warnings += "experiment $id: allocation lowered (${a.allocationBasisPoints} -> ${b.allocationBasisPoints} basis points); some enrolled users leave the test"
            val wa = a.arms.associate { it.id to it.weight }
            val wb = b.arms.associate { it.id to it.weight }
            if (wa != wb) warnings += "experiment $id: arms or weights changed ($wa -> $wb); users already enrolled can move between arms, so analyse it as a new test, or start one under a new id"
          }
          RuleReport(id, k, "changed", fieldChanges(ee.schimke.livery.protocol.Experiment.serializer(), a, b))
        }
      }
    }
  }

  private fun designReports(old: Manifest?, new: Manifest): List<RuleReport> {
    val before = old?.designs.orEmpty().associateBy { it.id }
    val after = new.designs.associateBy { it.id }
    return (before.keys + after.keys).sorted().mapNotNull { id ->
      val a = before[id]
      val b = after[id]
      when {
        a == null -> RuleReport(id, b!!.contract, "added", listOf("${b.install}: ${b.title}"))
        b == null -> RuleReport(id, a.contract, "removed")
        // Previews are rebuilt each time; they are pictures of the templates, reviewed there.
        else -> fieldChanges(ee.schimke.livery.protocol.Design.serializer(), a, b, skip = setOf("previews")).takeIf { it.isNotEmpty() }?.let { RuleReport(id, b.contract, "changed", it) }
      }
    }
  }

  private fun placementReports(old: Manifest?, new: Manifest): List<RuleReport> {
    val before = old?.placements.orEmpty().associateBy { it.id }
    val after = new.placements.associateBy { it.id }
    return (before.keys + after.keys).sorted().mapNotNull { id ->
      val a = before[id]
      val b = after[id]
      when {
        a == null -> RuleReport(id, b!!.placement, "added", listOf(
          "draws ${b.template.designId ?: b.template.sha256} at priority ${b.priority}, ${time(b.startEpochMs)} to ${time(b.endEpochMs)}",
        ) + listOfNotNull(
          b.frequency?.let { "at most ${it.maxImpressions} per ${it.perSeconds}s" },
          b.rollout?.let { "rollout ${it.experiment}/${it.arm} ${it.fromBasisPoints}..${it.toBasisPoints}" },
          b.arm?.let { "only for ${it.experiment} arm ${it.arm}" },
          b.presentation.takeIf { it != ee.schimke.livery.protocol.Presentation.Inline }?.let { "shown as ${it.name.lowercase()}" },
        ))
        b == null -> RuleReport(id, a.placement, "removed")
        a == b -> null
        else -> RuleReport(id, b.placement, "changed", fieldChanges(ee.schimke.livery.protocol.PlacementRule.serializer(), a, b))
      }
    }
  }

  /** Who guarantees each value [t] reads (see [TemplateReport.values]). */
  private fun valueGuarantees(c: Contract, t: TemplateEntry, bytes: ByteArray?): Map<String, String> {
    val reads = (t.reads + (bytes?.let { TemplateInspector.facts(it)?.declaredReads }.orEmpty())).distinct().sorted()
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
        appendLine("      requires minor ${t.requiresMinor}${t.previousRequiresMinor?.takeIf { it != t.requiresMinor }?.let { " (was $it)" } ?: ""}, ${t.bytes} bytes${t.previousBytes?.takeIf { it != t.bytes }?.let { " (was $it)" } ?: ""}${if (t.features.isEmpty()) "" else ", uses ${t.features.joinToString()}"}")
        t.values.entries.groupBy({ it.value }, { it.key }).entries.sortedBy { it.key }.forEach { (k, v) -> appendLine("      $k: ${v.joinToString(", ")}") }
      }
    }
    val same = r.templates.count { it.status == "unchanged" }
    if (same > 0) appendLine("\n  $same template(s) unchanged")
    for ((title, rules) in listOf("designs" to r.designs, "experiments and seasons" to r.experiments, "placement rules" to r.placements)) {
      if (rules.isEmpty()) continue
      appendLine("\n$title")
      for (x in rules) {
        appendLine("  ${x.status.padEnd(9)} ${x.kind} ${x.id}")
        x.changes.forEach { appendLine("      $it") }
      }
    }
    if (r.warnings.isNotEmpty()) { appendLine("\nwarnings"); r.warnings.forEach { appendLine("  - $it") } }
    if (r.problems.isNotEmpty()) { appendLine("\nproblems"); r.problems.forEach { appendLine("  - $it") } }
  }

  /**
   * Images of one design for the review, drawn by a tool with a renderer (`livery review`): each
   * contract example before and after the change, as the paths or URLs the comment shows.
   */
  data class DesignPreview(
    val design: String,
    /** The template drawn, as the report describes its target. */
    val target: String,
    val widthDp: Int,
    val heightDp: Int,
    val shots: List<Shot>,
    /** Why nothing could be drawn, when nothing was. */
    val failure: String? = null,
  ) {
    data class Shot(val example: String, val before: String?, val after: String?, val unchanged: Boolean = false)
  }

  /**
   * The report for a pull request comment or a CI job summary: short enough to read. Templates are
   * summed up per design (a design has one template per target, often dozens), notes that repeat
   * across a design's templates are said once, and every template is listed in a collapsed section.
   * [previews] adds before and after images.
   */
  fun markdown(r: Report, previews: List<DesignPreview> = emptyList()): String = buildString {
    val against = r.against.takeUnless { HASH.matches(it) } ?: r.against.take(7)
    appendLine("### Livery check against `$against`: ${if (r.ok) "compatible" else "**not compatible**"}")
    appendLine()
    val changed = r.templates.filter { it.status != "unchanged" }
    val byDesign = r.templates.groupBy { it.design }
    val touched = changed.map { it.design }.distinct()
    summary(r, changed, touched)?.let { appendLine(it); appendLine() }
    if (r.problems.isNotEmpty()) {
      appendLine("**Problems** (these fail the check)")
      r.problems.forEach { appendLine("- ${it.replace("\n", " ")}") }
      appendLine()
    }
    val templateWarnings = r.templates.flatMap { t -> t.notes.map { "${t.design} ${t.target}: $it" } }.toSet()
    val grouped = r.templates.flatMap { t -> t.notes.map { it to t.design } }.groupBy({ it.first }, { it.second })
    val other = r.warnings.filter { it !in templateWarnings }
    if (grouped.isNotEmpty() || other.isNotEmpty()) {
      appendLine("**For review**")
      other.forEach { appendLine("- $it") }
      for ((message, designs) in grouped) {
        val counts = designs.groupingBy { it }.eachCount().entries.joinToString(", ") { (d, n) -> "`$d` (${templates(n)})" }
        appendLine("- ${message.replace("|", "\\|")}: $counts")
      }
      appendLine()
    }
    val contracts = r.contracts.filter { it.changes.isNotEmpty() || it.fromMinor != it.toMinor }
    if (contracts.isNotEmpty()) {
      appendLine("**Contracts**")
      for (c in contracts) {
        appendLine("- `${c.contract}` minor ${c.fromMinor ?: "-"} -> ${c.toMinor ?: "-"}${if (c.breaking) " **breaking**" else ""}")
        c.changes.forEach { appendLine("  - $it") }
      }
      appendLine()
    }
    if (touched.isNotEmpty()) {
      appendLine("**Templates**")
      appendLine()
      appendLine("| Design | Templates | Size | Reads | Emits | Minor |")
      appendLine("|---|---|---|---|---|---|")
      for (design in touched) {
        val ts = byDesign.getValue(design)
        val live = ts.filter { it.status != "removed" }
        val statuses = ts.groupingBy { it.status }.eachCount().entries
          .sortedBy { STATUS_ORDER.indexOf(it.key) }.joinToString(", ") { (s, n) -> "$n $s" }
        val size = range(live.map { it.bytes }).let { now ->
          val was = range(ts.filter { it.status != "added" }.mapNotNull { it.previousBytes })
          when {
            now == null -> was?.let { "was $it" } ?: ""
            was == null || was == now -> now
            else -> "$now (was $was, ${percent(ts)})"
          }
        }
        fun delta(add: (TemplateReport) -> List<String>, remove: (TemplateReport) -> List<String>) =
          (ts.flatMap(add).distinct().map { "+$it" } + ts.flatMap(remove).distinct().map { "-$it" }).joinToString(" ")
        val minors = live.map { it.requiresMinor }.distinct().sorted()
        val wasMinors = ts.mapNotNull { it.previousRequiresMinor }.distinct().sorted()
        val minor = minors.joinToString("/") + (wasMinors.takeIf { it.isNotEmpty() && it != minors }?.let { " (was ${it.joinToString("/")})" } ?: "")
        appendLine("| `$design` | $statuses | $size | ${delta({ it.readsAdded }, { it.readsRemoved })} | ${delta({ it.emitsAdded }, { it.emitsRemoved })} | $minor |")
      }
      appendLine()
    }
    if (previews.isNotEmpty()) {
      appendLine("**Previews** (the contract's examples)")
      appendLine()
      for (p in previews) {
        appendLine("`${p.design}` · ${p.target} at ${p.widthDp}×${p.heightDp} dp")
        appendLine()
        if (p.failure != null || p.shots.isEmpty()) { appendLine("_not drawn: ${p.failure ?: "no examples"}_"); appendLine(); continue }
        val same = p.shots.all { it.unchanged }
        appendLine("| | " + p.shots.joinToString(" | ") { it.example } + " |")
        appendLine("|---|" + p.shots.joinToString("") { "---|" })
        fun img(src: String?) = src?.let { "<img src=\"$it\" width=\"${(p.widthDp * PREVIEW_SCALE).coerceAtMost(PREVIEW_MAX)}\">" } ?: "–"
        if (same) {
          appendLine("| unchanged | " + p.shots.joinToString(" | ") { img(it.after) } + " |")
        } else {
          if (p.shots.any { it.before != null }) appendLine("| before | " + p.shots.joinToString(" | ") { img(it.before) } + " |")
          appendLine("| after | " + p.shots.joinToString(" | ") { img(it.after) } + " |")
        }
        appendLine()
      }
    }
    for ((title, rules) in listOf("Designs" to r.designs, "Experiments and seasons" to r.experiments, "Placement rules" to r.placements)) {
      if (rules.isEmpty()) continue
      appendLine("**$title**")
      for (x in rules) {
        appendLine("- ${x.status} ${x.kind} `${x.id}`")
        x.changes.forEach { appendLine("  - ${it.replace("|", "\\|")}") }
      }
      appendLine()
    }
    if (changed.isNotEmpty()) {
      appendLine("<details><summary>Every changed template (${changed.size})</summary>")
      appendLine()
      for ((design, ts) in changed.groupBy { it.design }) {
        for ((status, group) in ts.groupBy { it.status }) {
          val list = group.joinToString(", ") { t ->
            val bytes = if (t.status == "removed") "" else " ${kb(t.bytes)}" + (t.previousBytes?.takeIf { it != t.bytes }?.let { " (was ${kb(it)})" } ?: "")
            "${t.target}$bytes"
          }
          appendLine("- `$design` $status: $list")
        }
      }
      appendLine()
      appendLine("</details>")
      appendLine()
    }
  }

  private fun summary(r: Report, changed: List<TemplateReport>, touched: List<String>): String? {
    val same = r.templates.size - changed.size
    val parts = buildList {
      if (touched.isNotEmpty()) add("${touched.size} design${if (touched.size == 1) "" else "s"} with template changes")
      changed.groupingBy { it.status }.eachCount().entries.sortedBy { STATUS_ORDER.indexOf(it.key) }
        .forEach { (s, n) -> add("${templates(n)} $s") }
      if (same > 0) add("$same unchanged")
      listOf(Triple(r.designs, "design", "designs"), Triple(r.experiments, "experiment or season", "experiments or seasons"),
        Triple(r.placements, "placement rule", "placement rules"))
        .forEach { (rules, one, many) -> if (rules.isNotEmpty()) add("${rules.size} ${if (rules.size == 1) one else many} changed") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
  }

  private fun templates(n: Int) = "$n template${if (n == 1) "" else "s"}"

  private fun kb(bytes: Long): String = if (bytes < 1000) "$bytes B" else "${(bytes + 50) / 1000}.${(bytes + 50) % 1000 / 100} KB"

  private fun range(bytes: List<Long>): String? {
    if (bytes.isEmpty()) return null
    val lo = bytes.min(); val hi = bytes.max()
    return if (lo == hi) kb(lo) else "${kb(lo).removeSuffix(" KB").removeSuffix(" B")}–${kb(hi)}"
  }

  /** The change in total size of a design's templates that existed before and after. */
  private fun percent(ts: List<TemplateReport>): String {
    val both = ts.filter { it.status == "changed" && it.previousBytes != null }
    val was = both.sumOf { it.previousBytes!! }.takeIf { it > 0 } ?: return ""
    val now = both.sumOf { it.bytes }
    val p = kotlin.math.floor((now - was) * 100.0 / was + 0.5).toLong()
    return if (p > 0) "+$p%" else if (p < 0) "−${-p}%" else "±0%"
  }

  private val HASH = Regex("[0-9a-f]{40}")
  private val STATUS_ORDER = listOf("added", "changed", "removed", "unchanged")
  private const val PREVIEW_SCALE = 1
  private const val PREVIEW_MAX = 280
}
