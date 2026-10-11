package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.routes.archiveGeneratedFile
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.MutableTestClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingFailureCode
import network.lapis.cloud.shared.domain.ConferenceRecordingListQuery
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RecordingRawFilesMissingException
import network.lapis.cloud.shared.rpc.RecordingRecomputeInProgressException
import network.lapis.cloud.shared.rpc.RecordingRecomputeInsufficientDiskException
import network.lapis.cloud.shared.rpc.RecordingRecomputeNotAllowedException
import network.lapis.cloud.shared.rpc.RecordingRecomputeRateLimitedException
import network.lapis.cloud.shared.rpc.RecordingRetryRateLimitedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val ENABLED_CONFERENCE =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }

/**
 * Welle V1.9.109 -- `ConferenceRecordingService.recomputeRecording` ("Neu berechnen") on H2 AND PostgreSQL: the happy path (marker, audit,
 * poller wake-up, the READY row stays usable), the rights matrix, the rate limits (per member, and the per-recording budget SHARED with the
 * composition retry), the double start (sequential and parallel), the free-space refusal, missing raw files, the other states, and the DTO
 * fields that only a caller who may recompute is told. Callers authenticate through a real session (`Authorization: Bearer`).
 */
abstract class ConferenceRecordingRecomputeScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdDocumentIds = mutableListOf<Uuid>()
        val hostRawRoot: File = Files.createTempDirectory("recompute-test-raw").toFile()
        val documentRoot: File = Files.createTempDirectory("recompute-test-docs").toFile()
        val archiveFolder = "Neuberechnung-Test-${Uuid.random()}"
        val json = Json { ignoreUnknownKeys = true }

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db)
        afterSpec {
            hostRawRoot.deleteRecursively()
            documentRoot.deleteRecursively()
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                }
                val recordingIds =
                    ConferenceRecordingTable.selectAll().filter { it[ConferenceRecordingTable.roomId] in createdRoomIds }.map {
                        it[ConferenceRecordingTable.id]
                    }
                recordingIds.forEach { id -> ConferenceRecordingTrackTable.deleteWhere { recordingId eq id } }
                recordingIds.forEach { id -> ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.id eq id } }
                createdRoomIds.forEach { id -> ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq id } }
                createdDocumentIds.forEach { id -> DocumentVersionTable.deleteWhere { documentId eq id } }
                createdDocumentIds.forEach { id -> DocumentTable.deleteWhere { DocumentTable.id eq id } }
                DocumentFolderTable.deleteWhere { name eq archiveFolder }
            }
            db.deactivate()
        }

        fun recordingConfig(): ConferenceRecordingConfig =
            ConferenceRecordingConfig.load { key ->
                when (key) {
                    "LAPIS_RECORDING_ENABLED" -> "true"
                    "LAPIS_EGRESS_OUTPUT_HOST_DIR" -> hostRawRoot.path
                    "LAPIS_EGRESS_OUTPUT_CONTAINER_DIR" -> "/out"
                    else -> null
                }
            }

        fun createMember(
            label: String,
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Recompute Test $label"
                    it[email] = "recompute-$label-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createRoom(creator: Uuid): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "RECOMPUTE-RAUMTITEL-$id"
                    it[description] = ""
                    it[livekitRoomName] = "lc-recompute-$id"
                    it[createdByMemberId] = creator
                    it[createdAt] = now
                    it[endedAt] = now
                    it[maxParticipants] = 25
                }
            }
            createdRoomIds += id
            return id
        }

        /**
         * A READY recording with an archived document (one version, a real blob) and a camera and a microphone track; raw files written
         * unless [withFiles] is false. [status] other than READY gives the same row in another state (no document then).
         */
        fun seedRecording(
            starter: Uuid,
            roomId: Uuid = createRoom(starter),
            status: ConferenceRecordingStatus = ConferenceRecordingStatus.READY,
            code: ConferenceRecordingFailureCode? = ConferenceRecordingFailureCode.COMPOSE_FAILED,
            accessLevel: DocumentAccessLevel = DocumentAccessLevel.BOARD_ONLY,
            withFiles: Boolean = true,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val documentId =
                if (status == ConferenceRecordingStatus.READY) {
                    val source = Files.createTempFile("recompute-seed", ".mp4").toFile().apply { writeBytes(ByteArray(2048) { 7 }) }
                    archiveGeneratedFile(
                        storageRoot = documentRoot,
                        folderName = archiveFolder,
                        fileName = "aufzeichnung-$id.mp4",
                        title = "Aufzeichnung $id",
                        sourceFile = source,
                        mimeType = "video/mp4",
                        uploadedBy = starter,
                        accessLevel = accessLevel,
                    ).also {
                        createdDocumentIds += it
                        source.delete()
                    }
                } else {
                    null
                }
            transaction {
                ConferenceRecordingTable.insert {
                    it[ConferenceRecordingTable.id] = id
                    it[ConferenceRecordingTable.roomId] = roomId
                    it[startedByMemberId] = starter
                    it[startedAt] = now
                    it[stoppedAt] = now
                    it[readyAt] = if (status == ConferenceRecordingStatus.READY) now else null
                    it[ConferenceRecordingTable.status] = status
                    it[ConferenceRecordingTable.accessLevel] = accessLevel
                    it[ConferenceRecordingTable.documentId] = documentId
                    it[rawDir] = id.toString()
                    it[durationSeconds] = 5L
                    it[fileSizeBytes] = if (documentId != null) 2048L else null
                    it[failureReason] =
                        if (status == ConferenceRecordingStatus.FAILED) "Die Aufzeichnung konnte nicht zusammengesetzt werden." else null
                    it[failureCode] = if (status == ConferenceRecordingStatus.FAILED) code else null
                    it[composeAttempts] = 5
                }
                listOf(ConferenceRecordingTrackSource.CAMERA to "cam.mp4", ConferenceRecordingTrackSource.MICROPHONE to "mic.ogg")
                    .forEach { (source, name) ->
                        ConferenceRecordingTrackTable.insert {
                            it[ConferenceRecordingTrackTable.id] = Uuid.random()
                            it[recordingId] = id
                            it[egressId] = "eg-${Uuid.random()}"
                            it[livekitTrackId] = "tr-${Uuid.random()}"
                            it[participantIdentity] = "member-$id"
                            it[trackSource] = source
                            it[ConferenceRecordingTrackTable.status] = ConferenceRecordingTrackStatus.COMPLETE
                            it[startedAtEpochNanos] = 0L
                            it[endedAtEpochNanos] = null
                            it[fileName] = name
                            it[durationMs] = 5_000L
                            it[sizeBytes] = null
                        }
                    }
            }
            if (withFiles) {
                val dir = hostRawRoot.resolve(id.toString()).apply { mkdirs() }
                dir.resolve("cam.mp4").writeBytes(byteArrayOf(1, 2, 3))
                dir.resolve("mic.ogg").writeBytes(byteArrayOf(4, 5))
            }
            return id
        }

        fun row(id: Uuid) = transaction { ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq id }.single() }

        fun auditEntries(id: Uuid) = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq id }.toList() }

        fun tokenOf(member: Uuid): String = SessionStore.createSession(member).rawToken

        val wakeUps = AtomicInteger(0)

        /**
         * Throwaway routes: `/recompute` and `/retry` (answer the DTO JSON or `ERR:<ExceptionSimpleName>`), `/list` (a room's recording as the
         * caller sees it). [free] is the injected free-space probe.
         */
        fun withRoutes(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            free: (File) -> Long = { Long.MAX_VALUE / 4 },
            block: suspend (client: HttpClient) -> Unit,
        ) {
            wakeUps.set(0)
            testApplication {
                application {
                    routing {
                        fun service(call: io.ktor.server.application.ApplicationCall) =
                            ConferenceRecordingService(
                                call = call,
                                ffmpegAvailable = true,
                                config = ENABLED_CONFERENCE,
                                recordingConfig = recordingConfig(),
                                retryCompositionRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                                recomputeRateLimiter = limiter,
                                documentStorageRoot = documentRoot,
                                diskSpace = free,
                                onRecomputeRequested = { wakeUps.incrementAndGet() },
                            )
                        post("/recompute") {
                            val body =
                                try {
                                    json.encodeToString(
                                        ConferenceRecordingDto.serializer(),
                                        service(call).recomputeRecording(call.request.queryParameters["recordingId"]!!),
                                    )
                                } catch (e: Exception) {
                                    "ERR:" + e::class.simpleName
                                }
                            call.respondText(body)
                        }
                        post("/retry") {
                            val body =
                                try {
                                    json.encodeToString(
                                        ConferenceRecordingDto.serializer(),
                                        service(call).retryRecordingComposition(call.request.queryParameters["recordingId"]!!),
                                    )
                                } catch (e: Exception) {
                                    "ERR:" + e::class.simpleName
                                }
                            call.respondText(body)
                        }
                        post("/list") {
                            val page =
                                service(
                                    call,
                                ).listRecordings(ConferenceRecordingListQuery(roomId = call.request.queryParameters["roomId"]))
                            call.respondText(json.encodeToString(ConferenceRecordingDto.serializer(), page.rows.single()))
                        }
                    }
                }
                block(client)
            }
        }

        suspend fun HttpClient.recompute(
            recordingId: Uuid,
            member: Uuid,
        ): String = post("/recompute?recordingId=$recordingId") { header("Authorization", "Bearer ${tokenOf(member)}") }.bodyAsText()

        suspend fun HttpClient.retry(
            recordingId: Uuid,
            member: Uuid,
        ): String = post("/retry?recordingId=$recordingId") { header("Authorization", "Bearer ${tokenOf(member)}") }.bodyAsText()

        suspend fun HttpClient.dtoFor(
            roomId: Uuid,
            member: Uuid,
        ): ConferenceRecordingDto =
            json.decodeFromString(
                ConferenceRecordingDto.serializer(),
                post("/list?roomId=$roomId") { header("Authorization", "Bearer ${tokenOf(member)}") }.bodyAsText(),
            )

        fun parse(body: String): ConferenceRecordingDto = json.decodeFromString(ConferenceRecordingDto.serializer(), body)

        fun err(type: kotlin.reflect.KClass<*>) = "ERR:" + type.simpleName

        // ── happy path ───────────────────────────────────────────────────────

        test("happy path: READY with raw files -> marker set, still READY with its media URL, audit entry, poller woken once") {
            val starter = createMember("starter")
            val id = seedRecording(starter)
            withRoutes { client ->
                val before = client.dtoFor(row(id)[ConferenceRecordingTable.roomId], starter)
                before.canRecompute shouldBe true
                before.recomputeInProgress shouldBe false
                val dto = parse(client.recompute(id, starter))
                dto.status shouldBe ConferenceRecordingStatus.READY
                dto.recomputeInProgress shouldBe true
                dto.canRecompute shouldBe false
                dto.lastRecomputeFailed shouldBe false
                // the file stays playable, the URL carries the current document version
                val versionId =
                    transaction {
                        DocumentTable
                            .selectAll()
                            .where { DocumentTable.id eq row(id)[ConferenceRecordingTable.documentId]!! }
                            .single()[DocumentTable.currentVersionId]
                    }
                dto.mediaUrl shouldBe "/api/conference/recordings/$id/media?v=$versionId"
            }
            val r = row(id)
            r[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.READY
            (r[ConferenceRecordingTable.recomputeStartedAt] != null) shouldBe true
            r[ConferenceRecordingTable.lastRecomputeFailureCode] shouldBe null
            r[ConferenceRecordingTable.manualRetryCount] shouldBe 1
            wakeUps.get() shouldBe 1
            val audit = auditEntries(id)
            audit.size shouldBe 1
            audit.single()[AuditLogEntryTable.afterSnapshot] shouldBe "recompute requested"
            audit.single()[AuditLogEntryTable.beforeSnapshot] shouldBe "status=READY"
        }

        test("the audit entry carries no path and no title") {
            val starter = createMember("starter-audit")
            val id = seedRecording(starter)
            withRoutes { client -> client.recompute(id, starter) }
            val text = auditEntries(id).joinToString { "${it[AuditLogEntryTable.beforeSnapshot]} ${it[AuditLogEntryTable.afterSnapshot]}" }
            text shouldNotContain "/"
            text shouldNotContain "RECOMPUTE-RAUMTITEL"
            text shouldNotContain "aufzeichnung-"
        }

        test("FAILED + COMPOSE_FAILED takes the retry path: PROCESSING with one attempt left, same counter") {
            val starter = createMember("starter-failed")
            val id = seedRecording(starter, status = ConferenceRecordingStatus.FAILED)
            withRoutes { client ->
                val dto = parse(client.recompute(id, starter))
                dto.status shouldBe ConferenceRecordingStatus.PROCESSING
            }
            val r = row(id)
            r[ConferenceRecordingTable.composeAttempts] shouldBe 4
            r[ConferenceRecordingTable.failureCode] shouldBe null
            r[ConferenceRecordingTable.manualRetryCount] shouldBe 1
            r[ConferenceRecordingTable.recomputeStartedAt] shouldBe null
            auditEntries(id).single()[AuditLogEntryTable.afterSnapshot] shouldBe "recompute requested (failed recording)"
            wakeUps.get() shouldBe 1
        }

        // ── double start ─────────────────────────────────────────────────────

        test("sequential double start: the second call is RecordingRecomputeInProgressException, one audit entry, counter 1") {
            val starter = createMember("starter-seq")
            val id = seedRecording(starter)
            withRoutes { client ->
                parse(client.recompute(id, starter)).recomputeInProgress shouldBe true
                client.recompute(id, starter) shouldBe err(RecordingRecomputeInProgressException::class)
            }
            row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 1
            auditEntries(id).size shouldBe 1
            wakeUps.get() shouldBe 1
        }

        test("parallel double start: exactly one marker, one audit entry, counter 1, one wake-up") {
            val starter = createMember("starter-par")
            val id = seedRecording(starter)
            val results = mutableListOf<String>()
            withRoutes { client ->
                results +=
                    coroutineScope {
                        listOf(async { client.recompute(id, starter) }, async { client.recompute(id, starter) }).awaitAll()
                    }
            }
            results.count { !it.startsWith("ERR:") } shouldBe 1
            results.count { it == err(RecordingRecomputeInProgressException::class) } shouldBe 1
            row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 1
            auditEntries(id).size shouldBe 1
            wakeUps.get() shouldBe 1
        }

        // ── refusals ─────────────────────────────────────────────────────────

        test("RECORDING, STOPPING and PROCESSING are RecordingRecomputeNotAllowedException and stay unchanged") {
            val starter = createMember("starter-states")
            val cases =
                listOf(
                    seedRecording(starter, status = ConferenceRecordingStatus.RECORDING),
                    seedRecording(starter, status = ConferenceRecordingStatus.STOPPING),
                    seedRecording(starter, status = ConferenceRecordingStatus.PROCESSING),
                    seedRecording(starter, status = ConferenceRecordingStatus.FAILED, code = ConferenceRecordingFailureCode.EGRESS_TIMEOUT),
                    seedRecording(starter, status = ConferenceRecordingStatus.FAILED, code = ConferenceRecordingFailureCode.NO_TRACKS),
                )
            withRoutes { client ->
                cases.forEach { id ->
                    val before = row(id)[ConferenceRecordingTable.status]
                    client.recompute(id, starter) shouldBe err(RecordingRecomputeNotAllowedException::class)
                    row(id)[ConferenceRecordingTable.status] shouldBe before
                    row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 0
                    row(id)[ConferenceRecordingTable.recomputeStartedAt] shouldBe null
                }
            }
            wakeUps.get() shouldBe 0
        }

        test("raw files missing: RecordingRawFilesMissingException; the moderator sees rawFilesAvailable=false, a stranger sees null") {
            val creator = createMember("room-creator-raw")
            val stranger = createMember("stranger-raw")
            val board = createMember("board-raw", AccountRole.BOARD)
            val roomId = createRoom(creator)
            val id = seedRecording(creator, roomId = roomId, accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS, withFiles = false)
            withRoutes { client ->
                client.recompute(id, creator) shouldBe err(RecordingRawFilesMissingException::class)
                row(id)[ConferenceRecordingTable.recomputeStartedAt] shouldBe null
                val moderatorView = client.dtoFor(roomId, board)
                moderatorView.canRecompute shouldBe false
                moderatorView.rawFilesAvailable shouldBe false
                val strangerView = client.dtoFor(roomId, stranger)
                strangerView.rawFilesAvailable shouldBe null
                strangerView.canRecompute shouldBe false
                strangerView.recomputeInProgress shouldBe false
            }
            wakeUps.get() shouldBe 0
        }

        test("an unknown recording is NotFoundException") {
            val member = createMember("nf", AccountRole.BOARD)
            withRoutes { client -> client.recompute(Uuid.random(), member) shouldBe err(NotFoundException::class) }
        }

        test("not enough free disk space: RecordingRecomputeInsufficientDiskException, nothing changed, no wake-up") {
            val starter = createMember("starter-disk")
            val id = seedRecording(starter)
            withRoutes(free = { 1_000L }) { client ->
                client.recompute(id, starter) shouldBe err(RecordingRecomputeInsufficientDiskException::class)
            }
            val r = row(id)
            r[ConferenceRecordingTable.recomputeStartedAt] shouldBe null
            r[ConferenceRecordingTable.manualRetryCount] shouldBe 0
            wakeUps.get() shouldBe 0
            auditEntries(id).size shouldBe 0
        }

        test("free space is checked on the work volume AND on the document volume") {
            val starter = createMember("starter-disk2")
            val id = seedRecording(starter)
            // plenty of room on the raw volume, none on the document volume
            withRoutes(free = { dir -> if (dir.path == documentRoot.path) 1_000L else Long.MAX_VALUE / 4 }) { client ->
                client.recompute(id, starter) shouldBe err(RecordingRecomputeInsufficientDiskException::class)
            }
            withRoutes(free = { dir -> if (dir.path == hostRawRoot.path) 1_000L else Long.MAX_VALUE / 4 }) { client ->
                client.recompute(id, starter) shouldBe err(RecordingRecomputeInsufficientDiskException::class)
            }
            row(id)[ConferenceRecordingTable.recomputeStartedAt] shouldBe null
        }

        // ── rights ───────────────────────────────────────────────────────────

        test(
            "rights: non-moderator and guest are forbidden; a moderator without access to the tier is forbidden; creator, BOARD, ADMIN may",
        ) {
            val creator = createMember("room-creator")
            val other = createMember("other")
            val guest = createMember("guest", status = MemberStatus.GUEST)
            val board = createMember("board", AccountRole.BOARD)
            val admin = createMember("admin", AccountRole.ADMIN)
            val forbidden = err(ForbiddenException::class)
            withRoutes { client ->
                val boardOnly = seedRecording(creator)
                client.recompute(boardOnly, other) shouldBe forbidden
                client.recompute(boardOnly, guest) shouldBe forbidden
                // moderator (room creator) who did not start an ADMIN_ONLY recording: no access to its tier
                val adminOnly = seedRecording(starter = admin, roomId = createRoom(creator), accessLevel = DocumentAccessLevel.ADMIN_ONLY)
                client.recompute(adminOnly, creator) shouldBe forbidden
                row(adminOnly)[ConferenceRecordingTable.recomputeStartedAt] shouldBe null
                parse(client.recompute(boardOnly, creator)).recomputeInProgress shouldBe true
                parse(client.recompute(seedRecording(creator), board)).recomputeInProgress shouldBe true
                parse(
                    client.recompute(seedRecording(creator, accessLevel = DocumentAccessLevel.ADMIN_ONLY), admin),
                ).recomputeInProgress shouldBe
                    true
            }
        }

        test(
            "the archived document's tightened tier also counts: a moderator the recording tier admits but the document tier does not is forbidden",
        ) {
            val creator = createMember("room-creator-tier")
            val roomId = createRoom(creator)
            val id = seedRecording(creator, roomId = roomId, accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS)
            // an admin tightens the archive folder: the document's own level goes up to ADMIN_ONLY
            transaction {
                DocumentTable.update({ DocumentTable.id eq row(id)[ConferenceRecordingTable.documentId]!! }) {
                    it[accessLevel] = DocumentAccessLevel.ADMIN_ONLY
                }
            }
            withRoutes { client ->
                client.recompute(id, creator) shouldBe err(ForbiddenException::class)
                // and the list does not offer it either
                client.dtoFor(roomId, creator).canRecompute shouldBe false
            }
        }

        // ── rate limits ──────────────────────────────────────────────────────

        test("per member: the 11th call within a minute is RecordingRecomputeRateLimitedException") {
            val starter = createMember("starter-member-rate")
            val limiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)
            withRoutes(limiter = limiter) { client ->
                val ids = (1..11).map { seedRecording(starter, withFiles = false) }
                ids.take(10).forEach { client.recompute(it, starter) shouldBe err(RecordingRawFilesMissingException::class) }
                client.recompute(ids.last(), starter) shouldBe err(RecordingRecomputeRateLimitedException::class)
            }
        }

        test("per recording on a pinned clock: 15 minute spacing, 3 per 24 hours, new window afterwards") {
            val starter = createMember("starter-rate")
            val id = seedRecording(starter)
            val limited = err(RecordingRecomputeRateLimitedException::class)
            val clock = MutableTestClock("2026-10-11T08:00:00Z")

            fun finish() {
                // what the poller does when a recompute ends: the marker is cleared
                transaction { ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq id }) { it[recomputeStartedAt] = null } }
            }
            TimeTestSupport.withMutableServerClock(clock = clock) {
                withRoutes { client ->
                    parse(client.recompute(id, starter)).recomputeInProgress shouldBe true
                    finish()
                    clock.advance(10.minutes)
                    client.recompute(id, starter) shouldBe limited
                    clock.advance(6.minutes)
                    parse(client.recompute(id, starter)).recomputeInProgress shouldBe true
                    finish()
                    clock.advance(16.minutes)
                    parse(client.recompute(id, starter)).recomputeInProgress shouldBe true
                    row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 3
                    finish()
                    clock.advance(16.minutes)
                    client.recompute(id, starter) shouldBe limited
                    row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 3
                    clock.advance(24.hours)
                    parse(client.recompute(id, starter)).recomputeInProgress shouldBe true
                    row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 1
                }
            }
        }

        test("the per-recording budget is SHARED with the composition retry") {
            val starter = createMember("starter-shared")
            val id = seedRecording(starter)
            val clock = MutableTestClock("2026-10-11T09:00:00Z")
            TimeTestSupport.withMutableServerClock(clock = clock) {
                withRoutes { client ->
                    parse(client.recompute(id, starter)).recomputeInProgress shouldBe true
                    // the recompute failed and the whole recording is FAILED now (e.g. a later retry of the first composition)
                    transaction {
                        ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq id }) {
                            it[recomputeStartedAt] = null
                            it[status] = ConferenceRecordingStatus.FAILED
                            it[failureCode] = ConferenceRecordingFailureCode.COMPOSE_FAILED
                            it[failureReason] = "Die Aufzeichnung konnte nicht zusammengesetzt werden."
                        }
                    }
                    clock.advance(5.minutes)
                    client.retry(id, starter) shouldBe err(RecordingRetryRateLimitedException::class)
                    clock.advance(11.minutes)
                    parse(client.retry(id, starter)).status shouldBe ConferenceRecordingStatus.PROCESSING
                    row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 2
                }
            }
        }

        // ── DTO ──────────────────────────────────────────────────────────────

        test("a failed recompute is reported to the moderator only; the recompute time is general information") {
            val creator = createMember("room-creator-dto")
            val stranger = createMember("stranger-dto")
            val admin = createMember("admin-dto", AccountRole.ADMIN)
            val roomId = createRoom(creator)
            val id = seedRecording(creator, roomId = roomId, accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS)
            transaction {
                ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq id }) {
                    it[lastRecomputeFailureCode] = ConferenceRecordingFailureCode.COMPOSE_FAILED
                    it[recomputedAt] = DbClock.nowLocalDateTime()
                }
            }
            withRoutes { client ->
                val moderator = client.dtoFor(roomId, admin)
                moderator.lastRecomputeFailed shouldBe true
                moderator.canRecompute shouldBe true
                val other = client.dtoFor(roomId, stranger)
                other.lastRecomputeFailed shouldBe false
                other.canRecompute shouldBe false
                other.recomputeInProgress shouldBe false
                other.rawFilesAvailable shouldBe null
                (other.recomputedAt != null) shouldBe true
                (other.mediaUrl ?: "") shouldContain "/media?v="
            }
        }
    })
