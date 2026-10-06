package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.formFieldLimit
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import network.lapis.cloud.server.conference.BackgroundImageOutcome
import network.lapis.cloud.server.conference.ConferenceBackgroundImageProcessor
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.forMemberUpdate
import network.lapis.cloud.server.db.generated.ConferenceBackgroundImageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.ConferenceBackgroundRules
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Only these two byte-sniffed types are ever accepted -- see [sniffMimeType] (shared with [registerTravelExpenseReceiptRoutes]). Deliberately no WebP, no SVG. */
private val ACCEPTED_BACKGROUND_MIME_TYPES = setOf("image/jpeg", "image/png")
private const val SNIFF_BYTES = 8
private const val STORAGE_PREFIX = "conference-backgrounds"

/** The client's own JPEG re-encode should already be well under [ConferenceBackgroundRules.MAX_UPLOAD_BYTES] -- this only bounds a MALICIOUS/broken client's multipart overhead. */
private const val MULTIPART_OVERHEAD_BYTES = 64L * 1024

/** Shared ceiling for BOTH the request-level `Content-Length` pre-check AND [BACKGROUND_FORM_FIELD_LIMIT] below -- see that constant's own KDoc for why the two must be the same value. */
private const val MAX_ACCEPTED_DECLARED_BYTES = ConferenceBackgroundRules.MAX_UPLOAD_BYTES + MULTIPART_OVERHEAD_BYTES

/**
 * Security-Audit fix (2026-09-27, MINOR "multipart handling has no overall byte budget"): rejects
 * `contentLength` when it exceeds [MAX_ACCEPTED_DECLARED_BYTES], OR when it is `null` (no
 * `Content-Length` header at all -- e.g. `Transfer-Encoding: chunked`), which would otherwise bypass
 * this whole gate entirely -- the previous `declaredContentLength != null && declaredContentLength >
 * maxAcceptedBytes` shape silently let a header-less request through unchecked. Mirrors
 * `SocialPublicRoutes.reportBodyExceedsLimit`'s own KDoc/shape, including WHY this is `internal`
 * rather than tested through a real chunked-encoded HTTP request via the Ktor test client.
 */
internal fun declaredUploadTooLarge(contentLength: Long?): Boolean = contentLength == null || contentLength > MAX_ACCEPTED_DECLARED_BYTES

/**
 * Security-Audit fix (2026-09-27, MINOR "multipart handling has no overall byte budget"): this
 * route's own multipart form has exactly one field (`"file"`, the [PartData.FileItem] streamed and
 * bounded by hand below) -- no legitimate non-file [PartData.FormItem] exists at all. Caps Ktor's
 * OWN form-field buffering (default 50 MiB, see [io.ktor.server.request.formFieldLimit]) down to
 * this route's own already-enforced upload ceiling, same doctrine as `SocialPublicRoutes`'
 * `REPORT_MAX_BODY_BYTES` / `EventPublicRoutes`' `EVENT_FORM_FIELD_LIMIT` KDoc.
 *
 * **Can NOT be pushed down to a few bytes, unlike those two.** [io.ktor.http.cio.CIOMultipartDataBase]
 * applies `formFieldLimit` uniformly to EVERY part -- if a part's OWN `Content-Length` header (not
 * the outer request's) is present and exceeds the limit, the whole multipart read throws an
 * `IOException` immediately, before this route's handler ever sees the part. `ConferenceBackgroundRoutesTest`'s
 * own HTTP-client-built multipart bodies DO set a per-part `Content-Length` (verified empirically --
 * setting this to a genuinely small value like 4 KiB broke every test that uploads a real file with
 * an `IOException`, not the intended 413/415/422). A real browser's `FormData` upload is not
 * guaranteed to omit a per-part `Content-Length` either. So this is set to the SAME ceiling as
 * [MAX_ACCEPTED_DECLARED_BYTES] above -- still a >12x reduction from the 50 MiB default (bounding a
 * malicious non-file field to, worst case, roughly this route's own upload ceiling instead of 50 MiB
 * per field), while never rejecting a legitimate file part.
 */
private const val BACKGROUND_FORM_FIELD_LIMIT = MAX_ACCEPTED_DECLARED_BYTES

