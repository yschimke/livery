package ee.schimke.livery.client

import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * The browser's WebCrypto: SHA-256 (`crypto.subtle.digest`) and Ed25519 verification
 * (`crypto.subtle.verify`), which every current browser supports. A key or signature WebCrypto
 * refuses verifies as false, as [JavaCrypto] does.
 */
object WebCrypto : Crypto {
  override suspend fun sha256Hex(bytes: ByteArray): String {
    val digest = toBytes(asUint8(digest(toJs(bytes)).await()))
    return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
  }

  override suspend fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
    val key = importEd25519(toJs(publicKey)).await<JsAny>()
    verify(key, toJs(signature), toJs(message)).await<JsBoolean>().toBoolean()
  }.getOrDefault(false)
}

/** A JavaScript `Uint8Array`. */
external interface JsUint8Array : JsAny

/** [bytes] as a new `Uint8Array`. */
fun toJs(bytes: ByteArray): JsUint8Array {
  val a = newUint8(bytes.size)
  for (i in bytes.indices) setUint8(a, i, bytes[i].toInt() and 0xff)
  return a
}

/** A `Uint8Array`'s bytes. */
fun toBytes(a: JsUint8Array): ByteArray = ByteArray(uint8Length(a)) { getUint8(a, it).toByte() }

private fun newUint8(size: Int): JsUint8Array = js("new Uint8Array(size)")

private fun setUint8(a: JsUint8Array, i: Int, v: Int): Unit = js("{ a[i] = v; }")

private fun getUint8(a: JsUint8Array, i: Int): Int = js("a[i]")

private fun uint8Length(a: JsUint8Array): Int = js("a.length")

private fun asUint8(buffer: JsAny): JsUint8Array = js("new Uint8Array(buffer)")

private fun digest(data: JsUint8Array): Promise<JsAny> = js("crypto.subtle.digest('SHA-256', data)")

private fun importEd25519(key: JsUint8Array): Promise<JsAny> =
  js("crypto.subtle.importKey('raw', key, { name: 'Ed25519' }, false, ['verify'])")

private fun verify(key: JsAny, signature: JsUint8Array, message: JsUint8Array): Promise<JsBoolean> =
  js("crypto.subtle.verify({ name: 'Ed25519' }, key, signature, message)")
