package ee.schimke.livery.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A [Contract] declared in Kotlin, with a typed handle for every input and action, so the app that
 * fills it and the Compose code that draws it share one declaration: a renamed input breaks both
 * at compile time instead of shipping a blank field.
 *
 * Authoring only: [toContract] gives the exact protocol model, which serializes to the
 * `contracts/<name>.json` a catalog holds, and nothing on the wire changes. Validity is still
 * `ContractBinder`'s: the handles carry Livery's own [InputSpec] and [ActionSpec], and [data] only
 * checks that each value is keyed by one of this contract's handles.
 *
 * ```kotlin
 * object BannerContract : ContractDef("promo/banner", major = 1, title = "Banner", dataSource = DataSource.Decision) {
 *   val headline = string("headline", required = true, maxLength = 40)
 *   val hero = image("hero")
 *   val theme = themeRoles()
 *   val cta = action("cta", ActionKinds.OPEN_URL, arg = "url")
 *
 *   override val examples get() = mapOf("default" to mapOf(headline to "Try Premium free"))
 * }
 * ```
 *
 * Declare inputs and actions as properties, in the order they should appear: [toContract] keeps
 * declaration order. A name declared twice fails when the object is created. The constructor's
 * values are private, so `title`, `id` or `name` are free for inputs; read them from [toContract]. Within a major, a new
 * minor only adds optional inputs and actions with `since` set to it (PROTOCOL.md §4.3); a `since`
 * above [minor], or a required input with a `since`, fails here as it would in the publisher.
 */
