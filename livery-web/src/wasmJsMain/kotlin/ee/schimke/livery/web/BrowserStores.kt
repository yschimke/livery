package ee.schimke.livery.web

import okio.ByteString
import okio.ByteString.Companion.toByteString
import ee.schimke.livery.client.BlobStore
import ee.schimke.livery.client.KeyValueStore
import ee.schimke.livery.client.JsUint8Array
import ee.schimke.livery.client.toBytes
import ee.schimke.livery.client.toJs
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json

/** IndexedDB only: repository eviction and image-cache policy still own the stored entries. */
internal class BrowserStores private constructor(private val db: JsAny, private val namespace: String) {
  val blobs: BlobStore = object : BlobStore {
    override suspend fun get(sha256: String): ByteString? = read(db, key("blob", sha256)).await()?.let { toBytes(it.unsafeCast<JsUint8Array>()).toByteString() }
    override suspend fun put(sha256: String, bytes: ByteString) { write(db, key("blob", sha256), toJs(bytes.toByteArray())).await() }
    override suspend fun delete(sha256: String) { remove(db, key("blob", sha256)).await() }
    override suspend fun keys(): Set<String> = Json.decodeFromString<List<String>>(keys(db, key("blob", "")).await().toString()).toSet()
  }
  val values: KeyValueStore = object : KeyValueStore {
    override suspend fun get(key: String): String? = read(db, key("kv", key)).await()?.toString()
    override suspend fun put(key: String, value: String) { write(db, key("kv", key), value.toJsString()).await() }
    override suspend fun remove(key: String) { remove(db, key("kv", key)).await() }
  }
  private fun key(kind: String, name: String) = "$namespace/$kind/$name"

  companion object {
    suspend fun open(namespace: String): BrowserStores = BrowserStores(openDatabase().await(), namespace)
  }
}

private fun openDatabase(): Promise<JsAny> = js("""new Promise((resolve, reject) => {
  if (!globalThis.navigator?.locks) { reject(new Error('Persistent Livery caching requires Web Locks on a secure origin; use persistentCache: false otherwise')); return; }
  const request = indexedDB.open('livery-v1', 1);
  request.onupgradeneeded = () => request.result.createObjectStore('entries');
  request.onerror = () => reject(request.error);
  request.onblocked = () => reject(new Error('Livery IndexedDB upgrade blocked'));
  request.onsuccess = () => {
    const db = request.result;
    db.onversionchange = () => db.close();
    resolve(db);
  };
})""")

private fun read(db: JsAny, key: String): Promise<JsAny?> = js("""new Promise((resolve, reject) => {
  const tx = db.transaction('entries', 'readonly');
  const request = tx.objectStore('entries').get(key);
  tx.oncomplete = () => resolve(request.result ?? null);
  tx.onabort = () => reject(tx.error);
  tx.onerror = () => reject(tx.error);
})""")

private fun write(db: JsAny, key: String, value: JsAny): Promise<JsAny?> = js("""new Promise((resolve, reject) => {
  const tx = db.transaction('entries', 'readwrite');
  tx.objectStore('entries').put(value, key);
  tx.oncomplete = () => resolve(null);
  tx.onabort = () => reject(tx.error);
  tx.onerror = () => reject(tx.error);
})""")

private fun remove(db: JsAny, key: String): Promise<JsAny?> = js("""new Promise((resolve, reject) => {
  const tx = db.transaction('entries', 'readwrite');
  tx.objectStore('entries').delete(key);
  tx.oncomplete = () => resolve(null);
  tx.onabort = () => reject(tx.error);
  tx.onerror = () => reject(tx.error);
})""")

private fun keys(db: JsAny, prefix: String): Promise<JsString> = js("""new Promise((resolve, reject) => {
  const tx = db.transaction('entries', 'readonly');
  const request = tx.objectStore('entries').getAllKeys(IDBKeyRange.bound(prefix, prefix + '\uffff'));
  tx.oncomplete = () => resolve(JSON.stringify(request.result.map(key => key.slice(prefix.length))));
  tx.onabort = () => reject(tx.error);
  tx.onerror = () => reject(tx.error);
})""")
