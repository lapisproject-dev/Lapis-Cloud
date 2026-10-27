package network.lapis.cloud.client

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.PartgImportApplyDto
import network.lapis.cloud.shared.domain.PartgImportApplyResultDto
import network.lapis.cloud.shared.domain.PartgImportPreviewDto
import network.lapis.cloud.shared.domain.PartgImportRejectionCode
import network.lapis.cloud.shared.domain.PartgImportRejectionDto
import org.w3c.files.File
import org.w3c.xhr.FormData
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume

/**
 * V1.9.225 -- the two-step Excel import of the party report over `XMLHttpRequest`. The server always answers with JSON; an error is a stable
 * CODE ([PartgImportRejectionCode]), never free text, so this client maps only codes ([partgRejectionText]); a server text is never shown.
 * A network failure is `Rejected(null)`. The form fields `year` and `unit` are appended BEFORE the file (the server checks them first).
 */
internal object PartyReportHttp {
    sealed interface PreviewResult {
        data class Ok(
            val preview: PartgImportPreviewDto,
        ) : PreviewResult

        data class Rejected(
            val code: PartgImportRejectionCode?,
        ) : PreviewResult
    }

    sealed interface ApplyResult {
        data class Ok(
            val result: PartgImportApplyResultDto,
        ) : ApplyResult

        data class Rejected(
            val code: PartgImportRejectionCode?,
        ) : ApplyResult
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The relative download URL of the template of [unitKey] and [year]. A unit key is `FEDERAL` or `STATE:`/`SUB:` plus a UUID, so it needs no escaping. */
    fun templateUrl(
        year: Int,
        unitKey: String,
    ): String = "/api/partg-report/template?year=$year&unit=$unitKey"

    suspend fun preview(
        year: Int,
        unitKey: String,
        file: File,
    ): PreviewResult =
        suspendCancellableCoroutine { cont ->
            val form = FormData()
            form.append("year", year.toString())
            form.append("unit", unitKey)
            form.append("file", file, file.name)
            val xhr = XMLHttpRequest()
            xhr.open("POST", "/api/partg-report/import/preview")
            xhr.withCredentials = true
            xhr.onload = {
                cont.resume(parsePreview(xhr.status.toInt(), xhr.responseText))
                Unit
            }
            xhr.onerror = {
                cont.resume(PreviewResult.Rejected(null))
                Unit
            }
            cont.invokeOnCancellation { xhr.abort() }
            xhr.send(form)
        }

    suspend fun apply(body: PartgImportApplyDto): ApplyResult =
        suspendCancellableCoroutine { cont ->
            val xhr = XMLHttpRequest()
            xhr.open("POST", "/api/partg-report/import/apply")
            xhr.withCredentials = true
            xhr.setRequestHeader("Content-Type", "application/json")
            xhr.onload = {
                cont.resume(parseApply(xhr.status.toInt(), xhr.responseText))
                Unit
            }
            xhr.onerror = {
                cont.resume(ApplyResult.Rejected(null))
                Unit
            }
            cont.invokeOnCancellation { xhr.abort() }
            xhr.send(json.encodeToString(PartgImportApplyDto.serializer(), body))
        }

    /** Pure (tested without a network). */
    internal fun parsePreview(
        status: Int,
        body: String,
    ): PreviewResult {
        if (status in 200..299) {
            val dto = runCatching { json.decodeFromString(PartgImportPreviewDto.serializer(), body) }.getOrNull()
            if (dto != null) return PreviewResult.Ok(dto)
        }
        return PreviewResult.Rejected(rejectionCode(body))
    }

    internal fun parseApply(
        status: Int,
        body: String,
    ): ApplyResult {
        if (status in 200..299) {
            val dto = runCatching { json.decodeFromString(PartgImportApplyResultDto.serializer(), body) }.getOrNull()
            if (dto != null) return ApplyResult.Ok(dto)
        }
        return ApplyResult.Rejected(rejectionCode(body))
    }

    private fun rejectionCode(body: String): PartgImportRejectionCode? =
        runCatching { json.decodeFromString(PartgImportRejectionDto.serializer(), body).code }.getOrNull()
}
