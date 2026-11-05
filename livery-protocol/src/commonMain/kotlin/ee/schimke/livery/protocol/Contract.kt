package ee.schimke.livery.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The interface between a template and the app that plays it: what data it reads, what actions it
 * may emit, and example datasets to design and preview against. Templates pin `id@major`; a minor
 * revision may only add optional inputs, list fields and actions (each marked with the [InputSpec.since]
 * minor that added it), so every template of a major keeps working (PROTOCOL.md §4.3).
 *
 * Remote Compose named values are flat, so [lists] flatten to indexed names:
 * `items.count`, `items.0.title`, `items.1.title`, … up to [ListSpec.max].
 *
 * Examples: `ha-card/tile@1` (a smart-home tile, data from the app's own connection),
 * `promo/banner@2` (data from a decision response), `feed/article@1` (data per feed item).
 */
@Serializable
data class Contract(
  val id: String,
  val major: Int,
  val minor: Int = 0,
  val title: String? = null,
  val inputs: Map<String, InputSpec> = emptyMap(),
  val lists: Map<String, ListSpec> = emptyMap(),
  val actions: Map<String, ActionSpec> = emptyMap(),
  /** Named datasets (`default`, `long-title`, `empty`, `unavailable`) in [Dataset] form. */
  val examples: Map<String, JsonObject> = emptyMap(),
  /** Where the data comes from, so tooling and agents know who fills it. */
  val dataSource: DataSource = DataSource.App,
) {
  val ref: String get() = ContractRef.format(id, major)
}

object ContractRef {
  fun format(id: String, major: Int): String = "$id@$major"

  fun parse(ref: String): Pair<String, Int> {
    val at = ref.lastIndexOf('@')
    require(at > 0) { "contract ref needs id@major: $ref" }
    return ref.substring(0, at) to ref.substring(at + 1).toInt()
  }
}

@Serializable
enum class DataSource {
  /** The app computes it (Home Assistant entity states, the app's own model). */
  App,
  /** It arrives with the placement decision or campaign (ads, promotions). */
  Decision,
  /** It arrives with each feed item. */
  Feed,
}

@Serializable
enum class ValueType {
  @SerialName("string") String,
  @SerialName("int") Int,
  @SerialName("long") Long,
  @SerialName("float") Float,
  @SerialName("bool") Bool,
  /** `#RRGGBB` / `#AARRGGBB`. */
  @SerialName("color") Color,
  /** An image the host resolves (asset id, allowlisted URL) and hands the player as a bitmap. */
  @SerialName("image") Image,
}

@Serializable
data class InputSpec(
  val type: ValueType,
  val required: Boolean = false,
  val default: JsonElement? = null,
  val maxLength: Int? = null,
  val min: Double? = null,
  val max: Double? = null,
  /** A theme role (`primary`, `onSurface`) the host fills from its own theme. */
  val role: String? = null,
  val description: String? = null,
  /**
   * The contract minor that added this input (0: there from the start). A template that reads it
   * needs an app that implements at least this minor ([TemplateEntry.requiresMinor]).
   */
  val since: Int = 0,
)

@Serializable
data class ListSpec(
  val item: Map<String, InputSpec>,
  val max: Int,
  val description: String? = null,
  /** The contract minor that added this list; its fields carry their own [InputSpec.since]. */
  val since: Int = 0,
)

/**
 * A host action a template may emit, by name with a flat payload. The client's [ActionSpec.kind]
 * says how it is handled; an app registers handlers for its own kinds.
 */
@Serializable
data class ActionSpec(
  val kind: String,
  val args: Map<String, InputSpec> = emptyMap(),
  val description: String? = null,
  /** The contract minor that added this action: an app below it can't route it. */
  val since: Int = 0,
)

/** Built-in action kinds every client understands; anything else needs an app handler. */
object ActionKinds {
  /** Open a URL, only on hosts the client allowlists. */
  const val OPEN_URL: String = "open-url"
  /** App navigation by deep link / route. */
  const val NAVIGATE: String = "navigate"
  const val DISMISS: String = "dismiss"
  /** Record an event, no side effect. */
  const val TRACK: String = "track"
  /** Re-run the decision or reload the feed item. */
  const val REFRESH: String = "refresh"
}

/** A typed named value as pushed into a player. */
@Serializable
sealed interface NamedValue {
  @Serializable @SerialName("string") data class Str(val value: String) : NamedValue
  @Serializable @SerialName("int") data class I(val value: Int) : NamedValue
  @Serializable @SerialName("long") data class L(val value: Long) : NamedValue
  @Serializable @SerialName("float") data class F(val value: Float) : NamedValue
  @Serializable @SerialName("bool") data class B(val value: Boolean) : NamedValue
  @Serializable @SerialName("color") data class Color(val argb: Long) : NamedValue
  /** Resolved by the host's image loader; never fetched by the document. */
  @Serializable @SerialName("image") data class Image(val ref: String) : NamedValue
}
