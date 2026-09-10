package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.FriendEmailVerificationTokenTable
import network.lapis.cloud.server.db.generated.MemberFamilyLinkTable
import network.lapis.cloud.server.db.generated.MemberFamilyTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PasswordResetTokenTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.AdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.FriendVerificationMailer
import network.lapis.cloud.server.mail.PasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfig
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.TemporaryPasswordGenerator
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminSort
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaSequenceType
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.LastAdminException
import network.lapis.cloud.shared.rpc.MemberAlreadyHasAccountException
import network.lapis.cloud.shared.rpc.MemberEmailInUseException
import network.lapis.cloud.shared.rpc.MemberEmailTooLongException
import network.lapis.cloud.shared.rpc.MemberHasNoAccountException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import network.lapis.cloud.shared.rpc.WeakPasswordException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"
private const val STRONG_PASSWORD = "a-genuinely-strong-password-1"

/**
 * Exercises [MemberService]'s Welle-V1.2.12 surface (listMembersForAdministration/
 * updateMemberCoreData/updateMemberStatus/updateMemberRole) end to end -- same "throwaway routes
 * calling the service class directly" house style as [RegistrationServiceTest]/
 * [AuditLogServiceTest]. DevSeedData's ADMIN/BOARD/TREASURER/MEMBER accounts are used only as the
 * *actors*; every target member under test is freshly created by this file and hard-deleted in
 * [afterSpec].
 */
class MemberAdministrationTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdCommitteeIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // AuditLogEntryTable rows are deliberately NEVER cleaned up -- append-only by design
                // (AuditLogRecorder KDoc), same posture AuditLogServiceTest's own cleanup already
                // establishes. entity_id carries no FK constraint (14-audit-log.kuml.kts file header)
                // and needs no fix-up, but actor_member_id DOES have one (a member acting as their
                // OWN caller -- e.g. the concurrent mutual-admin-demotion test, where a just-created
                // second admin is itself the caller for one of the two racing requests) -- nulled out
                // here, same idiom AuditLogServiceTest's own cleanup already establishes.
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                createdMemberIds.forEach { id ->
                    SepaMandateTable.deleteWhere { SepaMandateTable.memberId eq id }
                    CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.memberId eq id }
                    SessionTable.deleteWhere { SessionTable.memberId eq id }
                    // Test 25 (Review Runde 3) mints real FriendEmailVerificationTokenTable rows
                    // (fk_friend_email_verification_token_member_id has no ON DELETE CASCADE, see
                    // V1__baseline.sql) -- without this, MemberTable.deleteWhere below would fail
                    // an FK-violation for that test's FRIEND member.
                    FriendEmailVerificationTokenTable.deleteWhere { FriendEmailVerificationTokenTable.memberId eq id }
                    // Welle V1.4.9 -- sendPasswordResetMailToMember's tests mint real
                    // PasswordResetTokenTable rows (fk_password_reset_token_member_id has no
                    // ON DELETE CASCADE either, same V1__baseline.sql constraint shape) -- same
                    // reasoning as the FriendEmailVerificationTokenTable cleanup right above.
                    PasswordResetTokenTable.deleteWhere { PasswordResetTokenTable.memberId eq id }
                    AccountTable.deleteWhere { AccountTable.memberId eq id }
                    MemberTable.deleteWhere { MemberTable.id eq id }
                }
                createdCommitteeIds.forEach { id ->
                    CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId eq id }
                    CommitteeTable.deleteWhere { CommitteeTable.id eq id }
                }
            }
        }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
            role: AccountRole = AccountRole.MEMBER,
            displayName: String = "Roster Testmitglied",
            externalReference: String? = null,
            // Welle V1.4.4.5 -- optional, only relevant for the death-date-before-birth plausibility
            // tests; defaults to null so every pre-existing call site stays unaffected.
            dateOfBirth: LocalDate? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    it[MemberTable.externalReference] = externalReference
                    it[MemberTable.dateOfBirth] = dateOfBirth
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                    it[passwordHash] = PasswordHasher.hash(STRONG_PASSWORD)
                }
            }
            createdMemberIds += id
            return id
        }

        /** No `account` row at all -- the 407-CSV-import realism, see MemberAdminRowDto.role KDoc. */
        fun createAccountlessTestMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
            externalReference: String? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "CSV Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    it[MemberTable.externalReference] = externalReference
                }
            }
            createdMemberIds += id
            return id
        }

        fun statusOf(memberId: Uuid): MemberStatus =
            transaction { MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.status] }

        /** Welle V1.4.4.5. */
        fun dateOfDeathOf(memberId: Uuid): LocalDate? =
            transaction { MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.dateOfDeath] }

        fun roleOf(memberId: Uuid): AccountRole? =
            transaction {
                AccountTable
                    .selectAll()
                    .where { AccountTable.memberId eq memberId }
                    .singleOrNull()
                    ?.get(AccountTable.role)
            }

        fun activeSessionCount(memberId: Uuid): Long =
            transaction {
                SessionTable
                    .selectAll()
                    .where { (SessionTable.memberId eq memberId) and SessionTable.revokedAt.isNull() }
                    .count()
            }

        fun emailVerifiedAtOf(memberId: Uuid) =
            transaction { MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.emailVerifiedAt] }

        fun markEmailVerified(memberId: Uuid) {
            transaction { MemberTable.update({ MemberTable.id eq memberId }) { it[emailVerifiedAt] = DbClock.nowLocalDateTime() } }
        }

        fun grantTestMandate(memberId: Uuid): Uuid {
            val mandateId = Uuid.random()
            transaction {
                SepaMandateTable.insert {
                    it[id] = mandateId
                    it[SepaMandateTable.memberId] = memberId
                    it[mandateReference] = "TEST-${mandateId.toString().take(8)}"
                    it[debtorName] = "Testschuldner"
                    it[debtorIbanCiphertext] = "unit-test-ciphertext-not-real"
                    it[debtorIbanSetAt] = DbClock.nowLocalDateTime()
                    it[debtorIbanLast4] = "1234"
                    it[signatureDate] = LocalDate(2026, 1, 1)
                    it[sequenceType] = SepaSequenceType.RCUR
                    it[status] = SepaMandateStatus.ACTIVE
                    it[grantedAt] = DbClock.nowLocalDateTime()
                    it[createdBy] = memberId
                }
            }
            return mandateId
        }

        fun mandateStatus(mandateId: Uuid): SepaMandateStatus =
            transaction { SepaMandateTable.selectAll().where { SepaMandateTable.id eq mandateId }.single()[SepaMandateTable.status] }

        fun auditCountFor(memberId: Uuid): Long =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityId eq memberId) and
                            (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and
                            (AuditLogEntryTable.action eq AuditAction.UPDATE)
                    }.count()
            }

        /**
         * Welle V1.2.13 -- identical to [auditCountFor] but for `action == CREATE`, the ONE action
         * [MemberService.grantMemberAccount] writes. Deliberately a SEPARATE function, not a widened
         * [auditCountFor] with an action parameter -- test 22 depends on [auditCountFor] staying
         * hard-filtered to UPDATE.
         */
        fun auditCreateCountFor(memberId: Uuid): Long =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityId eq memberId) and
                            (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and
                            (AuditLogEntryTable.action eq AuditAction.CREATE)
                    }.count()
            }

        fun accountExists(memberId: Uuid): Boolean =
            transaction { AccountTable.selectAll().where { AccountTable.memberId eq memberId }.count() > 0 }

        fun passwordHashOf(memberId: Uuid): String? =
            transaction {
                AccountTable
                    .selectAll()
                    .where { AccountTable.memberId eq memberId }
                    .singleOrNull()
                    ?.get(AccountTable.passwordHash)
            }

        /** Welle V1.4.9 -- number of `password_reset_token` rows minted for [memberId] so far. */
        fun passwordResetTokenCountFor(memberId: Uuid): Long =
            transaction {
                PasswordResetTokenTable.selectAll().where { PasswordResetTokenTable.memberId eq memberId }.count()
            }

        // ── 1: Autz Roster ──
        test("listMembersForAdministration: MEMBER forbidden, unauthenticated rejected, BOARD/ADMIN/TREASURER ok") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                client.get("/test/roster") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/roster").status shouldBe HttpStatusCode.Unauthorized
                client.get("/test/roster") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                client.get("/test/roster") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                // Welle V1.4.4.4 review fix (MAJOR finding) deliberately widened this ONE method from
                // `!current.isPrivileged` (BOARD/ADMIN) to `requireRole(TREASURER, BOARD, ADMIN)` --
                // see IMemberService.listMembersForAdministration KDoc -- purely so a Schatzmeister can
                // search/pick a member to call updateMemberMembershipTier on. `isPrivileged` itself
                // (RequestContext) stays BOARD/ADMIN-only and untouched by this -- every OTHER
                // BOARD/ADMIN-gated member-administration action is unaffected.
                client.get("/test/roster") { header("X-Member-Id", TREASURER_ID) }.status shouldBe HttpStatusCode.OK
            }
        }

        // ── 2/3: Paginierung + Limit-Deckel ──
        test("listMembersForAdministration: pagination and server-side limit/offset clamping") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                repeat(60) { i -> createTestMember("roster-page-$i@example.org", displayName = "Pagetagxyz Member %03d".format(i)) }

                // Scoped to a search term unique to this test's own 60 rows, so the pagination
                // assertions below are exact regardless of what other tests/specs left in the
                // shared DB -- see class KDoc "every target member ... freshly created by this file".
                val page2 = client.get("/test/roster?search=pagetagxyz&limit=25&offset=25") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val (rowsPart, totalCount, limit, offset) = page2.split("|")
                rowsPart.split(";").filter { it.isNotBlank() }.size shouldBe 25
                totalCount.toInt() shouldBe 60
                limit shouldBe "25"
                offset shouldBe "25"

                val hugeLimit = client.get("/test/roster?limit=5000") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                hugeLimit.split("|")[2] shouldBe "100"

                val zeroLimit = client.get("/test/roster?limit=0") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                zeroLimit.split("|")[2] shouldBe "1"

                val negativeOffset = client.get("/test/roster?offset=-5") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                negativeOffset.split("|")[3] shouldBe "0"
            }
        }

        // ── 4/5: Suche ──
        test(
            "listMembersForAdministration: search across displayName/email/externalReference, case-insensitive, LIKE-metacharacters escaped",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val id =
                    createTestMember("zzsearch-target@example.org", displayName = "Zzsearchable Zieldorf", externalReference = "PN-ZZ-9001")

                val byName = client.get("/test/roster?search=zieldorf") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                byName.split("|")[0].contains(id.toString()) shouldBe true

                val byEmail = client.get("/test/roster?search=ZZSEARCH-TARGET") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                byEmail.split("|")[0].contains(id.toString()) shouldBe true

                val byExternalRef = client.get("/test/roster?search=pn-zz-9001") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                byExternalRef.split("|")[0].contains(id.toString()) shouldBe true

                // A bare '%'/'_' in the search text must be treated as a LITERAL character, not a
                // SQL LIKE wildcard -- without escaping, searching for a lone '%' would match every
                // row in the roster (a full-table match masquerading as a substring search). Neither
                // of the two rows created in this test contains a literal '%' or '_' in its
                // displayName/email/externalReference, so a correctly-escaped search for either
                // must return ZERO rows, not the full unfiltered roster.
                val percentSearch = client.get("/test/roster?search=%25") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                percentSearch.split("|")[1].toInt() shouldBe 0
                val underscoreSearch = client.get("/test/roster?search=_") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                underscoreSearch.split("|")[1].toInt() shouldBe 0

                // The positive counterpart: a row whose OWN externalReference genuinely contains a
                // literal '%' is found by an EXACT (escaped) search for that same literal, proving
                // this is real escaping, not merely "matches nothing".
                val percentId =
                    createTestMember("percent-literal@example.org", displayName = "Percent Literal", externalReference = "PN-50%-OFF")
                val exactPercentMatch = client.get("/test/roster?search=PN-50%25-OFF") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                exactPercentMatch.split("|")[0].contains(percentId.toString()) shouldBe true
            }
        }

        // ── 6/7: Statusfilter + statusCounts ──
        test("listMembersForAdministration: status filter narrows rows, statusCounts respects search but not the status filter") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val deceasedId =
                    createTestMember("roster-deceased@example.org", status = MemberStatus.DECEASED, displayName = "Uniquetag Deceasedowski")
                createTestMember(
                    "roster-active-uniquetag@example.org",
                    status = MemberStatus.ACTIVE,
                    displayName = "Uniquetag Activedowski",
                )

                val filtered =
                    client
                        .get("/test/roster?search=uniquetag&statuses=DECEASED") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                val rowsPart = filtered.split("|")[0]
                rowsPart.contains(deceasedId.toString()) shouldBe true
                rowsPart.split(";").filter { it.isNotBlank() }.size shouldBe 1

                val countsPart = filtered.split("|")[4]
                // statusCounts reflects the SEARCH ("uniquetag": one ACTIVE + one DECEASED), not the
                // DECEASED-only row filter -- both statuses must appear with count 1.
                countsPart.contains("ACTIVE=1") shouldBe true
                countsPart.contains("DECEASED=1") shouldBe true
            }
        }

        // ── 8: deterministische Sortierung ──
        test("listMembersForAdministration: deterministic tie-break by id on equal display names") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val a = createTestMember("roster-tie-a@example.org", displayName = "Tiebreak Zwilling")
                val b = createTestMember("roster-tie-b@example.org", displayName = "Tiebreak Zwilling")
                val expectedOrder = listOf(a, b).sortedBy { it.toString() }

                val page1 =
                    client
                        .get(
                            "/test/roster?search=tiebreak%20zwilling&limit=1&offset=0",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                val page2 =
                    client
                        .get(
                            "/test/roster?search=tiebreak%20zwilling&limit=1&offset=1",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                page1.split("|")[0].contains(expectedOrder[0].toString()) shouldBe true
                page2.split("|")[0].contains(expectedOrder[1].toString()) shouldBe true
            }
        }

        // ── 9: kontenloses Mitglied im Roster ──
        test("listMembersForAdministration: an accountless member appears with role == null, no exception") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val id = createAccountlessTestMember("roster-noaccount@example.org", externalReference = "PN-NOACC-1")
                val response = client.get("/test/roster?search=noaccount") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                val rowsPart = response.bodyAsText().split("|")[0]
                rowsPart.contains("$id:${MemberStatus.ACTIVE}:null:false") shouldBe true
            }
        }

        // ── 10: Kerndaten ──
        test(
            "updateMemberCoreData: validation, foreign-email conflict, own-email-unchanged ok, normalization, session revoked only on email change",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val a = createTestMember("core-a@example.org")
                val b = createTestMember("core-b@example.org")

                client.post("/test/core-data/$a?name=&email=core-a@example.org") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict
                client
                    .post(
                        "/test/core-data/$a?name=${"x".repeat(201)}&email=core-a@example.org",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.Conflict

                // Foreign email already used by b -- MemberEmailInUseException, mapped to Conflict.
                client.post("/test/core-data/$a?name=Core+A&email=core-b@example.org") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict

                // Own email unchanged -> ok, no session revocation.
                val session = SessionStore.createSession(a)
                val unchanged =
                    client.post("/test/core-data/$a?name=Core+A+Renamed&email=core-a@example.org") { header("X-Member-Id", ADMIN_ID) }
                unchanged.status shouldBe HttpStatusCode.OK
                activeSessionCount(a) shouldBe 1L

                // Email actually changed, normalized -- and it revokes the session.
                val changed =
                    client.post(
                        "/test/core-data/$a?name=Core+A&email=%20%20CORE-A-NEW%40EXAMPLE.ORG%20",
                    ) { header("X-Member-Id", ADMIN_ID) }
                changed.status shouldBe HttpStatusCode.OK
                transaction { MemberTable.selectAll().where { MemberTable.id eq a }.single()[MemberTable.email] } shouldBe
                    "core-a-new@example.org"
                activeSessionCount(a) shouldBe 0L
                SessionStore.resolve(session.rawToken) shouldBe null
            }
        }

        // ── 11: kontenloses Mitglied, Kerndaten ──
        test("updateMemberCoreData: succeeds against an accountless member (no 500)") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val id = createAccountlessTestMember("core-noaccount@example.org")
                val response =
                    client.post(
                        "/test/core-data/$id?name=Neuer+Name&email=core-noaccount@example.org",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().contains("null") shouldBe true
            }
        }

        // ── 12: Peer-Schutz Kerndaten ──
        test("updateMemberCoreData: BOARD is forbidden to edit an ADMIN-role target; ADMIN is allowed") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val targetAdmin = createTestMember("core-peer-admin@example.org", role = AccountRole.ADMIN)
                client
                    .post("/test/core-data/$targetAdmin?name=Renamed&email=core-peer-admin@example.org") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe
                    HttpStatusCode.Forbidden
                // TREASURER is not isPrivileged at all (BOARD/ADMIN only, see RequestContext) --
                // rejected at the base gate, before the peer-protection check below it even runs.
                client
                    .post("/test/core-data/$targetAdmin?name=Renamed&email=core-peer-admin@example.org") {
                        header("X-Member-Id", TREASURER_ID)
                    }.status shouldBe
                    HttpStatusCode.Forbidden
                client
                    .post("/test/core-data/$targetAdmin?name=Renamed&email=core-peer-admin@example.org") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.OK
            }
        }

        // ── 13a: Rolle -- BOARD forbidden, self forbidden, accountless conflict, visible without relogin ──
        test(
            "updateMemberRole: BOARD forbidden, self forbidden, accountless target conflict, change visible on the next call without re-login",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val target = createTestMember("role-target-a@example.org", role = AccountRole.MEMBER)

                client.post("/test/role/$target?newRole=BOARD") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.post("/test/role/$ADMIN_ID?newRole=MEMBER") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Forbidden

                val accountless = createAccountlessTestMember("role-noaccount@example.org")
                client.post("/test/role/$accountless?newRole=BOARD") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict

                val session = SessionStore.createSession(target)
                client.post("/test/role/$target?newRole=BOARD") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                // SessionStore.resolve re-reads the role fresh from the DB -- no re-login required.
                val resolved = SessionStore.resolve(session.rawToken)
                resolved shouldNotBe null
                resolved!!.role shouldBe AccountRole.BOARD
            }
        }

        // ── 13b: letzter Admin -- race-safe ──
        test(
            "updateMemberRole: two ADMINs concurrently demoting each other -- the second one to commit hits LastAdminException, never zero admins",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val adminA = ADMIN_ID.let { Uuid.parse(it) }
                val adminB = createTestMember("role-second-admin@example.org", role = AccountRole.ADMIN)

                // The race below only produces the exact "1 OK, 1 Conflict" outcome if EXACTLY two
                // ADMIN accounts exist while it runs -- any earlier test in this file that created
                // (and did not itself demote back) a third ADMIN account (e.g. the peer-schutz test
                // above) would let both concurrent demotions see size > 1 and both succeed. Neutralize
                // every OTHER admin THIS FILE has created so far (never touches accounts this file
                // did not create), restore them all afterwards.
                val otherAdminsToRestore =
                    createdMemberIds.filter { it != adminA && it != adminB && roleOf(it) == AccountRole.ADMIN }
                otherAdminsToRestore.forEach { id ->
                    transaction { AccountTable.update({ AccountTable.memberId eq id }) { it[role] = AccountRole.BOARD } }
                }

                try {
                    val results = runConcurrentMutualAdminDemotion(client = client, otherAdminId = adminB)
                    // Exactly one of the two concurrent demotions must succeed and the other must be
                    // rejected as a conflict -- never both OK (which would leave zero admins).
                    results.count { it == HttpStatusCode.OK } shouldBe 1
                    results.count { it == HttpStatusCode.Conflict } shouldBe 1

                    val remainingAdmins =
                        transaction { AccountTable.selectAll().where { AccountTable.role eq AccountRole.ADMIN }.count() }
                    (remainingAdmins >= 1L) shouldBe true
                } finally {
                    // Restore adminA/adminB to ADMIN so this test does not leave a permanently-
                    // degraded seeded fixture behind for any test running after it in the same JVM.
                    if (roleOf(adminB) != AccountRole.ADMIN) {
                        transaction { AccountTable.update({ AccountTable.memberId eq adminB }) { it[role] = AccountRole.ADMIN } }
                    }
                    if (roleOf(adminA) != AccountRole.ADMIN) {
                        transaction { AccountTable.update({ AccountTable.memberId eq adminA }) { it[role] = AccountRole.ADMIN } }
                    }
                    otherAdminsToRestore.forEach { id ->
                        transaction { AccountTable.update({ AccountTable.memberId eq id }) { it[role] = AccountRole.ADMIN } }
                    }
                }
            }
        }

        // ── 13c: letzter Admin bei Status-Wechsel -- race-safe (Security fix 2026-08-27, MEDIUM) ──
        test(
            "updateMemberStatus: two ADMINs concurrently WITHDRAWING each other -- the second one to commit hits LastAdminException, never zero non-blocked admins",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val adminA = ADMIN_ID.let { Uuid.parse(it) }
                val adminB =
                    createTestMember("status-second-admin@example.org", role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)

                // Same neutralization as test 13b -- the race below only produces the exact "1 OK, 1
                // Conflict" outcome if EXACTLY two ADMIN accounts (both ACTIVE) exist while it runs.
                val otherAdminsToRestore =
                    createdMemberIds.filter { it != adminA && it != adminB && roleOf(it) == AccountRole.ADMIN }
                otherAdminsToRestore.forEach { id ->
                    transaction { AccountTable.update({ AccountTable.memberId eq id }) { it[role] = AccountRole.BOARD } }
                }

                try {
                    val results = runConcurrentMutualAdminStatusWithdraw(client = client, otherAdminId = adminB)
                    // Exactly one of the two concurrent WITHDRAWALs must succeed and the other must
                    // be rejected as a conflict -- never both OK (which would leave zero non-blocked
                    // ADMIN accounts, i.e. nobody left who can administer the roster at all).
                    results.count { it == HttpStatusCode.OK } shouldBe 1
                    results.count { it == HttpStatusCode.Conflict } shouldBe 1

                    val remainingNonBlockedAdmins =
                        transaction {
                            (AccountTable innerJoin MemberTable)
                                .selectAll()
                                .where {
                                    (AccountTable.role eq AccountRole.ADMIN) and
                                        (MemberTable.status notInList MemberStatusSets.LOGIN_BLOCKED)
                                }.count()
                        }
                    (remainingNonBlockedAdmins >= 1L) shouldBe true
                } finally {
                    // Restore adminA/adminB to ACTIVE so this test does not leave a permanently-
                    // degraded seeded fixture behind for any test running after it in the same JVM.
                    if (statusOf(adminB) != MemberStatus.ACTIVE) {
                        transaction { MemberTable.update({ MemberTable.id eq adminB }) { it[status] = MemberStatus.ACTIVE } }
                    }
                    if (statusOf(adminA) != MemberStatus.ACTIVE) {
                        transaction { MemberTable.update({ MemberTable.id eq adminA }) { it[status] = MemberStatus.ACTIVE } }
                    }
                    otherAdminsToRestore.forEach { id ->
                        transaction { AccountTable.update({ AccountTable.memberId eq id }) { it[role] = AccountRole.ADMIN } }
                    }
                }
            }
        }

        // ── 13d: cross-method letzter-Admin-Race -- status dann role, SEQUENZIELL (Security-Fund
        // 2026-08-27, LOW regression-test gap) -- tests 13b/13c above only race the SAME method
        // against itself (updateMemberRole vs. updateMemberRole, updateMemberStatus vs.
        // updateMemberStatus); neither covers the cross-method combination MemberService.kt's own
        // updateMemberRole KDoc ("old adminAccountRows.size == 1 check") describes as the actual
        // motivating scenario for the Security fix (2026-08-27, MEDIUM): adminA withdraws adminB
        // via updateMemberStatus (allowed), then adminB -- still authenticated because
        // resolveCurrentMember does not re-check MemberStatusSets.LOGIN_BLOCKED per call, and
        // still role ADMIN because only its member.status changed -- tries to demote adminA via
        // updateMemberRole. MemberService.kt:651-666 must reject this. ──
        test(
            "updateMemberStatus then updateMemberRole: withdrawing the OTHER admin first, then that still-authenticated still-role-ADMIN admin trying to demote the survivor is rejected (MemberService.kt:666)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val adminA = ADMIN_ID.let { Uuid.parse(it) }
                val adminB =
                    createTestMember(
                        "cross-method-status-then-role@example.org",
                        role = AccountRole.ADMIN,
                        status = MemberStatus.ACTIVE,
                    )

                // Same neutralization as tests 13b/13c -- the sequence below only produces the
                // exact outcome below if EXACTLY two ADMIN accounts (both ACTIVE) exist while it
                // runs.
                val otherAdminsToRestore =
                    createdMemberIds.filter { it != adminA && it != adminB && roleOf(it) == AccountRole.ADMIN }
                otherAdminsToRestore.forEach { id ->
                    transaction { AccountTable.update({ AccountTable.memberId eq id }) { it[role] = AccountRole.BOARD } }
                }

                try {
                    // Step 1: adminA withdraws adminB -- allowed, adminA remains the sole
                    // non-blocked ADMIN. Runs through MemberService.kt:528-544's
                    // Letzter-Admin-Schutz WITHOUT throwing -- this is what sets up a realistic
                    // step 2 (adminB is now LOGIN_BLOCKED but still an ADMIN *account*).
                    client
                        .post("/test/status/$adminB?newStatus=WITHDRAWN&reason=Cross-Methoden-Testgrund") {
                            header("X-Member-Id", adminA.toString())
                        }.status shouldBe
                        HttpStatusCode.OK
                    statusOf(adminB) shouldBe MemberStatus.WITHDRAWN
                    roleOf(adminB) shouldBe AccountRole.ADMIN

                    // Step 2: adminB tries to demote adminA. adminA is the only non-blocked ADMIN
                    // once adminB (the target's sole "other admin") is excluded as the target of
                    // ITS OWN demotion -- MemberService.kt:651-666 must reject this, never let it
                    // through the way the old `adminAccountRows.size == 1` check would have.
                    client.post("/test/role/$adminA?newRole=MEMBER") { header("X-Member-Id", adminB.toString()) }.status shouldBe
                        HttpStatusCode.Conflict

                    // The actual invariant under test: adminA's role must be untouched.
                    roleOf(adminA) shouldBe AccountRole.ADMIN
                } finally {
                    // Restore adminA/adminB so this test does not leave a permanently-degraded
                    // seeded fixture behind for any test running after it in the same JVM.
                    if (statusOf(adminB) != MemberStatus.ACTIVE) {
                        transaction { MemberTable.update({ MemberTable.id eq adminB }) { it[status] = MemberStatus.ACTIVE } }
                    }
                    if (roleOf(adminA) != AccountRole.ADMIN) {
                        transaction { AccountTable.update({ AccountTable.memberId eq adminA }) { it[role] = AccountRole.ADMIN } }
                    }
                    if (roleOf(adminB) != AccountRole.ADMIN) {
                        transaction { AccountTable.update({ AccountTable.memberId eq adminB }) { it[role] = AccountRole.ADMIN } }
                    }
                    otherAdminsToRestore.forEach { id ->
                        transaction { AccountTable.update({ AccountTable.memberId eq id }) { it[role] = AccountRole.ADMIN } }
                    }
                }
            }
        }

        // ── 13e: cross-method letzter-Admin-Race -- Spiegelfall von 13d, ZWEITER Schritt läuft
        // diesmal auch über updateMemberStatus statt updateMemberRole, sodass der Wurf dieses Mal
        // aus MemberService.kt:544 selbst kommt (nicht :666) -- exercises the branch inside
        // updateMemberStatus's OWN Letzter-Admin-Schutz where the "other admin" being counted is
        // itself the caller, and is itself already LOGIN_BLOCKED. ──
        test(
            "updateMemberStatus then updateMemberStatus: the withdrawn-but-still-ADMIN admin trying to ALSO withdraw the survivor is rejected (MemberService.kt:544)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val adminA = ADMIN_ID.let { Uuid.parse(it) }
                val adminB =
                    createTestMember(
                        "cross-method-status-then-status@example.org",
                        role = AccountRole.ADMIN,
                        status = MemberStatus.ACTIVE,
                    )

                val otherAdminsToRestore =
                    createdMemberIds.filter { it != adminA && it != adminB && roleOf(it) == AccountRole.ADMIN }
                otherAdminsToRestore.forEach { id ->
                    transaction { AccountTable.update({ AccountTable.memberId eq id }) { it[role] = AccountRole.BOARD } }
                }

                try {
                    // Step 1 -- identical priming to test 13d: adminA withdraws adminB.
                    client
                        .post("/test/status/$adminB?newStatus=WITHDRAWN&reason=Cross-Methoden-Testgrund") {
                            header("X-Member-Id", adminA.toString())
                        }.status shouldBe
                        HttpStatusCode.OK

                    // Step 2 -- this time adminB attempts the SAME method (updateMemberStatus)
                    // against adminA instead of updateMemberRole. adminB is still role ADMIN (only
                    // its own member.status changed), so it still passes the ESCALATED_ROLES
                    // caller check at MemberService.kt:515 -- but the Letzter-Admin-Schutz at
                    // MemberService.kt:528-544 must reject it: adminA is the target, adminB (the
                    // only OTHER admin account) is itself LOGIN_BLOCKED (WITHDRAWN), so zero
                    // non-blocked admins would remain.
                    client
                        .post("/test/status/$adminA?newStatus=WITHDRAWN&reason=Cross-Methoden-Gegenangriff") {
                            header("X-Member-Id", adminB.toString())
                        }.status shouldBe
                        HttpStatusCode.Conflict

                    statusOf(adminA) shouldBe MemberStatus.ACTIVE
                } finally {
                    if (statusOf(adminB) != MemberStatus.ACTIVE) {
                        transaction { MemberTable.update({ MemberTable.id eq adminB }) { it[status] = MemberStatus.ACTIVE } }
                    }
                    if (statusOf(adminA) != MemberStatus.ACTIVE) {
                        transaction { MemberTable.update({ MemberTable.id eq adminA }) { it[status] = MemberStatus.ACTIVE } }
                    }
                    otherAdminsToRestore.forEach { id ->
                        transaction { AccountTable.update({ AccountTable.memberId eq id }) { it[role] = AccountRole.ADMIN } }
                    }
                }
            }
        }

        // ── 14: illegale Übergänge ──
        test("updateMemberStatus: transitions outside the administratively-managed quadrant are rejected") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val active = createTestMember("status-illegal-active@example.org", status = MemberStatus.ACTIVE)
                listOf("APPLICATION", "REJECTED", "GUEST").forEach { target ->
                    client
                        .post(
                            "/test/status/$active?newStatus=$target&reason=Testgrund",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .status shouldBe
                        HttpStatusCode.Conflict
                }
                val friend = createTestMember("status-illegal-friend@example.org", status = MemberStatus.FRIEND)
                client.post("/test/status/$friend?newStatus=ACTIVE&reason=Testgrund") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict
                val application = createTestMember("status-illegal-application@example.org", status = MemberStatus.APPLICATION)
                client
                    .post(
                        "/test/status/$application?newStatus=ACTIVE&reason=Testgrund",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        // ── 15: DECEASED -> ACTIVE ist ADMIN-exklusiv ──
        test("updateMemberStatus: leaving DECEASED requires ADMIN, BOARD is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val deceased = createTestMember("status-deceased-recovery@example.org", status = MemberStatus.DECEASED)
                client
                    .post(
                        "/test/status/$deceased?newStatus=ACTIVE&reason=Datenkorrektur",
                    ) { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe
                    HttpStatusCode.Forbidden
                // TREASURER is not isPrivileged at all (BOARD/ADMIN only, see RequestContext) --
                // rejected at the base gate, before the DECEASED-recovery admin-exclusivity check.
                client
                    .post(
                        "/test/status/$deceased?newStatus=ACTIVE&reason=Datenkorrektur",
                    ) { header("X-Member-Id", TREASURER_ID) }
                    .status shouldBe
                    HttpStatusCode.Forbidden
                client
                    .post(
                        "/test/status/$deceased?newStatus=ACTIVE&reason=Datenkorrektur",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.OK
                statusOf(deceased) shouldBe MemberStatus.ACTIVE
            }
        }

        // ── V1.4.4.5 Sterbefall-Workflow ──────────────────────────────────────────────────────

        test("updateMemberStatus: ACTIVE -> DECEASED with a valid dateOfDeath sets the column") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val active = createTestMember("dod-set@example.org", status = MemberStatus.ACTIVE)
                client
                    .post("/test/status/$active?newStatus=DECEASED&reason=Sterbefall+gemeldet&dateOfDeath=2026-01-15") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe
                    HttpStatusCode.OK
                statusOf(active) shouldBe MemberStatus.DECEASED
                dateOfDeathOf(active) shouldBe LocalDate(2026, 1, 15)
            }
        }

        test("updateMemberStatus: ACTIVE -> DECEASED without a dateOfDeath leaves the column NULL") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val active = createTestMember("dod-unset@example.org", status = MemberStatus.ACTIVE)
                client
                    .post("/test/status/$active?newStatus=DECEASED&reason=Sterbefall+gemeldet") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe
                    HttpStatusCode.OK
                statusOf(active) shouldBe MemberStatus.DECEASED
                dateOfDeathOf(active) shouldBe null
            }
        }

        test("updateMemberStatus: ACTIVE -> DECEASED with a dateOfDeath in the future is Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val active = createTestMember("dod-future@example.org", status = MemberStatus.ACTIVE)
                val tomorrow = DbClock.nowLocalDateTime().date.plus(1, DateTimeUnit.DAY)
                client
                    .post("/test/status/$active?newStatus=DECEASED&reason=Sterbefall+gemeldet&dateOfDeath=$tomorrow") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe
                    HttpStatusCode.Conflict
                statusOf(active) shouldBe MemberStatus.ACTIVE
                dateOfDeathOf(active) shouldBe null
            }
        }

        test("updateMemberStatus: ACTIVE -> DECEASED with a dateOfDeath before dateOfBirth is Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val active =
                    createTestMember(
                        "dod-before-birth@example.org",
                        status = MemberStatus.ACTIVE,
                        dateOfBirth = LocalDate(1990, 1, 1),
                    )
                client
                    .post("/test/status/$active?newStatus=DECEASED&reason=Sterbefall+gemeldet&dateOfDeath=1980-01-01") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe
                    HttpStatusCode.Conflict
                statusOf(active) shouldBe MemberStatus.ACTIVE
            }
        }

        test("updateMemberStatus: ACTIVE -> WITHDRAWN with a dateOfDeath is Conflict (wrong target status)") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val active = createTestMember("dod-wrong-target@example.org", status = MemberStatus.ACTIVE)
                client
                    .post("/test/status/$active?newStatus=WITHDRAWN&reason=Austritt&dateOfDeath=2026-01-15") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe
                    HttpStatusCode.Conflict
                statusOf(active) shouldBe MemberStatus.ACTIVE
            }
        }

        test(
            "updateMemberStatus: DECEASED -> DECEASED (no-op) with a different dateOfDeath leaves the date unchanged and writes no audit entry",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val deceased =
                    transaction {
                        val id = createTestMember("dod-noop@example.org", status = MemberStatus.DECEASED)
                        MemberTable.update({ MemberTable.id eq id }) { it[dateOfDeath] = LocalDate(2026, 1, 1) }
                        id
                    }
                val beforeAuditCount = auditCountFor(deceased)
                client
                    .post("/test/status/$deceased?newStatus=DECEASED&reason=Erneuter+Versuch&dateOfDeath=2026-06-01") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe
                    HttpStatusCode.OK
                // Idempotence promise from IMemberService.updateMemberStatus KDoc holds literally --
                // even a DIFFERENT dateOfDeath on a newStatus == from call changes nothing.
                dateOfDeathOf(deceased) shouldBe LocalDate(2026, 1, 1)
                auditCountFor(deceased) shouldBe beforeAuditCount
            }
        }

        test("updateMemberStatus: DECEASED -> ACTIVE (ADMIN) clears dateOfDeath, does not raise a raw 500") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val deceased =
                    transaction {
                        val id = createTestMember("dod-clear-on-leave@example.org", status = MemberStatus.DECEASED)
                        MemberTable.update({ MemberTable.id eq id }) { it[dateOfDeath] = LocalDate(2026, 1, 1) }
                        id
                    }
                client
                    .post("/test/status/$deceased?newStatus=ACTIVE&reason=Datenkorrektur") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.OK
                statusOf(deceased) shouldBe MemberStatus.ACTIVE
                dateOfDeathOf(deceased) shouldBe null
            }
        }

        test("correctDateOfDeath: BOARD forbidden, TREASURER forbidden, ADMIN succeeds") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val deceased = createTestMember("dod-correct-authz@example.org", status = MemberStatus.DECEASED)
                client
                    .post("/test/death-date/$deceased?dateOfDeath=2026-01-01&reason=Korrektur") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe
                    HttpStatusCode.Forbidden
                client
                    .post("/test/death-date/$deceased?dateOfDeath=2026-01-01&reason=Korrektur") {
                        header("X-Member-Id", TREASURER_ID)
                    }.status shouldBe
                    HttpStatusCode.Forbidden
                client
                    .post("/test/death-date/$deceased?dateOfDeath=2026-01-01&reason=Korrektur") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.OK
                dateOfDeathOf(deceased) shouldBe LocalDate(2026, 1, 1)
            }
        }

        test("correctDateOfDeath: dateOfDeath = null retracts an already-recorded date") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val deceased =
                    transaction {
                        val id = createTestMember("dod-retract@example.org", status = MemberStatus.DECEASED)
                        MemberTable.update({ MemberTable.id eq id }) { it[dateOfDeath] = LocalDate(2026, 1, 1) }
                        id
                    }
                client
                    .post("/test/death-date/$deceased?reason=Irrtuemlich+erfasst") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.OK
                dateOfDeathOf(deceased) shouldBe null
            }
        }

        test("correctDateOfDeath: on an ACTIVE (non-DECEASED) member is Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val active = createTestMember("dod-correct-wrong-status@example.org", status = MemberStatus.ACTIVE)
                client
                    .post("/test/death-date/$active?dateOfDeath=2026-01-01&reason=Korrektur") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("correctDateOfDeath: blank or too-long reason is rejected (Conflict)") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val deceased = createTestMember("dod-correct-reason@example.org", status = MemberStatus.DECEASED)
                client
                    .post("/test/death-date/$deceased?dateOfDeath=2026-01-01&reason=ab") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.Conflict
                client
                    .post("/test/death-date/$deceased?dateOfDeath=2026-01-01&reason=${"x".repeat(1001)}") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("correctDateOfDeath: self-targeting is always Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                client
                    .post("/test/death-date/$ADMIN_ID?dateOfDeath=2026-01-01&reason=Selbstversuch") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("correctDateOfDeath: an identical value is idempotent -- 200, no additional audit entry") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val deceased =
                    transaction {
                        val id = createTestMember("dod-correct-noop@example.org", status = MemberStatus.DECEASED)
                        MemberTable.update({ MemberTable.id eq id }) { it[dateOfDeath] = LocalDate(2026, 1, 1) }
                        id
                    }
                val beforeAuditCount = auditCountFor(deceased)
                client
                    .post("/test/death-date/$deceased?dateOfDeath=2026-01-01&reason=Erneut+dasselbe+Datum") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.OK
                auditCountFor(deceased) shouldBe beforeAuditCount
            }
        }

        test("correctDateOfDeath: on an anonymized member is Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val deceased = createTestMember("dod-correct-anon@example.org", status = MemberStatus.DECEASED)
                transaction { MemberTable.update({ MemberTable.id eq deceased }) { it[anonymizedAt] = DbClock.nowLocalDateTime() } }
                client
                    .post("/test/death-date/$deceased?dateOfDeath=2026-01-01&reason=Korrektur") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("audit: a genuine dateOfDeath change carries dateOfDeathChanged=true and no raw date value in the JSON snapshot") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val active = createTestMember("dod-audit@example.org", status = MemberStatus.ACTIVE)
                client
                    .post("/test/status/$active?newStatus=DECEASED&reason=Sterbefall+gemeldet&dateOfDeath=2026-03-03") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe
                    HttpStatusCode.OK

                val afterJson =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { (AuditLogEntryTable.entityId eq active) and (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) }
                            .single()[AuditLogEntryTable.afterSnapshot]
                    }
                requireNotNull(afterJson)
                afterJson.contains("\"dateOfDeathChanged\":true") shouldBe true
                // PII-Disziplin (siehe MemberChangeSnapshot KDoc): das Rohdatum selbst darf NIE in
                // der hash-verketteten Audit-Kette landen.
                afterJson.contains("2026-03-03") shouldBe false
            }
        }

        // ── 16: Begründung ──
        test("updateMemberStatus: reason must be 3-1000 characters, whitespace-only rejected") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val active = createTestMember("status-reason@example.org", status = MemberStatus.ACTIVE)
                client.post("/test/status/$active?newStatus=WITHDRAWN&reason=ab") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict
                client.post("/test/status/$active?newStatus=WITHDRAWN&reason=%20%20%20") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict
                client
                    .post(
                        "/test/status/$active?newStatus=WITHDRAWN&reason=${"x".repeat(1001)}",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        // ── 17: self ──
        test("updateMemberStatus: a self-targeting status change is always Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                client
                    .post(
                        "/test/status/$ADMIN_ID?newStatus=WITHDRAWN&reason=Selbstversuch",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        // ── 18/19: volle Nebenwirkungen ──
        listOf(MemberStatus.WITHDRAWN, MemberStatus.DECEASED).forEach { target ->
            test("updateMemberStatus: ACTIVE -> $target revokes sessions, ends open committee seat, revokes ACTIVE mandate") {
                testApplication {
                    application {
                        install(StatusPages) { installMemberAdminExceptionHandlers() }
                        routing {
                            registerMemberAdminTestRoutes()
                            registerCommitteeHelperRoutesForMemberAdminTests()
                        }
                    }
                    val member = createTestMember("status-full-side-effects-$target@example.org", status = MemberStatus.ACTIVE)
                    val session = SessionStore.createSession(member)
                    val mandateId = grantTestMandate(member)

                    val committeeId =
                        client.post("/test/gov/create-committee/WORKING_GROUP") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                    createdCommitteeIds += Uuid.parse(committeeId)
                    client.post("/test/gov/add-member/$committeeId/$member") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                        HttpStatusCode.OK

                    val response =
                        client.post(
                            "/test/status/$member?newStatus=$target&reason=Nebenwirkungstest",
                        ) { header("X-Member-Id", ADMIN_ID) }
                    response.status shouldBe HttpStatusCode.OK

                    activeSessionCount(member) shouldBe 0L
                    SessionStore.resolve(session.rawToken) shouldBe null
                    mandateStatus(mandateId) shouldBe SepaMandateStatus.REVOKED
                    val untilSet =
                        transaction {
                            CommitteeMembershipTable
                                .selectAll()
                                .where {
                                    CommitteeMembershipTable.memberId eq member
                                }.single()[CommitteeMembershipTable.until]
                        }
                    untilSet shouldNotBe null
                }
            }
        }

        // ── 20: Idempotenz ──
        test("updateMemberStatus: a no-op call (newStatus == current) is a pure read -- no audit entry, no session/mandate side effect") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("status-idempotent@example.org", status = MemberStatus.ACTIVE)
                val session = SessionStore.createSession(member)
                val mandateId = grantTestMandate(member)
                val beforeAuditCount = auditCountFor(member)

                client.post("/test/status/$member?newStatus=ACTIVE&reason=NoopVersuch") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.OK

                auditCountFor(member) shouldBe beforeAuditCount
                activeSessionCount(member) shouldBe 1L
                mandateStatus(mandateId) shouldBe SepaMandateStatus.ACTIVE
                SessionStore.resolve(session.rawToken) shouldNotBe null
            }
        }

        test("updateMemberRole: a no-op call (newRole == current) does not write an audit entry") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("role-idempotent@example.org", role = AccountRole.BOARD)
                val beforeAuditCount = auditCountFor(member)
                client.post("/test/role/$member?newRole=BOARD") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                auditCountFor(member) shouldBe beforeAuditCount
            }
        }

        // ── 21: anonymisiertes Mitglied ──
        test("all four member-administration RPCs reject an anonymized member with Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("anonymized-target@example.org", status = MemberStatus.ACTIVE)
                transaction {
                    MemberTable.update({ MemberTable.id eq member }) {
                        it[anonymizedAt] = DbClock.nowLocalDateTime()
                    }
                }
                client
                    .post(
                        "/test/core-data/$member?name=X&email=anonymized-target@example.org",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.Conflict
                client.post("/test/status/$member?newStatus=WITHDRAWN&reason=Testgrund") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict
                client.post("/test/role/$member?newRole=BOARD") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict

                // grantMemberAccount, Welle V1.2.13 -- the target here MUST be accountless (unlike
                // the three RPCs above, whose target already has an account and would otherwise
                // reach MemberAlreadyHasAccountException first, never actually exercising the
                // anonymizedAt guard). This is the DSGVO-erasure-revival scenario the guard exists
                // for: FoundationPersonalData.erase hard-deletes the account row, so an anonymized,
                // accountless member is indistinguishable from a CSV import by role == null alone.
                val anonymizedAccountless = createAccountlessTestMember("anonymized-accountless-target@example.org")
                transaction {
                    MemberTable.update({ MemberTable.id eq anonymizedAccountless }) {
                        it[anonymizedAt] = DbClock.nowLocalDateTime()
                    }
                }
                client
                    .post(
                        "/test/grant-account/$anonymizedAccountless?password=$STRONG_PASSWORD&role=MEMBER",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.Conflict
                accountExists(anonymizedAccountless) shouldBe false
            }
        }

        // ── 22: Audit ──
        test("audit: exactly one MEMBER/UPDATE entry per mutation, after-JSON carries status and reason") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("audit-target@example.org", status = MemberStatus.ACTIVE)
                client
                    .post("/test/status/$member?newStatus=WITHDRAWN&reason=Austrittserklaerung+liegt+vor") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.OK

                auditCountFor(member) shouldBe 1L
                val afterJson =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { (AuditLogEntryTable.entityId eq member) and (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) }
                            .single()[AuditLogEntryTable.afterSnapshot]
                    }
                requireNotNull(afterJson)
                afterJson.contains("WITHDRAWN") shouldBe true
                afterJson.contains("Austrittserklaerung") shouldBe true
            }
        }

        // ── 23: E-Mail-Längenschranke (Review Runde 3) ──
        test(
            "updateMemberCoreData: an email over MEMBER_EMAIL_MAX_LENGTH (320) is rejected as MemberEmailTooLongException/Conflict, not silently accepted",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("length-target@example.org")
                // "a".repeat(310) + "@example.org" is syntactically a fine mailbox address (no
                // control characters, exactly one '@') but 322 characters long -- passes
                // isValidMailboxAddress (syntax only, see that check's own KDoc "checks syntax, not
                // length") while still exceeding MemberTable.email's VARCHAR(320).
                val overlongEmail = "a".repeat(310) + "@example.org"
                (overlongEmail.length > MEMBER_EMAIL_MAX_LENGTH) shouldBe true

                val response =
                    client.post("/test/core-data/$member?name=Name&email=$overlongEmail") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
                // Review Runde 4: pin the exception TYPE, not just the shared 409 status code --
                // installMemberAdminExceptionHandlers below maps MemberEmailInUseException,
                // MemberHasNoAccountException, LastAdminException AND a plain ConflictException all
                // to the same Conflict status, so asserting the status alone would stay green if
                // MemberService.updateMemberCoreData's `throw MemberEmailTooLongException()` were
                // reverted to a generic `ConflictException(...)` -- exactly the regression this test
                // exists to catch (see MemberEmailTooLongException's own KDoc: the client-side
                // MemberAdminGuard needs the TYPE to show the correct, non-misleading toast).
                response.bodyAsText() shouldBe "email exceeds the maximum length"
                // Rejected before any write -- the original address is untouched.
                transaction { MemberTable.selectAll().where { MemberTable.id eq member }.single()[MemberTable.email] } shouldBe
                    "length-target@example.org"
            }
        }

        // ── 24: emailVerifiedAt-Reset (Review Runde 3) ──
        test(
            "updateMemberCoreData: emailVerifiedAt is reset to null when the address actually changes, but left untouched by a bare name correction",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("verified-active@example.org", status = MemberStatus.ACTIVE)
                markEmailVerified(member)
                emailVerifiedAtOf(member) shouldNotBe null

                // Bare name correction, same email -- emailVerifiedAt must survive untouched, same
                // "only when it actually changed" guard SessionStore.revokeAllForMember already gets
                // (test 10 above pins the session-untouched half of that guard; this pins the
                // emailVerifiedAt half).
                client
                    .post(
                        "/test/core-data/$member?name=Renamed+Only&email=verified-active@example.org",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.OK
                emailVerifiedAtOf(member) shouldNotBe null

                // The address itself changes -- must be cleared, an ADMIN/BOARD correction to the
                // NEW address carries no proof the caller controls it, see updateMemberCoreData's
                // own "emailChanged" comment.
                client
                    .post(
                        "/test/core-data/$member?name=Renamed+Only&email=verified-active-new@example.org",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.OK
                emailVerifiedAtOf(member) shouldBe null
            }
        }

        // ── 25: Verifikations-Token-Invalidierung + Resend (Review Runde 3) ──
        test(
            "updateMemberCoreData: a real address change invalidates the OLD verification token AND, for a FRIEND target, mints+sends a fresh one to the NEW address -- but not for an ACTIVE target",
        ) {
            testApplication {
                val recordingMailer = RecordingResendFriendVerificationMailer()
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes(mailer = recordingMailer) }
                }

                // -- FRIEND target: old token invalidated, a new one is minted and sent to the NEW
                // address, and the NEW token (unlike the old one) actually verifies the member.
                val friend = createTestMember("friend-resend@example.org", status = MemberStatus.FRIEND)
                val oldToken = FriendEmailVerificationTokenStore.createToken(friend)
                FriendEmailVerificationTokenStore.peekMemberId(oldToken) shouldBe friend

                client
                    .post(
                        "/test/core-data/$friend?name=Friend+Resend&email=friend-resend-new@example.org",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.OK

                // Old token is dead -- consumeToken.KDoc "Never throws for an invalid token", peek
                // just as authoritative for "still usable".
                FriendEmailVerificationTokenStore.peekMemberId(oldToken) shouldBe null
                // A NEW token was sent to the NEW address (never the old one, never the raw member
                // id, see the caught-in-review bug this test would have failed on: the send-site
                // originally passed the memberId string instead of the normalized email).
                recordingMailer.sentTo shouldBe listOf("friend-resend-new@example.org")

                // -- ACTIVE target: mailer must NOT fire -- MembershipGuards only ever reads
                // emailVerifiedAt for MemberStatus.FRIEND (requireLtrEligibleMembership/
                // requireConferenceEligibleMembership), so an unsolicited "please confirm your
                // email" mail to an ACTIVE member would just be confusing noise.
                val active = createTestMember("active-no-resend@example.org", status = MemberStatus.ACTIVE)
                client
                    .post(
                        "/test/core-data/$active?name=Active+NoResend&email=active-no-resend-new@example.org",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.OK
                recordingMailer.sentTo shouldBe listOf("friend-resend-new@example.org")
            }
        }

        // ── 26: PII-Minimierung im Audit-Log (Security fix 2026-08-27, MAJOR) ──
        test(
            "audit: MEMBER before/after snapshot never carries the subject's plaintext displayName/email, only changed-flags",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val original = "Pii Minimierung Zieldorf"
                val member = createTestMember("pii-audit-before@example.org", status = MemberStatus.ACTIVE, displayName = original)
                client
                    .post(
                        "/test/core-data/$member?name=Pii+Minimierung+Danach&email=pii-audit-after@example.org",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.OK

                val (beforeJson, afterJson) =
                    transaction {
                        val row =
                            AuditLogEntryTable
                                .selectAll()
                                .where {
                                    (AuditLogEntryTable.entityId eq member) and (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER)
                                }.single()
                        row[AuditLogEntryTable.beforeSnapshot] to row[AuditLogEntryTable.afterSnapshot]
                    }
                requireNotNull(beforeJson)
                requireNotNull(afterJson)
                // Neither the OLD nor the NEW displayName/email plaintext ever reaches the
                // append-only, hash-chained audit_log_entry row -- an Art. 17 erasure of `member`
                // must be able to remove every trace of these values, and AuditLogPersonalData.erase
                // never clears this table's payload (GoBD retention, see that object's KDoc).
                listOf(beforeJson, afterJson).forEach { json ->
                    json.contains("Pii Minimierung") shouldBe false
                    json.contains("pii-audit-before@example.org") shouldBe false
                    json.contains("pii-audit-after@example.org") shouldBe false
                }
                // The GoBD-relevant FACT that both fields changed is still recorded, just not the
                // values themselves.
                beforeJson.contains("\"displayNameChanged\":false") shouldBe true
                beforeJson.contains("\"emailChanged\":false") shouldBe true
                afterJson.contains("\"displayNameChanged\":true") shouldBe true
                afterJson.contains("\"emailChanged\":true") shouldBe true
            }
        }

        // ── 27: Rate-Limit für den Verifikations-Resend (Security fix 2026-08-27, LOW) ──
        test(
            "updateMemberCoreData: friend-verification resend mail is rate-limited per actor/target -- the RPC itself never fails when the limiter trips",
        ) {
            testApplication {
                val recordingMailer = RecordingResendFriendVerificationMailer()
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes(
                            mailer = recordingMailer,
                            memberCoreDataFriendMailRateLimiter = FederationInboxRateLimiter(maxRequests = 2),
                        )
                    }
                }
                val friend = createTestMember("rate-limit-friend@example.org", status = MemberStatus.FRIEND)
                // Three real address changes in a row -- each one WOULD mint+send a fresh
                // verification mail on its own (see test 25), but the maxRequests=2 limiter above
                // caps it at 2 sends for this actor/target pair.
                listOf("rl-1@example.org", "rl-2@example.org", "rl-3@example.org").forEach { newEmail ->
                    client
                        .post("/test/core-data/$friend?name=Rate+Limit+Friend&email=$newEmail") {
                            header("X-Member-Id", ADMIN_ID)
                        }.status shouldBe
                        HttpStatusCode.OK
                }
                recordingMailer.sentTo.size shouldBe 2
            }
        }

        // ══ Welle V1.2.13 -- grantMemberAccount ══════════════════════════════════════════════════

        // ── 28: Erfolgsfall gegen ein kontenloses ACTIVE-Mitglied ──
        test("grantMemberAccount: ADMIN grants a login account to an accountless ACTIVE member") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member =
                    createAccountlessTestMember("grant-success@example.org", externalReference = "PN-GRANT-1")
                val beforeStatus = statusOf(member)
                val beforeRow =
                    transaction { MemberTable.selectAll().where { MemberTable.id eq member }.single() }
                val beforeDisplayName = beforeRow[MemberTable.displayName]
                val beforeEmail = beforeRow[MemberTable.email]
                val beforeJoinedAt = beforeRow[MemberTable.joinedAt]

                client
                    .post(
                        "/test/grant-account/$member?password=$STRONG_PASSWORD&role=MEMBER",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.OK

                roleOf(member) shouldBe AccountRole.MEMBER
                PasswordHasher.verify(rawPassword = STRONG_PASSWORD, storedHash = passwordHashOf(member)) shouldBe true
                auditCreateCountFor(member) shouldBe 1L
                auditCountFor(member) shouldBe 0L

                val (beforeJson, afterJson) =
                    transaction {
                        val row =
                            AuditLogEntryTable
                                .selectAll()
                                .where {
                                    (AuditLogEntryTable.entityId eq member) and
                                        (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and
                                        (AuditLogEntryTable.action eq AuditAction.CREATE)
                                }.single()
                        row[AuditLogEntryTable.beforeSnapshot] to row[AuditLogEntryTable.afterSnapshot]
                    }
                requireNotNull(beforeJson)
                requireNotNull(afterJson)
                beforeJson.contains("\"role\":null") shouldBe true
                afterJson.contains("\"role\":\"MEMBER\"") shouldBe true

                // MemberTable row itself is untouched -- a pure account insert, never a member write.
                val afterRow = transaction { MemberTable.selectAll().where { MemberTable.id eq member }.single() }
                afterRow[MemberTable.status] shouldBe beforeStatus
                afterRow[MemberTable.displayName] shouldBe beforeDisplayName
                afterRow[MemberTable.email] shouldBe beforeEmail
                afterRow[MemberTable.joinedAt] shouldBe beforeJoinedAt
            }
        }

        // ── 29: Autorisierung ──
        test("grantMemberAccount: BOARD/MEMBER/TREASURER forbidden, unauthenticated rejected -- never leaves a partial account") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createAccountlessTestMember("grant-authz@example.org")

                client
                    .post(
                        "/test/grant-account/$member?password=$STRONG_PASSWORD&role=MEMBER",
                    ) { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe
                    HttpStatusCode.Forbidden
                accountExists(member) shouldBe false

                client
                    .post(
                        "/test/grant-account/$member?password=$STRONG_PASSWORD&role=MEMBER",
                    ) { header("X-Member-Id", MEMBER_ID) }
                    .status shouldBe
                    HttpStatusCode.Forbidden
                accountExists(member) shouldBe false

                client
                    .post(
                        "/test/grant-account/$member?password=$STRONG_PASSWORD&role=MEMBER",
                    ) { header("X-Member-Id", TREASURER_ID) }
                    .status shouldBe
                    HttpStatusCode.Forbidden
                accountExists(member) shouldBe false

                client
                    .post("/test/grant-account/$member?password=$STRONG_PASSWORD&role=MEMBER")
                    .status shouldBe
                    HttpStatusCode.Unauthorized
                accountExists(member) shouldBe false
            }
        }

        // ── 30: Ziel hat bereits ein Konto ──
        test("grantMemberAccount: target already has an account -- MemberAlreadyHasAccountException/Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("grant-already-has-one@example.org", role = AccountRole.MEMBER)
                val response =
                    client.post(
                        "/test/grant-account/$member?password=$STRONG_PASSWORD&role=BOARD",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldBe "Member already has a login account"
                roleOf(member) shouldBe AccountRole.MEMBER
            }
        }

        // ── 31: ADMIN auf die eigene memberId -- kein separater Self-Check nötig ──
        test("grantMemberAccount: ADMIN targeting their own member id hits MemberAlreadyHasAccountException, same body as test 30") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val response =
                    client.post(
                        "/test/grant-account/$ADMIN_ID?password=$STRONG_PASSWORD&role=MEMBER",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldBe "Member already has a login account"
            }
        }

        // ── 32: Passwort-Policy ──
        test("grantMemberAccount: password policy violations are rejected, never leaving a partial account behind") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createAccountlessTestMember("grant-weak-password@example.org")

                client
                    .post(
                        "/test/grant-account/$member?password=${"x".repeat(11)}&role=MEMBER",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.BadRequest
                accountExists(member) shouldBe false

                client
                    .post(
                        "/test/grant-account/$member?password=${"x".repeat(129)}&role=MEMBER",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.BadRequest
                accountExists(member) shouldBe false

                // Pins that validation runs against the DB-stored address, not a client-supplied
                // one -- this RPC never even accepts an email parameter.
                client
                    .post(
                        "/test/grant-account/$member?password=grant-weak-password@example.org&role=MEMBER",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.BadRequest
                accountExists(member) shouldBe false
            }
        }

        // ── 33: DECEASED-Ziel blockiert, Rückweg über Statuskorrektur ──
        test("grantMemberAccount: a DECEASED target is rejected, but succeeds after an ADMIN corrects the status back to ACTIVE") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createAccountlessTestMember("grant-deceased@example.org", status = MemberStatus.DECEASED)

                val response =
                    client.post(
                        "/test/grant-account/$member?password=$STRONG_PASSWORD&role=MEMBER",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldBe "Cannot grant a login account to a deceased member"
                accountExists(member) shouldBe false

                client
                    .post(
                        "/test/status/$member?newStatus=ACTIVE&reason=Datenkorrektur",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.OK

                client
                    .post(
                        "/test/grant-account/$member?password=$STRONG_PASSWORD&role=MEMBER",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.OK
                accountExists(member) shouldBe true
            }
        }

        // ── 34: LOGIN_BLOCKED-aber-nicht-DECEASED-Ziele sind erlaubt ──
        listOf(MemberStatus.DONOR, MemberStatus.WITHDRAWN).forEach { blockedButAllowed ->
            test("grantMemberAccount: a $blockedButAllowed target succeeds -- the account is created but inert (LOGIN_BLOCKED)") {
                testApplication {
                    application {
                        install(StatusPages) { installMemberAdminExceptionHandlers() }
                        routing { registerMemberAdminTestRoutes() }
                    }
                    // LOGIN_BLOCKED membership is documented as containing this status -- if this
                    // ever stops being true, "the account is created but inert" is no longer an
                    // accurate description and this test's premise should be revisited.
                    (blockedButAllowed in MemberStatusSets.LOGIN_BLOCKED) shouldBe true

                    val member =
                        createAccountlessTestMember("grant-blocked-status-$blockedButAllowed@example.org", status = blockedButAllowed)
                    client
                        .post(
                            "/test/grant-account/$member?password=$STRONG_PASSWORD&role=MEMBER",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .status shouldBe
                        HttpStatusCode.OK
                    accountExists(member) shouldBe true
                    statusOf(member) shouldBe blockedButAllowed
                }
            }
        }

        // ── 36: unbekannte/ungültige memberId ──
        test("grantMemberAccount: an unknown or syntactically invalid memberId is NotFound, never a 500") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                client
                    .post(
                        "/test/grant-account/${Uuid.random()}?password=$STRONG_PASSWORD&role=MEMBER",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.NotFound
                client
                    .post(
                        "/test/grant-account/not-a-uuid?password=$STRONG_PASSWORD&role=MEMBER",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        // ── 37: eskalierte Rolle direkt beim Anlegen -- Letzter-Admin-Schutz strukturell unbeteiligt ──
        test("grantMemberAccount: creating the account directly with role=ADMIN succeeds -- LastAdminException never fires on a grant") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createAccountlessTestMember("grant-escalated-role@example.org")
                client
                    .post(
                        "/test/grant-account/$member?password=$STRONG_PASSWORD&role=ADMIN",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe
                    HttpStatusCode.OK
                roleOf(member) shouldBe AccountRole.ADMIN
            }
        }

        // ── Welle V1.4.4.4 "Familienmitgliedschaften" -- updateMemberMembershipTier ─────────────

        fun tierOf(memberId: Uuid): Uuid? =
            transaction { MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.membershipTierId] }

        test("updateMemberMembershipTier: assigning a real tier as BOARD is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val target = createTestMember(email = "tier-board-forbidden@example.org")
                client
                    .post("/test/tier/$target?tierId=${DevSeedData.standardTierId}") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Forbidden
                tierOf(target) shouldBe null
            }
        }

        listOf(TREASURER_ID, ADMIN_ID).forEach { actorId ->
            test("updateMemberMembershipTier: assigning a real tier as $actorId (TREASURER/ADMIN) succeeds") {
                testApplication {
                    application {
                        install(StatusPages) { installMemberAdminExceptionHandlers() }
                        routing { registerMemberAdminTestRoutes() }
                    }
                    val target = createTestMember(email = "tier-assign-$actorId@example.org")
                    client
                        .post("/test/tier/$target?tierId=${DevSeedData.standardTierId}") { header("X-Member-Id", actorId) }
                        .status shouldBe HttpStatusCode.OK
                    tierOf(target) shouldBe DevSeedData.standardTierId
                }
            }
        }

        test("updateMemberMembershipTier: removing a tier (null) as BOARD succeeds") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val target = createTestMember(email = "tier-remove-board@example.org")
                transaction { MemberTable.update({ MemberTable.id eq target }) { it[membershipTierId] = DevSeedData.standardTierId } }
                client.post("/test/tier/$target") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                tierOf(target) shouldBe null
            }
        }

        test("updateMemberMembershipTier: on an anonymized target is rejected (ConflictException)") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val target = createTestMember(email = "tier-anon@example.org")
                transaction { MemberTable.update({ MemberTable.id eq target }) { it[anonymizedAt] = DbClock.nowLocalDateTime() } }
                client
                    .post("/test/tier/$target?tierId=${DevSeedData.standardTierId}") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test("updateMemberMembershipTier: blank or too-long reason is rejected (ConflictException)") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val target = createTestMember(email = "tier-badreason@example.org")
                client
                    .post("/test/tier/$target?tierId=${DevSeedData.standardTierId}&reason=") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test("updateMemberMembershipTier: unknown tier id is rejected (BadRequestException)") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val target = createTestMember(email = "tier-unknown@example.org")
                client
                    .post("/test/tier/$target?tierId=${Uuid.random()}") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("updateMemberMembershipTier: a no-op call (same tier again) writes no audit entry") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val target = createTestMember(email = "tier-noop@example.org")
                transaction { MemberTable.update({ MemberTable.id eq target }) { it[membershipTierId] = DevSeedData.standardTierId } }
                val before =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and (AuditLogEntryTable.entityId eq target) }
                            .count()
                    }
                client
                    .post("/test/tier/$target?tierId=${DevSeedData.standardTierId}") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK
                val after =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and (AuditLogEntryTable.entityId eq target) }
                            .count()
                    }
                after shouldBe before
            }
        }

        // ── Security fixes (review): self-target + Peer-Schutz + Statuskorrektur-vor-Tarif ────────

        test("updateMemberMembershipTier: self-target is forbidden regardless of role or direction") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                // Fresh fixtures, not the shared DevSeedData ADMIN_ID/BOARD_ID accounts (those are
                // seeded WITH DevSeedData.standardTierId already and reused by every other test in
                // this file -- mutating them here would leak state across tests).
                val selfAdmin = createTestMember(email = "tier-self-admin@example.org", role = AccountRole.ADMIN)
                // ADMIN assigning a REAL tier to themselves.
                client
                    .post("/test/tier/$selfAdmin?tierId=${DevSeedData.standardTierId}") { header("X-Member-Id", selfAdmin.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                tierOf(selfAdmin) shouldBe null

                // BOARD removing their OWN tier (membershipTierId == null only needs isPrivileged) --
                // the actual self-benefit scenario the finding describes: escaping the next
                // contribution run without TREASURER/ADMIN involvement.
                val selfBoard = createTestMember(email = "tier-self-board@example.org", role = AccountRole.BOARD)
                transaction { MemberTable.update({ MemberTable.id eq selfBoard }) { it[membershipTierId] = DevSeedData.standardTierId } }
                client.post("/test/tier/$selfBoard") { header("X-Member-Id", selfBoard.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                tierOf(selfBoard) shouldBe DevSeedData.standardTierId
            }
        }

        test("updateMemberMembershipTier: TREASURER may not ASSIGN a real tier to a fellow ADMIN/BOARD/TREASURER peer, ADMIN may") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                // TREASURER passes the method's own role gate (assigning a real tier needs
                // TREASURER/ADMIN) but must still be rejected by the Peer-Schutz check below it --
                // this is the scenario the fix actually targets, not the pre-existing role gate.
                val peerAdmin = createTestMember(email = "tier-peer-admin-assign@example.org", role = AccountRole.ADMIN)
                client
                    .post("/test/tier/$peerAdmin?tierId=${DevSeedData.standardTierId}") { header("X-Member-Id", TREASURER_ID) }
                    .status shouldBe HttpStatusCode.Forbidden
                tierOf(peerAdmin) shouldBe null
                client
                    .post("/test/tier/$peerAdmin?tierId=${DevSeedData.standardTierId}") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK
                tierOf(peerAdmin) shouldBe DevSeedData.standardTierId
            }
        }

        test("updateMemberMembershipTier: BOARD may not REMOVE a fellow ADMIN/BOARD/TREASURER peer's tier, ADMIN may") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                // BOARD passes the method's own role gate (removing a tier only needs isPrivileged)
                // but must still be rejected by the Peer-Schutz check below it.
                val peerAdmin = createTestMember(email = "tier-peer-admin-remove@example.org", role = AccountRole.ADMIN)
                transaction { MemberTable.update({ MemberTable.id eq peerAdmin }) { it[membershipTierId] = DevSeedData.standardTierId } }
                client.post("/test/tier/$peerAdmin") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.Forbidden
                tierOf(peerAdmin) shouldBe DevSeedData.standardTierId
                client.post("/test/tier/$peerAdmin") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                tierOf(peerAdmin) shouldBe null
            }
        }

        listOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED).forEach { endedStatus ->
            test("updateMemberMembershipTier: assigning a real tier to a $endedStatus member is rejected (ConflictException)") {
                testApplication {
                    application {
                        install(StatusPages) { installMemberAdminExceptionHandlers() }
                        routing { registerMemberAdminTestRoutes() }
                    }
                    val target = createTestMember(email = "tier-ended-$endedStatus@example.org", status = endedStatus)
                    client
                        .post("/test/tier/$target?tierId=${DevSeedData.standardTierId}") { header("X-Member-Id", ADMIN_ID) }
                        .status shouldBe HttpStatusCode.Conflict
                    tierOf(target) shouldBe null
                    // Removing (null) stays allowed regardless of status -- never blocked by this guard.
                    transaction { MemberTable.update({ MemberTable.id eq target }) { it[membershipTierId] = DevSeedData.standardTierId } }
                    client.post("/test/tier/$target") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                    tierOf(target) shouldBe null
                }
            }
        }

        test(
            "listMembersForAdministration: family/tier LEFT JOINs surface correctly and totalCount is unaffected (no row multiplication)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes()
                        registerFamilyHelperRoutesForMemberAdminTests()
                    }
                }
                val withoutFamily = createTestMember(email = "roster-family-none-${Uuid.random()}@example.org")
                val payer = createTestMember(email = "roster-family-payer-${Uuid.random()}@example.org")
                transaction { MemberTable.update({ MemberTable.id eq payer }) { it[membershipTierId] = DevSeedData.standardTierId } }

                val baselineTotal =
                    client
                        .get("/test/roster?search=roster-family-") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")[1]
                        .toInt()

                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", ADMIN_ID)
                            parameter("payerMemberId", payer.toString())
                            parameter("name", "Roster-Testfamilie")
                        }.bodyAsText()

                val response = client.get("/test/roster?search=roster-family-") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val (rowsPart, totalCount) = response.split("|")
                totalCount.toInt() shouldBe baselineTotal

                val rows = rowsPart.split(";").filter { it.isNotBlank() }
                rows.any { it.startsWith("$withoutFamily:") && it.endsWith(":null:null:null:null") } shouldBe true
                rows.any {
                    it.contains(
                        "$payer:",
                    ) &&
                        it.contains(":$familyId:Roster-Testfamilie:PAYER:${DevSeedData.standardTierId}")
                } shouldBe
                    true

                // Cleanup BEFORE this file's shared afterSpec deletes `payer` -- MemberFamilyLinkTable
                // .member_id has an FK to member(id) with no ON DELETE CASCADE, same class of fixup
                // this file's afterSpec already applies to AuditLogEntryTable.actorMemberId.
                transaction {
                    val familyUuid = Uuid.parse(familyId)
                    MemberFamilyLinkTable.deleteWhere { MemberFamilyLinkTable.familyId eq familyUuid }
                    MemberFamilyTable.deleteWhere { MemberFamilyTable.id eq familyUuid }
                }
            }
        }

        // Review fix (Runde 4, MEDIUM finding): the two tests below are the missing regression
        // guard for MemberService.toMemberAdminRowDto's `includeFamilyDetails = current.isPrivileged`
        // gate (see its KDoc) -- without them, reverting that gate to `true` (or defaulting the
        // parameter) left `./gradlew clean check` fully green. Both reuse the exact same
        // registerFamilyHelperRoutesForMemberAdminTests()/createTestMember fixture shape as the
        // "family/tier LEFT JOINs" test directly above, just read back as TREASURER instead of ADMIN.
        test(
            "listMembersForAdministration: TREASURER caller gets the three family fields nulled out, membershipTierId stays populated (security fix regression guard)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes()
                        registerFamilyHelperRoutesForMemberAdminTests()
                    }
                }
                val payer = createTestMember(email = "roster-treasurer-family-${Uuid.random()}@example.org")
                transaction { MemberTable.update({ MemberTable.id eq payer }) { it[membershipTierId] = DevSeedData.standardTierId } }

                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", ADMIN_ID)
                            parameter("payerMemberId", payer.toString())
                            parameter("name", "Treasurer-Sichtbarkeits-Testfamilie")
                        }.bodyAsText()

                // Sanity check first: as ADMIN the three family fields ARE populated -- otherwise the
                // TREASURER assertion below (`null`) would trivially pass even if the gate were gone.
                val adminRows =
                    client
                        .get("/test/roster?search=roster-treasurer-family-") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")[0]
                        .split(";")
                        .filter { it.isNotBlank() }
                adminRows.any {
                    it.contains("$payer:") &&
                        it.endsWith(":$familyId:Treasurer-Sichtbarkeits-Testfamilie:PAYER:${DevSeedData.standardTierId}")
                } shouldBe true

                val treasurerRows =
                    client
                        .get("/test/roster?search=roster-treasurer-family-") { header("X-Member-Id", TREASURER_ID) }
                        .bodyAsText()
                        .split("|")[0]
                        .split(";")
                        .filter { it.isNotBlank() }
                treasurerRows.any {
                    it.contains("$payer:") && it.endsWith(":null:null:null:${DevSeedData.standardTierId}")
                } shouldBe true

                transaction {
                    val familyUuid = Uuid.parse(familyId)
                    MemberFamilyLinkTable.deleteWhere { MemberFamilyLinkTable.familyId eq familyUuid }
                    MemberFamilyTable.deleteWhere { MemberFamilyTable.id eq familyUuid }
                }
            }
        }

        test(
            "updateMemberMembershipTier: TREASURER caller's own returned row also has the three family fields nulled out",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes()
                        registerFamilyHelperRoutesForMemberAdminTests()
                    }
                }
                val payer = createTestMember(email = "tier-treasurer-family-${Uuid.random()}@example.org")
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", ADMIN_ID)
                            parameter("payerMemberId", payer.toString())
                            parameter("name", "Tier-Treasurer-Testfamilie")
                        }.bodyAsText()

                // Same actor, same call, as ADMIN first -- sanity check that the fixture actually
                // carries non-null family fields before proving the TREASURER path nulls them.
                client
                    .post("/test/tier/$payer?tierId=${DevSeedData.standardTierId}") { header("X-Member-Id", ADMIN_ID) }
                    .bodyAsText() shouldBe
                    "$payer:${DevSeedData.standardTierId}:$familyId:Tier-Treasurer-Testfamilie:PAYER"

                client
                    .post("/test/tier/$payer?tierId=${DevSeedData.standardTierId}") { header("X-Member-Id", TREASURER_ID) }
                    .bodyAsText() shouldBe
                    "$payer:${DevSeedData.standardTierId}:null:null:null"

                transaction {
                    val familyUuid = Uuid.parse(familyId)
                    MemberFamilyLinkTable.deleteWhere { MemberFamilyLinkTable.familyId eq familyUuid }
                    MemberFamilyTable.deleteWhere { MemberFamilyTable.id eq familyUuid }
                }
            }
        }

        // ══ Welle V1.4.9 "Admin-Passwort-Reset" ══════════════════════════════════════════════════

        // ── Weg 1: setTemporaryPasswordForMember ──

        test("setTemporaryPasswordForMember: operator-chosen password -- hash changes, generatedPassword is null, one UPDATE audit entry") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("temp-pw-chosen@example.org")
                val beforeHash = passwordHashOf(member)
                val response =
                    client.post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Telefonat") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                response.status shouldBe HttpStatusCode.OK
                val parts = response.bodyAsText().split(":")
                parts[0] shouldBe member.toString()
                parts[1] shouldBe "null"
                passwordHashOf(member) shouldNotBe beforeHash
                PasswordHasher.verify(rawPassword = STRONG_PASSWORD, storedHash = passwordHashOf(member)) shouldBe true
                auditCountFor(member) shouldBe 1L
            }
        }

        test(
            "setTemporaryPasswordForMember: newPassword=null -- server generates a 19-character, hyphen-grouped password, visible exactly once",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("temp-pw-generated@example.org")
                val response =
                    client.post("/test/temp-password/$member?reason=Telefonat") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                val generated = response.bodyAsText().split(":")[1]
                generated.length shouldBe 19
                generated.count { it == '-' } shouldBe 3
                generated.filter { it != '-' }.all { it in TemporaryPasswordGenerator.ALPHABET } shouldBe true
                PasswordHasher.verify(rawPassword = generated, storedHash = passwordHashOf(member)) shouldBe true
            }
        }

        test(
            "setTemporaryPasswordForMember: BOARD/MEMBER/TREASURER forbidden, unauthenticated rejected -- ADMIN-exclusive, checked before existence",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("temp-pw-authz@example.org")
                val beforeHash = passwordHashOf(member)
                for (callerId in listOf(BOARD_ID, MEMBER_ID, TREASURER_ID)) {
                    client
                        .post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                            header("X-Member-Id", callerId)
                        }.status shouldBe
                        HttpStatusCode.Forbidden
                }
                client.post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Testgrund").status shouldBe
                    HttpStatusCode.Unauthorized
                passwordHashOf(member) shouldBe beforeHash

                // Rollen-Gate VOR jeder Existenzprüfung -- BOARD gegen eine unbekannte UUID ist 403, nicht 404.
                client
                    .post("/test/temp-password/${Uuid.random()}?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("setTemporaryPasswordForMember: self-target is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                client
                    .post("/test/temp-password/$ADMIN_ID?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("setTemporaryPasswordForMember: anonymized member is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("temp-pw-anonymized@example.org")
                transaction { MemberTable.update({ MemberTable.id eq member }) { it[anonymizedAt] = DbClock.nowLocalDateTime() } }
                client
                    .post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("setTemporaryPasswordForMember: accountless target -- MemberHasNoAccountException/Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createAccountlessTestMember("temp-pw-noaccount@example.org")
                client
                    .post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("setTemporaryPasswordForMember: weak password is rejected, hash unchanged, no audit entry") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("temp-pw-weak@example.org")
                val beforeHash = passwordHashOf(member)
                client
                    .post("/test/temp-password/$member?newPassword=${"x".repeat(11)}&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.BadRequest
                // Pins that validation runs against the DB-stored address -- this RPC accepts no e-mail parameter at all.
                client
                    .post("/test/temp-password/$member?newPassword=temp-pw-weak@example.org&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.BadRequest
                passwordHashOf(member) shouldBe beforeHash
                auditCountFor(member) shouldBe 0L
            }
        }

        test("setTemporaryPasswordForMember: reason blank/too long is rejected, hash unchanged") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("temp-pw-reason@example.org")
                val beforeHash = passwordHashOf(member)
                client
                    .post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.Conflict
                client
                    .post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=${"x".repeat(1001)}") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.Conflict
                passwordHashOf(member) shouldBe beforeHash
            }
        }

        test("setTemporaryPasswordForMember: DECEASED target is rejected, hash unchanged") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("temp-pw-deceased@example.org", status = MemberStatus.DECEASED)
                val beforeHash = passwordHashOf(member)
                client
                    .post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe
                    HttpStatusCode.Conflict
                passwordHashOf(member) shouldBe beforeHash
            }
        }

        test("setTemporaryPasswordForMember: revokes every live session of the target -- the receipt reports the real count") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes() }
                }
                val member = createTestMember("temp-pw-sessions@example.org")
                SessionStore.createSession(member)
                SessionStore.createSession(member)
                activeSessionCount(member) shouldBe 2L

                val response =
                    client.post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split(":")[2] shouldBe "2"
                activeSessionCount(member) shouldBe 0L
            }
        }

        test("setTemporaryPasswordForMember: SMTP NotConfigured -- action still succeeds, memberNotified is NOT_CONFIGURED") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes(smtpConfigState = SmtpConfigState.NotConfigured) }
                }
                val member = createTestMember("temp-pw-notconfigured@example.org")
                val response =
                    client.post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split(":")[3] shouldBe MailDeliveryState.NOT_CONFIGURED.name
                PasswordHasher.verify(rawPassword = STRONG_PASSWORD, storedHash = passwordHashOf(member)) shouldBe true
            }
        }

        test("setTemporaryPasswordForMember: a throwing notification mailer never affects the result -- password still changes") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes(
                            smtpConfigState = configuredSmtpState(),
                            adminPasswordResetNotificationMailer =
                                object : AdminPasswordResetNotificationMailer {
                                    override fun send(
                                        email: String,
                                        occurredAt: kotlinx.datetime.LocalDateTime,
                                    ): DeliveryStatus = throw IllegalStateException("boom")
                                },
                        )
                    }
                }
                val member = createTestMember("temp-pw-throwing-mailer@example.org")
                val response =
                    client.post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                response.status shouldBe HttpStatusCode.OK
                PasswordHasher.verify(rawPassword = STRONG_PASSWORD, storedHash = passwordHashOf(member)) shouldBe true
            }
        }

        // ── Weg 2: sendPasswordResetMailToMember ──

        test(
            "sendPasswordResetMailToMember: happy path -- HANDED_TO_SMTP, exactly one token minted, correct recipient, no session revoked, one UPDATE audit entry",
        ) {
            testApplication {
                val recordingMailer = RecordingPasswordResetMailer()
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes(
                            smtpConfigState = configuredSmtpState(),
                            passwordResetMailer = recordingMailer,
                        )
                    }
                }
                val member = createTestMember("reset-mail-happy@example.org")
                SessionStore.createSession(member)
                activeSessionCount(member) shouldBe 1L

                val response = client.post("/test/reset-mail/$member") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe MailDeliveryState.HANDED_TO_SMTP.name

                recordingMailer.sentTo.size shouldBe 1
                recordingMailer.sentTo.single().first shouldBe "reset-mail-happy@example.org"
                val rawToken = recordingMailer.sentTo.single().second
                PasswordResetTokenStore.peekMemberId(rawToken) shouldBe member

                // Weg 2 revokes NOTHING -- the negative counterpart of setTemporaryPasswordForMember's own session test.
                activeSessionCount(member) shouldBe 1L
                auditCountFor(member) shouldBe 1L
            }
        }

        test("sendPasswordResetMailToMember: BOARD/MEMBER/TREASURER forbidden, unauthenticated rejected, self-target forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes(smtpConfigState = configuredSmtpState()) }
                }
                val member = createTestMember("reset-mail-authz@example.org")
                for (callerId in listOf(BOARD_ID, MEMBER_ID, TREASURER_ID)) {
                    client.post("/test/reset-mail/$member") { header("X-Member-Id", callerId) }.status shouldBe
                        HttpStatusCode.Forbidden
                }
                client.post("/test/reset-mail/$member").status shouldBe HttpStatusCode.Unauthorized
                client.post("/test/reset-mail/$ADMIN_ID") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
                auditCountFor(member) shouldBe 0L
            }
        }

        test("sendPasswordResetMailToMember: anonymized/accountless targets are rejected, no token minted") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes(smtpConfigState = configuredSmtpState()) }
                }
                val anonymized = createTestMember("reset-mail-anonymized@example.org")
                transaction { MemberTable.update({ MemberTable.id eq anonymized }) { it[anonymizedAt] = DbClock.nowLocalDateTime() } }
                client.post("/test/reset-mail/$anonymized") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict

                val accountless = createAccountlessTestMember("reset-mail-noaccount@example.org")
                client.post("/test/reset-mail/$accountless") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test(
            "sendPasswordResetMailToMember: every LOGIN_BLOCKED status is rejected -- deliberate asymmetry with the self-service endpoint, no token minted",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes(smtpConfigState = configuredSmtpState()) }
                }
                for (blocked in MemberStatusSets.LOGIN_BLOCKED) {
                    val member = createTestMember("reset-mail-blocked-$blocked@example.org", status = blocked)
                    client.post("/test/reset-mail/$member") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                        HttpStatusCode.Conflict
                    auditCountFor(member) shouldBe 0L
                }
            }
        }

        test("sendPasswordResetMailToMember: SMTP NotConfigured -- NOT_CONFIGURED, no token minted, no audit entry") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes(smtpConfigState = SmtpConfigState.NotConfigured) }
                }
                val member = createTestMember("reset-mail-notconfigured@example.org")
                val response = client.post("/test/reset-mail/$member") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe MailDeliveryState.NOT_CONFIGURED.name
                auditCountFor(member) shouldBe 0L
                passwordResetTokenCountFor(member) shouldBe 0L
            }
        }

        test(
            "sendPasswordResetMailToMember: target-side rate limit -- 4th call against the same member is RATE_LIMITED, no token, no audit",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes(
                            smtpConfigState = configuredSmtpState(),
                            adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(maxRequests = 3),
                        )
                    }
                }
                val member = createTestMember("reset-mail-target-limit@example.org")
                repeat(3) {
                    client.post("/test/reset-mail/$member") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe
                        MailDeliveryState.HANDED_TO_SMTP.name
                }
                val fourth = client.post("/test/reset-mail/$member") { header("X-Member-Id", ADMIN_ID) }
                fourth.bodyAsText() shouldBe MailDeliveryState.RATE_LIMITED.name
                auditCountFor(member) shouldBe 3L
                passwordResetTokenCountFor(member) shouldBe 3L
            }
        }

        test("sendPasswordResetMailToMember: actor-side rate limit trips even across DIFFERENT targets") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes(
                            smtpConfigState = configuredSmtpState(),
                            adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(maxRequests = 2),
                        )
                    }
                }
                val members = (1..3).map { createTestMember("reset-mail-actor-limit-$it@example.org") }
                members.take(2).forEach { m ->
                    client.post("/test/reset-mail/$m") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe
                        MailDeliveryState.HANDED_TO_SMTP.name
                }
                client.post("/test/reset-mail/${members[2]}") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe
                    MailDeliveryState.RATE_LIMITED.name
            }
        }

        test("setTemporaryPasswordForMember: rate limit hits only the notification, never the action itself") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes(
                            smtpConfigState = configuredSmtpState(),
                            adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(maxRequests = 0),
                        )
                    }
                }
                val member = createTestMember("temp-pw-rate-limited-notice@example.org")
                val response =
                    client.post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split(":")[3] shouldBe MailDeliveryState.RATE_LIMITED.name
                PasswordHasher.verify(rawPassword = STRONG_PASSWORD, storedHash = passwordHashOf(member)) shouldBe true
            }
        }

        test(
            "setTemporaryPasswordForMember: notification target pool is INDEPENDENT of the reset-mail " +
                "target pool -- a rogue admin exhausting Weg 2's budget against a target cannot silence Weg 1's security notice (MINOR security fix)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes(
                            smtpConfigState = configuredSmtpState(),
                            // Zeroed out -- every sendPasswordResetMailToMember call against this
                            // target is RATE_LIMITED from the very first attempt.
                            adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(maxRequests = 0),
                        )
                    }
                }
                val member = createTestMember("temp-pw-notice-not-starved@example.org")
                client.post("/test/reset-mail/$member") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe
                    MailDeliveryState.RATE_LIMITED.name

                // Weg 1's security notice draws from its OWN, untouched pool -- it must still go out.
                val response =
                    client.post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                response.bodyAsText().split(":")[3] shouldBe MailDeliveryState.HANDED_TO_SMTP.name
            }
        }

        test(
            "setTemporaryPasswordForMember: security notice no longer shares sendPasswordResetMailToMember's " +
                "ACTOR-side pool -- exhausting it for FREE against non-existent targets cannot silence Weg 1's " +
                "notice for a real member (MINOR security fix, residual round 2)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes(
                            smtpConfigState = configuredSmtpState(),
                            // Only ONE actor-side slot total -- if notifyMemberOfAdminPasswordReset
                            // still consulted this pool, the second call below would come back
                            // RATE_LIMITED.
                            adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(maxRequests = 1),
                        )
                    }
                }
                // Burns the ONLY actor-side slot against a well-formed but NON-EXISTENT member id.
                // sendPasswordResetMailToMember's own actor-side checkAndRecord runs before its
                // existence check (see that function's own comment), so this leaves no token, no
                // audit entry, and no other trace -- exactly the "free" exhaustion the finding
                // described.
                client.post("/test/reset-mail/${Uuid.random()}") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.NotFound

                // A REAL admin action against a REAL member must still trigger the security notice --
                // it must NOT come back RATE_LIMITED just because sendPasswordResetMailToMember's
                // (no longer shared) actor pool above is now empty.
                val member = createTestMember("temp-pw-actor-pool-not-shared@example.org")
                val response =
                    client.post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Testgrund") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().split(":")[3] shouldBe MailDeliveryState.HANDED_TO_SMTP.name
            }
        }

        test(
            "setTemporaryPasswordForMember: invalidates every outstanding reset token of the target -- a token " +
                "minted earlier via Weg 2 can no longer be consumed after Weg 1 runs (MAJOR security fix)",
        ) {
            testApplication {
                val recordingMailer = RecordingPasswordResetMailer()
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing {
                        registerMemberAdminTestRoutes(
                            smtpConfigState = configuredSmtpState(),
                            passwordResetMailer = recordingMailer,
                        )
                    }
                }
                val member = createTestMember("temp-pw-invalidates-token@example.org")
                // Weg 2 first: an outstanding, still-valid reset token exists for this member.
                client.post("/test/reset-mail/$member") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe
                    MailDeliveryState.HANDED_TO_SMTP.name
                val rawToken = recordingMailer.sentTo.single().second
                PasswordResetTokenStore.peekMemberId(rawToken) shouldBe member

                // Weg 1: admin sets a temporary password directly, believing the account is now
                // secured -- the earlier token must die with it, or whoever holds it can still take
                // the account over via POST /api/auth/password-reset/confirm.
                val response =
                    client.post("/test/temp-password/$member?newPassword=$STRONG_PASSWORD&reason=Kompromittiert") {
                        header("X-Member-Id", ADMIN_ID)
                    }
                response.status shouldBe HttpStatusCode.OK
                PasswordResetTokenStore.peekMemberId(rawToken) shouldBe null
                PasswordResetTokenStore.consumeToken(rawToken) shouldBe null
                // The row still exists (not purged), just marked consumed -- passwordResetTokenCountFor
                // deliberately counts ALL rows regardless of consumed state (see its own KDoc).
                passwordResetTokenCountFor(member) shouldBe 1L
            }
        }

        // ── Audit-Inhalt (beide Wege) ──

        test("audit trail: neither password, hash, nor bcrypt marker ever appear in a before/after snapshot for either path") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes(smtpConfigState = configuredSmtpState()) }
                }
                val member1 = createTestMember("audit-no-password-1@example.org")
                client.post("/test/temp-password/$member1?newPassword=$STRONG_PASSWORD&reason=Telefonat") {
                    header("X-Member-Id", ADMIN_ID)
                }
                val member2 = createTestMember("audit-no-password-2@example.org")
                client.post("/test/reset-mail/$member2") { header("X-Member-Id", ADMIN_ID) }

                listOf(member1, member2).forEach { member ->
                    val (beforeJson, afterJson) =
                        transaction {
                            val row =
                                AuditLogEntryTable
                                    .selectAll()
                                    .where {
                                        (AuditLogEntryTable.entityId eq member) and
                                            (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and
                                            (AuditLogEntryTable.action eq AuditAction.UPDATE)
                                    }.single()
                            row[AuditLogEntryTable.beforeSnapshot] to row[AuditLogEntryTable.afterSnapshot]
                        }
                    listOf(beforeJson, afterJson).forEach { json ->
                        requireNotNull(json)
                        json.contains(STRONG_PASSWORD) shouldBe false
                        json.contains("\$2a$") shouldBe false
                        json.contains("\$2b$") shouldBe false
                        passwordHashOf(member)?.let { json.contains(it) shouldBe false }
                    }
                }
            }
        }

        test("audit trail: adminPasswordAction distinguishes the two paths, reason only on Weg 1") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes(smtpConfigState = configuredSmtpState()) }
                }
                val member1 = createTestMember("audit-content-1@example.org")
                client.post("/test/temp-password/$member1?newPassword=$STRONG_PASSWORD&reason=Telefonat+Hilfe") {
                    header("X-Member-Id", ADMIN_ID)
                }
                val afterJson1 =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityId eq member1) and
                                    (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.single()[AuditLogEntryTable.afterSnapshot]
                    }
                requireNotNull(afterJson1)
                afterJson1.contains("\"adminPasswordAction\":\"TEMPORARY_PASSWORD_SET\"") shouldBe true
                afterJson1.contains("Telefonat") shouldBe true

                val member2 = createTestMember("audit-content-2@example.org")
                client.post("/test/reset-mail/$member2") { header("X-Member-Id", ADMIN_ID) }
                val afterJson2 =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityId eq member2) and
                                    (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.single()[AuditLogEntryTable.afterSnapshot]
                    }
                requireNotNull(afterJson2)
                afterJson2.contains("\"adminPasswordAction\":\"RESET_MAIL_SENT\"") shouldBe true
                // reason stays at its default (null) for Weg 2 -- kotlinx.serialization's default
                // Json instance omits a property entirely when it equals its declared default,
                // so the key itself is simply absent, not present as literal `null`.
                afterJson2.contains("\"reason\"") shouldBe false
            }
        }

        // ── Preflight ──

        test(
            "getMemberAccessPreflight: reports the real active-session count (expired sessions do not count) and mail-delivery state; ADMIN-only",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes(smtpConfigState = configuredSmtpState()) }
                }
                val member = createTestMember("preflight-happy@example.org")
                SessionStore.createSession(member)
                SessionStore.createSession(member)

                val response = client.get("/test/access-preflight/$member") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "${MailDeliveryState.HANDED_TO_SMTP}:2"

                client.get("/test/access-preflight/$member") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.get("/test/access-preflight/${Uuid.random()}") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        test("getMemberAccessPreflight: SMTP NotConfigured is reflected honestly") {
            testApplication {
                application {
                    install(StatusPages) { installMemberAdminExceptionHandlers() }
                    routing { registerMemberAdminTestRoutes(smtpConfigState = SmtpConfigState.NotConfigured) }
                }
                val member = createTestMember("preflight-notconfigured@example.org")
                client.get("/test/access-preflight/$member") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe
                    "${MailDeliveryState.NOT_CONFIGURED}:0"
            }
        }
    })

