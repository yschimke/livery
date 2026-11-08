package ee.schimke.livery.instantiate

import ee.schimke.livery.client.RenderPlan

/** Height for a host that imposes none: the target's minimum, then the authored document height. */
fun RenderPlan.templateHeightDp(): Int? = template.entry.target.size.minHeightDp
  ?: runCatching { TemplateInstantiator.authoredHeightDp(template.bytes) }.getOrNull()
