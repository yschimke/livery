package ee.schimke.livery.review

import ee.schimke.livery.publisher.CompatibilityCheck
import ee.schimke.livery.publisher.LiveryCommand
import java.io.File

/** `livery review`: [CompatibilityCheck]'s report as markdown, with [ReviewPreviews]' pictures. */
class ReviewCommand : LiveryCommand {
  override val name: String = "review"

  override val usage: String = """
  livery review <src> --against <dir|git-rev> --previews <dir> [--image-base <url>] [--inspect]
      [--size <w>x<h>] [--density <d>]  the check's markdown report with each changed design
                                        drawn before and after with its contract's examples:
                                        PNGs in <dir>, cited as <url><file>; exits 1 when the
                                        change isn't compatible
"""

  override fun run(args: List<String>, env: Map<String, String>): Int {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    fun fail(message: String): Int { System.err.println(message); return 64 }
    val src = File(args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: return fail(usage))
    val against = opt("--against") ?: return fail(usage)
    val out = File(opt("--previews") ?: return fail(usage))
    val size = opt("--size")?.let { s ->
      val (w, h) = s.split('x').takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return fail("--size is <w>x<h> in dp")
      (w ?: return fail("--size is <w>x<h> in dp")) to (h ?: return fail("--size is <w>x<h> in dp"))
    }
    val density = opt("--density")?.let { it.toFloatOrNull() ?: return fail("--density is a number") } ?: 2f
    val comparison = CompatibilityCheck.checkTrees(src, against, inspect = "--inspect" in args)
    val previews = if (comparison.after == null) emptyList()
      else ReviewPreviews(out, opt("--image-base").orEmpty(), size, density).render(comparison)
    print(CompatibilityCheck.markdown(comparison.report, previews))
    return if (comparison.report.ok) 0 else 1
  }
}
