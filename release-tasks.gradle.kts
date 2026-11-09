// Which Livery modules a release publishes, and at which version each one is named.
//
// A release used to upload every published module at the tag. `.github/scripts/maven-publish-plan.sh`
// now works out which modules changed since the version each last published at, and the release
// passes that set here as `-Plivery.publishSet=livery-client,livery-compose,…`:
//
//  * absent: no plan ran (a local build, a manual recovery run), so everything takes the tag;
//  * present but empty: the plan found nothing to publish, so nothing publishes, not even the BOM;
//  * otherwise: those modules take the tag, and every other module keeps the version it is already
//    published at, read from `publishing-manifest.json` (written by the plan, never committed).
//
// The version matters beyond the module itself: a POM names its project dependencies at THEIR
// `project.version`, and `livery-bom` pins every module at its version. A skipped module named at the
// tag would point consumers at a coordinate that was never uploaded. The plan publishes everything
// that depends on a changed module, so a module is only skipped when everything it depends on is too.
//
// Kept out of build.gradle.kts on purpose: the plan treats the root build file as an input to every
// artifact, and this file only decides which tasks run, not what they build.

@Suppress("UNCHECKED_CAST")
val publishedPaths = extra["liveryPublishedPaths"] as List<String>
val tagVersion = version.toString()

val publishSet: Set<String>? =
  providers.gradleProperty("livery.publishSet").orNull
    ?.split(",")
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.toSet()

val manifestText: String =
  layout.projectDirectory.file("publishing-manifest.json").asFile.takeIf { it.isFile }?.readText() ?: "{}"

/** The version the manifest records for [artifactId], or null. The plan writes the file's shape. */
fun recordedVersion(artifactId: String): String? =
  Regex("\"${Regex.escape(artifactId)}\"\\s*:\\s*\"([^\"]+)\"").find(manifestText)?.groupValues?.get(1)

val bomPath = ":livery-bom"
val artifactIds: Map<String, String> = publishedPaths.associateWith { it.substringAfterLast(':') }

val unknown = publishSet.orEmpty() - artifactIds.values.toSet()
require(unknown.isEmpty()) {
  "livery.publishSet names ${unknown.sorted()}, which match no published module " +
    "(known: ${artifactIds.values.sorted()}). The publish plan and this build disagree; " +
    "publishing would silently skip them."
}
require(publishSet == null || artifactIds.getValue(bomPath) !in publishSet) {
  "livery.publishSet names livery-bom; the BOM publishes whenever anything else does."
}

/** The version [path] publishes at, or carries as a dependency of a module that publishes. */
fun versionOf(path: String): String {
  val id = artifactIds.getValue(path)
  if (publishSet == null || id in publishSet || path == bomPath) return tagVersion
  return recordedVersion(id)
    ?: error(
      "$id is not in livery.publishSet and publishing-manifest.json has no version for it, so " +
        "there is no version it can safely be named at. Publish it, or let the plan record it."
    )
}

val versions: Map<String, String> = publishedPaths.associateWith(::versionOf)
publishedPaths.forEach { project(it).version = versions.getValue(it) }
// For livery-bom's constraints: artifact id -> the version a consumer gets for it.
extra["liveryPublishedVersions"] = versions.mapKeys { (path, _) -> artifactIds.getValue(path) }

val toPublish: List<String> =
  publishedPaths.filter { it != bomPath && (publishSet == null || artifactIds.getValue(it) in publishSet) }

tasks.register("publishLivery") {
  group = "publishing"
  description = "Publishes the modules this release changed, and the BOM, to Maven Central."
  // The BOM is the index of the release, so it goes up whenever anything does, naming each skipped
  // module at the version it is already published at. A plan that found nothing publishes nothing.
  if (toPublish.isNotEmpty()) dependsOn("$bomPath:publishToMavenCentral")
  toPublish.forEach { dependsOn("$it:publishToMavenCentral") }
}

tasks.register("printPublishSet") {
  group = "publishing"
  description = "Prints the artifact id and version of each module publishLivery would upload."
  notCompatibleWithConfigurationCache("Reports a configuration-time decision")
  val lines = (toPublish + if (toPublish.isEmpty()) emptyList() else listOf(bomPath))
    .map { "${artifactIds.getValue(it)}:${versions.getValue(it)}" }
    .sorted()
  doLast { lines.forEach(::println) }
}
