import java.util.Properties
import java.util.zip.ZipFile
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

kotlin { jvmToolchain(17) }

dependencies {
  api(project(":livery-protocol"))
  api(project(":livery-client"))
  // The catalog logic, in common code; this module is its JVM edge (files, git, the CLI).
  api(project(":livery-catalog"))
  // `livery build --a2ui`: the catalog's contracts as A2UI components.
  implementation(project(":livery-a2ui"))
  // TemplateInspector, for `livery build --inspect`: what a document really reads and emits.
  implementation(project(":livery-instantiate"))
  testImplementation(kotlin("test"))
  testImplementation(platform(libs.rc.players.bom))
  testImplementation(libs.rc.player.protocol)
}

application {
  mainClass.set("ee.schimke.livery.publisher.MainKt")
  applicationName = "livery"
}

// Optional commands (LiveryCommand), shipped in the CLI's distribution but not in this library's
// dependencies: `livery ui-builder …`, and `livery review` (which brings the renderer).
val cliExtensions: Configuration by configurations.creating { isCanBeConsumed = false }
dependencies {
  cliExtensions(project(":livery-uibuilder"))
  cliExtensions(project(":livery-review"))
}
tasks.named<CreateStartScripts>("startScripts") { classpath = classpath!! + cliExtensions }
distributions { main { contents { into("lib") { from(cliExtensions) } } } }
tasks.withType<Sync>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.withType<AbstractArchiveTask>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

// `livery play --surface launcher-widget` runs Android 16's own widget player under Robolectric, in
// a child JVM (Java 21) with this release's Android classes: livery-verify's runner and the
// libraries it plays a template with, resolved as an Android app's release build resolves them,
// and each AAR's classes.jar taken out. It goes in lib/android-play, kept apart from the CLI's own
// classpath. Robolectric downloads its Android SDK on first use.
abstract class AarClasses : TransformAction<TransformParameters.None> {
  @get:InputArtifact abstract val aar: Provider<FileSystemLocation>
  override fun transform(outputs: TransformOutputs) {
    val file = aar.get().asFile
    ZipFile(file).use { zip ->
      val entry = zip.getEntry("classes.jar") ?: return
      val out = outputs.file("${file.nameWithoutExtension}.jar")
      zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
    }
  }
}
val artifactType = Attribute.of("artifactType", String::class.java)
dependencies {
  registerTransform(AarClasses::class) {
    from.attribute(artifactType, "aar")
    to.attribute(artifactType, "android-classes-jar")
  }
}
val androidPlay: Configuration by configurations.creating {
  isCanBeConsumed = false
  attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute, org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm)
    attribute(com.android.build.api.attributes.BuildTypeAttr.ATTRIBUTE, objects.named("release"))
    attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.ANDROID))
  }
}
dependencies {
  androidPlay(project(":livery-verify"))
  androidPlay(libs.robolectric)
  androidPlay("junit:junit:4.13.2")
}
// Livery's own projects come as their full jars (classes and resources: the runner's
// robolectric.properties); other libraries' AARs as their classes jar, through the transform above,
// and plain jars as they are. Each is copied under its component's name: Android projects' jars are
// all called full.jar.
val androidPlayClasses = androidPlay.incoming.artifactView {
  attributes { attribute(artifactType, "android-classes-jar") }
  componentFilter { it !is ProjectComponentIdentifier }
}.artifacts
val androidPlayJars = androidPlay.incoming.artifactView { attributes { attribute(artifactType, "jar") } }.artifacts

// Robolectric's Config names android.app.Application in its defaults, and loads on the plain JVM's
// class path before any sandbox: a stub class lets it load there. The sandbox plays Android's own.
val androidStub: SourceSet by sourceSets.creating
val androidStubJar by tasks.registering(Jar::class) {
  from(androidStub.output)
  archiveFileName.set("android-application-stub.jar")
  destinationDirectory.set(layout.buildDirectory.dir("android-stub"))
}

val DOWNLOADED = setOf("org.robolectric:nativeruntime-dist-compat")
val collectAndroidPlay by tasks.registering {
  val out = layout.buildDirectory.dir("android-play")
  val classes = androidPlayClasses.resolvedArtifacts
  val jars = androidPlayJars.resolvedArtifacts
  inputs.files(androidPlayClasses.artifactFiles, androidPlayJars.artifactFiles)
  outputs.dir(out)
  doLast {
    val dir = out.get().asFile.apply { deleteRecursively(); mkdirs() }
    // An AAR's classes come from the classes view; its jar view would be empty or the same.
    val fromClasses = classes.get().map { it.id.componentIdentifier }.toSet()
    val downloads = Properties()
    for (a in classes.get() + jars.get().filter { it.id.componentIdentifier !in fromClasses }) {
      val id = a.id.componentIdentifier
      // Robolectric's native graphics, for every desktop platform, is most of the size: `livery
      // play` downloads it from Maven Central on first use instead.
      if (id is ModuleComponentIdentifier && "${id.group}:${id.module}" in DOWNLOADED) {
        downloads.setProperty("${id.group}:${id.module}", id.version)
        continue
      }
      val name = id.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
      a.file.copyTo(dir.resolve("$name-${a.file.name}"), overwrite = true)
    }
    dir.resolve("downloads.properties").outputStream().use { downloads.store(it, "Downloaded by livery play on first use") }
  }
}
distributions { main { contents { into("lib/android-play") { from(collectAndroidPlay, androidStubJar) } } } }
