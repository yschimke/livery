package ee.schimke.livery.client

import ee.schimke.livery.protocol.ActionSpec
import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.ListSpec

/**
 * One difference between two versions of a contract. [breaking] means it isn't a minor change
 * (PROTOCOL.md §4.3): a template or a data producer of the old version could break.
 */
data class ContractChange(val path: String, val message: String, val breaking: Boolean) {
  override fun toString(): String = "${if (breaking) "breaking" else "compatible"}: $path $message"
}

/**
 * How contracts may change within a major, and what a template needs of the app that plays it
 * (PROTOCOL.md §4.3). The publisher, `livery check`, the MCP server and a decision server all use
 * it, so "compatible" means the same thing everywhere.
 */
object ContractEvolution {
  /** Every difference from [old] to [new], each marked compatible or breaking. */
  fun compare(old: Contract, new: Contract): List<ContractChange> = buildList {
    if (old.id != new.id || old.major != new.major) {
      add(ContractChange(new.ref, "is a different contract from ${old.ref}", breaking = true))
      return@buildList
    }
    if (new.minor < old.minor) add(ContractChange("minor", "went back from ${old.minor} to ${new.minor}", breaking = true))
    if (old.dataSource != new.dataSource) add(ContractChange("dataSource", "changed from ${old.dataSource} to ${new.dataSource}", breaking = true))

    inputs("", old.inputs, new.inputs, old.minor)
    for ((name, o) in old.lists) {
      val n = new.lists[name]
      if (n == null) { add(ContractChange("lists.$name", "removed", breaking = true)); continue }
      list("lists.$name", o, n, old.minor)
    }
    for ((name, n) in new.lists) if (name !in old.lists) {
      add(added("lists.$name", n.since, old.minor))
    }
    for ((name, o) in old.actions) {
      val n = new.actions[name]
      if (n == null) { add(ContractChange("actions.$name", "removed", breaking = true)); continue }
      action("actions.$name", o, n, old.minor)
    }
    for ((name, n) in new.actions) if (name !in old.actions) {
      add(added("actions.$name", n.since, old.minor))
    }
  }

  /** Problems with [c] on its own: a `since` above its minor, or a required input added after minor 0. */
  fun problems(c: Contract): List<String> = buildList {
    fun check(path: String, since: Int, required: Boolean) {
      if (since > c.minor) add("$path: since $since is above the contract's minor ${c.minor}")
      if (since > 0 && required) add("$path: added in minor $since but required; a required input needs a new major")
    }
    for ((n, s) in c.inputs) check(n, s.since, s.required)
    for ((n, l) in c.lists) {
      check(n, l.since, required = false)
      for ((f, s) in l.item) check("$n.$f", maxOf(l.since, s.since), s.required && s.since > l.since)
    }
    for ((n, a) in c.actions) {
      check("actions.$n", a.since, required = false)
      // An argument added after its action follows the input rules: it may not be required.
      for ((arg, s) in a.args) check("actions.$n.args.$arg", maxOf(a.since, s.since), s.required && s.since > a.since)
    }
  }

  /**
   * The lowest minor of [c] an app must implement to play a template that [reads] and [emits]
   * these: the highest `since` among them. Names [c] doesn't declare (theme roles) count as 0.
   */
  fun requiresMinor(c: Contract, reads: Collection<String>, emits: Collection<String>): Int {
    val r = reads.maxOfOrNull { sinceOf(c, it) } ?: 0
    val e = emits.maxOfOrNull { c.actions[it]?.since ?: 0 } ?: 0
    return maxOf(r, e)
  }

  /** The `since` of a value a template reads: an input, a list's count, or a list field. */
  fun sinceOf(c: Contract, read: String): Int {
    c.inputs[read]?.let { return it.since }
    for ((name, list) in c.lists) {
      if (read == "$name.count") return list.since
      if (read.startsWith("$name.")) {
        val field = read.removePrefix("$name.").substringAfter('.', "")
        list.item[field]?.let { return maxOf(list.since, it.since) }
      }
    }
    return 0
  }

