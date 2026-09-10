package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.BankStatementImportRejectionDto
import network.lapis.cloud.shared.domain.BankStatementImportResultDto
import org.w3c.fetch.INCLUDE
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.files.File
import org.w3c.xhr.FormData

/**
 * Welle V1.4.5.1.1 -- mirrors `network.lapis.cloud.server.routes.BankStatementRoutes.kt`'s upload
 * route. File bytes travel over THIS Ktor route, not Kilua RPC -- see `IBankStatementService`
 * KDoc. Structured after [BackupHttp] (a pure, DOM-free `parseXOutcome(status, bodyText)` function,
 * directly unit-testable), not [DocumentHttp] -- the server always answers with a JSON body here
 * (success or [BankStatementImportRejectionDto]), unlike [DocumentHttp.uploadVersion]'s plain-text
 * error path.
 *
 * CSRF-Haltung bewusst von [DocumentHttp]/[BackupHttp] geerbt: reine Same-Origin-Cookie-Session,
 * kein Token-Mechanismus existiert in diesem Codebase auf IRGENDEINEM Upload-Pfad -- hier wird
 * nichts ad hoc erfunden.
 */
object BankStatementHttp {
    private const val IMPORT_URL = "/api/bank-statements/import"

    /**
     * Spiegel von `BankStatementImportService.MAX_UPLOAD_BYTES` (5 MiB). Reine UX-Vorpruefung --
     * die eigentliche Grenze bleibt das serverseitige 413, das dieselbe Zahl durchsetzt. Bei
     * Aenderung dort HIER von Hand nachziehen (gleiche Hand-Sync-Konvention wie
     * `RestoreSuccessResult` gegenueber `BackupRoutes.kt`).
     */
    const val MAX_UPLOAD_BYTES: Long = 5L * 1024 * 1024

    suspend fun import(file: File): BankStatementImportOutcome {
        val formData = FormData()
        formData.append("file", file, file.name)
        val response =
            window
                .fetch(
                    IMPORT_URL,
                    RequestInit(method = "POST", body = formData, credentials = RequestCredentials.INCLUDE),
                ).await()
        val bodyText = response.text().await()
        return parseBankStatementImportOutcome(response.status.toInt(), bodyText)
    }
}

/**
 * The three real outcomes of a `POST /api/bank-statements/import` call. [Other] covers every
 * response this client did not anticipate (a bare 5xx, an empty body, an unknown future rejection
 * code) -- surfaced honestly rather than silently mapped onto [Rejected].
 */
sealed interface BankStatementImportOutcome {
    data class Success(
        val result: BankStatementImportResultDto,
    ) : BankStatementImportOutcome

    data class Rejected(
        val status: Int,
        val rejection: BankStatementImportRejectionDto,
    ) : BankStatementImportOutcome

    data class Other(
        val status: Int,
        val message: String,
    ) : BankStatementImportOutcome
}

private val bankStatementResponseJson = Json { ignoreUnknownKeys = true }

/**
 * Pure HTTP-status + response-body -> [BankStatementImportOutcome] mapping -- no network/DOM
 * dependency, directly unit-testable (see `BankStatementHttpTest.kt`), same idiom
 * [parseRestoreOutcome] already establishes.
 *
 * Stolperfalle (plan §11.2): `Json { ignoreUnknownKeys = true }` guards against unknown FIELDS in
 * the response, not an unknown [network.lapis.cloud.shared.domain.BankStatementRejectionCode]
 * ENUM LITERAL -- a future eleventh server-side code this client build does not know about yet
 * would otherwise throw a `SerializationException` out of this function. `runCatching` below turns
 * that into an honest [BankStatementImportOutcome.Other] instead of crashing the screen.
 */
fun parseBankStatementImportOutcome(
    status: Int,
    bodyText: String,
): BankStatementImportOutcome =
    when (status) {
        200 ->
            runCatching { bankStatementResponseJson.decodeFromString(BankStatementImportResultDto.serializer(), bodyText) }
                .fold(
                    onSuccess = { BankStatementImportOutcome.Success(it) },
                    onFailure = { BankStatementImportOutcome.Other(status, gettext("Antwort des Servers konnte nicht gelesen werden.")) },
                )
        413, 400, 429, 422, 409 ->
            runCatching { bankStatementResponseJson.decodeFromString(BankStatementImportRejectionDto.serializer(), bodyText) }
                .fold(
                    onSuccess = { BankStatementImportOutcome.Rejected(status, it) },
                    onFailure = { BankStatementImportOutcome.Other(status, gettext("Unbekannter Fehler (HTTP %1).", status)) },
                )
        else -> BankStatementImportOutcome.Other(status, gettext("Unbekannter Fehler (HTTP %1).", status))
    }

/** Rein: clientseitige Groessen-Vorpruefung, Grenzwert inklusive (> Limit = zu gross). */
fun exceedsBankStatementUploadLimit(fileSizeBytes: Long): Boolean = fileSizeBytes > BankStatementHttp.MAX_UPLOAD_BYTES
