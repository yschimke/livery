package ee.schimke.livery.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

actual fun defaultHttpClient(): HttpClient = HttpClient(Js) { liveryDefaults() }
