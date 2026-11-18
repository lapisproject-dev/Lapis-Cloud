package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.PartgReportVersionTable
import network.lapis.cloud.server.db.generated.PartgReviewerEventTable
import network.lapis.cloud.server.db.generated.PartgReviewerInvitationTable
import network.lapis.cloud.server.db.generated.PartgReviewerInvitationVersionTable
import network.lapis.cloud.server.db.generated.PartgReviewerSessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgReportFormat
import network.lapis.cloud.shared.domain.PartgReviewerEventKind
import network.lapis.cloud.shared.domain.PartgReviewerInvitationStatus
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportVersionIntegrityException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.310 (R6b) -- the auditor's read access on H2 AND PostgreSQL with the same assertions: the CHECKs of `V158__partg_reviewer_access.sql`, the
 * board's operations (create, list, revoke, erase) with their gate and validation, the whole code-and-session flow with every limit (5 tries, 3 burned
 * codes, 3 codes per hour, 10 in total, one use, 10 minutes; 30 minutes idle, 4 hours at most), session fixation, the scope, the integrity check, the
 * removal of a version, retention, the privacy of the audit and the usage list, and (on both lanes) the parallel tries.
 *
 * Own report year (2007); the removal scenarios use 2001 (versions old enough for the deletion routine).
 */