private const val DECODE_SEMAPHORE_TIMEOUT_SECONDS = 5

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen". Byte-carrying routes for a
 * member's OWN custom conference background photos -- reused pattern from
 * [registerTravelExpenseReceiptRoutes] (file bytes travel over dedicated routes, not Kilua RPC --
 * see that route's own KDoc for why), simplified because the whole upload is buffered IN MEMORY
 * (capped at [ConferenceBackgroundRules.MAX_UPLOAD_BYTES] + [MULTIPART_OVERHEAD_BYTES]) rather
 * than streamed to disk -- there is no slow disk-I/O phase between a cheap pre-check and the
 * authoritative one, so a single `forUpdate()`-locked transaction is both the count-check AND the
 * insert.
 *
 * **Security checklist**:
 * - **F1 (who may upload)**: [MemberStatusSets.CUSTOM_BACKGROUND_UPLOAD_ELIGIBLE] gates POST only
 *   -- a member who later loses that status can still list/view/delete their OWN
 *   already-uploaded images (see that set's own KDoc).
 * - **IDOR**: every read/delete route filters `WHERE id = ... AND member_id = <caller>`. An
 *   unknown id and a REAL id belonging to someone else return the byte-for-byte IDENTICAL 404 --
 *   NO privileged (BOARD/ADMIN) bypass exists anywhere in this file, unlike
 *   [registerTravelExpenseReceiptRoutes]'s download route. These are private photos; there is no
 *   organizational interest in a board member being able to browse them.
 * - **MIME allowlist from sniffed bytes only**: [sniffMimeType] (shared with
 *   [registerTravelExpenseReceiptRoutes]), restricted further to [ACCEPTED_BACKGROUND_MIME_TYPES]
 *   -- the client-declared `Content-Type` is discarded entirely.
 * - **Decompression bomb / DoS**: [ConferenceBackgroundImageProcessor] rejects on HEADER dimensions
 *   alone before ever decoding (see that class's own KDoc). [decodeSemaphore] additionally bounds
 *   how many decodes run concurrently server-wide; [FederationInboxRateLimiter] bounds how often
 *   one member may even reach that far.
 * - **Path traversal**: every `storage_key`/`thumb_storage_key` this route READS is always one
 *   THIS route itself generated (`conference-backgrounds/<memberId>/<imageId>.jpg`) -- but the GET
 *   routes still re-verify via [resolveInsideRoot] as defense-in-depth (mirrors
 *   `KnowledgeIndexer`'s own canonical-path check), in case a row's key were ever corrupted or
 *   hand-edited in the database.
 * - **Orphaned files**: any failure AFTER the bytes are written but BEFORE the DB transaction
 *   commits deletes both the main and thumbnail file (`.part` files too) -- same "generate
 *   outside, finalize in a short transaction, clean up on ANY failure" doctrine
 *   [registerTravelExpenseReceiptRoutes] KDoc documents.
 * - **Admin backup**: `conference_background_image` is listed in
 *   `OrganizationSchemaCatalog.EXCLUDED_TABLES`, deliberately NOT in
 *   `OrganizationExportService.BLOB_TABLES` -- see that object's own KDoc.
 * - **No audit-log entry** -- uploading/deleting one's own private photo is not an organizational
 *   event (same posture as `TravelExpenseReceiptRoutes`' draft-mutation receipts).
 */
internal fun Route.registerConferenceBackgroundRoutes(
    storageRoot: File,
    rateLimiter: FederationInboxRateLimiter,
    decodeSemaphore: Semaphore,
    processor: ConferenceBackgroundImageProcessor = ConferenceBackgroundImageProcessor(),
) {
    post("/api/conference-backgrounds") {
        val current = resolveCurrentMember(call)

        if (current.status !in MemberStatusSets.CUSTOM_BACKGROUND_UPLOAD_ELIGIBLE) {
            call.respond(HttpStatusCode.Forbidden, "Not eligible to upload a custom background")
            return@post
        }
        if (!rateLimiter.checkAndRecord("member:${current.memberId}")) {
            call.respond(HttpStatusCode.TooManyRequests, "Too many uploads -- please try again later")
            return@post
        }

        val declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredUploadTooLarge(contentLength = declaredContentLength)) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Max upload size is ${ConferenceBackgroundRules.MAX_UPLOAD_BYTES} bytes")
            return@post
        }

        // See BACKGROUND_FORM_FIELD_LIMIT KDoc -- must be set BEFORE receiveMultipart() is called.
        call.formFieldLimit = BACKGROUND_FORM_FIELD_LIMIT

        val buffer = java.io.ByteArrayOutputStream()
        var fileItemCount = 0
        var tooLarge = false
        var sawAnyFilePart = false

        try {
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        fileItemCount++
                        sawAnyFilePart = true
                        if (fileItemCount == 1) {
                            val channel: ByteReadChannel = part.provider()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val read = channel.readAvailable(chunk)
                                if (read == -1) break
                                if (buffer.size() + read > ConferenceBackgroundRules.MAX_UPLOAD_BYTES) {
                                    tooLarge = true
                                    // Security-Audit fix (2026-09-27, same MINOR finding): a plain
                                    // `break` here only ended THIS part's read loop -- `forEachPart`
                                    // itself kept running afterwards, draining the rest of this
                                    // oversized part and parsing/buffering any later part, so a
                                    // chunked request with no upper Content-Length bound (see above)
                                    // could still push an unbounded amount of data through the
                                    // server. Throwing aborts the ENTIRE multipart read immediately,
                                    // same doctrine as MultipleBackgroundFilePartsRejected below.
                                    part.release()
                                    throw BackgroundFileTooLargeRejected
                                }
                                buffer.write(chunk, 0, read)
                            }
                            part.release()
                        } else {
                            // Second file part -- reject the whole request, same doctrine as
                            // registerTravelExpenseReceiptRoutes (see that file's own KDoc).
                            part.release()
                            throw MultipleBackgroundFilePartsRejected
                        }
                    }
                    else -> part.release()
                }
            }
        } catch (e: MultipleBackgroundFilePartsRejected) {
            fileItemCount = 2
        } catch (e: BackgroundFileTooLargeRejected) {
            // tooLarge is already true -- set inside the read loop before this was thrown.
        }

        if (tooLarge) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Max upload size is ${ConferenceBackgroundRules.MAX_UPLOAD_BYTES} bytes")
            return@post
        }
        if (fileItemCount > 1) {
            call.respond(HttpStatusCode.BadRequest, "Request must contain exactly one file part")
            return@post
        }
        if (!sawAnyFilePart) {
            call.respond(HttpStatusCode.BadRequest, "No file part in request")
            return@post
        }

        val uploadBytes = buffer.toByteArray()
        val sniffFilled = minOf(SNIFF_BYTES, uploadBytes.size)
        val sniffBuffer = uploadBytes.copyOf(sniffFilled)
        val mimeType = sniffMimeType(buffer = sniffBuffer, filled = sniffFilled)
        if (mimeType == null || mimeType !in ACCEPTED_BACKGROUND_MIME_TYPES) {
            call.respond(HttpStatusCode.UnsupportedMediaType, "Unsupported or unrecognized file type")
            return@post
        }

        val acquired = withTimeoutOrNull(DECODE_SEMAPHORE_TIMEOUT_SECONDS.seconds) { decodeSemaphore.acquire() } != null
        if (!acquired) {
            call.respond(HttpStatusCode.ServiceUnavailable, "Server is busy processing images -- please try again")
            return@post
        }
        val outcome =
            try {
                withContext(Dispatchers.IO) { processor.process(bytes = uploadBytes, sniffedMime = mimeType) }
            } finally {
                decodeSemaphore.release()
            }

        val processed =
            when (outcome) {
                BackgroundImageOutcome.Undecodable -> {
                    call.respond(HttpStatusCode.UnsupportedMediaType, "Unsupported or unrecognized file type")
                    return@post
                }
                BackgroundImageOutcome.DimensionsOutOfRange -> {
                    call.respond(
                        HttpStatusCode.UnprocessableEntity,
                        "Image dimensions must be between ${ConferenceBackgroundRules.MIN_SIDE_PX} and " +
                            "${ConferenceBackgroundRules.MAX_SIDE_PX} pixels",
                    )
                    return@post
                }
                is BackgroundImageOutcome.Ok -> outcome
            }

        val imageId = Uuid.random()
        val storageKey = "$STORAGE_PREFIX/${current.memberId}/$imageId.jpg"
        val thumbStorageKey = "$STORAGE_PREFIX/${current.memberId}/$imageId.thumb.jpg"
        val mainFile = storageRoot.resolve(storageKey)
        val thumbFile = storageRoot.resolve(thumbStorageKey)
        mainFile.parentFile.mkdirs()

        try {
            writeAtomically(target = mainFile, bytes = processed.main)
            writeAtomically(target = thumbFile, bytes = processed.thumb)
        } catch (e: Exception) {
            mainFile.delete()
            thumbFile.delete()
            throw e
        }

        val now = DbClock.nowLocalDateTime()
        val insertOutcome =
            try {
                transaction {
                    // Lock the member row so a concurrent upload for the SAME member serializes
                    // against this one -- the count check and the insert are the SAME transaction,
                    // so there is no TOCTOU window (unlike registerTravelExpenseReceiptRoutes,
                    // there is no slow disk-streaming phase between a cheap gate and this).
                    MemberTable
                        .selectAll()
                        .where { MemberTable.id eq current.memberId }
                        .forMemberUpdate()
                        .singleOrNull()
                        ?: return@transaction BackgroundInsertOutcome.MemberNotFound
                    val existingCount =
                        ConferenceBackgroundImageTable
                            .selectAll()
                            .where { ConferenceBackgroundImageTable.memberId eq current.memberId }
                            .count()
                    if (existingCount >= ConferenceBackgroundRules.MAX_PER_MEMBER) {
                        return@transaction BackgroundInsertOutcome.TooMany
                    }
                    ConferenceBackgroundImageTable.insert {
                        it[id] = imageId
                        it[memberId] = current.memberId
                        it[ConferenceBackgroundImageTable.storageKey] = storageKey
                        it[ConferenceBackgroundImageTable.thumbStorageKey] = thumbStorageKey
                        it[width] = processed.width
                        it[height] = processed.height
                        it[sizeBytes] = processed.main.size.toLong()
                        it[sha256] = processed.sha256Hex
                        it[createdAt] = now
                    }
                    BackgroundInsertOutcome.Inserted
                }
            } catch (e: Exception) {
                mainFile.delete()
                thumbFile.delete()
                throw e
            }

        when (insertOutcome) {
            BackgroundInsertOutcome.MemberNotFound -> {
                mainFile.delete()
                thumbFile.delete()
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            BackgroundInsertOutcome.TooMany -> {
                mainFile.delete()
                thumbFile.delete()
                call.respond(
                    HttpStatusCode.Conflict,
                    "You already have ${ConferenceBackgroundRules.MAX_PER_MEMBER} custom backgrounds",
                )
                return@post
            }
            BackgroundInsertOutcome.Inserted -> Unit
        }

        logger.info {
            "ConferenceBackgroundRoutes: uploaded custom background, ${processed.width}x${processed.height}, " +
                "${processed.main.size} bytes"
        }

        call.respond(
            HttpStatusCode.Created,
            mapOf(
                "id" to imageId.toString(),
                "width" to processed.width.toString(),
                "height" to processed.height.toString(),
            ),
        )
    }

    delete("/api/conference-backgrounds/{id}") {
        val imageId = runCatching { Uuid.parse(call.parameters["id"]!!) }.getOrNull()
        if (imageId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid id")
            return@delete
        }
        val current = resolveCurrentMember(call)

        val keys =
            transaction {
                val row =
                    ConferenceBackgroundImageTable
                        .selectAll()
                        .where {
                            (ConferenceBackgroundImageTable.id eq imageId) and
                                (ConferenceBackgroundImageTable.memberId eq current.memberId)
                        }.singleOrNull()
                        ?: return@transaction null
                val storageKey = row[ConferenceBackgroundImageTable.storageKey]
                val thumbStorageKey = row[ConferenceBackgroundImageTable.thumbStorageKey]
                ConferenceBackgroundImageTable.deleteWhere { ConferenceBackgroundImageTable.id eq imageId }
                storageKey to thumbStorageKey
            }
        if (keys == null) {
            call.respond(HttpStatusCode.NotFound)
            return@delete
        }
        val (storageKey, thumbStorageKey) = keys
        runCatching {
            storageRoot.resolve(storageKey).delete()
            storageRoot.resolve(thumbStorageKey).delete()
        }.onFailure { e ->
            logger.warn(e) { "ConferenceBackgroundRoutes: failed to delete files on disk after DB delete (DB row is authoritative)" }
        }
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/conference-backgrounds/{id}/image") {
        serveConferenceBackgroundFile(storageRoot = storageRoot, thumb = false)
    }

    get("/api/conference-backgrounds/{id}/thumb") {
        serveConferenceBackgroundFile(storageRoot = storageRoot, thumb = true)
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.serveConferenceBackgroundFile(
    storageRoot: File,
    thumb: Boolean,
) {
    val imageId = runCatching { Uuid.parse(call.parameters["id"]!!) }.getOrNull()
    if (imageId == null) {
        call.respond(HttpStatusCode.BadRequest, "Invalid id")
        return
    }
    val current =
        try {
            resolveCurrentMember(call)
        } catch (_: UnauthenticatedException) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

    val row =
        transaction {
            ConferenceBackgroundImageTable
                .selectAll()
                .where { (ConferenceBackgroundImageTable.id eq imageId) and (ConferenceBackgroundImageTable.memberId eq current.memberId) }
                .singleOrNull()
        }
    if (row == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val key = if (thumb) row[ConferenceBackgroundImageTable.thumbStorageKey] else row[ConferenceBackgroundImageTable.storageKey]
    val sha256 = row[ConferenceBackgroundImageTable.sha256]
    val etag = if (thumb) "\"$sha256-t\"" else "\"$sha256\""

    val file = resolveInsideRoot(storageRoot = storageRoot, storageKey = key)
    if (file == null || !file.exists()) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    // If-None-Match checked only AFTER the ownership gate above -- a 304 must never leak whether an
    // id/etag belongs to someone else.
    if (backgroundIfNoneMatchHits(headerValue = call.request.headers[HttpHeaders.IfNoneMatch], etag = etag)) {
        call.response.header(HttpHeaders.ETag, etag)
        call.respond(HttpStatusCode.NotModified)
        return
    }

    call.response.header(HttpHeaders.ETag, etag)
    call.response.header("X-Content-Type-Options", "nosniff")
    call.response.header(HttpHeaders.CacheControl, "private, no-cache")
    call.response.header("Cross-Origin-Resource-Policy", "same-origin")
    call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")
    call.respond(LocalFileContent(file, ContentType.Image.JPEG))
}

private fun backgroundIfNoneMatchHits(
    headerValue: String?,
    etag: String,
): Boolean {
    if (headerValue == null) return false
    if (headerValue.trim() == "*") return true
    val target = etag.trim('"')
    return headerValue.split(",").map { it.trim() }.any { candidate -> candidate.trim('"') == target }
}

/** Same canonical-path containment check as `KnowledgeIndexer` -- see this file's class KDoc "Path traversal". */
private fun resolveInsideRoot(
    storageRoot: File,
    storageKey: String,
): File? {
    val root = storageRoot.canonicalFile
    val file = File(root, storageKey).canonicalFile
    if (!file.path.startsWith(root.path + File.separator)) return null
    return file
}

/**
 * Review-Befund (MINOR): die frueheren Aufrufer raeumten bei einem Fehlschlag nur `mainFile`/`thumbFile`
 * auf, nie die `.part`-Zwischendatei selbst -- widersprach der Klassen-KDoc oben ("`.part` files too").
 * `part.writeBytes` (Platte voll) oder `Files.move` (EIO) koennen beide werfen; dieses `try/catch` raeumt
 * die EIGENE `.part`-Datei in JEDEM Fall auf, bevor der Fehler an den Aufrufer weitergereicht wird -- egal
 * ob dieser Aufruf `mainFile` oder `thumbFile` betraf.
 */
internal fun writeAtomically(
    target: File,
    bytes: ByteArray,
) {
    val part = File(target.path + ".part")
    try {
        part.writeBytes(bytes)
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        part.delete()
        throw e
    }
}

/** Mirrors [MultipleFilePartsRejected] in [registerTravelExpenseReceiptRoutes] -- see that class's own KDoc. */
private object MultipleBackgroundFilePartsRejected : RuntimeException(null, null, false, false)

/** See the `tooLarge` throw site above (Security-Audit fix 2026-09-27) -- aborts `forEachPart` immediately instead of a plain `break`. */
private object BackgroundFileTooLargeRejected : RuntimeException(null, null, false, false)

private sealed interface BackgroundInsertOutcome {
    data object MemberNotFound : BackgroundInsertOutcome

    data object TooMany : BackgroundInsertOutcome

    data object Inserted : BackgroundInsertOutcome
}
