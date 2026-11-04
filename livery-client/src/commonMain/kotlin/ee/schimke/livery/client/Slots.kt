package ee.schimke.livery.client

import ee.schimke.livery.protocol.Contract
import ee.schimke.livery.protocol.Decision
import ee.schimke.livery.protocol.FeedItem
import ee.schimke.livery.protocol.TemplateEvent
import kotlinx.serialization.json.JsonObject

/**
 * Everything a player needs for one slot: the document, the values to push (or bake in), and who
 * to tell about it. Platform modules turn this into a composable, a View, RemoteViews, or a Wear
 * widget document; this layer stays platform-free.
 */
data class RenderPlan(
  val template: LoadedTemplate,
  val contract: Contract,
  val bindings: Bindings,
  val decision: Decision? = null,
  val feedItem: FeedItem? = null,
)

sealed interface PlanResult {
  data class Ready(val plan: RenderPlan) : PlanResult
  /** Draw the app's native fallback (or nothing), and report why. */
  data class Fallback(val reason: String) : PlanResult
}

fun interface EventSink {
  fun emit(event: TemplateEvent)
}

/**
 * Turns "what goes here" into a [RenderPlan] for the three ways data arrives: the app's own (live
 * content), a decision (ads, promotions), or a feed item. All three end at the same contract gate.
 */
class SlotPlanner(
  private val repository: TemplateRepository,
  private val events: EventSink,
  private val clock: Clock,
) {
  /** App-owned data, e.g. a smart-home card: [data] is recomputed and pushed on every change. */
  suspend fun forAppData(query: TemplateQuery, data: JsonObject, extra: Bindings = Bindings.EMPTY): PlanResult {
    val t = repository.resolve(query) ?: return fallback(query, "no template", null)
    return plan(t, data, extra, null, null)
  }

  suspend fun forDecision(query: TemplateQuery, decision: Decision, extra: Bindings = Bindings.EMPTY): PlanResult {
    val t = repository.ensure(decision.template, query) ?: return fallback(query, "no template for ${decision.template}", decision)
    val ref = decision.template
    if ((ref.sha256 != null && t.entry.sha256 != ref.sha256) || (ref.designId != null && t.entry.designId != ref.designId)) {
      // A decision draws as decided or not at all: its data and tracking belong to that creative.
      return fallback(query, "decided template $ref unavailable", decision)
    }
    return plan(t, decision.data, extra, decision, null)
  }

  suspend fun forFeedItem(query: TemplateQuery, item: FeedItem, extra: Bindings = Bindings.EMPTY): PlanResult {
    val t = repository.ensure(item.template, query.copy(contract = item.template.contract ?: query.contract))
      ?: return fallback(query, "no template for item ${item.id}", null)
    return plan(t, item.data, extra, null, item)
  }

  private suspend fun plan(t: LoadedTemplate, data: JsonObject, extra: Bindings, d: Decision?, f: FeedItem?): PlanResult {
    val contract = t.contract ?: return PlanResult.Fallback("contract ${t.entry.contract} unknown")
    return when (val b = ContractBinder.bind(contract, data)) {
      is BindResult.Ok -> PlanResult.Ready(RenderPlan(t, contract, b.bindings + extra, d, f))
      is BindResult.Invalid -> {
        events.emit(TemplateEvent(TemplateEvent.Type.Error, clock.nowEpochMs(), d?.placement, d?.decisionId, f?.id, t.entry.designId, t.entry.sha256, detail = b.problems.joinToString { "${it.name}: ${it.message}" }))
        PlanResult.Fallback("invalid data")
      }
    }
  }

  private fun fallback(q: TemplateQuery, reason: String, d: Decision?): PlanResult {
    events.emit(TemplateEvent(TemplateEvent.Type.Fallback, clock.nowEpochMs(), d?.placement, d?.decisionId, detail = "${q.contract}: $reason"))
    return PlanResult.Fallback(reason)
  }
}

/**
 * Routes a template's host action to a handler by the kind its contract declares. Unknown actions
 * and kinds are dropped and reported: a template can only reach what the app registered.
 */
class ActionRouter(
  private val handlers: Map<String, suspend (plan: RenderPlan, action: String, args: Map<String, String>) -> Unit>,
  private val urlAllowlist: (String) -> Boolean,
  private val events: EventSink,
  private val clock: Clock,
) {
  suspend fun dispatch(plan: RenderPlan, action: String, args: Map<String, String>) {
    val spec = plan.contract.actions[action]
    val base = TemplateEvent(TemplateEvent.Type.Action, clock.nowEpochMs(), plan.decision?.placement, plan.decision?.decisionId, plan.feedItem?.id, plan.template.entry.designId, plan.template.entry.sha256, action)
    if (spec == null) { events.emit(base.copy(type = TemplateEvent.Type.Error, detail = "undeclared action")); return }
    if (spec.kind == "open-url" && args["url"]?.let(urlAllowlist) != true) { events.emit(base.copy(type = TemplateEvent.Type.Error, detail = "url not allowed")); return }
    val h = handlers[spec.kind] ?: run { events.emit(base.copy(type = TemplateEvent.Type.Error, detail = "no handler for ${spec.kind}")); return }
    events.emit(base)
    h(plan, action, args)
  }
}