  /** The [InputSpec] behind a value a template reads, or null for a theme role or an unknown name. */
  fun specOf(c: Contract, read: String): InputSpec? {
    c.inputs[read]?.let { return it }
    for ((name, list) in c.lists) if (read.startsWith("$name.")) {
      list.item[read.removePrefix("$name.").substringAfter('.', "")]?.let { return it }
    }
    return null
  }

  /** An addition is compatible when it is marked as added after [oldMinor]; otherwise old apps would be offered templates that use it. */
  private fun added(path: String, since: Int, oldMinor: Int) =
    if (since > oldMinor) ContractChange(path, "added in minor $since", breaking = false)
    else ContractChange(path, "added with since $since; it needs since above $oldMinor, the minor it was added after", breaking = true)

  private fun MutableList<ContractChange>.inputs(prefix: String, old: Map<String, InputSpec>, new: Map<String, InputSpec>, oldMinor: Int) {
    for ((name, o) in old) {
      val n = new[name]
      if (n == null) { add(ContractChange("$prefix$name", "removed", breaking = true)); continue }
      input("$prefix$name", o, n)
    }
    for ((name, n) in new) if (name !in old) {
      val path = "$prefix$name"
      when {
        n.required -> add(ContractChange(path, "added as required: producers of the old minor don't send it", breaking = true))
        else -> add(added(path, n.since, oldMinor))
      }
    }
  }

  private fun MutableList<ContractChange>.input(path: String, o: InputSpec, n: InputSpec) {
    if (o.type != n.type) add(ContractChange(path, "type changed from ${o.type} to ${n.type}", breaking = true))
    if (!o.required && n.required) add(ContractChange(path, "became required", breaking = true))
    if (o.required && !n.required) add(ContractChange(path, "is no longer required", breaking = false))
    if (o.since != n.since) add(ContractChange(path, "since changed from ${o.since} to ${n.since}", breaking = true))
    limit(path, "maxLength", o.maxLength?.toDouble(), n.maxLength?.toDouble(), upperBound = true)
    limit(path, "min", o.min, n.min, upperBound = false)
    limit(path, "max", o.max, n.max, upperBound = true)
    if (o.default != n.default) add(ContractChange(path, "default changed from ${o.default} to ${n.default}", breaking = false))
    if (o.role != n.role) add(ContractChange(path, "role changed from ${o.role} to ${n.role}", breaking = false))
  }

  /** A bound tightened (an upper bound lowered or added, a lower bound raised or added) breaks producers. */
  private fun MutableList<ContractChange>.limit(path: String, what: String, o: Double?, n: Double?, upperBound: Boolean) {
    if (o == n) return
    val tightened = when {
      n == null -> false
      o == null -> true
      upperBound -> n < o
      else -> n > o
    }
    fun show(d: Double?) = d?.let { if (it == kotlin.math.floor(it)) it.toLong().toString() else it.toString() } ?: "none"
    add(ContractChange(path, "$what ${if (tightened) "tightened" else "loosened"} from ${show(o)} to ${show(n)}", breaking = tightened))
  }

  private fun MutableList<ContractChange>.list(path: String, o: ListSpec, n: ListSpec, oldMinor: Int) {
    if (o.since != n.since) add(ContractChange(path, "since changed from ${o.since} to ${n.since}", breaking = true))
    limit(path, "max", o.max.toDouble(), n.max.toDouble(), upperBound = true)
    inputs("$path.", o.item, n.item, oldMinor)
  }

  private fun MutableList<ContractChange>.action(path: String, o: ActionSpec, n: ActionSpec, oldMinor: Int) {
    if (o.kind != n.kind) add(ContractChange(path, "kind changed from ${o.kind} to ${n.kind}", breaking = true))
    if (o.since != n.since) add(ContractChange(path, "since changed from ${o.since} to ${n.since}", breaking = true))
    // Templates send the args and the app reads them: the same rules as inputs (a new required arg,
    // a tightened limit or a type change breaks templates of the old minor).
    inputs("$path.args.", o.args, n.args, oldMinor)
  }
}
