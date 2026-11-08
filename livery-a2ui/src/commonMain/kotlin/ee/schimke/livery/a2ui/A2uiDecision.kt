package ee.schimke.livery.a2ui

import ee.schimke.livery.client.*
import ee.schimke.livery.protocol.Decision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One request's answer. The host retains this only for the component/request it belongs to. */
class A2uiDecision(val request: DecisionRequest) {
  private val lock = Mutex()
  var decision: Decision? = null
    private set
  var completed: Boolean = false
    private set
  private var expiresAt: Long = Long.MAX_VALUE

  fun expired(now: Long): Boolean = completed && now >= expiresAt

  /** Off the render path: fetch the answer and its document before asking the host to replan. */
  suspend fun fetch(decider: Decider, repository: TemplateRepository, clock: Clock) = lock.withLock {
    if (completed) return@withLock
    try {
      val answer = decider.decide(request.copy(context = request.context.copy(nowEpochMs = clock.nowEpochMs())))
      val receivedAt = clock.nowEpochMs()
      if (answer != null && answer.ttlSeconds > 0 && (answer.template.contract == null || answer.template.contract == request.query.contract)) {
        repository.ensure(answer.template, request.query)
        decision = answer
      }
      val ttl = decision?.ttlSeconds ?: 30L
      expiresAt = receivedAt + ttl.coerceIn(1, 86_400) * 1000
      completed = true
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      expiresAt = clock.nowEpochMs() + 30_000
      completed = true
    }
  }
}
