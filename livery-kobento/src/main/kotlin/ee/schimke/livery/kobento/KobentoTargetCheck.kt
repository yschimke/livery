package ee.schimke.livery.kobento

import ee.schimke.composeai.rcplayer.edit.RcProfileViolation
import ee.schimke.composeai.rcplayer.edit.RcProfiles
import ee.schimke.composeai.rcplayer.edit.RcTargetProfile
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireException
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target

/**
 * What in a Kobento document the [Target]'s player would not play, before it reaches the contract
 * checks. Kobento's players (its C++ engine, kobento-ts, its iOS and MCU players) play more than
 * AndroidX's: chiefly the ten 3D operations (opcodes 110–120) from an AndroidX change still in
 * review, which no released AndroidX player, and not rc-players' decoder, reads.
 *
 * - A 3D operation is refused unless the target names the [PROFILE_3D] profile. Selection only gives
 *   a template to a player whose capabilities list every profile its target names, so a `3d`
 *   template never reaches a player that hasn't said it draws 3D; and never on a surface an Android
 *   system process plays ([SYSTEM_SURFACES]), whose player the app can't choose.
 * - Otherwise, the operations and header level rc-player-edit's [RcTargetProfile] for the target's
 *   level and profiles doesn't read ([RcProfileViolation.Kind.UNSUPPORTED_OPERATION],
 *   [RcProfileViolation.Kind.HEADER_API_LEVEL]). Not the whole `validateFor`: a template reads named
 *   values by design (see livery-capture-cmp's `CaptureProfiles`).
 */
object KobentoTargetCheck {
  /** The target profile a template that draws 3D names: a player that lists it draws Kobento's 3D operations. */
  const val PROFILE_3D: String = "3d"

  /** The 3D operations (DEFINE_MESH_3D … MESH_PRIMITIVE_3D) and their names. */
  val OPS_3D: Map<Int, String> = mapOf(
    110 to "DEFINE_MESH_3D", 111 to "SET_CAMERA_3D", 112 to "MATRIX_3D_OP", 113 to "DRAW_MESH_3D",
    114 to "PAINT_3D_STATE", 115 to "SET_LIGHTS_3D", 116 to "VECTOR_EXPRESSION", 117 to "MESH_EXPRESSION_3D",
    118 to "SET_TEXTURE_3D", 120 to "MESH_PRIMITIVE_3D",
  )

  /** Surfaces an Android system process plays (a launcher, Wear's widget host, SystemUI): never 3D. */
  val SYSTEM_SURFACES: Set<String> = setOf(Surfaces.LAUNCHER_WIDGET, Surfaces.WEAR_WIDGET, Surfaces.NOTIFICATION)

  fun problems(document: ByteArray, target: Target): List<String> {
    val profileProblems = buildList {
      if (PROFILE_3D in target.profiles && target.surface in SYSTEM_SURFACES) {
        add("a ${target.surface} target can't name the '$PROFILE_3D' profile: its player is Android's, which draws no 3D")
      }
    }
    val decoded = try {
      RcDocumentCodec.decode(document, RcWireLimits())
    } catch (e: RcWireException) {
      val op = e.operationOpcode
      return profileProblems + if (op != null && op in OPS_3D) {
        listOf("uses Kobento's 3D operations (${OPS_3D[op]}, opcode $op), which no AndroidX player plays and Livery's " +
          "tools can't read yet: it can't be a Livery template until rc-players decodes them")
      } else {
        listOf("not a Remote Compose document Livery reads: ${e.message}")
      }
    } catch (e: IllegalArgumentException) {
      return profileProblems + "not a Remote Compose document Livery reads: ${e.message}"
    }
    val opcodes = decoded.operations.map { it.opcode }.toSet()
    val threeD = opcodes.filter { it in OPS_3D }.sorted()
    return buildList {
      addAll(profileProblems)
      if (threeD.isNotEmpty() && PROFILE_3D !in target.profiles) {
        add("uses Kobento's 3D operations (${threeD.joinToString { OPS_3D.getValue(it) }}): only a target with the " +
          "'$PROFILE_3D' profile, for a player that draws 3D, may")
      }
      val profile = RcTargetProfile.of(target.rcApiLevel, operationsProfiles(target))
      decoded.validateFor(profile)
        .filter { it.kind == RcProfileViolation.Kind.UNSUPPORTED_OPERATION || it.kind == RcProfileViolation.Kind.HEADER_API_LEVEL }
        // A 3D target's player is not one rc-player-edit has a table for; the rule above is its check.
        .filterNot { PROFILE_3D in target.profiles && it.opcode in OPS_3D }
        .map { it.message }.distinct()
        .forEach { add("$it (for level ${target.rcApiLevel}${target.profiles.takeIf { p -> p.isNotEmpty() }?.let { p -> ", $p" } ?: ""})") }
    }
  }

  /** The rc-player-edit operation profiles a target's `profiles` name (as livery-capture-cmp captures for them). */
  fun operationsProfiles(target: Target): Int = when {
    "widgets" in target.profiles -> RcProfiles.WIDGETS
    "glance-wear" in target.profiles -> RcProfiles.WEAR_WIDGETS
    "experimental" in target.profiles -> RcProfiles.ANDROIDX or RcProfiles.EXPERIMENTAL
    else -> RcProfiles.ANDROIDX
  }
}