/** Welle V1.4.4.4 -- minimal helper route so [MemberAdministrationTest] can create a family fixture without duplicating [MemberFamilyServiceTest]'s full route set. */
private fun Route.registerFamilyHelperRoutesForMemberAdminTests() {
    post("/test/family/create") {
        val service = MemberFamilyService(call = call)
        val q = call.request.queryParameters
        val dto = service.createFamily(name = q["name"] ?: "Testfamilie", payerMemberId = q["payerMemberId"]!!)
        call.respondText(dto.id)
    }
}

private fun StatusPagesConfig.installMemberAdminExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<MemberEmailInUseException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<MemberEmailTooLongException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<MemberHasNoAccountException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<MemberAlreadyHasAccountException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<LastAdminException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    // Welle V1.2.13 -- without this handler, WeakPasswordException became an uncaught 500 and
    // test 32 would assert the wrong thing entirely (a server error, not a validation rejection).
    exception<WeakPasswordException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
    // Welle V1.4.4.4 "Familienmitgliedschaften" -- updateMemberMembershipTier's unknown-tier case.
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

private fun Route.registerMemberAdminTestRoutes(
    mailer: FriendVerificationMailer = FakeFriendVerificationMailer(),
    // ONE instance per `registerMemberAdminTestRoutes()` call, shared across every request handler
    // below -- same "constructed once, reused across the whole route registration" shape the real
    // `Application.kt` wiring uses for `memberCoreDataFriendMailRateLimiter`, not a fresh instance
    // per request (which would make the limiter a permanent no-op in every test).
    memberCoreDataFriendMailRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(),
    // Security fix (2026-08-27, LOW, follow-up) -- SEPARATE actor-side limiter, see MemberService
    // constructor KDoc "memberCoreDataFriendMailActorRateLimiter" for why. A fresh instance by
    // default, same "no default in production, generous default here" shape the parameter above
    // already establishes for tests that don't care about this specific rate limit.
    memberCoreDataFriendMailActorRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(),
    // Welle V1.4.9 "Admin-Passwort-Reset" -- five new parameters, all defaulted so every pre-existing
    // call site above/below stays unaffected. `smtpConfigState` defaults to `NotConfigured` (the
    // realistic default for a test that does not care about mail delivery at all); tests that DO
    // care pass `SmtpConfigState.Configured(...)` explicitly.
    passwordResetMailer: PasswordResetMailer = FakePasswordResetMailer(),
    adminPasswordResetNotificationMailer: AdminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
    smtpConfigState: SmtpConfigState = SmtpConfigState.NotConfigured,
    // ONE instance per `registerMemberAdminTestRoutes()` call, same reasoning as
    // memberCoreDataFriendMailRateLimiter above.
    adminPasswordMailTargetRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(),
    adminPasswordMailActorRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(),
    // Security fix (Welle V1.4.9 review round, MINOR) -- DEDICATED pool for Weg 1's security
    // notice, see MemberService constructor KDoc "adminPasswordNotificationTargetRateLimiter". A
    // fresh instance by default, same "no default in production, generous default here" shape
    // adminPasswordMailTargetRateLimiter above already establishes.
    adminPasswordNotificationTargetRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(),
) {
    get("/test/roster") {
        val service =
            MemberService(
                call = call,
                friendVerificationMailer = mailer,
                memberCoreDataFriendMailRateLimiter = memberCoreDataFriendMailRateLimiter,
                memberCoreDataFriendMailActorRateLimiter = memberCoreDataFriendMailActorRateLimiter,
                passwordResetMailer = passwordResetMailer,
                adminPasswordResetNotificationMailer = adminPasswordResetNotificationMailer,
                smtpConfigState = smtpConfigState,
                adminPasswordMailTargetRateLimiter = adminPasswordMailTargetRateLimiter,
                adminPasswordMailActorRateLimiter = adminPasswordMailActorRateLimiter,
                adminPasswordNotificationTargetRateLimiter = adminPasswordNotificationTargetRateLimiter,
            )
        val q = call.request.queryParameters
        val statuses =
            q["statuses"]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.map { MemberStatus.valueOf(it) }
                ?.toSet() ?: emptySet()
        val page =
            service.listMembersForAdministration(
                MemberAdminQuery(
                    search = q["search"],
                    statuses = statuses,
                    sort = q["sort"]?.let { MemberAdminSort.valueOf(it) } ?: MemberAdminSort.NAME_ASC,
                    limit = q["limit"]?.toInt() ?: MemberAdminQuery.DEFAULT_LIMIT,
                    offset = q["offset"]?.toInt() ?: 0,
                ),
            )
        val rowsPart =
            page.rows.joinToString(";") {
                "${it.id}:${it.status}:${it.role}:${it.anonymized}:${it.familyId}:${it.familyName}:${it.familyRole}:${it.membershipTierId}"
            }
        val countsPart = page.statusCounts.entries.joinToString(",") { "${it.key}=${it.value}" }
        call.respondText("$rowsPart|${page.totalCount}|${page.limit}|${page.offset}|$countsPart")
    }
    post("/test/core-data/{id}") {
        val service =
            MemberService(
                call = call,
                friendVerificationMailer = mailer,
                memberCoreDataFriendMailRateLimiter = memberCoreDataFriendMailRateLimiter,
                memberCoreDataFriendMailActorRateLimiter = memberCoreDataFriendMailActorRateLimiter,
                passwordResetMailer = passwordResetMailer,
                adminPasswordResetNotificationMailer = adminPasswordResetNotificationMailer,
                smtpConfigState = smtpConfigState,
                adminPasswordMailTargetRateLimiter = adminPasswordMailTargetRateLimiter,
                adminPasswordMailActorRateLimiter = adminPasswordMailActorRateLimiter,
                adminPasswordNotificationTargetRateLimiter = adminPasswordNotificationTargetRateLimiter,
            )
        val q = call.request.queryParameters
        val dto =
            service.updateMemberCoreData(
                memberId = call.parameters["id"]!!,
                displayName = q["name"] ?: "",
                email = q["email"] ?: "",
            )
        call.respondText("${dto.id}:${dto.displayName}:${dto.email}:${dto.status}:${dto.role}")
    }
    post("/test/status/{id}") {
        val service =
            MemberService(
                call = call,
                friendVerificationMailer = mailer,
                memberCoreDataFriendMailRateLimiter = memberCoreDataFriendMailRateLimiter,
                memberCoreDataFriendMailActorRateLimiter = memberCoreDataFriendMailActorRateLimiter,
                passwordResetMailer = passwordResetMailer,
                adminPasswordResetNotificationMailer = adminPasswordResetNotificationMailer,
                smtpConfigState = smtpConfigState,
                adminPasswordMailTargetRateLimiter = adminPasswordMailTargetRateLimiter,
                adminPasswordMailActorRateLimiter = adminPasswordMailActorRateLimiter,
                adminPasswordNotificationTargetRateLimiter = adminPasswordNotificationTargetRateLimiter,
            )
        val q = call.request.queryParameters
        val dto =
            service.updateMemberStatus(
                memberId = call.parameters["id"]!!,
                newStatus = MemberStatus.valueOf(q["newStatus"]!!),
                reason = q["reason"] ?: "",
                dateOfDeath = q["dateOfDeath"]?.let { LocalDate.parse(it) },
            )
        call.respondText("${dto.id}:${dto.status}:${dto.dateOfDeath}")
    }
    // Welle V1.4.4.5 -- Testroute fuer IMemberService.correctDateOfDeath, Muster wie /test/status/{id}.
    post("/test/death-date/{id}") {
        val service =
            MemberService(
                call = call,
                friendVerificationMailer = mailer,
                memberCoreDataFriendMailRateLimiter = memberCoreDataFriendMailRateLimiter,
                memberCoreDataFriendMailActorRateLimiter = memberCoreDataFriendMailActorRateLimiter,
                passwordResetMailer = passwordResetMailer,
                adminPasswordResetNotificationMailer = adminPasswordResetNotificationMailer,
                smtpConfigState = smtpConfigState,
                adminPasswordMailTargetRateLimiter = adminPasswordMailTargetRateLimiter,
                adminPasswordMailActorRateLimiter = adminPasswordMailActorRateLimiter,
                adminPasswordNotificationTargetRateLimiter = adminPasswordNotificationTargetRateLimiter,
            )
        val q = call.request.queryParameters
        val dto =
            service.correctDateOfDeath(
                memberId = call.parameters["id"]!!,
                dateOfDeath = q["dateOfDeath"]?.let { LocalDate.parse(it) },
                reason = q["reason"] ?: "",
            )
        call.respondText("${dto.id}:${dto.status}:${dto.dateOfDeath}")
    }
    post("/test/role/{id}") {
        val service =
            MemberService(
                call = call,
                friendVerificationMailer = mailer,
                memberCoreDataFriendMailRateLimiter = memberCoreDataFriendMailRateLimiter,
                memberCoreDataFriendMailActorRateLimiter = memberCoreDataFriendMailActorRateLimiter,
                passwordResetMailer = passwordResetMailer,
                adminPasswordResetNotificationMailer = adminPasswordResetNotificationMailer,
                smtpConfigState = smtpConfigState,
                adminPasswordMailTargetRateLimiter = adminPasswordMailTargetRateLimiter,
                adminPasswordMailActorRateLimiter = adminPasswordMailActorRateLimiter,
                adminPasswordNotificationTargetRateLimiter = adminPasswordNotificationTargetRateLimiter,
            )
        val q = call.request.queryParameters
        val dto = service.updateMemberRole(memberId = call.parameters["id"]!!, newRole = AccountRole.valueOf(q["newRole"]!!))
        call.respondText("${dto.id}:${dto.role}")
    }
    // Welle V1.2.13.
    post("/test/grant-account/{id}") {
        val service =
            MemberService(
                call = call,
                friendVerificationMailer = mailer,
                memberCoreDataFriendMailRateLimiter = memberCoreDataFriendMailRateLimiter,
                memberCoreDataFriendMailActorRateLimiter = memberCoreDataFriendMailActorRateLimiter,
                passwordResetMailer = passwordResetMailer,
                adminPasswordResetNotificationMailer = adminPasswordResetNotificationMailer,
                smtpConfigState = smtpConfigState,
                adminPasswordMailTargetRateLimiter = adminPasswordMailTargetRateLimiter,
                adminPasswordMailActorRateLimiter = adminPasswordMailActorRateLimiter,
                adminPasswordNotificationTargetRateLimiter = adminPasswordNotificationTargetRateLimiter,
            )
        val q = call.request.queryParameters
        val dto =
            service.grantMemberAccount(
                memberId = call.parameters["id"]!!,
                temporaryPassword = q["password"] ?: "",
                role = AccountRole.valueOf(q["role"] ?: "MEMBER"),
            )
        call.respondText("${dto.id}:${dto.role}:${dto.status}")
    }
    // Welle V1.4.4.4 "Familienmitgliedschaften".
    post("/test/tier/{id}") {
        val service =
            MemberService(
                call = call,
                friendVerificationMailer = mailer,
                memberCoreDataFriendMailRateLimiter = memberCoreDataFriendMailRateLimiter,
                memberCoreDataFriendMailActorRateLimiter = memberCoreDataFriendMailActorRateLimiter,
                passwordResetMailer = passwordResetMailer,
                adminPasswordResetNotificationMailer = adminPasswordResetNotificationMailer,
                smtpConfigState = smtpConfigState,
                adminPasswordMailTargetRateLimiter = adminPasswordMailTargetRateLimiter,
                adminPasswordMailActorRateLimiter = adminPasswordMailActorRateLimiter,
                adminPasswordNotificationTargetRateLimiter = adminPasswordNotificationTargetRateLimiter,
            )
        val q = call.request.queryParameters
        val dto =
            service.updateMemberMembershipTier(
                memberId = call.parameters["id"]!!,
                membershipTierId = q["tierId"],
                reason = q["reason"] ?: "Testbegründung",
            )
        // Review fix (Runde 4, MEDIUM finding): the three family fields were added to this response
        // so a test can assert the `includeFamilyDetails` gate (MemberService.toMemberAdminRowDto
        // KDoc) also holds for THIS call site's own returned row, not only for the roster read --
        // see the two "TREASURER caller ... family fields nulled out" tests above.
        call.respondText("${dto.id}:${dto.membershipTierId}:${dto.familyId}:${dto.familyName}:${dto.familyRole}")
    }
    // Welle V1.4.9 "Admin-Passwort-Reset".
    get("/test/access-preflight/{id}") {
        val service =
            MemberService(
                call = call,
                friendVerificationMailer = mailer,
                memberCoreDataFriendMailRateLimiter = memberCoreDataFriendMailRateLimiter,
                memberCoreDataFriendMailActorRateLimiter = memberCoreDataFriendMailActorRateLimiter,
                passwordResetMailer = passwordResetMailer,
                adminPasswordResetNotificationMailer = adminPasswordResetNotificationMailer,
                smtpConfigState = smtpConfigState,
                adminPasswordMailTargetRateLimiter = adminPasswordMailTargetRateLimiter,
                adminPasswordMailActorRateLimiter = adminPasswordMailActorRateLimiter,
                adminPasswordNotificationTargetRateLimiter = adminPasswordNotificationTargetRateLimiter,
            )
        val dto = service.getMemberAccessPreflight(memberId = call.parameters["id"]!!)
        call.respondText("${dto.mailDelivery}:${dto.activeSessionCount}")
    }
    post("/test/temp-password/{id}") {
        val service =
            MemberService(
                call = call,
                friendVerificationMailer = mailer,
                memberCoreDataFriendMailRateLimiter = memberCoreDataFriendMailRateLimiter,
                memberCoreDataFriendMailActorRateLimiter = memberCoreDataFriendMailActorRateLimiter,
                passwordResetMailer = passwordResetMailer,
                adminPasswordResetNotificationMailer = adminPasswordResetNotificationMailer,
                smtpConfigState = smtpConfigState,
                adminPasswordMailTargetRateLimiter = adminPasswordMailTargetRateLimiter,
                adminPasswordMailActorRateLimiter = adminPasswordMailActorRateLimiter,
                adminPasswordNotificationTargetRateLimiter = adminPasswordNotificationTargetRateLimiter,
            )
        val q = call.request.queryParameters
        val dto =
            service.setTemporaryPasswordForMember(
                memberId = call.parameters["id"]!!,
                newPassword = q["newPassword"],
                reason = q["reason"] ?: "",
            )
        call.respondText(
            "${dto.member.id}:${dto.generatedPassword}:${dto.revokedSessionCount}:${dto.memberNotified}",
        )
    }
    post("/test/reset-mail/{id}") {
        val service =
            MemberService(
                call = call,
                friendVerificationMailer = mailer,
                memberCoreDataFriendMailRateLimiter = memberCoreDataFriendMailRateLimiter,
                memberCoreDataFriendMailActorRateLimiter = memberCoreDataFriendMailActorRateLimiter,
                passwordResetMailer = passwordResetMailer,
                adminPasswordResetNotificationMailer = adminPasswordResetNotificationMailer,
                smtpConfigState = smtpConfigState,
                adminPasswordMailTargetRateLimiter = adminPasswordMailTargetRateLimiter,
                adminPasswordMailActorRateLimiter = adminPasswordMailActorRateLimiter,
                adminPasswordNotificationTargetRateLimiter = adminPasswordNotificationTargetRateLimiter,
            )
        val dto = service.sendPasswordResetMailToMember(memberId = call.parameters["id"]!!)
        call.respondText("${dto.delivery}")
    }
}

