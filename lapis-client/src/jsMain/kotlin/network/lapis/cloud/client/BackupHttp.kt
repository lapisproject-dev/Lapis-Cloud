package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.fetch.INCLUDE
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.files.File

/**
 * Compliance UI wave, screen 2 of 5 ("Backup & Wiederherstellung") -- mirrors
 * `network.lapis.cloud.server.routes.BackupRoutes.kt`'s two raw-HTTP routes. The full-organization
 * export/restore bundle bytes never travel over Kilua RPC at all (`IBackupService` only exposes the
 * lightweight `listOperations` metadata listing) -- same "dedicated HTTP route for a large/streamed
 * payload" reasoning [DocumentHttp] already establishes for document file bytes.
 *
 * Unlike [DocumentHttp.uploadVersion] (`multipart/form-data` via `FormData`), the server's restore
 * route reads the request body directly via `call.receiveChannel()` -- a raw byte stream, not a
 * multipart form (see `BackupRoutes.kt`) -- so [restore] POSTs the selected native `File` (which
 * implements `Blob`) as the request body directly, with no `FormData` wrapper.
 */
object BackupHttp {
    /** Same-origin GET, session cookie travels automatically -- see `renderBackupScreen`'s plain `link(...)` call, the exact idiom `DocumentsScreen`'s version-download link already uses. */
    const val EXPORT_URL = "/api/backup/export"

    private const val RESTORE_URL = "/api/backup/restore"

    suspend fun restore(
        file: File,
        allowNonEmptyTarget: Boolean,
    ): RestoreOutcome {
        val url = "$RESTORE_URL?allowNonEmptyTarget=$allowNonEmptyTarget"
        val response =
            window
                .fetch(
                    url,
                    RequestInit(method = "POST", body = file, credentials = RequestCredentials.INCLUDE),
                ).await()
        val bodyText = response.text().await()
        return parseRestoreOutcome(response.status.toInt(), bodyText)
    }
}

/**
 * Mirrors the server's own private `RestoreResultResponse` (`BackupRoutes.kt`) field for field --
 * duplicated by hand rather than shared from `lapis-shared` because the server type deliberately
 * stays private to that HTTP route file (it is not, and must never become, part of the Kilua RPC
 * DTO surface -- see that class's own KDoc on the real `mapOf(...)`-serialization bug this exact
 * response shape already survived once during the V1.0 E2E wave). Keep in sync by hand if the
 * server route's response shape ever changes.
 */
@Serializable
data class RestoreSuccessResult(
    val tablesRestored: Int,
    val totalRowCount: Long,
    val blobsRestored: Int,
    val warnings: List<String>,
)

/**
 * The Backup screen's design decision "three distinct restore error paths" -- each of
 * `BackupRoutes.kt`'s three real server exceptions (`IncompatibleBundleException`=400,
 * `NonEmptyTargetException`=409, `RestoreIncompleteException`=422) must render as its own distinct
 * message, never folded into one generic error toast (`RestoreIncompleteException` in particular is
 * NOT a safe-to-retry outcome, unlike the other two -- see that exception's KDoc "accepted
 * partial-write tradeoff"). [Other] covers every response this client did not anticipate (e.g. a
 * 413 Payload Too Large from the server's `MAX_RESTORE_BUNDLE_BYTES` guard, or a bare 5xx) --
 * surfaced honestly rather than silently mapped onto one of the three named cases.
 */
sealed interface RestoreOutcome {
    data class Success(
        val result: RestoreSuccessResult,
    ) : RestoreOutcome

    data class IncompatibleBundle(
        val message: String,
    ) : RestoreOutcome

    data class NonEmptyTarget(
        val message: String,
    ) : RestoreOutcome

    data class Incomplete(
        val message: String,
    ) : RestoreOutcome

    data class Other(
        val status: Int,
        val message: String,
    ) : RestoreOutcome
}

private val restoreResultJson = Json { ignoreUnknownKeys = true }

/**
 * Pure HTTP-status + response-body -> [RestoreOutcome] mapping -- no network/DOM dependency, so
 * this is directly unit-testable (see `BackupHttpTest.kt`) despite living in a `jsMain` file that
 * otherwise needs a browser `fetch`.
 */
fun parseRestoreOutcome(
    status: Int,
    bodyText: String,
): RestoreOutcome =
    when (status) {
        200 ->
            runCatching { restoreResultJson.decodeFromString(RestoreSuccessResult.serializer(), bodyText) }
                .fold(
                    onSuccess = { RestoreOutcome.Success(it) },
                    onFailure = {
                        RestoreOutcome.Other(
                            status,
                            gettext("Antwort des Servers konnte nicht gelesen werden: %1", bodyText),
                        )
                    },
                )
        400 -> RestoreOutcome.IncompatibleBundle(bodyText)
        409 -> RestoreOutcome.NonEmptyTarget(bodyText)
        422 -> RestoreOutcome.Incomplete(bodyText)
        else -> RestoreOutcome.Other(status, bodyText.ifBlank { gettext("Unbekannter Fehler (HTTP %1).", status) })
    }
