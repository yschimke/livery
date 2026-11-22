package ee.schimke.livery.uibuilder.capture

import kotlinx.serialization.json.JsonObject

/**
 * An app's UI Builder catalog, as Livery draws designs built from it: the components for its
 * `componentId`s. On the JVM, `UiBuilderCapture` finds every one on the classpath with
 * `java.util.ServiceLoader`, so an app registers its catalog by listing its class in
 * `META-INF/services/ee.schimke.livery.uibuilder.capture.UiBuilderCatalog`, and the Gradle plugin's
 * `capture { uiBuilder() }` needs nothing else.
 */
interface UiBuilderCatalog {
  /** The UI Builder catalogs (a document's `catalogPin.systemId`) this draws; empty for any. */
  val systemIds: Set<String>

  /** The components, by `componentId`. They may replace [UiLayouts]'s. */
  val components: Map<String, UiComponent>

  /**
   * The font families its components name (a brand face in the catalog's `fonts/fonts.json`): each
   * template drawn with this catalog lists them in its sidecar's `fonts`, so their files download
   * with it. Empty by default.
   */
  val fonts: Collection<String> get() = emptyList()

  /**
   * The document as it should be drawn for a Livery template: unchanged by default. A catalog whose
   * designs name a sample of their data (an example entity to preview with) rewrites it here to
   * the names its contract declares.
   */
  fun prepare(document: JsonObject): JsonObject = document
}