private fun Route.registerCommitteeHelperRoutesForMemberAdminTests() {
    post("/test/gov/create-committee/{type}") {
        val service = GovernanceService(call = call)
        val c =
            service.createCommittee(
                CommitteeInput(
                    name = "MemberAdministrationTest Committee ${Uuid.random()}",
                    type = CommitteeType.valueOf(call.parameters["type"]!!),
                    description = "Member-administration side-effect test committee",
                    quorumPercent = 50,
                ),
            )
        call.respondText(c.id)
    }
    post("/test/gov/add-member/{committeeId}/{memberId}") {
        val service = GovernanceService(call = call)
        val m =
            service.addCommitteeMember(
                committeeId = call.parameters["committeeId"]!!,
                input =
                    CommitteeMembershipInput(
                        memberId = call.parameters["memberId"]!!,
                        role = CommitteeRole.MEMBER,
                        since = LocalDate(2026, 1, 1),
                    ),
            )
        call.respondText(m.id)
    }
}

/**
 * Fires two concurrent [MemberService.updateMemberRole] demotions of each other (adminA demotes
 * adminB, adminB demotes adminA) from two independent OS threads, synchronized via
 * [CountDownLatch] -- same shape as [RegistrationServiceTest]'s own
 * `runConcurrentApproveAndReject`. Exercises the ACTUAL race
 * [MemberService.updateMemberRole]'s "Letzter-Admin-Schutz" closes: without the `.forUpdate()`
 * lock, both requests could observe `size == 2` under READ COMMITTED and both succeed, leaving
 * zero ADMIN accounts.
 */
