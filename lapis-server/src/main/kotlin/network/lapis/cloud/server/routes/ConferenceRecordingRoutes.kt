package network.lapis.cloud.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import network.lapis.cloud.server.conference.ConferenceRecordingAccess
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.uuid.Uuid

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- the ONE HTTP route the composed
 * recording's bytes ever travel over. Bytes never travel over Kilua RPC (this codebase's stated
 * rule -- see [registerDocumentRoutes] KDoc), only
 * [network.lapis.cloud.shared.domain.ConferenceRecordingDto.mediaUrl] (a plain URL string) does.
 * Mirrors [registerDocumentRoutes]'s own security checklist:
 * - **Access control**: enforced here via [ConferenceRecordingAccess.mayAccess] -- the SAME
 *   predicate [network.lapis.cloud.server.rpc.ConferenceRecordingService.listRecordings] filters on
 *   and [ConferenceRecordingDto.mediaUrl] is computed from, never re-derived independently. Checked
 *   both on the document (`isDeleted`) AND the recording (`status == READY`) -- a recording whose
 *   underlying document was soft-deleted via [network.lapis.cloud.shared.rpc.IDocumentService
 *   .deleteDocument] (this wave's only deletion path, see that interface's own KDoc "No
 *   deleteRecording method") must 404 here too, not just disappear from `listDocuments`.
 * - **Resource leaks / large files**: `call.respond(LocalFileContent(...))` streams from disk
 *   (Ktor's `ReadChannelContent`), never buffers the whole file in memory -- the same fix applied
 *   to [registerDocumentRoutes]'s own download route in this wave (see that file's own comment).
 * - **Range/seeking**: relies entirely on the already-installed `PartialContent` plugin
 *   ([network.lapis.cloud.server.Application.module]) -- this route does nothing Range-specific
 *   itself, `LocalFileContent` + `PartialContent` supply `Accept-Ranges`/`206`/`Content-Range` for
 *   free, which is what makes in-browser seeking on a `<video>` element work at all.
 */
fun Route.registerConferenceRecordingRoutes(documentStorageRoot: File) {
    get("/api/conference/recordings/{recordingId}/media") {
        val recordingId = runCatching { Uuid.parse(call.parameters["recordingId"]!!) }.getOrNull()
        if (recordingId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid recordingId")
            return@get
        }
        val current =
            try {
                resolveCurrentMember(call)
            } catch (_: Exception) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }

        val recordingRow =
            transaction { ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq recordingId }.singleOrNull() }
        if (recordingRow == null || recordingRow[ConferenceRecordingTable.status] != ConferenceRecordingStatus.READY) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val documentId = recordingRow[ConferenceRecordingTable.documentId]
        if (documentId == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val documentRow = transaction { DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull() }
        if (documentRow == null || documentRow[DocumentTable.isDeleted]) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        if (!ConferenceRecordingAccess.mayAccess(
                current = current,
                accessLevel = recordingRow[ConferenceRecordingTable.accessLevel],
                startedByMemberId = recordingRow[ConferenceRecordingTable.startedByMemberId],
            )
        ) {
            throw ForbiddenException("Not authorized to access this recording")
        }

        val versionId = documentRow[DocumentTable.currentVersionId]
        if (versionId == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val versionRow = transaction { DocumentVersionTable.selectAll().where { DocumentVersionTable.id eq versionId }.singleOrNull() }
        if (versionRow == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        val file = documentStorageRoot.resolve(versionRow[DocumentVersionTable.storageKey])
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, "Stored file missing")
            return@get
        }

        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Inline.toString())
        call.respond(LocalFileContent(file, ContentType.Video.MP4))
    }
}
