package ee.schimke.livery.instantiate

import ee.schimke.livery.client.*
import ee.schimke.livery.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Exact documents: catalog authority first, otherwise a signed, bounded fetch on catalog hosts. */
class NamedDocuments(
  private val repository: TemplateRepository,
  private val documents: DocumentAdmission?,
  private val fetcher: RedirectingFetcher?,
) {
  suspend fun load(sha256: String, url: String?, signature: ManifestSignature?, contract: Contract, query: TemplateQuery, network: Boolean = true): LoadedTemplate? {
    return try { loadChecked(sha256, url, signature, contract, query, network) }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }
  }

  private suspend fun loadChecked(sha256: String, url: String?, signature: ManifestSignature?, contract: Contract, query: TemplateQuery, network: Boolean): LoadedTemplate? {
    if (query.contract != contract.ref) return null
    val listed = if (network) repository.ensure(TemplateRef(sha256 = sha256), query)
      else withContext(CacheOnly()) { repository.ensure(TemplateRef(sha256 = sha256), query) }
    if (signature == null) return listed // A URL alone never grants authority.
    val admission = documents ?: return null
    val bytes = listed?.bytes ?: run {
      if (!network) return null
      if (repository.current()?.templates?.any { it.sha256 == sha256 } == true) return null
      val target = url ?: repository.documentUrl(sha256)
      val hosts = repository.documentHosts()
      val allowed = { to: String -> ImageResolver.remoteHost(to)?.let { it in hosts } == true }
      if (!allowed(target)) return null
      val response = fetcher?.get(target, null, minOf(admission.maxSignedBytes.toLong(), query.caps.maxBytes)) { allowed(it) }
        as? FetchResult.Ok ?: return null
      response.bytes
    }
    val admitted = admission.admit(bytes, contract, signature) as? Admission.Admitted ?: return null
    if (admitted.sha256 != sha256 || bytes.size > query.caps.maxBytes) return null
    return listed ?: LoadedTemplate(
      TemplateEntry(sha256, bytes.size.toLong(), "a2ui:signed:${admitted.signedBy}", contract.ref, Target(query.caps.surface, query.caps.maxRcApiLevel)),
      null, bytes, LoadedTemplate.Origin.Fresh, contract,
    )
  }
}
