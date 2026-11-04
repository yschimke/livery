package ee.schimke.livery.client

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Design
import ee.schimke.livery.protocol.InputSpec
import ee.schimke.livery.protocol.InstallPolicy
import ee.schimke.livery.protocol.Manifest
import ee.schimke.livery.protocol.PlayerCapabilities
import ee.schimke.livery.protocol.SizeRange
import ee.schimke.livery.protocol.Surfaces
import ee.schimke.livery.protocol.Target
import ee.schimke.livery.protocol.TemplateEntry
import ee.schimke.livery.protocol.ValueType

/** Hashing for tests without a platform: content-addressed by a fake but stable digest. */
object FakeCrypto : Crypto {
  override suspend fun sha256Hex(bytes: ByteArray): String = sha(bytes)

  fun sha(bytes: ByteArray): String {
    var h = 1469598103934665603L
    for (b in bytes) { h = h xor (b.toLong() and 0xFF); h *= 1099511628211L }
    return h.toULong().toString(16).padStart(16, '0').repeat(4)
  }
  override suspend fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray) =
    signature.contentEquals(publicKey + message.size.toString().encodeToByteArray())
}

object Fx {
  val inApp = PlayerCapabilities(Surfaces.IN_APP, maxRcApiLevel = 8, profiles = setOf("androidx", "core"))
  val launcher = PlayerCapabilities(Surfaces.LAUNCHER_WIDGET, maxRcApiLevel = 7, profiles = setOf("widgets"), livePush = false, maxBytes = 50_000)
  val wearSmall = PlayerCapabilities(Surfaces.WEAR_WIDGET, 7, setOf("glance-wear"), livePush = false, themes = setOf("baked:dark"), container = "wear:small")

  val tile = Contract(
    id = "live/status-tile", major = 1,
    inputs = mapOf(
      "name" to InputSpec(ValueType.String, required = true, maxLength = 10),
      "is_on" to InputSpec(ValueType.Bool),
      "level" to InputSpec(ValueType.Float, min = 0.0, max = 1.0),
    ),
  )

  fun blob(name: String) = "rc:$name".encodeToByteArray()

  fun entry(design: String, name: String, target: Target, revoked: Boolean = false): TemplateEntry {
    val b = blob(name)
    return TemplateEntry(FakeCrypto.sha(b), b.size.toLong(), design, "live/status-tile@1", target, revoked = revoked)
  }

  val inAppV7 = Target(Surfaces.IN_APP, 7, listOf("androidx"))
  val inAppV8 = Target(Surfaces.IN_APP, 8, listOf("androidx"))
  val inAppV9 = Target(Surfaces.IN_APP, 9, listOf("androidx"))
  val launcherT = Target(Surfaces.LAUNCHER_WIDGET, 7, listOf("widgets"), SizeRange(110, 400, 40, 400))
  val wearSmallT = Target(Surfaces.WEAR_WIDGET, 7, listOf("glance-wear"), theme = "baked:dark", container = "wear:small")

  fun manifest(serial: Long = 1, expires: Long = 10_000_000) = Manifest(
    catalog = "test", serial = serial, generatedAtEpochMs = 0, expiresAtEpochMs = expires, ttlSeconds = 60,
    contracts = listOf(tile),
    designs = listOf(
      Design("tile-default", "live/status-tile@1", "Built-in", install = InstallPolicy.Always),
      Design("tile-retro", "live/status-tile@1", "Retro", variant = "retro", install = InstallPolicy.OnRequest),
    ),
    templates = listOf(
      entry("tile-default", "default-v7", inAppV7),
      entry("tile-default", "default-v8", inAppV8),
      entry("tile-default", "default-v9", inAppV9),
      entry("tile-default", "default-launcher", launcherT),
      entry("tile-default", "default-wear-small", wearSmallT),
      entry("tile-retro", "retro-v7", inAppV7),
      entry("tile-retro", "retro-launcher", launcherT),
    ),
  )
}
