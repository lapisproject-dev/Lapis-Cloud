package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.conference.FakeLiveKitAdminClient
import network.lapis.cloud.server.conference.FakeLiveKitEgressClient
import network.lapis.cloud.server.conference.FakeRecordingComposer
import network.lapis.cloud.server.conference.RecordingComposeException
import network.lapis.cloud.server.conference.RecordingPoller
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTrackTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.MutableTestClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingFailureCode
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackSource
import network.lapis.cloud.shared.domain.ConferenceRecordingTrackStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RecordingRawFilesMissingException
import network.lapis.cloud.shared.rpc.RecordingRetryNotAllowedException
import network.lapis.cloud.shared.rpc.RecordingRetryRateLimitedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
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
 * Welle V1.9.107 -- `ConferenceRecordingService.retryRecordingComposition` on H2 AND PostgreSQL: the happy path with a real poller tick
 * (success and failure of the single extra attempt), the double start (sequential and parallel), every refusal (status, failure code, raw
 * files in all their broken shapes), the rights matrix, the three rate limits on a pinned clock, the audit entry and the DTO fields.
 * Callers authenticate through a real session (`Authorization: Bearer`), so the same routes run on both databases.
 */
abstract class ConferenceRecordingRetryScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdDocumentIds = mutableListOf<Uuid>()
        val createdFolderIds = mutableListOf<Uuid>()
        val hostRawRoot: File = Files.createTempDirectory("retry-test-raw").toFile()
        val documentRoot: File = Files.createTempDirectory("retry-test-docs").toFile()
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
                createdDocumentIds.forEach { id -> DocumentTable.deleteWhere { DocumentTable.id eq id } }
                createdFolderIds.forEach { id -> DocumentFolderTable.deleteWhere { DocumentFolderTable.id eq id } }
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

        /** A poller wired to [composer] and the SAME raw root the spec writes to; never started, only ticked. */
        fun pollerWith(composer: FakeRecordingComposer): RecordingPoller =
            RecordingPoller(
                liveKitAdminClient = FakeLiveKitAdminClient(),
                liveKitEgressClient = FakeLiveKitEgressClient(),
                recordingConfig = recordingConfig(),
                documentStorageRoot = documentRoot,
                composer = composer,
            )

        fun createMember(
            label: String,
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Retry Test $label"
                    it[email] = "retry-$label-$id@example.org"
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
                    it[title] = "GEHEIMER-RAUMTITEL-$id"
                    it[description] = ""
                    it[livekitRoomName] = "lc-retry-$id"
                    it[createdByMemberId] = creator
                    it[createdAt] = now
                    it[endedAt] = now
                    it[maxParticipants] = 25
                }
            }
            createdRoomIds += id
            return id
        }

        /** FAILED + COMPOSE_FAILED recording with a camera and a microphone track; raw files written unless [withFiles] is false. */
        fun seedFailed(
            starter: Uuid,
            roomId: Uuid = createRoom(starter),
            status: ConferenceRecordingStatus = ConferenceRecordingStatus.FAILED,
            code: ConferenceRecordingFailureCode? = ConferenceRecordingFailureCode.COMPOSE_FAILED,
            accessLevel: DocumentAccessLevel = DocumentAccessLevel.BOARD_ONLY,
            withFiles: Boolean = true,
            composeAttempts: Int = 5,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRecordingTable.insert {
                    it[ConferenceRecordingTable.id] = id
                    it[ConferenceRecordingTable.roomId] = roomId
                    it[startedByMemberId] = starter
                    it[startedAt] = now
                    it[stoppedAt] = now
                    it[ConferenceRecordingTable.status] = status
                    it[ConferenceRecordingTable.accessLevel] = accessLevel
                    it[rawDir] = id.toString()
                    it[failureReason] =
                        if (status == ConferenceRecordingStatus.FAILED) "Die Aufzeichnung konnte nicht zusammengesetzt werden." else null
                    it[failureCode] = if (status == ConferenceRecordingStatus.FAILED) code else null
                    it[ConferenceRecordingTable.composeAttempts] = composeAttempts
                }
                val tracks =
                    listOf(
                        ConferenceRecordingTrackSource.CAMERA to "cam.mp4",
                        ConferenceRecordingTrackSource.MICROPHONE to "mic.ogg",
                    )
                tracks.forEach { (source, name) ->
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

        fun markFailedAgain(id: Uuid) {
            transaction {
                ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq id }) {
                    it[status] = ConferenceRecordingStatus.FAILED
                    it[failureCode] = ConferenceRecordingFailureCode.COMPOSE_FAILED
                    it[failureReason] = "Die Aufzeichnung konnte nicht zusammengesetzt werden."
                }
            }
        }

        /** Earlier tests of this spec leave PROCESSING rows behind; a tick would compose them too, so park all but [keep] as FAILED. Returns the PROCESSING count left. */
        fun parkOtherProcessing(keep: Uuid): Int {
            transaction {
                ConferenceRecordingTable.update({
                    (ConferenceRecordingTable.status eq ConferenceRecordingStatus.PROCESSING) and
                        (ConferenceRecordingTable.id neq keep) and
                        (ConferenceRecordingTable.roomId inList createdRoomIds.toList())
                }) {
                    it[status] = ConferenceRecordingStatus.FAILED
                    it[failureCode] = ConferenceRecordingFailureCode.COMPOSE_FAILED
                    it[failureReason] = "Die Aufzeichnung konnte nicht zusammengesetzt werden."
                }
            }
            return transaction {
                ConferenceRecordingTable
                    .selectAll()
                    .where { ConferenceRecordingTable.status eq ConferenceRecordingStatus.PROCESSING }
                    .count()
                    .toInt()
            }
        }

        fun auditEntries(id: Uuid) = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq id }.toList() }

        fun tokenOf(member: Uuid): String = SessionStore.createSession(member).rawToken

        val tick = AtomicInteger(0)

        /**
         * Runs [block] against throwaway routes: `/retry` (the RPC; answers the DTO JSON or `ERR:<ExceptionSimpleName>`) and `/dto`
         * (a failed recording's DTO as another caller sees it, via listRecordings).
         */
        fun withRoutes(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            block: suspend (client: HttpClient) -> Unit,
        ) {
            tick.set(0)
            testApplication {
                application {
                    routing {
                        post("/retry") {
                            val service =
                                ConferenceRecordingService(
                                    call = call,
                                    ffmpegAvailable = true,
                                    config = ENABLED_CONFERENCE,
                                    recordingConfig = recordingConfig(),
                                    retryCompositionRateLimiter = limiter,
                                    onCompositionRetried = { tick.incrementAndGet() },
                                )
                            val body =
                                try {
                                    json.encodeToString(
                                        ConferenceRecordingDto.serializer(),
                                        service.retryRecordingComposition(call.request.queryParameters["recordingId"]!!),
                                    )
                                } catch (e: Exception) {
                                    "ERR:" + e::class.simpleName
                                }
                            call.respondText(body)
                        }
                        post("/list") {
                            val service =
                                ConferenceRecordingService(
                                    call = call,
                                    ffmpegAvailable = true,
                                    config = ENABLED_CONFERENCE,
                                    recordingConfig = recordingConfig(),
                                )
                            val page =
                                service.listRecordings(
                                    network.lapis.cloud.shared.domain
                                        .ConferenceRecordingListQuery(roomId = call.request.queryParameters["roomId"]),
                                )
                            call.respondText(json.encodeToString(ConferenceRecordingDto.serializer(), page.rows.single()))
                        }
                    }
                }
                block(client)
            }
        }

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

        // ── happy path ───────────────────────────────────────────────────────

        test("happy path: FAILED/COMPOSE_FAILED with raw files -> PROCESSING, attempts 4, code and text null; poller wake-up fired once") {
            val starter = createMember("starter")
            val id = seedFailed(starter)
            withRoutes { client ->
                val dto = parse(client.retry(id, starter))
                dto.status shouldBe ConferenceRecordingStatus.PROCESSING
                dto.failureCode shouldBe null
                dto.failureReason shouldBe null
                dto.canRetryComposition shouldBe false
            }
            val r = row(id)
            r[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.PROCESSING
            r[ConferenceRecordingTable.composeAttempts] shouldBe 4
            r[ConferenceRecordingTable.failureReason] shouldBe null
            r[ConferenceRecordingTable.failureCode] shouldBe null
            r[ConferenceRecordingTable.manualRetryCount] shouldBe 1
            tick.get() shouldBe 1
        }

        test("after the retry one poller tick makes exactly one attempt: success -> READY with attempts 5") {
            val starter = createMember("starter-ok")
            val id = seedFailed(starter)
            withRoutes { client -> client.retry(id, starter) }
            val composer = FakeRecordingComposer()
            val poller = pollerWith(composer)
            val processing = parkOtherProcessing(keep = id)
            runBlocking { poller.tick() }
            composer.callCount shouldBe processing
            val r = row(id)
            r[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.READY
            r[ConferenceRecordingTable.composeAttempts] shouldBe 5
        }

        test("after the retry one poller tick: failure -> FAILED/COMPOSE_FAILED again, attempts 5, raw files kept, one compose call") {
            val starter = createMember("starter-fail")
            val id = seedFailed(starter)
            withRoutes { client -> client.retry(id, starter) }
            val composer = FakeRecordingComposer(behavior = { _, _ -> throw RecordingComposeException(message = "simulated") })
            val poller = pollerWith(composer)
            val processing = parkOtherProcessing(keep = id)
            runBlocking { poller.tick() }
            runBlocking { poller.tick() }
            composer.callCount shouldBe processing
            val r = row(id)
            r[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
            r[ConferenceRecordingTable.failureCode] shouldBe ConferenceRecordingFailureCode.COMPOSE_FAILED
            r[ConferenceRecordingTable.composeAttempts] shouldBe 5
            hostRawRoot.resolve(id.toString()).resolve("cam.mp4").exists() shouldBe true
        }

        // ── double start ─────────────────────────────────────────────────────

        test("sequential double start: the second call is RecordingRetryNotAllowedException") {
            val starter = createMember("starter-seq")
            val id = seedFailed(starter)
            withRoutes { client ->
                parse(client.retry(id, starter)).status shouldBe ConferenceRecordingStatus.PROCESSING
                client.retry(id, starter) shouldBe "ERR:" + RecordingRetryNotAllowedException::class.simpleName
            }
            row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 1
        }

        test("parallel double start: exactly one state change, one audit entry, counter 1") {
            val starter = createMember("starter-par")
            val id = seedFailed(starter)
            val results = mutableListOf<String>()
            withRoutes { client ->
                results +=
                    coroutineScope {
                        listOf(async { client.retry(id, starter) }, async { client.retry(id, starter) }).awaitAll()
                    }
            }
            results.count { !it.startsWith("ERR:") } shouldBe 1
            results.count { it == "ERR:" + RecordingRetryNotAllowedException::class.simpleName } shouldBe 1
            row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 1
            auditEntries(id).size shouldBe 1
            tick.get() shouldBe 1
        }

        // ── refusals ─────────────────────────────────────────────────────────

        test("wrong status or failure code is refused with the same exception and leaves the row unchanged") {
            val starter = createMember("starter-states")
            val notAllowed = "ERR:" + RecordingRetryNotAllowedException::class.simpleName
            val cases =
                listOf(
                    seedFailed(starter, status = ConferenceRecordingStatus.RECORDING),
                    seedFailed(starter, status = ConferenceRecordingStatus.STOPPING),
                    seedFailed(starter, status = ConferenceRecordingStatus.PROCESSING),
                    seedFailed(starter, status = ConferenceRecordingStatus.READY),
                    seedFailed(starter, code = ConferenceRecordingFailureCode.EGRESS_TIMEOUT),
                    seedFailed(starter, code = ConferenceRecordingFailureCode.NO_TRACKS),
                    seedFailed(starter, code = ConferenceRecordingFailureCode.NO_TRACK_STORED),
                    seedFailed(starter, code = null),
                )
            withRoutes { client ->
                cases.forEach { id ->
                    val before = row(id)[ConferenceRecordingTable.status]
                    client.retry(id, starter) shouldBe notAllowed
                    row(id)[ConferenceRecordingTable.status] shouldBe before
                    row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 0
                }
            }
            tick.get() shouldBe 0
        }

        test("raw files missing in every broken shape -> RecordingRawFilesMissingException, row unchanged") {
            val starter = createMember("starter-raw")
            val missing = "ERR:" + RecordingRawFilesMissingException::class.simpleName
            val noFiles = seedFailed(starter, withFiles = false)
            val emptyFile = seedFailed(starter).also { hostRawRoot.resolve(it.toString()).resolve("mic.ogg").writeBytes(byteArrayOf()) }
            val symlinked =
                seedFailed(starter).also {
                    val link = hostRawRoot.resolve(it.toString()).resolve("cam.mp4")
                    val outside = Files.createTempFile("outside-secret", ".bin").toFile().apply { writeText("x") }
                    link.delete()
                    Files.createSymbolicLink(link.toPath(), outside.toPath())
                    outside.deleteOnExit()
                }
            val badRawDir =
                seedFailed(starter).also { id ->
                    transaction { ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq id }) { it[rawDir] = "not-a-uuid" } }
                }
            val noVideo =
                seedFailed(starter).also { id ->
                    transaction {
                        ConferenceRecordingTrackTable.update({ ConferenceRecordingTrackTable.recordingId eq id }) {
                            it[trackSource] = ConferenceRecordingTrackSource.MICROPHONE
                        }
                    }
                }
            withRoutes { client ->
                listOf(noFiles, emptyFile, symlinked, badRawDir, noVideo).forEach { id ->
                    client.retry(id, starter) shouldBe missing
                    row(id)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                }
            }
        }

        test("an unknown recording is NotFoundException") {
            val member = createMember("nf", AccountRole.BOARD)
            withRoutes { client ->
                client.retry(Uuid.random(), member) shouldBe "ERR:" + NotFoundException::class.simpleName
            }
        }

        // ── rights ───────────────────────────────────────────────────────────

        test(
            "rights: non-moderator and guest are forbidden; moderator without access to ADMIN_ONLY is forbidden; starter, BOARD and ADMIN may",
        ) {
            val creator = createMember("room-creator")
            val other = createMember("other")
            val guest = createMember("guest", status = MemberStatus.GUEST)
            val board = createMember("board", AccountRole.BOARD)
            val admin = createMember("admin", AccountRole.ADMIN)
            val forbidden = "ERR:" + ForbiddenException::class.simpleName
            withRoutes { client ->
                val boardOnly = seedFailed(creator)
                client.retry(boardOnly, other) shouldBe forbidden
                client.retry(boardOnly, guest) shouldBe forbidden
                // moderator (room creator) who did not start an ADMIN_ONLY recording: no access to its tier
                val adminOnly = seedFailed(starter = admin, roomId = createRoom(creator), accessLevel = DocumentAccessLevel.ADMIN_ONLY)
                client.retry(adminOnly, creator) shouldBe forbidden
                row(adminOnly)[ConferenceRecordingTable.status] shouldBe ConferenceRecordingStatus.FAILED
                // starter who is also the room's creator
                parse(client.retry(boardOnly, creator)).status shouldBe ConferenceRecordingStatus.PROCESSING
                parse(client.retry(seedFailed(creator), board)).status shouldBe ConferenceRecordingStatus.PROCESSING
                parse(client.retry(seedFailed(creator, accessLevel = DocumentAccessLevel.ADMIN_ONLY), admin)).status shouldBe
                    ConferenceRecordingStatus.PROCESSING
            }
        }

        // ── rate limits ──────────────────────────────────────────────────────

        test("per-recording limits on a pinned clock: 15 minute spacing, 3 per 24 hours, new window afterwards") {
            val starter = createMember("starter-rate")
            val id = seedFailed(starter)
            val limited = "ERR:" + RecordingRetryRateLimitedException::class.simpleName
            val clock = MutableTestClock("2026-10-11T08:00:00Z")
            TimeTestSupport.withMutableServerClock(clock = clock) {
                withRoutes { client ->
                    parse(client.retry(id, starter)).status shouldBe ConferenceRecordingStatus.PROCESSING
                    markFailedAgain(id)
                    clock.advance(10.minutes)
                    client.retry(id, starter) shouldBe limited
                    clock.advance(6.minutes) // 16 minutes after the first
                    parse(client.retry(id, starter)).status shouldBe ConferenceRecordingStatus.PROCESSING
                    markFailedAgain(id)
                    clock.advance(16.minutes)
                    parse(client.retry(id, starter)).status shouldBe ConferenceRecordingStatus.PROCESSING
                    row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 3
                    markFailedAgain(id)
                    clock.advance(16.minutes)
                    client.retry(id, starter) shouldBe limited // fourth inside the window
                    row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 3
                    clock.advance(24.hours) // window over
                    parse(client.retry(id, starter)).status shouldBe ConferenceRecordingStatus.PROCESSING
                    row(id)[ConferenceRecordingTable.manualRetryCount] shouldBe 1
                }
            }
        }

        test("per-member call rate: the eleventh call within a minute is rate limited, even for a refused call") {
            val member = createMember("burst", AccountRole.BOARD)
            val limited = "ERR:" + RecordingRetryRateLimitedException::class.simpleName
            withRoutes(limiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)) { client ->
                repeat(10) { client.retry(Uuid.random(), member) shouldBe "ERR:" + NotFoundException::class.simpleName }
                client.retry(Uuid.random(), member) shouldBe limited
            }
        }

        // ── audit and DTO ────────────────────────────────────────────────────

        test("audit: one UPDATE entry with the exact before/after text and no path or title") {
            val starter = createMember("starter-audit")
            val id = seedFailed(starter)
            withRoutes { client -> client.retry(id, starter) }
            val entries = auditEntries(id)
            entries.size shouldBe 1
            val e = entries.single()
            e[AuditLogEntryTable.entityType] shouldBe AuditEntityType.CONFERENCE_RECORDING
            e[AuditLogEntryTable.beforeSnapshot] shouldBe "status=FAILED"
            e[AuditLogEntryTable.afterSnapshot] shouldBe "status=PROCESSING (manual composition retry #1)"
            e[AuditLogEntryTable.actorMemberId] shouldBe starter
            val all = (e[AuditLogEntryTable.beforeSnapshot] ?: "") + (e[AuditLogEntryTable.afterSnapshot] ?: "")
            all shouldNotContain id.toString()
            all shouldNotContain "GEHEIMER"
            all shouldNotContain hostRawRoot.path
        }

        test(
            "DTO fields: canRetryComposition/rawFilesAvailable only for a caller with the rights; none for others; PROCESSING exposes the retry start",
        ) {
            val creator = createMember("dto-creator")
            val stranger = createMember("dto-stranger")
            val admin = createMember("dto-admin", AccountRole.ADMIN)
            val roomId = createRoom(creator)
            val id = seedFailed(creator, roomId = roomId, accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS)
            withRoutes { client ->
                val mine = client.dtoFor(roomId, creator)
                mine.failureCode shouldBe ConferenceRecordingFailureCode.COMPOSE_FAILED
                mine.canRetryComposition shouldBe true
                mine.rawFilesAvailable shouldBe true
                // a plain member may SEE a PUBLIC_MEMBERS recording but learns nothing about the file system
                val theirs = client.dtoFor(roomId, stranger)
                theirs.canRetryComposition shouldBe false
                theirs.rawFilesAvailable shouldBe null
                // raw files gone: the moderator sees it, the button is off
                hostRawRoot.resolve(id.toString()).deleteRecursively()
                val gone = client.dtoFor(roomId, admin)
                gone.canRetryComposition shouldBe false
                gone.rawFilesAvailable shouldBe false
                // PROCESSING carries the retry start
                hostRawRoot
                    .resolve(id.toString())
                    .apply { mkdirs() }
                    .resolve("cam.mp4")
                    .writeBytes(byteArrayOf(1))
                hostRawRoot.resolve(id.toString()).resolve("mic.ogg").writeBytes(byteArrayOf(1))
                val processing = parse(client.retry(id, creator))
                (processing.compositionRetryStartedAt != null) shouldBe true
            }
        }

        test("the poller is not woken for a refused call") {
            val starter = createMember("starter-notick")
            val id = seedFailed(starter, withFiles = false)
            withRoutes { client -> client.retry(id, starter) }
            tick.get() shouldBe 0
        }
    })