abstract class PartgReviewerScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PartgFixtures()
        val year = 2007
        val federal = "FEDERAL"
        val limiter = FederationInboxRateLimiter(maxRequests = 100_000, window = 60.minutes)

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest { fx.setParty(true) }
        afterTest {
            ServerClock.source = Clock.System
            fx.cleanUp()
        }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        var now = ServerClock.now()
        val sent = mutableListOf<Pair<String, String>>()
        beforeTest {
            now = ServerClock.now()
            sent.clear()
        }

        fun ops() = PartgReviewerOperations(writeRateLimiter = limiter, clock = { now })

        fun access() = PartgReviewerAccess(sendCode = { email, code -> sent += email to code }, clock = { now })

        fun board(label: String = "board") = fx.member(label = label, role = AccountRole.BOARD)

        fun cur(
            member: Uuid,
            role: AccountRole = AccountRole.BOARD,
        ) = fx.currentOf(memberId = member, role = role)

        /** ONE real kept version of [year]; the live state is changed first so it never equals the newest one. */
        suspend fun realVersion(
            member: Uuid,
            cents: Long,
        ): Uuid {
            var kept: Uuid? = null
            partgTestApplication(fixtures = fx) {
                val sheet = call(member = member) { service(it).getSheet(year = year, unitKey = federal) }
                call(member = member) {
                    service(it).saveSheet(
                        year = year,
                        unitKey = federal,
                        expectedRevision = sheet.revision,
                        expectedPriorRevision = sheet.priorRevision,
                        changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = cents)),
                    )
                }
                val dto = call(member = member) { service(it).freezeVersion(year = year, scopeKey = "ALL", comment = null) }
                kept = Uuid.parse(dto.id)
            }
            return checkNotNull(kept)
        }

        /** A version row with fake content (scope and retention scenarios; it is not renderable). */
        fun fakeVersion(
            by: Uuid,
            reportYear: Int,
            scope: String = "ALL",
            number: Int = 1,
            keptAt: LocalDateTime = LocalDateTime(2026, 1, 1, 0, 0),
        ): Uuid =
            transaction {
                val counter = PartgVersionStore.lockCounter(year = reportYear, scopeKey = scope, actor = by, now = keptAt)
                val json = "{\"fake\":$number,\"year\":$reportYear}"
                val id =
                    PartgVersionStore.insertVersion(
                        counter = counter,
                        number = number,
                        formatVersion = PartgVersionFormat.CURRENT,
                        contentJson = json,
                        contentHash = PartgVersionFormat.hash(json),
                        byteSize = json.length,
                        hasThirdPartyData = false,
                        comment = null,
                        actor = by,
                        now = keptAt,
                    )
                PartgVersionStore.bumpCounter(counter = counter, number = number, actor = by, now = keptAt)
                id
            }

        fun tokenOf(link: String) = link.substringAfter("#t=")

        fun invite(
            member: Uuid,
            versions: List<Uuid>,
            days: Int = 14,
            email: String = "pruefer@example.org",
            forYear: Int = year,
        ) = ops().create(
            current = cur(member),
            year = forYear,
            versionIds = versions.map { it.toString() },
            validityDays = days,
            email = email,
        )

        fun invRow(id: String) =
            transaction {
                PartgReviewerInvitationTable
                    .selectAll()
                    .where { PartgReviewerInvitationTable.id eq Uuid.parse(id) }
                    .single()
            }

        fun events(id: String) =
            transaction {
                PartgReviewerEventTable
                    .selectAll()
                    .where { PartgReviewerEventTable.invitationId eq Uuid.parse(id) }
                    .map { it[PartgReviewerEventTable.kind] }
            }

        fun sessionCount(id: String) =
            transaction {
                PartgReviewerSessionTable.selectAll().where { PartgReviewerSessionTable.invitationId eq Uuid.parse(id) }.count()
            }

        /** Asks for a code and returns it (the one the fake mail got). */
        fun askCode(
            a: PartgReviewerAccess,
            token: String,
        ): String? {
            val before = sent.size
            a.requestCode(token)
            return if (sent.size > before) sent.last().second else null
        }

        val wrong = "000000"

        fun wrongCodeFor(real: String) = if (real == wrong) "111111" else wrong

        // ------------------------------------------------------------------ the schema

        test("V158: the CHECKs, the unique token and the foreign keys refuse bad rows, each in its own transaction") {
            val member = board()
            val v = fakeVersion(member, year)
            val created = LocalDateTime(2026, 1, 1, 0, 0)

            fun row(
                tokenHash: String =
                    Uuid
                        .random()
                        .toString()
                        .replace("-", "")
                        .padEnd(64, '0'),
                codeHash: String? = null,
                codeExpiresAt: LocalDateTime? = null,
                attempts: Int = 0,
                burned: Int = 0,
                sentTotal: Int = 0,
                windowCount: Int = 0,
                expiresAt: LocalDateTime = created.plusDuration(1.days),
                revokedAt: LocalDateTime? = null,
                endReason: String? = null,
            ) = transaction {
                PartgReviewerInvitationTable.insert {
                    it[id] = Uuid.random()
                    it[reportYear] = year
                    it[inviteeEmail] = "a@example.org"
                    it[linkTokenHash] = tokenHash
                    it[PartgReviewerInvitationTable.codeHash] = codeHash
                    it[PartgReviewerInvitationTable.codeExpiresAt] = codeExpiresAt
                    it[codeAttempts] = attempts
                    it[codesBurned] = burned
                    it[codesSentTotal] = sentTotal
                    it[codesWindowCount] = windowCount
                    it[PartgReviewerInvitationTable.expiresAt] = expiresAt
                    it[PartgReviewerInvitationTable.revokedAt] = revokedAt
                    it[PartgReviewerInvitationTable.endReason] = endReason
                    it[createdBy] = member
                    it[createdAt] = created
                }
            }
            val dupe = "d".repeat(64)
            row(tokenHash = dupe)
            val refused: List<() -> Unit> =
                listOf(
                    { row(tokenHash = dupe) },
                    { row(tokenHash = "short") },
                    { row(attempts = 6) },
                    { row(burned = 4) },
                    { row(sentTotal = 11) },
                    { row(windowCount = 4) },
                    { row(codeHash = "a".repeat(64)) },
                    { row(codeExpiresAt = created) },
                    { row(codeHash = "a".repeat(10), codeExpiresAt = created) },
                    { row(expiresAt = created) },
                    { row(revokedAt = created) },
                    { row(endReason = "REVOKED") },
                    { row(revokedAt = created, endReason = "OTHER") },
                )
            refused
                .withIndex()
                .filter { (_, attempt) -> runCatching { attempt() }.isSuccess }
                .map { it.index }
                .shouldBeEmpty()
            row(codeHash = "a".repeat(64), codeExpiresAt = created, attempts = 5, burned = 3, sentTotal = 10, windowCount = 3)
            row(revokedAt = created, endReason = "SCOPE_DELETED")

            val inv = invite(member, listOf(v))
            val id = Uuid.parse(inv.invitation.id)
            val refusedEvents: List<() -> Unit> =
                listOf(
                    {
                        transaction {
                            PartgReviewerStore.insertEvent(
                                invitationId = id,
                                kind = "BOGUS",
                                versionId = null,
                                format = null,
                                at = created,
                            )
                        }
                    },
                    {
                        transaction {
                            PartgReviewerStore.insertEvent(
                                invitationId = id,
                                kind = "DOWNLOAD",
                                versionId = null,
                                format = "DOC",
                                at = created,
                            )
                        }
                    },
                    {
                        transaction {
                            PartgReviewerStore.insertEvent(
                                invitationId = Uuid.random(),
                                kind = "DOWNLOAD",
                                versionId = null,
                                format = "PDF",
                                at = created,
                            )
                        }
                    },
                )
            refusedEvents.forEach { attempt -> runCatching { attempt() }.isFailure shouldBe true }
            transaction {
                PartgReviewerStore.insertEvent(
                    invitationId = id,
                    kind = "DOWNLOAD",
                    versionId = Uuid.random(),
                    format = "PDF",
                    at = created,
                )
            } // no FK on version_id
            // the scope row points to a real version only
            runCatching {
                transaction {
                    PartgReviewerInvitationVersionTable.insert {
                        it[invitationId] = id
                        it[versionId] = Uuid.random()
                    }
                }
            }.isFailure shouldBe true
            // and the same version cannot be linked twice
            runCatching {
                transaction {
                    PartgReviewerInvitationVersionTable.insert {
                        it[invitationId] = id
                        it[versionId] = v
                    }
                }
            }.isFailure shouldBe true
            // no cascade: a version that is still linked cannot simply be deleted
            runCatching { transaction { PartgVersionStore.deleteVersions(listOf(v)) } }.isFailure shouldBe true
        }

        test("V158: the audit CHECK knows the new entity type and still the old ones") {
            val types =
                listOf(AuditEntityType.PARTG_REVIEWER_ACCESS, AuditEntityType.CONFERENCE_ROOM_POLL, AuditEntityType.PARTG_REPORT_VERSION)
            val ids = types.map { Uuid.random() }
            types.forEachIndexed { index, type ->
                transaction {
                    AuditLogRecorder.record(
                        actorMemberId = null,
                        actorRole = null,
                        entityType = type,
                        entityId = ids[index],
                        action = AuditAction.UPDATE,
                        after = "{}",
                    )
                }
            }
            transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId inList ids }.count() } shouldBe
                types.size.toLong()
        }

        // ------------------------------------------------------------------ the board

        test("create: the link carries the token, only its hash is stored, the list shows no secret, and the audit holds ids only") {
            val member = board()
            val v = fakeVersion(member, year)
            val created = invite(member, listOf(v), days = 14, email = "  Pruefer@Example.ORG ")
            val token = tokenOf(created.link)
            created.link.contains("/pruefung#t=") shouldBe true
            created.link.contains("?") shouldBe false
            PartgReviewerTokens.plausibleLinkToken(token) shouldBe true
            created.invitation.email shouldBe "pruefer@example.org"
            created.invitation.expiresAt shouldBe now.plusDuration(14.days)
            created.invitation.status shouldBe PartgReviewerInvitationStatus.ACTIVE
            created.invitation.versionNumbers shouldContainExactly listOf(1)
            val row = invRow(created.invitation.id)
            row[PartgReviewerInvitationTable.linkTokenHash] shouldBe PartgReviewerTokens.hash(token)
            (row[PartgReviewerInvitationTable.linkTokenHash] == token) shouldBe false
            val listed = ops().list(current = cur(member), year = year)
            val listedJson =
                Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        network.lapis.cloud.shared.domain.PartgReviewerInvitationDto
                            .serializer(),
                    ),
                    listed,
                )
            listedJson shouldNotContain token
            listedJson shouldNotContain PartgReviewerTokens.hash(token)
            listed.single().events.map { it.kind } shouldContainExactly listOf(PartgReviewerEventKind.INVITATION_CREATED)
            val audits =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.entityId eq Uuid.parse(created.invitation.id) }
                        .map { it[AuditLogEntryTable.action] to it[AuditLogEntryTable.afterSnapshot].orEmpty() }
                }
            audits.single().first shouldBe AuditAction.CREATE
            audits.single().second shouldNotContain token
            audits.single().second shouldNotContain "example.org"
        }

        test("the gate: a member, a treasurer and a chapter treasurer get 403; BOARD and ADMIN may") {
            val board = board()
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val plain = fx.member(label = "plain", role = AccountRole.MEMBER)
            val treasurer = fx.member(label = "treasurer", role = AccountRole.TREASURER)
            val chapterTreasurer = fx.member(label = "chapter", role = AccountRole.MEMBER)
            fx.grantTreasurer(member = chapterTreasurer, chapter = fx.chapter())
            val v = fakeVersion(board, year)
            partgTestApplication(fixtures = fx) {
                listOf(plain, treasurer, chapterTreasurer).forEach { who ->
                    attempt(member = who) {
                        service(
                            it,
                        ).createReviewerInvitation(
                            year = year,
                            versionIds = listOf(v.toString()),
                            validityDays = 14,
                            email = "a@example.org",
                        )
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                    attempt(
                        member = who,
                    ) { service(it).listReviewerInvitations(year) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    attempt(member = who) { service(it).revokeReviewerInvitation(Uuid.random().toString()) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                    attempt(
                        member = who,
                    ) { service(it).eraseReviewerEmail("a@example.org") }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
                val byBoard =
                    call(member = board) {
                        service(
                            it,
                        ).createReviewerInvitation(
                            year = year,
                            versionIds = listOf(v.toString()),
                            validityDays = 7,
                            email = "b@example.org",
                        )
                    }
                val byAdmin =
                    call(member = admin) {
                        service(
                            it,
                        ).createReviewerInvitation(
                            year = year,
                            versionIds = listOf(v.toString()),
                            validityDays = 7,
                            email = "c@example.org",
                        )
                    }
                call(member = board) { service(it).listReviewerInvitations(year) }.map { it.id } shouldContain byBoard.invitation.id
                // another board member can revoke an invitation of someone else (the author is not special)
                call(member = admin) { service(it).revokeReviewerInvitation(byBoard.invitation.id) }.status shouldBe
                    PartgReviewerInvitationStatus.REVOKED
                call(member = board) { service(it).eraseReviewerEmail("c@example.org") } shouldBe 1
                byAdmin.invitation.email shouldBe "c@example.org"
            }
        }

        test("create: every invalid input is refused and nothing is stored") {
            val member = board()
            val v1 = fakeVersion(member, year)
            val other = fakeVersion(member, year + 1)
            val unit = fakeVersion(member, year, scope = "STATE:${Uuid.random()}")

            fun attempt(
                ids: List<String> = listOf(v1.toString()),
                days: Int = 14,
                email: String = "a@example.org",
                forYear: Int = year,
            ) = runCatching {
                ops().create(current = cur(member), year = forYear, versionIds = ids, validityDays = days, email = email)
            }.exceptionOrNull()

            attempt(days = 5).shouldBeInstanceOf<PartyReportInvalidInputException>()
            attempt(days = 0).shouldBeInstanceOf<PartyReportInvalidInputException>()
            attempt(ids = emptyList()).shouldBeInstanceOf<PartyReportInvalidInputException>()
            attempt(ids = List(51) { Uuid.random().toString() }).shouldBeInstanceOf<PartyReportInvalidInputException>()
            attempt(ids = listOf(v1.toString(), v1.toString())).shouldBeInstanceOf<PartyReportInvalidInputException>()
            attempt(ids = listOf("not-a-uuid")).shouldBeInstanceOf<PartyReportInvalidInputException>()
            attempt(ids = listOf(Uuid.random().toString())).shouldBeInstanceOf<PartyReportInvalidInputException>()
            attempt(ids = listOf(other.toString())).shouldBeInstanceOf<PartyReportInvalidInputException>() // another year
            attempt(ids = listOf(unit.toString())).shouldBeInstanceOf<PartyReportInvalidInputException>() // a unit version
            attempt(email = "a@example.org\nBcc: x@example.org").shouldBeInstanceOf<PartyReportInvalidInputException>()
            attempt(email = "a@exa\rmple.org").shouldBeInstanceOf<PartyReportInvalidInputException>()
            attempt(email = "no-at-sign").shouldBeInstanceOf<PartyReportInvalidInputException>()
            attempt(email = "a".repeat(250) + "@example.org").shouldBeInstanceOf<PartyReportInvalidInputException>()
            attempt(forYear = 1999).shouldBeInstanceOf<PartyReportInvalidInputException>()
            ops().list(current = cur(member), year = year).size shouldBe 0
            // at most twenty open invitations per year
            repeat(20) { invite(member, listOf(v1), email = "p$it@example.org") }
            attempt().shouldBeInstanceOf<PartyReportInvalidInputException>()
        }

        test(
            "revoke ends the invitation and the sessions at once and is idempotent; erase removes the address, ends the invitation and says how many",
        ) {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val inv = invite(member, listOf(v))
            val code = askCode(a, tokenOf(inv.link)).shouldBeInstanceOf<String>()
            val opened =
                a
                    .openSession(
                        rawToken = tokenOf(inv.link),
                        rawCode = code,
                        previousRawSession = null,
                    ).shouldBeInstanceOf<PartgReviewerAccess.OpenedSession>()
            a.resolveSession(opened.rawToken)?.invitationId.toString() shouldBe inv.invitation.id
            val revoked = ops().revoke(current = cur(member), invitationId = inv.invitation.id)
            revoked.status shouldBe PartgReviewerInvitationStatus.REVOKED
            sessionCount(inv.invitation.id) shouldBe 0L
            a.resolveSession(opened.rawToken) shouldBe null
            ops().revoke(current = cur(member), invitationId = inv.invitation.id).status shouldBe PartgReviewerInvitationStatus.REVOKED
            runCatching {
                ops().revoke(current = cur(member), invitationId = Uuid.random().toString())
            }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
            // erase: same address on two invitations, different case
            val a1 = invite(member, listOf(v), email = "same@example.org")
            val a2 = invite(member, listOf(v), email = "SAME@example.org")
            invite(member, listOf(v), email = "other@example.org")
            ops().erase(current = cur(member), email = "Same@Example.org") shouldBe 2
            listOf(a1, a2).forEach {
                val row = invRow(it.invitation.id)
                row[PartgReviewerInvitationTable.inviteeEmail] shouldBe null
                row[PartgReviewerInvitationTable.endReason] shouldBe "ERASED"
            }
            ops().erase(current = cur(member), email = "same@example.org") shouldBe 0
            ops().list(current = cur(member), year = year).count { it.email == "other@example.org" } shouldBe 1
        }

        // ------------------------------------------------------------------ the auditor: code and session

        test(
            "the whole flow: code by mail, one session, versions, the stored text byte for byte, PDF, XLSX, the comparison; one event and one audit per step",
        ) {
            val member = board()
            val v1 = realVersion(member, 1_000)
            val v2 = realVersion(member, 2_000)
            val a = access()
            val inv = invite(member, listOf(v1, v2))
            val token = tokenOf(inv.link)
            val code = askCode(a, token).shouldBeInstanceOf<String>()
            sent.single().first shouldBe "pruefer@example.org"
            (code.length == 6 && code.all { it.isDigit() }) shouldBe true
            val opened =
                a
                    .openSession(
                        rawToken = token,
                        rawCode = spaced(code),
                        previousRawSession = null,
                    ).shouldBeInstanceOf<PartgReviewerAccess.OpenedSession>()
            val session = a.resolveSession(opened.rawToken).shouldBeInstanceOf<PartgReviewerAccess.ReviewerSession>()
            session.year shouldBe year
            a.versions(session).map { it.versionNumber } shouldContainExactly listOf(1, 2)
            val stored = transaction { PartgReportVersionTable.selectAll().where { PartgReportVersionTable.id eq v1 }.single() }
            val doc = a.document(session = session, versionId = v1, download = false)
            doc.json shouldBe stored[PartgReportVersionTable.contentJson]
            PartgVersionFormat.hash(doc.json) shouldBe stored[PartgReportVersionTable.contentHash]
            val (pdfMeta, pdf) = a.render(session = session, versionId = v1, format = PartgReportFormat.PDF)
            String(pdf.copyOf(4)) shouldBe "%PDF"
            PartgStoredVersionRenderer.fileName(meta = pdfMeta.meta, extension = "pdf") shouldBe "rechenschaftsbericht-$year-version-1.pdf"
            val (_, xlsx) = a.render(session = session, versionId = v2, format = PartgReportFormat.XLSX)
            String(xlsx.copyOf(2)) shouldBe "PK"
            a.document(session = session, versionId = v2, download = true)
            val diff = a.compare(session = session, idA = v1, idB = v2)
            diff.versionNumberA shouldBe 1
            diff.versionNumberB shouldBe 2
            events(inv.invitation.id) shouldContainExactlyInAnyOrder
                listOf(
                    "INVITATION_CREATED",
                    "CODE_SENT",
                    "SESSION_OPENED",
                    "DOCUMENT_VIEWED",
                    "DOWNLOAD",
                    "DOWNLOAD",
                    "DOWNLOAD",
                    "COMPARISON_VIEWED",
                )
            val shown = ops().list(current = cur(member), year = year).single()
            shown.events.size shouldBe 8
            shown.events.mapNotNull { it.versionNumber }.toSet() shouldBe setOf(1, 2)
            shown.lastUsedAt shouldBe now
            a.endSession(opened.rawToken)
            a.resolveSession(opened.rawToken) shouldBe null
            events(inv.invitation.id) shouldContain "SESSION_ENDED"
        }

        test("a code is used once, a new code voids the old one, and a code is good for ten minutes only") {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val token = tokenOf(invite(member, listOf(v)).link)
            val first = askCode(a, token).shouldBeInstanceOf<String>()
            a
                .openSession(
                    rawToken = token,
                    rawCode = first,
                    previousRawSession = null,
                ).shouldBeInstanceOf<PartgReviewerAccess.OpenedSession>()
            a.openSession(rawToken = token, rawCode = first, previousRawSession = null) shouldBe null // used
            val second = askCode(a, token).shouldBeInstanceOf<String>()
            val third = askCode(a, token).shouldBeInstanceOf<String>()
            // voided by the third
            if (second != third) a.openSession(rawToken = token, rawCode = second, previousRawSession = null) shouldBe null
            now = now.plusDuration(10.minutes).plusDuration(1.minutes)
            a.openSession(rawToken = token, rawCode = third, previousRawSession = null) shouldBe null // 10 minutes are over
            now = now.plusDuration(1.hours)
            val fourth = askCode(a, token).shouldBeInstanceOf<String>()
            now = now.plusDuration(9.minutes)
            a
                .openSession(
                    rawToken = token,
                    rawCode = fourth,
                    previousRawSession = null,
                ).shouldBeInstanceOf<PartgReviewerAccess.OpenedSession>()
        }

        test(
            "five wrong tries burn the code, even the right one fails afterwards; three burned codes lock the invitation and end the sessions",
        ) {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val inv = invite(member, listOf(v))
            val token = tokenOf(inv.link)
            // a session that already exists is ended by the lock
            val live =
                a
                    .openSession(
                        rawToken = token,
                        rawCode = askCode(a, token)!!,
                        previousRawSession = null,
                    ).shouldBeInstanceOf<PartgReviewerAccess.OpenedSession>()
            repeat(3) { round ->
                now = now.plusDuration(61.minutes) // three codes per hour: every round starts a new window
                val code = askCode(a, token).shouldBeInstanceOf<String>()
                repeat(4) { a.openSession(rawToken = token, rawCode = wrongCodeFor(code), previousRawSession = null) shouldBe null }
                invRow(inv.invitation.id)[PartgReviewerInvitationTable.codeAttempts] shouldBe 4
                // the fifth burns the code
                a.openSession(rawToken = token, rawCode = wrongCodeFor(code), previousRawSession = null) shouldBe null
                val row = invRow(inv.invitation.id)
                row[PartgReviewerInvitationTable.codesBurned] shouldBe round + 1
                row[PartgReviewerInvitationTable.codeHash] shouldBe null
                // burned: the right code is of no use
                a.openSession(rawToken = token, rawCode = code, previousRawSession = null) shouldBe null
                if (round == 1) sessionCount(inv.invitation.id) shouldBe 1L // not locked yet: the session row is still there
            }
            invRow(inv.invitation.id)[PartgReviewerInvitationTable.lockedAt] shouldBe now
            sessionCount(inv.invitation.id) shouldBe 0L
            a.resolveSession(live.rawToken) shouldBe null
            ops().list(current = cur(member), year = year).single().status shouldBe PartgReviewerInvitationStatus.LOCKED
            askCode(a, token) shouldBe null // locked: no more codes
            events(inv.invitation.id) shouldContain "INVITATION_LOCKED"
            events(inv.invitation.id).count { it == "CODE_FAILED" } shouldBe 15
        }

        test("a successful login does not count towards the lock") {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val inv = invite(member, listOf(v))
            val token = tokenOf(inv.link)
            repeat(3) {
                val code = askCode(a, token).shouldBeInstanceOf<String>()
                a
                    .openSession(
                        rawToken = token,
                        rawCode = code,
                        previousRawSession = null,
                    ).shouldBeInstanceOf<PartgReviewerAccess.OpenedSession>()
            }
            invRow(inv.invitation.id)[PartgReviewerInvitationTable.lockedAt] shouldBe null
            invRow(inv.invitation.id)[PartgReviewerInvitationTable.codesBurned] shouldBe 0
        }

        test("at most three codes per hour and ten per invitation; an ended, revoked or foreign link gets nothing and looks the same") {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val inv = invite(member, listOf(v))
            val token = tokenOf(inv.link)
            repeat(3) { askCode(a, token).shouldBeInstanceOf<String>() }
            askCode(a, token) shouldBe null
            now = now.plusDuration(61.minutes)
            askCode(a, token).shouldBeInstanceOf<String>()
            // ten in total
            transaction {
                PartgReviewerInvitationTable.update({ PartgReviewerInvitationTable.id eq Uuid.parse(inv.invitation.id) }) {
                    it[codesSentTotal] = 10
                    it[codesWindowCount] = 0
                }
            }
            now = now.plusDuration(3.hours)
            askCode(a, token) shouldBe null
            // unknown token, revoked, expired
            askCode(a, PartgReviewerTokens.newLinkToken()) shouldBe null
            val revokedInv = invite(member, listOf(v))
            ops().revoke(current = cur(member), invitationId = revokedInv.invitation.id)
            askCode(a, tokenOf(revokedInv.link)) shouldBe null
            val shortInv = invite(member, listOf(v), days = 7)
            now = now.plusDuration(8.days)
            askCode(a, tokenOf(shortInv.link)) shouldBe null
            ops().list(current = cur(member), year = year).first { it.id == shortInv.invitation.id }.status shouldBe
                PartgReviewerInvitationStatus.EXPIRED
        }

        test(
            "a session ends after 30 minutes idle and 4 hours at most; every request extends the idle time; the invitation always decides",
        ) {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val inv = invite(member, listOf(v))
            val token = tokenOf(inv.link)
            val opened = a.openSession(rawToken = token, rawCode = askCode(a, token)!!, previousRawSession = null)!!
            val start = now
            now = start.plusDuration(29.minutes)
            a.resolveSession(opened.rawToken).shouldBeInstanceOf<PartgReviewerAccess.ReviewerSession>() // extends to +59
            now = start.plusDuration(58.minutes)
            a.resolveSession(opened.rawToken).shouldBeInstanceOf<PartgReviewerAccess.ReviewerSession>()
            // keep it alive in small steps up to the absolute end
            var t = start.plusDuration(58.minutes)
            while (t < start.plusDuration(3.hours + 50.minutes)) {
                t = t.plusDuration(25.minutes)
                now = t
                a.resolveSession(opened.rawToken).shouldBeInstanceOf<PartgReviewerAccess.ReviewerSession>()
            }
            now = start.plusDuration(4.hours + 1.minutes)
            a.resolveSession(opened.rawToken) shouldBe null // absolute end, though used a minute ago
            // idle
            now = start.plusDuration(5.hours)
            val second = a.openSession(rawToken = token, rawCode = askCode(a, token)!!, previousRawSession = null)!!
            now = now.plusDuration(31.minutes)
            a.resolveSession(second.rawToken) shouldBe null
        }

        test("an invitation ended during a session ends the session at once: revoked, expired, the party switch off") {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val inv = invite(member, listOf(v), days = 7)
            val token = tokenOf(inv.link)
            val opened = a.openSession(rawToken = token, rawCode = askCode(a, token)!!, previousRawSession = null)!!
            fx.setParty(false)
            a.resolveSession(opened.rawToken) shouldBe null
            fx.setParty(true)
            askCode(a, token) // party on again: a new code can be had
            val again = a.openSession(rawToken = token, rawCode = sent.last().second, previousRawSession = null)!!
            a.resolveSession(again.rawToken).shouldBeInstanceOf<PartgReviewerAccess.ReviewerSession>()
            transaction {
                PartgReviewerInvitationTable.update({ PartgReviewerInvitationTable.id eq Uuid.parse(inv.invitation.id) }) {
                    it[expiresAt] = now.plusDuration(1.minutes)
                }
            }
            now = now.plusDuration(2.minutes)
            a.resolveSession(again.rawToken) shouldBe null
        }

        test("the party switch off: no code goes out and no session opens") {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val token = tokenOf(invite(member, listOf(v)).link)
            val code = askCode(a, token)!!
            fx.setParty(false)
            askCode(a, token) shouldBe null
            a.openSession(rawToken = token, rawCode = code, previousRawSession = null) shouldBe null
        }

        test("session fixation: a cookie sent along is never taken over, its session of the same invitation is ended") {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val token = tokenOf(invite(member, listOf(v)).link)
            val old = a.openSession(rawToken = token, rawCode = askCode(a, token)!!, previousRawSession = null)!!
            val fresh = a.openSession(rawToken = token, rawCode = askCode(a, token)!!, previousRawSession = old.rawToken)!!
            (fresh.rawToken == old.rawToken) shouldBe false
            a.resolveSession(old.rawToken) shouldBe null
            a.resolveSession(fresh.rawToken).shouldBeInstanceOf<PartgReviewerAccess.ReviewerSession>()
            // a session of ANOTHER invitation is not touched by a login with its cookie
            val other = invite(member, listOf(v))
            val otherToken = tokenOf(other.link)
            val otherSession = a.openSession(rawToken = otherToken, rawCode = askCode(a, otherToken)!!, previousRawSession = null)!!
            a.openSession(rawToken = token, rawCode = askCode(a, token)!!, previousRawSession = otherSession.rawToken)!!
            a.resolveSession(otherSession.rawToken).shouldBeInstanceOf<PartgReviewerAccess.ReviewerSession>()
        }

        test("a malformed code is a failed try, and a short or foreign token opens nothing") {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val inv = invite(member, listOf(v))
            val token = tokenOf(inv.link)
            askCode(a, token)
            listOf("12345", "1234567", "12a456", "", "      ").forEach {
                a.openSession(rawToken = token, rawCode = it, previousRawSession = null) shouldBe
                    null
            }
            invRow(inv.invitation.id)[PartgReviewerInvitationTable.codesBurned] shouldBe 1
            a.openSession(rawToken = "short", rawCode = "123456", previousRawSession = null) shouldBe null
            a.openSession(rawToken = token + "+", rawCode = "123456", previousRawSession = null) shouldBe null
        }

        // ------------------------------------------------------------------ the auditor: scope and integrity

        test(
            "scope: only the versions of the invitation; any other id (also of the same year, another year, a random one) is the same refusal",
        ) {
            val member = board()
            val inScope = realVersion(member, 500)
            val outside = realVersion(member, 600)
            val otherYear = fakeVersion(member, year + 1)
            val a = access()
            val token = tokenOf(invite(member, listOf(inScope)).link)
            val session =
                a.resolveSession(
                    a.openSession(rawToken = token, rawCode = askCode(a, token)!!, previousRawSession = null)!!.rawToken,
                )!!
            a.versions(session).map { it.id } shouldContainExactly listOf(inScope.toString())
            a.document(session = session, versionId = inScope, download = false)
            listOf(outside, otherYear, Uuid.random()).forEach { id ->
                runCatching {
                    a.document(session = session, versionId = id, download = false)
                }.exceptionOrNull().shouldBeInstanceOf<PartgReviewerNotInScopeException>()
                runCatching {
                    a.render(
                        session = session,
                        versionId = id,
                        format = PartgReportFormat.PDF,
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartgReviewerNotInScopeException>()
                runCatching {
                    a.compare(session = session, idA = inScope, idB = id)
                }.exceptionOrNull().shouldBeInstanceOf<PartgReviewerNotInScopeException>()
                runCatching {
                    a.compare(session = session, idA = id, idB = inScope)
                }.exceptionOrNull().shouldBeInstanceOf<PartgReviewerNotInScopeException>()
            }
        }

        test("integrity: a stored text that no longer matches its checksum is never rendered nor shown") {
            val member = board()
            val v = realVersion(member, 700)
            val a = access()
            val token = tokenOf(invite(member, listOf(v)).link)
            val session =
                a.resolveSession(
                    a.openSession(rawToken = token, rawCode = askCode(a, token)!!, previousRawSession = null)!!.rawToken,
                )!!
            transaction {
                PartgReportVersionTable.update({ PartgReportVersionTable.id eq v }) {
                    it[contentJson] = "{\"tampered\":true}"
                }
            }
            val onDocument = runCatching { a.document(session = session, versionId = v, download = false) }.exceptionOrNull()
            onDocument.shouldBeInstanceOf<PartyReportVersionIntegrityException>()
            val onPdf = runCatching { a.render(session = session, versionId = v, format = PartgReportFormat.PDF) }.exceptionOrNull()
            onPdf.shouldBeInstanceOf<PartyReportVersionIntegrityException>()
        }

        // ------------------------------------------------------------------ removal of a version, retention

        test("the deletion routine ends an invitation whose last version it removes and keeps the others; no foreign key error") {
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val old = 2001
            val gone = fakeVersion(admin, old, number = 1, keptAt = LocalDateTime(2002, 3, 4, 0, 0))
            val goneToo = fakeVersion(admin, old, scope = "ALL", number = 2, keptAt = LocalDateTime(2002, 3, 5, 0, 0))
            val a = access()
            val onlyGone = invite(admin, listOf(gone), forYear = old)
            val both = invite(admin, listOf(gone, goneToo), forYear = old)
            val token = tokenOf(onlyGone.link)
            val session = a.openSession(rawToken = token, rawCode = askCode(a, token)!!, previousRawSession = null)!!
            val today = OrganizationTimeZone.today()
            val candidates =
                transaction {
                    PartgVersionWriter
                        .deletionCandidates(olderThanYears = 10, today = today)
                        .first
                        .map { it.id }
                        .toSet()
                }
            candidates shouldBe setOf(gone, goneToo)
            PartgVersionWriter.deleteOld(
                current = cur(admin, AccountRole.ADMIN),
                olderThanYears = 10,
                expectedIds = candidates,
                today = today,
                now = ServerClock.now(),
            ) shouldBe
                2
            invRow(onlyGone.invitation.id)[PartgReviewerInvitationTable.endReason] shouldBe "SCOPE_DELETED"
            invRow(both.invitation.id)[PartgReviewerInvitationTable.endReason] shouldBe "SCOPE_DELETED"
            sessionCount(onlyGone.invitation.id) shouldBe 0L
            a.resolveSession(session.rawToken) shouldBe null
            transaction {
                PartgReviewerInvitationVersionTable.selectAll().where { PartgReviewerInvitationVersionTable.versionId eq gone }.count()
            } shouldBe 0L
            events(onlyGone.invitation.id) shouldContain "INVITATION_REVOKED"
        }

        test("the deletion routine keeps an invitation that still has a version, and only detaches the removed one") {
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val old = 2001
            val gone = fakeVersion(admin, old, number = 1, keptAt = LocalDateTime(2002, 3, 4, 0, 0))
            // too young to be removed
            val stays = fakeVersion(admin, old, scope = "ALL", number = 2, keptAt = LocalDateTime(2026, 3, 5, 0, 0))
            val inv = invite(admin, listOf(gone, stays), forYear = old)
            val today = OrganizationTimeZone.today()
            val candidates =
                transaction {
                    PartgVersionWriter
                        .deletionCandidates(olderThanYears = 10, today = today)
                        .first
                        .map { it.id }
                        .toSet()
                }
            candidates shouldBe setOf(gone)
            PartgVersionWriter.deleteOld(
                current = cur(admin, AccountRole.ADMIN),
                olderThanYears = 10,
                expectedIds = candidates,
                today = today,
                now = ServerClock.now(),
            ) shouldBe
                1
            invRow(inv.invitation.id)[PartgReviewerInvitationTable.revokedAt] shouldBe null
            ops().list(current = cur(admin), year = old).single().versionIds shouldContainExactly listOf(stays.toString())
        }

        test("retention: the address goes 90 days after the end, not before; expired sessions are removed") {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val expiring = invite(member, listOf(v), days = 7)
            val revoked = invite(member, listOf(v), days = 90, email = "r@example.org")
            val running = invite(member, listOf(v), days = 90, email = "run@example.org")
            val token = tokenOf(expiring.link)
            a.openSession(rawToken = token, rawCode = askCode(a, token)!!, previousRawSession = null)!!
            val start = now
            ops().revoke(current = cur(member), invitationId = revoked.invitation.id) // ends at `start`

            fun email(id: String) = invRow(id)[PartgReviewerInvitationTable.inviteeEmail]

            fun tick(at: LocalDateTime) = PartgReviewerRetentionPoller(clock = { at }).tick()
            val (sessionsRemoved, none) = tick(start.plusDuration(89.days))
            sessionsRemoved shouldBe 1
            none shouldBe 0
            email(revoked.invitation.id) shouldBe "r@example.org" // 89 days after the end: still there
            val (_, first) = tick(start.plusDuration(91.days))
            first shouldBe 1
            email(revoked.invitation.id) shouldBe null
            email(expiring.invitation.id) shouldBe "pruefer@example.org" // it ended at +7 days
            val (_, second) = tick(start.plusDuration(98.days))
            second shouldBe 1
            email(expiring.invitation.id) shouldBe null
            email(running.invitation.id) shouldBe "run@example.org" // runs until +90 days
        }

        // ------------------------------------------------------------------ privacy

        test("the audit and the usage list hold no code, no token, no hash, no address and no content") {
            val member = board()
            val v = realVersion(member, 123_456)
            val a = access()
            val inv = invite(member, listOf(v))
            val token = tokenOf(inv.link)
            val code = askCode(a, token)!!
            a.openSession(rawToken = token, rawCode = wrongCodeFor(code), previousRawSession = null)
            val opened = a.openSession(rawToken = token, rawCode = code, previousRawSession = null)!!
            val session = a.resolveSession(opened.rawToken)!!
            a.document(session = session, versionId = v, download = true)
            a.render(session = session, versionId = v, format = PartgReportFormat.PDF)
            ops().revoke(current = cur(member), invitationId = inv.invitation.id)
            val texts =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.entityId eq Uuid.parse(inv.invitation.id) }
                        .map { it[AuditLogEntryTable.beforeSnapshot].orEmpty() + it[AuditLogEntryTable.afterSnapshot].orEmpty() } +
                        PartgReviewerEventTable.selectAll().map {
                            "${it[PartgReviewerEventTable.kind]} ${it[PartgReviewerEventTable.versionId]} ${it[PartgReviewerEventTable.format]}"
                        }
                }
            val secrets =
                listOf(
                    token,
                    PartgReviewerTokens.hash(token),
                    code,
                    PartgReviewerTokens.codeHash(invitationId = Uuid.parse(inv.invitation.id), code = code),
                    opened.rawToken,
                    PartgReviewerTokens.hash(opened.rawToken),
                    "example.org",
                    "pruefer",
                    "Partg-Test",
                )
            texts.isNotEmpty() shouldBe true
            texts.forEach { text -> secrets.forEach { secret -> text shouldNotContain secret } }
            // the audit of an auditor's own step has no actor
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityId eq Uuid.parse(inv.invitation.id) }
                    .filter { it[AuditLogEntryTable.afterSnapshot].orEmpty().contains("SESSION_OPENED") }
                    .all { it[AuditLogEntryTable.actorMemberId] == null }
            } shouldBe true
        }

        // ------------------------------------------------------------------ parallel tries (both lanes)

        test("parallel wrong tries lose no update: exactly five count, the code is burned once") {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val inv = invite(member, listOf(v))
            val token = tokenOf(inv.link)
            val code = askCode(a, token)!!
            val wrongOne = wrongCodeFor(code)
            coroutineScope {
                (1..10)
                    .map {
                        async(
                            Dispatchers.IO,
                        ) { a.openSession(rawToken = token, rawCode = wrongOne, previousRawSession = null) }
                    }.awaitAll()
            }.all { it == null } shouldBe true
            val row = invRow(inv.invitation.id)
            row[PartgReviewerInvitationTable.codesBurned] shouldBe 1
            row[PartgReviewerInvitationTable.codeHash] shouldBe null
            events(inv.invitation.id).count { it == "CODE_FAILED" } shouldBe 5
        }

        test("parallel use of the right code opens exactly one session") {
            val member = board()
            val v = fakeVersion(member, year)
            val a = access()
            val inv = invite(member, listOf(v))
            val token = tokenOf(inv.link)
            val code = askCode(a, token)!!
            val results =
                coroutineScope {
                    (1..6)
                        .map {
                            async(
                                Dispatchers.IO,
                            ) { a.openSession(rawToken = token, rawCode = code, previousRawSession = null) }
                        }.awaitAll()
                }
            results.count { it != null } shouldBe 1
            sessionCount(inv.invitation.id) shouldBe 1L
        }
    })

/** Test helper: the code the way it is shown in the mail ("482 913"). */
private fun spaced(code: String) = "${code.substring(0, 3)} ${code.substring(3)}"
