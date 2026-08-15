package network.lapis.cloud.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toOutputStream
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import network.lapis.cloud.server.backup.IncompatibleBundleException
import network.lapis.cloud.server.backup.NonEmptyTargetException
import network.lapis.cloud.server.backup.OrganizationExportService
import network.lapis.cloud.server.backup.OrganizationRestoreService
import network.lapis.cloud.server.backup.RestoreIncompleteException
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import kotlin.time.Clock

/**
 * Hard cap on an uploaded restore bundle -- DoS guard, enforced while streaming the request body
 * into a temp file, before a single byte of it is ever interpreted as ZIP content. Mirrors
 * [registerDocumentRoutes]'s own `MAX_UPLOAD_BYTES` idiom exactly (byte-counting read loop, abort
 * once the cap is exceeded, delete the partial file). 512 MiB comfortably covers a Verein/Partei-
 * scale organization's full data + document blobs while still bounding worst-case disk usage per
 * concurrent upload; adjust upward per-deployment if a real organization's document corpus grows
 * past this (Review-Pflicht: revisit before a very large deployment).
 */
private const val MAX_RESTORE_BUNDLE_BYTES = 512L * 1024 * 1024

/**
 * The successful-restore response body -- MUST be a typed `@Serializable` class, not a raw
 * `mapOf(...)` of heterogeneous value types (`Int`/`Long`/`List<String>` mixed in one map).
 *
 * **Real bug, found and fixed by the V1.0 E2E wave's Scenario 6 (Organization-Snapshot-Konsistenz)
 * -- see `network.lapis.cloud.server.e2e.OrganizationSnapshotJourneyTest`.** The route used to
 * `call.respond(HttpStatusCode.OK, mapOf("tablesRestored" to Int, "totalRowCount" to Long,
 * "blobsRestored" to Int, "warnings" to List<String>))`. Ktor's `kotlinx.serialization`
 * content-negotiation infers a serializer for an untyped `Map`/`List` by inspecting its element
 * type (`SerializerLookupKt.guessSerializer`/`elementSerializer`) -- which only works when every
 * element/value is of the SAME type (see, e.g., `registerDocumentRoutes`' own
 * `mapOf("versionId" to versionId.toString())`, a `Map<String, String>`, which this failure mode
 * does NOT affect). A map whose VALUES span three different types throws
 * `IllegalStateException: Serializing collections of different element types is not yet
 * supported`, mapped by no `StatusPages` handler -- i.e. every genuinely SUCCESSFUL restore over
 * real HTTP crashed with a 500, unconditionally. This had gone completely undetected because every
 * pre-existing test either (a) only exercises the ADMIN-only role-check rejection path (never
 * reaches this `respond` call, see `BackupRoutesAuthorizationTest`) or (b) calls
 * [network.lapis.cloud.server.backup.OrganizationRestoreService.restore] directly as a plain Kotlin
 * method, bypassing this HTTP route entirely (see
 * [network.lapis.cloud.server.backup.OrganizationBackupRoundTripTest]) -- Scenario 6 was the first
 * test in this codebase to drive a genuinely successful restore through the real HTTP surface.
 */
@Serializable
private data class RestoreResultResponse(
    val tablesRestored: Int,
    val totalRowCount: Long,
    val blobsRestored: Int,
    val warnings: List<String>,
)

/**
 * Full-organization export/restore (V0.5.4 Backup-/Restore-/Datenexport-Garantie) -- ADMIN only,
 * see [OrganizationExportService]/[OrganizationRestoreService] KDoc for the full design. Travels
 * over dedicated HTTP routes, not Kilua RPC -- same reasoning [registerDocumentRoutes]/
 * [registerDsgvoRoutes] already establish: the payload can be arbitrarily large (every table in the
 * organization, plus every document's file bytes) and must stream, not be buffered into one
 * RPC-sized in-memory response. [network.lapis.cloud.shared.rpc.IBackupService] only exposes the
 * lightweight `BackupOperationLogDto` listing (counts/timestamps, no payload) over RPC.
 *
 * **Review-Pflicht**: the export bundle is as sensitive as a full database dump (it includes every
 * member's `account.password_hash`/`oidc_subject` and the DSGVO audit log -- see
 * [OrganizationExportService] KDoc's scope-decision note). This wave's security boundary is HTTPS
 * transport + the ADMIN-only check below; there is no additional at-rest encryption of the
 * downloaded/uploaded bundle file here.
 */
fun Route.registerBackupRoutes(
    database: Database,
    documentStorageRoot: File,
) {
    get("/api/backup/export") {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)

        val exportService = OrganizationExportService(database = database, documentStorageRoot = documentStorageRoot)
        val fileName = "lapis-cloud-backup-${Clock.System.now().toEpochMilliseconds()}.zip"
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString(),
        )
        call.respondBytesWriter(contentType = ContentType.Application.Zip) {
            exportService.streamExport(actor = current, sink = toOutputStream())
        }
    }

    post("/api/backup/restore") {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val allowNonEmptyTarget = call.request.queryParameters["allowNonEmptyTarget"]?.toBoolean() ?: false

        val tempFile = File.createTempFile("lapis-restore-upload-", ".zip")
        var totalBytes = 0L
        var tooLarge = false
        try {
            tempFile.outputStream().use { out ->
                val channel = call.receiveChannel()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = channel.readAvailable(buffer)
                    if (read == -1) break
                    totalBytes += read
                    if (totalBytes > MAX_RESTORE_BUNDLE_BYTES) {
                        tooLarge = true
                        break
                    }
                    out.write(buffer, 0, read)
                }
            }
            if (tooLarge) {
                call.respond(HttpStatusCode.PayloadTooLarge, "Max restore bundle size is $MAX_RESTORE_BUNDLE_BYTES bytes")
                return@post
            }

            val restoreService = OrganizationRestoreService(database = database, documentStorageRoot = documentStorageRoot)
            try {
                val result = restoreService.restore(actor = current, bundleFile = tempFile, allowNonEmptyTarget = allowNonEmptyTarget)
                call.respond(
                    HttpStatusCode.OK,
                    RestoreResultResponse(
                        tablesRestored = result.tablesRestored.size,
                        totalRowCount = result.tablesRestored.sumOf { it.rowCount },
                        blobsRestored = result.blobsRestored,
                        warnings = result.warnings,
                    ),
                )
            } catch (e: IncompatibleBundleException) {
                call.respond(HttpStatusCode.BadRequest, (e.message ?: "Incompatible bundle"))
            } catch (e: NonEmptyTargetException) {
                call.respond(HttpStatusCode.Conflict, (e.message ?: "Target database is not empty"))
            } catch (e: RestoreIncompleteException) {
                call.respond(HttpStatusCode.UnprocessableEntity, (e.message ?: "Restore did not complete successfully"))
            }
        } finally {
            tempFile.delete()
        }
    }
}