private fun runConcurrentMutualAdminDemotion(
    client: HttpClient,
    otherAdminId: Uuid,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val results = mutableListOf<HttpStatusCode>()
    val failures = mutableListOf<Throwable>()

    fun demoteThread(
        callerId: String,
        targetId: String,
    ): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response = client.post("/test/role/$targetId?newRole=MEMBER") { header("X-Member-Id", callerId) }
                    synchronized(results) { results += response.status }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val aDemotesB = demoteThread(ADMIN_ID, otherAdminId.toString())
    val bDemotesA = demoteThread(otherAdminId.toString(), ADMIN_ID)
    aDemotesB.start()
    bDemotesA.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent mutual admin demotion did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return results.toList()
}

/**
 * Fires two concurrent [MemberService.updateMemberStatus] WITHDRAWALs of each other (adminA
 * withdraws adminB, adminB withdraws adminA) from two independent OS threads, synchronized via
 * [CountDownLatch] -- same shape as [runConcurrentMutualAdminDemotion] above, for the sibling
 * Security fix (2026-08-27, MEDIUM): [updateMemberStatus] had no equivalent "Letzter-Admin-Schutz"
 * at all before that fix, so both requests could each lock only their own (disjoint) target member
 * row and both succeed, leaving zero non-[MemberStatusSets.LOGIN_BLOCKED] ADMIN accounts.
 */
