package network.lapis.cloud.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.canAccessDocumentAtLevel
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Hard cap on a single uploaded document version — DoS guard, rejected before fully buffering.
 * Configurable via `LAPIS_DOCUMENT_MAX_UPLOAD_MB` (whole megabytes) — Nutzer-Beschwerde 2026-09-15:
 * the previous hardcoded 25 MiB was too low for real-world documents (satzungsnahe PDFs mit
 * eingebetteten Scans, Präsentationen etc.). Default raised to 128 MiB. `coerceAtLeast` (not a
 * fail-fast `check {}`) follows the same "operator typo degrades, never refuses to start" posture
 * [network.lapis.cloud.server.webhook.WebhookConfig]'s own poll-interval/retention floors document
 * — `LAPIS_DOCUMENT_MAX_UPLOAD_MB=0` would otherwise make EVERY upload fail with
 * [HttpStatusCode.PayloadTooLarge] before a single byte is accepted, not just degrade to a smaller
 * cap.
 */
private const val DEFAULT_MAX_UPLOAD_MB = 128L
private const val MIN_MAX_UPLOAD_MB = 1L

internal fun documentMaxUploadBytes(env: (String) -> String? = System::getenv): Long =
    (env("LAPIS_DOCUMENT_MAX_UPLOAD_MB")?.trim()?.toLongOrNull() ?: DEFAULT_MAX_UPLOAD_MB)
        .coerceAtLeast(MIN_MAX_UPLOAD_MB) * 1024 * 1024

private val MAX_UPLOAD_BYTES = documentMaxUploadBytes()

/**
 * File bytes travel over these routes, not Kilua RPC (see [network.lapis.cloud.shared.rpc.IDocumentService]
 * KDoc). Security checklist applied here:
 * - **Path traversal**: `storageKey` is always server-generated (`{documentId}/{versionUuid}.bin`),
 *   never derived from the client-supplied file name — the file name is stored purely as
 *   metadata for `Content-Disposition` on download.
 * - **DoS cap**: [MAX_UPLOAD_BYTES] enforced while streaming, before the whole file is buffered.
 * - **Resource leaks**: the output stream is always closed via `use {}`; DB access goes through
 *   Exposed's `transaction {}` block.
 * - **Access control**: enforced both on listing (service-side filtering, see [DocumentService])
 *   and again here on download — never only hidden in the UI.
 *
 * Review finding fix (Runde 3, Welle "Treasurer Document Upload"): the upload route
 * (`POST /api/documents/{documentId}/versions`) previously checked only [ESCALATED_ROLES] role
 * membership and never [canAccessDocumentAtLevel] on the document it had already loaded — the
 * same gap the Runde-2 fix closed on `deleteDocument`, left open here in the sibling write path.
 * A TREASURER could therefore overwrite the current version of an `ADMIN_ONLY` document (e.g. an
 * archived Serienbrief PDF, see [network.lapis.cloud.server.routes.registerMailmergeRoutes])
 * despite never being able to read it. The route now mirrors `deleteDocument`'s shape exactly:
 * 404 for a missing or already soft-deleted row, then 403 when the caller's role/access-level
 * combination cannot read this document's [network.lapis.cloud.shared.domain.DocumentAccessLevel]
 * — both checks now run in
 * lockstep across all four write/read paths (upload, download, listVersions, deleteDocument).
 */
