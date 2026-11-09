package ee.schimke.livery.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import ee.schimke.livery.client.DesignSummary
import ee.schimke.livery.client.PlanResult
import ee.schimke.livery.client.TemplateRepository

/**
 * A slot whose content Livery decides: [plan] is one of `SlotPlanner.forAppData`, `forDecision`
 * or `forFeedItem`, re-run whenever [key] changes (new data, a new decision, another item, a
 * chosen design: everything [plan] reads from the app) and whenever the repository's
 * [TemplateRepository.revision] does (a refreshed catalog, an install). While
 * it runs, and when it falls back, the slot draws [fallback]: the app's native version, or nothing.
 */
@Composable
fun LiverySlot(
  key: Any?,
  host: LiveryHost,
  modifier: Modifier = Modifier,
  fallback: @Composable (reason: String?) -> Unit = {},
  plan: suspend () -> PlanResult,
) {
  // A refreshed catalog or an install can change the answer, so the slot replans on either.
  val revision by host.repository.revision.collectAsState()
  // A slot that already had an answer at this revision (scrolled back, reopened) draws it at once
  // rather than its fallback for a frame; it still replans, and a new answer replaces it.
  val state = remember(key, host) { mutableStateOf(host.resources.lastPlan(key, revision)) }
  LaunchedEffect(state, revision) {
    state.value = plan().also { host.resources.keepPlan(key, revision, it) }
  }
  val result = state.value
  when (val r = result) {
    null -> fallback(null)
    is PlanResult.Fallback -> fallback(r.reason)
    is PlanResult.Ready -> LiveryTemplate(r.plan, host, modifier)
  }
}

/**
 * The designs a picker offers for [contract], refreshed with the catalog and after an install or
 * uninstall ([TemplateRepository.revision]), or when [key] changes.
 */
@Composable
fun rememberDesigns(repository: TemplateRepository, contract: String, key: Any? = null): List<DesignSummary> {
  val revision by repository.revision.collectAsState()
  val designs by produceState(emptyList<DesignSummary>(), repository, contract, key, revision) { value = repository.designs(contract) }
  return designs
}

/**
 * A plain design list: title, author, whether it is installed, and its first preview drawn by
 * [preview] (the app's image loader for `PreviewRef.url`). Apps with their own design system draw
 * [rememberDesigns] themselves; this is the minimal default.
 */
@Composable
fun DesignPicker(
  designs: List<DesignSummary>,
  onChoose: (DesignSummary) -> Unit,
  modifier: Modifier = Modifier,
  preview: @Composable (DesignSummary) -> Unit = {},
) {
  Column(modifier) {
    for (d in designs) {
      Row(Modifier.clickable { onChoose(d) }) {
        preview(d)
        BasicText(
          buildString {
            append(d.design.title)
            d.design.author?.let { append(" · ").append(it) }
            append(if (d.installed) " · installed" else " · ${d.downloadBytes / 1024} KB")
          },
        )
      }
    }
  }
}
