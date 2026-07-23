package network.lapis.cloud.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.security.canAccessDocumentAtLevel
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Hard cap on a single uploaded document version — DoS guard, rejected before fully buffering. */
private const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

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
        if (documentRow == null) {
            call.respond(HttpStatusCode.NotFound)
            return@post
        }
        if (!current.isPrivileged) {
            call.respond(HttpStatusCode.Forbidden, "Only Board/Admin may upload document versions")
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
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

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

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, versionRow[DocumentVersionTable.fileName])
                .toString(),
        )
        call.respondBytes(
            bytes = Files.readAllBytes(file.toPath()),
            contentType = ContentType.parse(versionRow[DocumentVersionTable.mimeType]),
        )
    }
}