private fun runConcurrentMutualAdminStatusWithdraw(
    client: HttpClient,
    otherAdminId: Uuid,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val results = mutableListOf<HttpStatusCode>()
    val failures = mutableListOf<Throwable>()

    fun withdrawThread(
        callerId: String,
        targetId: String,
    ): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response =
                        client.post("/test/status/$targetId?newStatus=WITHDRAWN&reason=Konkurrierender+Austritt") {
                            header("X-Member-Id", callerId)
                        }
                    synchronized(results) { results += response.status }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val aWithdrawsB = withdrawThread(ADMIN_ID, otherAdminId.toString())
    val bWithdrawsA = withdrawThread(otherAdminId.toString(), ADMIN_ID)
    aWithdrawsB.start()
    bWithdrawsA.start()

    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent mutual admin status withdraw did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return results.toList()
}

/**
 * Test-only [FriendVerificationMailer] that records every recipient it was asked to send to --
 * same shape as `FriendRegistrationTest.RecordingFriendVerificationMailer` (that one is `private`
 * to its own file, so this is a deliberate, small duplicate rather than a cross-file `internal`
 * export for one test double -- also distinctly named, not just distinctly scoped: Kotlin rejects
 * two file-private top-level CLASSES sharing one simple name in the same package as a
 * `Redeclaration`, unlike file-private top-level properties/functions, which that same-package
 * pattern tolerates). Used by test 25 to assert the Review-Runde-3 resend-on-change fix sends to
 * the NEW address, for a FRIEND target only, and NOT for an ACTIVE target.
 */
