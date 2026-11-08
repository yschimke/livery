# Contract query handler

The app owns this endpoint. A2UI sends `template: {"query": {"offer": "summer"}}`;
Livery adds the device query and the app's context (PROTOCOL.md §8.3). The client
uses `KtorDecider("https://app.example/v1/decisions/query")` and the same context
provider it uses for local placement decisions. No placement implementation needs
changing: the new `Decider` overload defaults to null.

An application using Ktor Server and its JSON content negotiation can implement:

```kotlin
post("/v1/decisions/query") {
  val request = call.receive<DecisionRequest>()
  // Authenticate at the app boundary. Agent params are data, never fetch destinations.
  val manifest = catalog.current()
  val contract = manifest?.contract(request.query.contract)
  val entry = manifest?.let { TemplateSelector.select(it, request.query) }
  val data = if (contract?.ref == "promo/banner@1") buildJsonObject {
    put("headline", "Summer offer")
    put("cta", "Shop now")
  } else null
  if (entry == null || contract == null || data == null ||
      ContractBinder.bind(contract, data) !is BindResult.Ok) {
    call.respond(HttpStatusCode.NoContent)
  } else {
    call.respond(Decision(
      placement = "assistant-offer",
      decisionId = newRequestId(),
      template = TemplateRef(sha256 = entry.sha256, contract = contract.ref),
      data = data,
      ttlSeconds = 60,
    ))
  }
}
```

`catalog` and `newRequestId` are app dependencies. The example selects from its
verified catalog and runs the same contract binder as the client. The app may
use params and context to personalize the answer. A response must still name a
document the client can trust and obtain from its configured catalog.

Give the A2UI component ordinary input data too: that is what the installed
automatic design shows while the server or document is unavailable. Server data
belongs only to its chosen design. Component events carry the A2UI component id
where the runtime exposes it; the render plan retains the server decision.
