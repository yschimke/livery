package ee.schimke.livery.publisher

/**
 * A `livery <name> …` command from an optional module (an authoring tool's import, say), found with
 * [java.util.ServiceLoader] so the CLI's own code never depends on one.
 */
interface LiveryCommand {
  /** The first argument that selects this command. */
  val name: String

  /** Lines for `livery`'s usage text. */
  val usage: String

  /** Runs with the arguments after [name]; returns the exit code. */
  fun run(args: List<String>, env: Map<String, String>): Int

  companion object {
    fun installed(): List<LiveryCommand> = java.util.ServiceLoader.load(LiveryCommand::class.java).toList()
  }
}
