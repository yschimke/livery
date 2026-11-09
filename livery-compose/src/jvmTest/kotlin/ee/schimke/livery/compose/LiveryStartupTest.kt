package ee.schimke.livery.compose

import ee.schimke.livery.client.Clock
import ee.schimke.livery.client.FetchResult
import ee.schimke.livery.client.Fetcher
import ee.schimke.livery.client.JavaCrypto
import ee.schimke.livery.client.MemoryBlobStore
import ee.schimke.livery.client.MemoryKeyValueStore
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.StaticSource
import ee.schimke.livery.client.TemplateRepository
import ee.schimke.livery.client.TrustPolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A splash screen waits on [LiveryStartup]: until the first screen is prepared, and never forever. */
class LiveryStartupTest {
  private val repository = TemplateRepository(
    StaticSource("https://catalog.invalid/"),
    Fetcher { _, _ -> FetchResult.Failed(503, "offline") },
    JavaCrypto,
    TrustPolicy.AllowUnsigned,
    MemoryBlobStore(),
    MemoryKeyValueStore(),
    Clock { 1_000L },
    emptyList(),
  )
  private val resources = LiveryResources(repository)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Test fun readyOnceTheFirstScreenIsPrepared() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val startup = resources.startup(scope, timeoutMillis = 60_000) {
      gate.await()
      prepareSlot("home.top", null) { PlanResult.Fallback("no catalog yet") }
    }
    assertFalse(startup.isReady, "the splash stays while the screen is being prepared")
    gate.complete(Unit)
    withTimeout(5_000) { startup.await() }
    assertTrue(startup.isReady)
    assertNotNull(resources.lastPlan("home.top", null, repository.revision.value), "the slot's answer is waiting for it")
    scope.cancel()
  }

  @Test fun readyAtTheTimeLimitWhilePreparationCarriesOn() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()
    val startup = resources.startup(scope, timeoutMillis = 50) {
      gate.await()
      finished.complete(Unit)
    }
    withTimeout(5_000) { startup.await() }
    assertFalse(finished.isCompleted, "the splash ended before preparation did")
    gate.complete(Unit)
    withTimeout(5_000) { finished.await() }
    scope.cancel()
  }

  @Test fun aFailedPreparationEndsTheWait() = runBlocking {
    val startup = resources.startup(scope, timeoutMillis = 60_000) { error("disk unavailable") }
    withTimeout(5_000) { startup.await() }
    scope.cancel()
  }
}