abstract class ContractDef(
  private val id: String,
  private val major: Int,
  private val minor: Int = 0,
  private val title: String? = null,
  private val dataSource: DataSource = DataSource.App,
) {
  private val declaredInputs = LinkedHashMap<String, ContractInput>()
  private val declaredActions = LinkedHashMap<String, ContractAction>()

  /** `id@major`, as [ContractRef] formats it: what templates pin and queries ask for. */
  val ref: String get() = ContractRef.format(id, major)

  /** The inputs in declaration order. */
  val inputs: List<ContractInput> get() = declaredInputs.values.toList()

  /** The actions in declaration order. */
  val actions: List<ContractAction> get() = declaredActions.values.toList()

  /**
   * Named example datasets (`default`, `long`, `empty`), keyed by this contract's own handles: the
   * picker's previews, the renders, and what an agent starts from. Override with a getter, or
   * declare it after the inputs it uses.
   */
  open val examples: Map<String, Map<ContractInput, Any?>> get() = emptyMap()

  /** Any input, from its [InputSpec]: for a field the typed builders below don't cover. */
  protected fun input(name: String, spec: InputSpec): ContractInput {
    require(name !in declaredInputs) { "$ref declares input $name twice" }
    require(spec.since in 0..minor) { "$ref input $name: since ${spec.since} is not in 0..$minor" }
    require(!(spec.required && spec.since > 0)) { "$ref input $name: a required input can't have a since (PROTOCOL.md §4.3)" }
    return ContractInput(name, spec, this).also { declaredInputs[name] = it }
  }

  protected fun string(
    name: String,
    required: Boolean = false,
    maxLength: Int? = null,
    default: String? = null,
    description: String? = null,
    since: Int = 0,
  ): ContractInput = input(name, InputSpec(ValueType.String, required, default?.let(::JsonPrimitive), maxLength, description = description, since = since))

  protected fun int(
    name: String,
    required: Boolean = false,
    default: Int? = null,
    min: Int? = null,
    max: Int? = null,
    description: String? = null,
    since: Int = 0,
  ): ContractInput =
    input(name, InputSpec(ValueType.Int, required, default?.let(::JsonPrimitive), min = min?.toDouble(), max = max?.toDouble(), description = description, since = since))

  protected fun long(
    name: String,
    required: Boolean = false,
    default: Long? = null,
    min: Long? = null,
    max: Long? = null,
    description: String? = null,
    since: Int = 0,
  ): ContractInput =
    input(name, InputSpec(ValueType.Long, required, default?.let(::JsonPrimitive), min = min?.toDouble(), max = max?.toDouble(), description = description, since = since))

  protected fun float(
    name: String,
    required: Boolean = false,
    default: Double? = null,
    min: Double? = null,
    max: Double? = null,
    description: String? = null,
    since: Int = 0,
  ): ContractInput = input(name, InputSpec(ValueType.Float, required, default?.let(::JsonPrimitive), min = min, max = max, description = description, since = since))

  protected fun bool(
    name: String,
    required: Boolean = false,
    default: Boolean? = null,
    description: String? = null,
    since: Int = 0,
  ): ContractInput = input(name, InputSpec(ValueType.Bool, required, default?.let(::JsonPrimitive), description = description, since = since))

  /**
   * A colour; [default] is ARGB (`0xFF6750A4`) and is written as `#AARRGGBB`. With a [role] the host
   * fills it from its own theme ([ThemeRoles]).
   */
  protected fun color(
    name: String,
    required: Boolean = false,
    default: Long? = null,
    role: String? = null,
    description: String? = null,
    since: Int = 0,
  ): ContractInput =
    input(name, InputSpec(ValueType.Color, required, default?.let { JsonPrimitive(argbHex(it)) }, role = role, description = description, since = since))

  /** An image reference the host resolves (PROTOCOL.md §6.1). */
  protected fun image(name: String, required: Boolean = false, description: String? = null, since: Int = 0): ContractInput =
    input(name, InputSpec(ValueType.Image, required, description = description, since = since))

  /**
   * Theme roles as optional `role`-tagged colour inputs, one per name, in order: `theme.primary`
   * with role `primary`, and so on. The names default to [ThemeRoles.CORE]; pass
   * [ThemeRoles.MATERIAL3] for every Material 3 colour role, or any `theme.<role>` names of your own
   * design system. Returns the handles by input name.
   */
  protected fun themeRoles(names: List<String> = ThemeRoles.CORE): Map<String, ContractInput> =
    names.associateWith { name ->
      require(name.startsWith(ThemeRoles.PREFIX) && name.length > ThemeRoles.PREFIX.length) { "$ref: a theme role is ${ThemeRoles.PREFIX}<role>, not $name" }
      color(name, role = ThemeRoles.roleOf(name))
    }

  /** Any action, from its [ActionSpec]. */
  protected fun action(name: String, spec: ActionSpec): ContractAction {
    require(name !in declaredActions) { "$ref declares action $name twice" }
    require(spec.since in 0..minor) { "$ref action $name: since ${spec.since} is not in 0..$minor" }
    return ContractAction(name, spec, this).also { declaredActions[name] = it }
  }

  /** An action of [kind] (an [ActionKinds] constant or `app:<kind>`) with declared [args]. */
  protected fun action(
    name: String,
    kind: String,
    args: Map<String, InputSpec> = emptyMap(),
    description: String? = null,
    since: Int = 0,
    placement: String? = null,
  ): ContractAction = action(name, ActionSpec(kind, args, description, since, placement))

  /**
   * An action with one required string argument, [arg]: the document sends `{"<arg>": "…"}`, or,
   * for `arg = "value"`, the bare text (PROTOCOL.md §7). [maxLength] limits it.
   */
  protected fun action(
    name: String,
    kind: String,
    arg: String,
    maxLength: Int? = null,
    description: String? = null,
    since: Int = 0,
  ): ContractAction = action(name, kind, mapOf(arg to InputSpec(ValueType.String, required = true, maxLength = maxLength)), description, since)

  /** A `set` action that writes [input], one of this contract's own, back to the host (PROTOCOL.md §7.1). */
  protected fun setAction(name: String, input: ContractInput, description: String? = null, since: Int = 0): ContractAction {
    requireOwn(input)
    return action(name, ActionSpec(ActionKinds.SET, description = description, since = since, input = input.name))
  }

  /**
   * A dataset for this contract, keyed by its own handles: what the app sends to
   * `ContractBinder`. Strings, booleans and numbers are written as they are, a colour input also
   * takes ARGB as a [Long] (written `#AARRGGBB`), a [JsonElement] passes through, and a null value
   * is left out (the input's default applies). Fails on a handle of another contract.
   */
  fun data(values: Map<ContractInput, Any?>): JsonObject =
    JsonObject(
      buildMap {
        for ((input, value) in values) {
          requireOwn(input)
          if (value != null) put(input.name, encode(input, value))
        }
      }
    )

  /** The protocol model, in declaration order; serialize it with [Contract]'s own serializer. */
  fun toContract(): Contract =
    Contract(
      id = id,
      major = major,
      minor = minor,
      title = title,
      inputs = declaredInputs.mapValuesTo(LinkedHashMap()) { it.value.spec },
      actions = declaredActions.mapValuesTo(LinkedHashMap()) { it.value.spec },
      examples = examples.mapValuesTo(LinkedHashMap()) { (_, values) -> data(values) },
      dataSource = dataSource,
    )

  override fun toString(): String = "ContractDef($ref)"

  private fun requireOwn(input: ContractInput) {
    require(input.contract === this && declaredInputs[input.name] === input) { "${input.name} is an input of ${input.contract.ref}, not of $ref" }
  }

  private fun encode(input: ContractInput, value: Any): JsonElement =
    when (value) {
      is JsonElement -> value
      is String -> JsonPrimitive(value)
      is Boolean -> JsonPrimitive(value)
      is Long -> if (input.spec.type == ValueType.Color) JsonPrimitive(argbHex(value)) else JsonPrimitive(value)
      is Number -> JsonPrimitive(value)
      else -> throw IllegalArgumentException("${input.name}: unsupported value ${value::class}")
    }

  private companion object {
    fun argbHex(argb: Long): String = "#" + (argb and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')
  }
}

/** A typed handle on one input of a [ContractDef]: its name, and Livery's own [InputSpec] for it. */
class ContractInput internal constructor(val name: String, val spec: InputSpec, val contract: ContractDef) {
  override fun toString(): String = "${contract.ref}:$name"
}

/** A typed handle on one action of a [ContractDef]: its name, and Livery's own [ActionSpec] for it. */
class ContractAction internal constructor(val name: String, val spec: ActionSpec, val contract: ContractDef) {
  override fun toString(): String = "${contract.ref}:$name"
}

/**
 * Theme role names: colour inputs named `theme.<role>` with `role` set, which the host fills from
 * its own theme and a theme pack can replace (PROTOCOL.md §4.2).
 *
 * These are the Material 3 colour roles because that is the design-system vocabulary most hosts
 * already have; they are not an app concept and nothing in Livery requires them. A contract may
 * declare any subset, or roles of another design system, and a host maps whatever theme it has
 * onto them (`livery-compose-material3`'s `ColorScheme.toLiveryTheme()` for Material 3).
 */
object ThemeRoles {
  const val PREFIX: String = "theme."

  const val PRIMARY: String = "theme.primary"
  const val ON_PRIMARY: String = "theme.onPrimary"
  const val PRIMARY_CONTAINER: String = "theme.primaryContainer"
  const val ON_PRIMARY_CONTAINER: String = "theme.onPrimaryContainer"
  const val INVERSE_PRIMARY: String = "theme.inversePrimary"
  const val SECONDARY: String = "theme.secondary"
  const val ON_SECONDARY: String = "theme.onSecondary"
  const val SECONDARY_CONTAINER: String = "theme.secondaryContainer"
  const val ON_SECONDARY_CONTAINER: String = "theme.onSecondaryContainer"
  const val TERTIARY: String = "theme.tertiary"
  const val ON_TERTIARY: String = "theme.onTertiary"
  const val TERTIARY_CONTAINER: String = "theme.tertiaryContainer"
  const val ON_TERTIARY_CONTAINER: String = "theme.onTertiaryContainer"
  const val BACKGROUND: String = "theme.background"
  const val ON_BACKGROUND: String = "theme.onBackground"
  const val SURFACE: String = "theme.surface"
  const val ON_SURFACE: String = "theme.onSurface"
  const val SURFACE_VARIANT: String = "theme.surfaceVariant"
  const val ON_SURFACE_VARIANT: String = "theme.onSurfaceVariant"
  const val SURFACE_TINT: String = "theme.surfaceTint"
  const val INVERSE_SURFACE: String = "theme.inverseSurface"
  const val INVERSE_ON_SURFACE: String = "theme.inverseOnSurface"
  const val ERROR: String = "theme.error"
  const val ON_ERROR: String = "theme.onError"
  const val ERROR_CONTAINER: String = "theme.errorContainer"
  const val ON_ERROR_CONTAINER: String = "theme.onErrorContainer"
  const val OUTLINE: String = "theme.outline"
  const val OUTLINE_VARIANT: String = "theme.outlineVariant"
  const val SCRIM: String = "theme.scrim"
  const val SURFACE_BRIGHT: String = "theme.surfaceBright"
  const val SURFACE_DIM: String = "theme.surfaceDim"
  const val SURFACE_CONTAINER: String = "theme.surfaceContainer"
  const val SURFACE_CONTAINER_HIGH: String = "theme.surfaceContainerHigh"
  const val SURFACE_CONTAINER_HIGHEST: String = "theme.surfaceContainerHighest"
  const val SURFACE_CONTAINER_LOW: String = "theme.surfaceContainerLow"
  const val SURFACE_CONTAINER_LOWEST: String = "theme.surfaceContainerLowest"

  /** A small set most card designs need: accents, a surface to draw on and text on it. */
  val CORE: List<String> = listOf(PRIMARY, ON_PRIMARY, SECONDARY, TERTIARY, SURFACE, ON_SURFACE, ON_SURFACE_VARIANT)

  /** Every Material 3 colour role, in `ColorScheme`'s order. */
  val MATERIAL3: List<String> =
    listOf(
      PRIMARY, ON_PRIMARY, PRIMARY_CONTAINER, ON_PRIMARY_CONTAINER, INVERSE_PRIMARY,
      SECONDARY, ON_SECONDARY, SECONDARY_CONTAINER, ON_SECONDARY_CONTAINER,
      TERTIARY, ON_TERTIARY, TERTIARY_CONTAINER, ON_TERTIARY_CONTAINER,
      BACKGROUND, ON_BACKGROUND, SURFACE, ON_SURFACE, SURFACE_VARIANT, ON_SURFACE_VARIANT, SURFACE_TINT,
      INVERSE_SURFACE, INVERSE_ON_SURFACE, ERROR, ON_ERROR, ERROR_CONTAINER, ON_ERROR_CONTAINER,
      OUTLINE, OUTLINE_VARIANT, SCRIM,
      SURFACE_BRIGHT, SURFACE_DIM, SURFACE_CONTAINER, SURFACE_CONTAINER_HIGH, SURFACE_CONTAINER_HIGHEST,
      SURFACE_CONTAINER_LOW, SURFACE_CONTAINER_LOWEST,
    )

  /** `theme.primary` → `primary`. */
  fun roleOf(name: String): String = name.removePrefix(PREFIX)

  /** `primary` → `theme.primary`. */
  fun nameOf(role: String): String = PREFIX + role
}