private class RecordingResendFriendVerificationMailer : FriendVerificationMailer {
    val sentTo = mutableListOf<String>()

    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus {
        sentTo += email
        return DeliveryStatus.SENT
    }
}

/**
 * Welle V1.4.9 -- records every `(email, rawToken)` pair [network.lapis.cloud.server.rpc.MemberService
 * .sendPasswordResetMailToMember] was asked to send, distinctly named from
 * [RecordingResendFriendVerificationMailer] for the same "no two file-private top-level classes
 * sharing a simple name" reason that class's own KDoc documents.
 */
private class RecordingPasswordResetMailer : PasswordResetMailer {
    val sentTo = mutableListOf<Pair<String, String>>()

    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus {
        sentTo += email to rawToken
        return DeliveryStatus.SENT
    }
}

/**
 * Welle V1.4.9 -- a real [SmtpConfigState.Configured] built purely from an injected env lambda (no
 * network/filesystem I/O, see [SmtpConfig.load] KDoc "Pure string validation ONLY"), for every test
 * in this file that needs `smtpConfigState` to report "mail CAN be sent" honestly.
 */
private fun configuredSmtpState(): SmtpConfigState.Configured {
    val env =
        mapOf(
            SmtpConfig.ENV_HOST to "mxe9fb.netcup.net",
            SmtpConfig.ENV_USERNAME to "no_reply@example.org",
            SmtpConfig.ENV_PASSWORD to "s3cr3t",
            SmtpConfig.ENV_FROM_ADDRESS to "no_reply@example.org",
            SmtpConfig.ENV_FROM_NAME to "MemberAdministrationTest",
        )
    return SmtpConfig.load(env::get) as SmtpConfigState.Configured
}
