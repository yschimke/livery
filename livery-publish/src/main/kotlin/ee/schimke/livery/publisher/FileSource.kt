package ee.schimke.livery.publisher

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A catalog source tree on disk, for the common catalog code in `livery-catalog`: the JVM edge.
 * Writes create their parent directories.
 */
class FileSource(val file: File) : SourceFile {
  override val name: String get() = file.name
  override val isFile: Boolean get() = file.isFile
  override val isDirectory: Boolean get() = file.isDirectory

  override fun resolve(name: String): SourceFile = FileSource(File(file, name))

  override fun listFiles(): List<SourceFile>? = file.listFiles()?.map(::FileSource)

  override fun readBytes(): ByteArray = file.readBytes()

  override fun readText(): String = file.readText()

  override fun length(): Long = file.length()

  override fun writeBytes(bytes: ByteArray) {
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
  }

  override fun delete() {
    file.delete()
  }

  override fun toString(): String = file.path
}

/** [file] as a [SourceFile]. */
val File.source: SourceFile get() = FileSource(this)

/** The [File] behind a [SourceFile] this edge made. */
val SourceFile.file: File get() = (this as? FileSource)?.file ?: error("$this is not on disk")

fun CatalogBuilder.build(src: File, layout: Layout, serial: Long? = null): CatalogBuilder.Output = build(src.source, layout, serial)

fun CompatibilityCheck.compare(src: File, old: File?, against: String = old?.path ?: "nothing", inspect: Boolean = false): CompatibilityCheck.Report =
  compare(src.source, old?.source, against, inspect)

fun CompatibilityCheck.compareTrees(src: File, old: File?, against: String = old?.path ?: "nothing", inspect: Boolean = false): CompatibilityCheck.Comparison =
  compareTrees(src.source, old?.source, against, inspect)

/**
 * Compares the tree at [src] with [against]: a directory, or a git revision of the repository [src]
 * is in. [inspect] decodes every document too (`livery build --inspect`): its reads, emits and types.
 */
fun CompatibilityCheck.check(src: File, against: String, inspect: Boolean = false): CompatibilityCheck.Report = checkTrees(src, against, inspect).report

/** [check], keeping the built trees. */
fun CompatibilityCheck.checkTrees(src: File, against: String, inspect: Boolean = false): CompatibilityCheck.Comparison {
  File(against).takeIf { it.isDirectory }?.let { return compareTrees(src, it, against, inspect) }
  val (temp, old) = atRevision(src, against)
  return try { compareTrees(src, old, against, inspect) } finally { temp.deleteRecursively() }
}

/** A git revision: a ref, tag or hash with `~`/`^` suffixes; never an option or a path outside git. */
private val REVISION = Regex("[A-Za-z0-9][A-Za-z0-9._/~^-]{0,199}")

/**
 * [src] as it was at [revision], extracted to a temporary directory: the same path in the same
 * repository. Missing at that revision gives an empty directory (nothing published yet). Returns
 * the temporary directory (delete it when done) and the tree inside it.
 */
@Suppress("UnusedReceiverParameter")
fun CompatibilityCheck.atRevision(src: File, revision: String): Pair<File, File> {
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

fun KeptExamples.load(src: File): Map<String, Map<String, kotlinx.serialization.json.JsonObject>> = load(src.source)

fun KeptExamples.missing(src: File, base: File): List<KeptExamples.Missing> = missing(src.source, base.source)

/** Writes [missing]'s data into [src]'s kept examples; returns the files written. */
fun KeptExamples.keep(src: File, base: File): List<File> = keep(src.source, base.source).map { it.file }

fun TemplateImport.into(src: File, designId: String, name: String, document: ByteArray, target: ee.schimke.livery.protocol.Target, source: Map<String, String> = emptyMap()): TemplateImport.Imported =
  into(src.source, designId, name, document, target, source)

fun TemplateImport.contract(src: File, ref: String): ee.schimke.livery.protocol.Contract = contract(src.source, ref)

/** Writes [result] as a static catalog into [out]/<channel>/, unsigned, and returns that directory. */
@Suppress("UnusedReceiverParameter")
fun PreviewCatalog.write(result: PreviewCatalog.Result, out: File): File {
  val dir = File(out, result.channel).apply { deleteRecursively(); mkdirs() }
  result.output.files.forEach { (path, b) -> File(dir, path).apply { parentFile.mkdirs() }.writeBytes(b) }
  File(dir, ee.schimke.livery.protocol.Manifest.FILE).writeBytes(result.output.manifestBytes)
  return dir
}