fun Route.registerDocumentRoutes(storageRoot: File) {
    post("/api/documents/{documentId}/versions") {
        val documentId = runCatching { Uuid.parse(call.parameters["documentId"]!!) }.getOrNull()
        if (documentId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid documentId")
            return@post
        }
        val current = resolveCurrentMember(call)

        val documentRow =
            transaction {
                DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull()
            }
        if (documentRow == null || documentRow[DocumentTable.isDeleted]) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        if (current.role !in ESCALATED_ROLES) {
            call.respond(HttpStatusCode.Forbidden, "Only Board/Treasurer/Admin may upload document versions")
            return@post
        }
        if (!current.canAccessDocumentAtLevel(documentRow[DocumentTable.accessLevel])) {
            call.respond(HttpStatusCode.Forbidden, "Not authorized to upload versions for this document")
            return@post
        }

        var uploadedFileName: String? = null
        var mimeType = "application/octet-stream"
        var changeNote: String? = null
        val versionId = Uuid.random()
        val storageKey = "$documentId/$versionId.bin"
        val targetFile = storageRoot.resolve(storageKey)
        targetFile.parentFile.mkdirs()

        var totalBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        var tooLarge = false

        call.receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> {
                    when (part.name) {
                        "changeNote" -> changeNote = part.value
                    }
                }

                is PartData.FileItem -> {
                    uploadedFileName = part.originalFileName ?: "upload.bin"
                    mimeType = part.contentType?.toString() ?: mimeType
                    targetFile.outputStream().use { out ->
                        val channel: ByteReadChannel = part.provider()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = channel.readAvailable(buffer)
                            if (read == -1) break
                            totalBytes += read
                            if (totalBytes > MAX_UPLOAD_BYTES) {
                                tooLarge = true
                                break
                            }
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                }

                else -> {}
            }
            part.release()
        }

        if (tooLarge) {
            targetFile.delete()
            call.respond(HttpStatusCode.PayloadTooLarge, "Max upload size is $MAX_UPLOAD_BYTES bytes")
            return@post
        }
        val fileName = uploadedFileName
        if (fileName == null) {
            call.respond(HttpStatusCode.BadRequest, "No file part in request")
            return@post
        }

        val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val now = DbClock.nowLocalDateTime()

        transaction {
            val nextVersionNumber =
                (DocumentVersionTable.selectAll().where { DocumentVersionTable.documentId eq documentId }.count() + 1)
                    .toInt()
            DocumentVersionTable.insert {
                it[id] = versionId
                it[DocumentVersionTable.documentId] = documentId
                it[versionNumber] = nextVersionNumber
                it[DocumentVersionTable.fileName] = fileName
                it[DocumentVersionTable.mimeType] = mimeType
                it[fileSizeBytes] = totalBytes
                it[DocumentVersionTable.storageKey] = storageKey
                it[checksumSha256] = checksum
                it[uploadedBy] = current.memberId
                it[uploadedAt] = now
                it[DocumentVersionTable.changeNote] = changeNote
            }
            DocumentTable.update({ DocumentTable.id eq documentId }) {
                it[currentVersionId] = versionId
            }
        }

        call.respond(HttpStatusCode.Created, mapOf("versionId" to versionId.toString()))
    }

    get("/api/documents/{documentId}/download") {
        val documentId = runCatching { Uuid.parse(call.parameters["documentId"]!!) }.getOrNull()
        if (documentId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid documentId")
            return@get
        }
        val requestedVersionId = call.request.queryParameters["version"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }

        val current =
            try {
                resolveCurrentMember(call)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }

        val documentRow =
            transaction {
                DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull()
            }
        if (documentRow == null || documentRow[DocumentTable.isDeleted]) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val accessLevel = documentRow[DocumentTable.accessLevel]
        if (!current.canAccessDocumentAtLevel(accessLevel)) {
            throw ForbiddenException("Not authorized to download this document")
        }

        val versionId = requestedVersionId ?: documentRow[DocumentTable.currentVersionId]
        if (versionId == null) {
            call.respond(HttpStatusCode.NotFound, "Document has no uploaded version yet")
            return@get
        }

        val versionRow =
            transaction {
                DocumentVersionTable
                    .selectAll()
                    .where { (DocumentVersionTable.id eq versionId) and (DocumentVersionTable.documentId eq documentId) }
                    .singleOrNull()
            }
        if (versionRow == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val file = storageRoot.resolve(versionRow[DocumentVersionTable.storageKey])
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, "Stored file missing")
            return@get
        }

        // Zaehlt nur einen tatsaechlichen Voll-Download: kein Range-Request (PartialContent-
        // Fortsetzung/Resume desselben Downloads), keine HEAD-Anfrage (diese Route ist nur `get`
        // registriert, also ohnehin nie HEAD -- Ktors PartialContent-Plugin beantwortet HEAD
        // separat, nie durch diesen Handler). Zwingend NACH der canAccessDocumentAtLevel-Pruefung
        // und dem versionRow-Null-Check, sonst wuerde ein abgelehnter (403) oder nicht
        // existierender (404) Versuch mitzaehlen -- Security-Pflichtpunkt dieser Welle: eine
        // Zahl, die sich ohne Berechtigung bewegt, ist ein Kanal. Atomares server-seitiges
        // Exposed-Update (`downloadCount + 1` als SQL-Ausdruck, kein Kotlin-seitiges Lesen+
        // Schreiben) -- kein Read-Modify-Write-Race bei gleichzeitigen Downloads derselben Version.
        if (call.request.headers[HttpHeaders.Range] == null) {
            transaction {
                DocumentVersionTable.update({ DocumentVersionTable.id eq versionId }) {
                    it[downloadCount] = downloadCount + 1
                }
            }
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, versionRow[DocumentVersionTable.fileName])
                .toString(),
        )
        // V1.0 Wave 2 "Aufzeichnung" fix: was `respondBytes(Files.readAllBytes(...))` -- fine for
        // the MAX_UPLOAD_BYTES (25 MiB) cap this route itself enforces on upload, but a document
        // reached via a conference recording (`registerConferenceRecordingRoutes`' own media route
        // hits this SAME underlying storage) can run to hundreds of megabytes, and a recording IS a
        // document -- see that route's own KDoc. LocalFileContent streams from disk (Ktor's
        // ReadChannelContent) instead of buffering the whole file, and as a side effect gets free
        // HTTP Range/206 support from the already-installed PartialContent plugin -- strictly
        // better for every existing document too, not only recordings.
        call.respond(LocalFileContent(file, ContentType.parse(versionRow[DocumentVersionTable.mimeType])))
    }
}
