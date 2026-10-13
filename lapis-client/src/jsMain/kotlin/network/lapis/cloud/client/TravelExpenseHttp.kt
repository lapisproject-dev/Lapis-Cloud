package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.TravelExpenseSepaBlocker
import network.lapis.cloud.shared.domain.TravelExpenseSepaErrorDto
import network.lapis.cloud.shared.domain.TravelExpenseSepaExportRequest
import network.lapis.cloud.shared.domain.TravelExpenseSepaReexportRequest
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.fetch.INCLUDE
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.files.File
import org.w3c.xhr.FormData

/** Result of a SEPA-file request: the file was delivered (and has been handed to the browser), or the server refused with a code. */
sealed interface SepaExportOutcome {
    data class Delivered(
        val filename: String,
        val count: Int,
        val sumCents: Long,
    ) : SepaExportOutcome

    data class Refused(
        val code: String,
        val blockers: Map<String, TravelExpenseSepaBlocker> = emptyMap(),
    ) : SepaExportOutcome
}

/**
 * Mirrors `network.lapis.cloud.server.routes.TravelExpenseReceiptRoutes.kt` -- file bytes travel
 * over dedicated HTTP routes, not Kilua RPC (same reasoning `DocumentHttp.kt` KDoc gives).
 * Client-side size/type checks below are pure COMFORT -- the server discards the declared
 * Content-Type and derives the real MIME type from magic bytes, see that route's own KDoc.
 */
object TravelExpenseHttp {
    /** Comfort-only mirror of the server's 10 MiB cap (`TravelExpenseReceiptRoutes.MAX_RECEIPT_BYTES`). */
    private const val MAX_RECEIPT_BYTES = 10L * 1024 * 1024

    fun receiptDownloadUrl(receiptId: String): String = "/api/travel-expenses/receipts/$receiptId/download"

    /** Returns `null` on success, an error text otherwise. */
    suspend fun uploadReceipt(
        lineId: String,
        file: File,
    ): String? {
        if (file.size.toLong() > MAX_RECEIPT_BYTES) return tr("Die Datei ist zu groß (maximal 10 MB).")
        val formData = FormData()
        formData.append("file", file, file.name)
        val response =
            window
                .fetch(
                    "/api/travel-expenses/lines/$lineId/receipts",
                    RequestInit(method = "POST", body = formData, credentials = RequestCredentials.INCLUDE),
                ).await()
        return if (response.ok) null else response.text().await().ifBlank { tr("Upload fehlgeschlagen.") }
    }

    /** Returns `null` on success, an error text otherwise. */
    suspend fun deleteReceipt(receiptId: String): String? {
        val response =
            window
                .fetch(
                    "/api/travel-expenses/receipts/$receiptId",
                    RequestInit(method = "DELETE", credentials = RequestCredentials.INCLUDE),
                ).await()
        return if (response.ok) null else response.text().await().ifBlank { tr("Löschen fehlgeschlagen.") }
    }

    // ── V1.9.123 "SEPA-Ueberweisungsdatei" -- a POST that commits and delivers the file in ONE request (the XML is never stored). ──

    private val sepaJson = Json { ignoreUnknownKeys = true }

    suspend fun exportSepaBatch(request: TravelExpenseSepaExportRequest): SepaExportOutcome =
        postSepa("/api/travel-expenses/sepa-batches", sepaJson.encodeToString(TravelExpenseSepaExportRequest.serializer(), request))

    suspend fun reexportSepa(
        reportId: String,
        request: TravelExpenseSepaReexportRequest,
    ): SepaExportOutcome =
        postSepa(
            "/api/travel-expenses/reports/$reportId/sepa-reexport",
            sepaJson.encodeToString(TravelExpenseSepaReexportRequest.serializer(), request),
        )

    private suspend fun postSepa(
        url: String,
        body: String,
    ): SepaExportOutcome {
        val response =
            window
                .fetch(
                    url,
                    RequestInit(
                        method = "POST",
                        body = body,
                        headers = js("({'Content-Type': 'application/json'})"),
                        credentials = RequestCredentials.INCLUDE,
                    ),
                ).await()
        if (!response.ok) {
            val text = runCatching { response.text().await() }.getOrDefault("")
            val error = runCatching { sepaJson.decodeFromString(TravelExpenseSepaErrorDto.serializer(), text) }.getOrNull()
            return SepaExportOutcome.Refused(code = error?.code ?: "http_${response.status}", blockers = error?.blockers.orEmpty())
        }
        val filename =
            Regex("filename=\"?([^\";]+)\"?").find(response.headers.get("Content-Disposition").orEmpty())?.groupValues?.get(1)
                ?: "sepa-reisekosten.xml"
        val count = response.headers.get("X-Lapis-Sepa-Count")?.toIntOrNull() ?: 0
        val sumCents = response.headers.get("X-Lapis-Sepa-Sum-Cents")?.toLongOrNull() ?: 0L
        val blob = response.blob().await()
        val objectUrl = URL.createObjectURL(blob)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = objectUrl
        anchor.download = filename
        anchor.style.display = "none"
        document.body?.appendChild(anchor)
        anchor.click()
        document.body?.removeChild(anchor)
        URL.revokeObjectURL(objectUrl)
        return SepaExportOutcome.Delivered(filename = filename, count = count, sumCents = sumCents)
    }
}
