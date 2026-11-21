package ee.schimke.livery.sessions.server

import ee.schimke.livery.sessions.SessionRelay
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Keeps a [SessionServer]'s sessions across a restart or deploy, so a person and their agent carry
 * on where they were. Everything lives in one [file] on the server's own volume, readable by the
 * server alone: it holds capabilities and drafts, never a backend credential. Writes follow changes
 * after a short pause and replace the file atomically; expired sessions are dropped on the way in.
 */
class SessionFile(private val file: File, private val server: SessionServer) {
  @Serializable
  data class Contents(
    val version: Int = 1,
    val relay: SessionRelay.Snapshot = SessionRelay.Snapshot(),
    /** Each kind's own save ([SessionKind.save]), by kind id. */
    val kinds: Map<String, JsonElement> = emptyMap(),
  )

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private val writing = Mutex()

  /** Restore what the last run saved: the kinds' stores first, then the relay. A missing or unreadable file starts empty. */
  suspend fun load() {
    val contents =
      runCatching { file.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(Contents.serializer(), it) } }
        .onFailure { System.err.println("Agent sessions not restored from $file: ${it.message}") }
        .getOrNull() ?: return
    for ((id, saved) in contents.kinds) server.kinds[id]?.restore(saved)
    server.relay.restore(contents.relay)
  }

  suspend fun save() =
    writing.withLock {
      val contents =
        Contents(
          relay = server.relay.snapshot(),
          kinds = server.kinds.mapNotNull { (id, kind) -> kind.save()?.let { id to it } }.toMap(),
        )
      file.parentFile?.mkdirs()
      val temp = File(file.parentFile, file.name + ".tmp")
      temp.writeText(json.encodeToString(Contents.serializer(), contents))
      runCatching { Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------")) }
      Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

  /**
   * Save a moment after each burst of changes, until [scope] ends. Each burst is saved at most
   * [pauseMs] after its first change; changes meanwhile are conflated, so steady traffic can't put
   * saving off forever.
   */
  fun start(scope: CoroutineScope, pauseMs: Long = 1_000): Job {
    // Compare with the count now, not the first value collected: a change made before the
    // collector subscribes would otherwise be taken for the starting point and never saved.
    val loaded = server.relay.changes.value
    return scope.launch(Dispatchers.IO) {
      server.relay.changes
        .filter { it != loaded }
        .conflate()
        .collect {
          delay(pauseMs)
          runCatching { save() }.onFailure { System.err.println("Agent sessions not saved to $file: ${it.message}") }
        }
    }
  }
}
