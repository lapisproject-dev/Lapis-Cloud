package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseDocumentRules
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

/** Result of one file upload (receipt or attachment): created, or refused with the HTTP status the server answered. */
sealed interface TravelExpenseUploadOutcome {
    data class Created(
        val id: String,
    ) : TravelExpenseUploadOutcome

    data class Refused(
        val status: Int,
        /** The answer body, read only for the document upload (a fixed token such as `macro_content`); empty otherwise. */
        val body: String = "",
    ) : TravelExpenseUploadOutcome
}

/** Accepted file types and extensions of the client pre-check; the server's magic-byte sniffing stays authoritative. */
private val TRAVEL_EXPENSE_ALLOWED_MIME_TYPES = setOf("application/pdf", "image/jpeg", "image/png")
private val TRAVEL_EXPENSE_ALLOWED_EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png")

/**
 * Comfort pre-check of a chosen file (the server decides for real): `null` when the file looks acceptable, otherwise a fixed German
 * refusal text. HEIC/WebP and other formats are refused here with a helpful hint; the server's answer is never shown verbatim.
 */
fun travelExpenseFilePrecheck(
    name: String,
    mimeType: String,
    sizeBytes: Long,
): String? {
    val extension = name.substringAfterLast('.', "").lowercase()
    val typeKnown = mimeType in TRAVEL_EXPENSE_ALLOWED_MIME_TYPES || (mimeType.isBlank() && extension in TRAVEL_EXPENSE_ALLOWED_EXTENSIONS)
    val extensionOk = extension in TRAVEL_EXPENSE_ALLOWED_EXTENSIONS
    if (!typeKnown || !extensionOk) {
        return gettext("„%1“ hat ein nicht unterstütztes Format. Erlaubt sind PDF, JPEG und PNG.", sanitizeUntrustedI18nText(name))
    }
    if (sizeBytes > TravelExpenseAmountRules.MAX_UPLOAD_BYTES) {
        return gettext("„%1“ ist zu groß (höchstens 10 MB pro Datei).", sanitizeUntrustedI18nText(name))
    }
    return null
}

fun travelExpenseFilePrecheck(file: File): String? = travelExpenseFilePrecheck(file.name, file.type, file.size.toLong())

/** Fixed German text for a refused upload -- the response body of the server is never shown. */
fun travelExpenseUploadRefusalText(
    status: Int,
    fileName: String,
): String {
    val name = sanitizeUntrustedI18nText(fileName)
    return when (status) {
        413 -> gettext("„%1“ ist zu groß (höchstens 10 MB pro Datei).", name)
        415 -> gettext("„%1“ hat ein nicht unterstütztes Format. Erlaubt sind PDF, JPEG und PNG.", name)
        409 -> gettext("Die Abrechnung kann nicht mehr geändert werden oder die Höchstzahl ist erreicht.")
        429 -> gettext("Zu viele Anfragen – bitte kurz warten und erneut versuchen.")
        else -> gettext("Hochladen fehlgeschlagen.")
    }
}

/** Extensions the document upload offers (the server's content check stays authoritative). */
internal val TRAVEL_EXPENSE_DOCUMENT_EXTENSIONS = setOf("pdf", "xlsx", "xls")

/** Comfort pre-check of a document (PDF/Excel, at most 10 MB); `null` == looks acceptable. The server decides for real. */
internal fun travelExpenseDocumentPrecheck(
    name: String,
    sizeBytes: Long,
): String? {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension !in TRAVEL_EXPENSE_DOCUMENT_EXTENSIONS) {
        return gettext(
            "„%1“ hat ein nicht unterstütztes Format. Erlaubt sind PDF und Excel (.xlsx, .xls).",
            sanitizeUntrustedI18nText(name),
        )
    }
    if (sizeBytes > TravelExpenseDocumentRules.MAX_BYTES) {
        return gettext("„%1“ ist größer als 10 MB.", sanitizeUntrustedI18nText(name))
    }
    return null
}

/** Fixed German text for a refused document upload -- the 415 body is one of three fixed tokens, anything else is generic. */
internal fun travelExpenseDocumentRefusalText(
    status: Int,
    body: String,
    fileName: String,
): String {
    val name = sanitizeUntrustedI18nText(fileName)
    return when {
        status == 413 -> gettext("„%1“ ist größer als 10 MB.", name)
        status == 415 && body == "macro_content" ->
            gettext("„%1“ enthält Makros. Bitte speichern Sie die Datei ohne Makros (.xlsx) und laden Sie sie erneut hoch.", name)
        status == 415 && body == "encrypted" ->
            gettext("„%1“ ist verschlüsselt oder passwortgeschützt. Bitte laden Sie eine unverschlüsselte Datei hoch.", name)
        status == 415 -> gettext("„%1“ hat ein nicht unterstütztes Format. Erlaubt sind PDF und Excel (.xlsx, .xls).", name)
        status == 409 ->
            gettext(
                "Es sind höchstens %1 Unterlagen möglich. Bitte löschen Sie zuerst eine.",
                TravelExpenseDocumentRules.MAX_DOCUMENTS,
            )
        status == 429 -> gettext("Zu viele Anfragen – bitte kurz warten und erneut versuchen.")
        status == 403 -> gettext("Dafür fehlt Ihnen die Berechtigung.")
        else -> gettext("Hochladen fehlgeschlagen.")
    }
}

