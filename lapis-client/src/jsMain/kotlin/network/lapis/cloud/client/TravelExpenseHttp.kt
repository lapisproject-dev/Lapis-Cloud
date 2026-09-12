package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.fetch.INCLUDE
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.files.File
import org.w3c.xhr.FormData

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
}
