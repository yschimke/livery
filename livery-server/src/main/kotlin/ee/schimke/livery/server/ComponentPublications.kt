package ee.schimke.livery.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import java.net.URI
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Anonymous, read-only publication pages, embeds and assets for an existing Ktor host.
 *
 * Call [install] inside the host's routing configuration. This installs no application plugins,
 * authentication or CSP/frame policy. The host owns those policies and the operator-supplied
 * viewer configuration; publication files cannot grant themselves trust or capabilities.
 */
class ComponentPublications(
  private val store: PublicationStore,
  private val viewerRoot: Path?,
  private val publicOrigin: String,
  private val viewerConfig: String = "{}",
) {
  init {
    val origin = URI(publicOrigin)
    require(origin.scheme == "https" ||
      (origin.scheme == "http" && origin.host in setOf("localhost", "127.0.0.1", "[::1]")))
    require(origin.host != null && origin.rawUserInfo == null && origin.rawQuery == null &&
      origin.rawFragment == null && origin.rawPath.orEmpty() in setOf("", "/"))
    require(Json.parseToJsonElement(viewerConfig) is JsonObject) {
      "preview viewer config must be an object"
    }
  }

  /** Registers GET and HEAD explicitly; no AutoHeadResponse plugin is needed. */
  fun install(route: Route) {
    val publicationPath = "/p/{publication}/r/{revision}/{file...}"
    route.get(publicationPath) { publication(call) }
    route.head(publicationPath) { publication(call) }
    if (viewerRoot != null) {
      val viewerPath = "/assets/livery-preview/{file...}"
      route.get(viewerPath) { viewer(call) }
      route.head(viewerPath) { viewer(call) }
    }
  }

  private suspend fun publication(call: ApplicationCall) {
    val id = call.parameters["publication"].orEmpty()
    val revision = call.parameters["revision"].orEmpty()
    // Every request, including conditional GET/HEAD, observes withdrawal before validators.
    val snapshot = when (val result = withContext(Dispatchers.IO) { store.read(id, revision) }) {
      PublicationResult.Withdrawn -> { call.unavailable(HttpStatusCode.Gone); return }
      PublicationResult.Unavailable -> { call.unavailable(); return }
      is PublicationResult.Ready -> result.snapshot
    }
    val requested = call.parameters.getAll("file").orEmpty().joinToString("/").trimEnd('/')
    if (requested.isEmpty() || requested == "embed") {
      if (!call.request.path().endsWith('/')) {
        call.response.headers.append(HttpHeaders.Location, call.request.path() + "/")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(HttpStatusCode.PermanentRedirect)
        return
      }
      val pagePath = "/p/$id/r/$revision/"
      val canonical = publicOrigin.trimEnd('/') + pagePath
      call.response.headers.append(HttpHeaders.Link,
        "<${pagePath}preview.json>; rel=\"alternate\"; type=\"application/vnd.livery.preview+json\"")
      call.response.headers.append(HttpHeaders.CacheControl, CACHE)
      call.response.headers.append("X-Content-Type-Options", "nosniff")
      call.text(html(snapshot, canonical, pagePath, requested == "embed"), ContentType.Text.Html)
    } else {
      val bytes = snapshot.files[requested]
      val type = PublicationFiles.publicationType(requested)
      if (bytes == null || type == null) call.unavailable()
      else call.bytes(bytes, ContentType.parse(type))
    }
  }

  private suspend fun viewer(call: ApplicationCall) {
    val root = viewerRoot ?: return call.unavailable()
    val name = call.parameters.getAll("file").orEmpty().joinToString("/")
    val type = PublicationFiles.viewerType(name)
    val bytes = if (type == null) null else withContext(Dispatchers.IO) {
      runCatching {
        PublicationFiles.safeFile(root, name)?.let {
          PublicationFiles.boundedRead(it, PublicationFiles.maxBundleBytes)
        }
      }.getOrNull()
    }
    if (bytes == null || type == null) call.unavailable()
    else call.bytes(bytes, ContentType.parse(type))
  }

  private suspend fun ApplicationCall.bytes(bytes: ByteArray, type: ContentType) {
    val etag = "\"${PublicationFiles.digest(bytes)}\""
    response.headers.append(HttpHeaders.CacheControl, CACHE)
    response.headers.append(HttpHeaders.ETag, etag)
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    if (request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.any {
        it.trim().removePrefix("W/") in setOf(etag, "*")
      } == true) {
      respond(HttpStatusCode.NotModified)
    } else if (request.httpMethod == HttpMethod.Head) {
      respond(HeadContent(bytes.size.toLong(), type, HttpStatusCode.OK))
    } else {
      respondBytes(bytes, type)
    }
  }

  private suspend fun ApplicationCall.unavailable(status: HttpStatusCode = HttpStatusCode.NotFound) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    text(if (status == HttpStatusCode.Gone) "This component preview has been withdrawn."
      else "Component preview unavailable.", ContentType.Text.Plain, status)
  }

  private suspend fun ApplicationCall.text(
    value: String,
    type: ContentType,
    status: HttpStatusCode = HttpStatusCode.OK,
  ) {
    if (request.httpMethod == HttpMethod.Head) {
      respond(HeadContent(value.toByteArray(Charsets.UTF_8).size.toLong(), type.withCharset(Charsets.UTF_8), status))
    } else {
      respondText(value, type, status)
    }
  }

  private class HeadContent(
    override val contentLength: Long,
    override val contentType: ContentType,
    override val status: HttpStatusCode,
  ) : OutgoingContent.NoContent()

  private fun html(bundle: PublicationSnapshot, canonical: String, pagePath: String, embed: Boolean): String {
    val escape = ::escapeHtml
    val title = escape(bundle.title)
    val description = escape(bundle.description)
    val url = escape(canonical)
    val viewer =
      if (viewerRoot == null) ""
      else
        """
        <link rel="stylesheet" href="/assets/livery-preview/viewer.css">
        <script type="module" src="/assets/livery-preview/viewer.js"></script>
        """
          .trimIndent()
    val mount =
      if (viewerRoot == null) ""
      else
        """
      <div data-livery-preview data-descriptor="${pagePath}preview.json" data-canonical="$url"
        data-mode="${if (embed) "embed" else "page"}" data-config="${escape(viewerConfig)}"></div>
    """
          .trimIndent()
    return """<!doctype html>
      <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <title>$title</title><meta name="description" content="$description">
      <link rel="canonical" href="$url">
      <link rel="alternate" type="application/vnd.livery.preview+json" href="${pagePath}preview.json">
      <meta property="og:type" content="website"><meta property="og:title" content="$title">
      <meta property="og:description" content="$description"><meta property="og:url" content="$url">
      <meta property="og:image" content="${url}thumbnail.png"><meta name="twitter:card" content="summary_large_image">
      $viewer</head><body><main class="livery-publication-page"><section data-livery-fallback aria-label="Component preview fallback">
      <h1>$title</h1><p>$description</p><img src="${pagePath}thumbnail.png" alt="$title" style="max-width:100%;height:auto">
      <p><a href="$url">Open component preview</a></p></section>$mount</main></body></html>
    """
      .trimIndent()
  }

  private fun escapeHtml(value: String): String = buildString {
    for (character in value) append(when (character) {
      '&' -> "&amp;"
      '<' -> "&lt;"
      '>' -> "&gt;"
      '"' -> "&quot;"
      '\'' -> "&#39;"
      else -> character.toString()
    })
  }

  private companion object {
    const val CACHE = "public, max-age=0, must-revalidate"
  }
}