/**
 * Mirrors `network.lapis.cloud.server.routes.TravelExpenseReceiptRoutes.kt` and `TravelExpenseAttachmentRoutes.kt` -- file bytes travel
 * over dedicated HTTP routes, not Kilua RPC (same reasoning `DocumentHttp.kt` KDoc gives).
 * Client-side size/type checks are pure COMFORT -- the server discards the declared
 * Content-Type and derives the real MIME type from magic bytes, see that route's own KDoc.
 */
object TravelExpenseHttp {
    fun receiptDownloadUrl(receiptId: String): String = "/api/travel-expenses/receipts/$receiptId/download"

    fun attachmentDownloadUrl(attachmentId: String): String = "/api/travel-expenses/attachments/$attachmentId/download"

    // ── V1.9.199 organisation-wide documents ──

    fun documentDownloadUrl(documentId: String): String = "/api/travel-expenses/documents/$documentId/download"

    /** [title] blank == the server derives it from the file name. */
    suspend fun uploadTravelExpenseDocument(
        file: File,
        title: String,
    ): TravelExpenseUploadOutcome = postFile("/api/travel-expenses/documents", file, "documentId", title = title.trim())

    /** Returns the HTTP status (0 == no answer); 204 == renamed. */
    suspend fun renameTravelExpenseDocument(
        documentId: String,
        title: String,
    ): Int {
        val body = buildJsonObject { put("title", title) }.toString()
        val response =
            runCatching {
                window
                    .fetch(
                        "/api/travel-expenses/documents/$documentId",
                        RequestInit(
                            method = "PATCH",
                            body = body,
                            headers = js("({'Content-Type': 'application/json'})"),
                            credentials = RequestCredentials.INCLUDE,
                        ),
                    ).await()
            }.getOrNull() ?: return 0
        return response.status.toInt()
    }

    /** Returns the HTTP status (0 == no answer); 204 == deleted. */
    suspend fun deleteTravelExpenseDocument(documentId: String): Int {
        val response =
            runCatching {
                window
                    .fetch(
                        "/api/travel-expenses/documents/$documentId",
                        RequestInit(method = "DELETE", credentials = RequestCredentials.INCLUDE),
                    ).await()
            }.getOrNull() ?: return 0
        return response.status.toInt()
    }

    private suspend fun postFile(
        url: String,
        file: File,
        idKey: String,
        title: String? = null,
    ): TravelExpenseUploadOutcome {
        val formData = FormData()
        // The text field goes first on purpose; the server reads the parts in body order and accepts either order.
        if (title != null) formData.append("title", title)
        formData.append("file", file, file.name)
        val response =
            runCatching {
                window.fetch(url, RequestInit(method = "POST", body = formData, credentials = RequestCredentials.INCLUDE)).await()
            }.getOrNull() ?: return TravelExpenseUploadOutcome.Refused(0)
        if (!response.ok) {
            val body = if (title != null) runCatching { response.text().await() }.getOrDefault("") else ""
            return TravelExpenseUploadOutcome.Refused(response.status.toInt(), body)
        }
        val id =
            runCatching {
                Json
                    .parseToJsonElement(response.text().await())
                    .jsonObject[idKey]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull().orEmpty()
        return TravelExpenseUploadOutcome.Created(id)
    }

    suspend fun uploadReceiptOutcome(
        lineId: String,
        file: File,
    ): TravelExpenseUploadOutcome = postFile("/api/travel-expenses/lines/$lineId/receipts", file, "receiptId")

    suspend fun uploadAttachment(
        reportId: String,
        file: File,
    ): TravelExpenseUploadOutcome = postFile("/api/travel-expenses/reports/$reportId/attachments", file, "attachmentId")

    /** Returns `null` on success, an error text otherwise. */
    suspend fun uploadReceipt(
        lineId: String,
        file: File,
    ): String? {
        if (file.size.toLong() > TravelExpenseAmountRules.MAX_UPLOAD_BYTES) return tr("Die Datei ist zu groß (maximal 10 MB).")
        return when (val outcome = uploadReceiptOutcome(lineId, file)) {
            is TravelExpenseUploadOutcome.Created -> null
            is TravelExpenseUploadOutcome.Refused -> travelExpenseUploadRefusalText(outcome.status, file.name)
        }
    }

    /** Returns `null` on success, an error text otherwise. */
    suspend fun deleteAttachment(attachmentId: String): String? {
        val response =
            window
                .fetch(
                    "/api/travel-expenses/attachments/$attachmentId",
                    RequestInit(method = "DELETE", credentials = RequestCredentials.INCLUDE),
                ).await()
        return if (response.ok) null else tr("Löschen fehlgeschlagen.")
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
