package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.backup.TestDatabaseFactory
import network.lapis.cloud.server.bootstrap.AdminBootstrap
import network.lapis.cloud.server.bootstrap.MemberCsvPlan
import network.lapis.cloud.server.bootstrap.PreparedMember
import network.lapis.cloud.server.bootstrap.runImport
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.StagingSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.FriendEmailVerificationTokenTable
import network.lapis.cloud.server.db.generated.FriendTermsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.OidcGuestClaims
import network.lapis.cloud.server.federation.OidcGuestMemberStore
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.member.MemberStatusHistory
import network.lapis.cloud.server.member.MemberStatusHistoryConsistency
import network.lapis.cloud.server.member.MemberStatusHistorySource
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.FriendRegistrationInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegistrationInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val WRITE_PATH_PASSWORD = "a-genuinely-strong-password-1"

/**
 * Welle V1.9.59 -- every writer of `member.status` appends exactly one history row with the right `previous_status` and `source`, a
 * failed change leaves no row, and the chain always ends at the member's current status. The source scan
 * (`MemberStatusWriteTripwireTest`) proves no writer is missing; this test proves each one behaves.
 *
 * The thirteen write sites: central status mutation (updateMemberStatus / privileged-action execution / operator console),
 * registerApplication, approveApplication, rejectApplication, createMemberDirect, leaveMembership, registerFriend,
 * applyForMembership, CSV import, first-admin bootstrap, OIDC guest, dev seed, staging seed.
 */
class MemberStatusHistoryWritePathTest :
    FunSpec({
        val fixtures = MemberStatisticsFixtures()
        val createdIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            cleanUpWritePathMembers(createdIds + fixtures.memberIds)
            fixtures.memberIds.clear()
        }

        /** (status, previous, source) of every row of [id], oldest first. */
        fun chain(
            id: Uuid,
            db: Database? = null,
        ): List<Triple<String, String?, String>> =
            transaction(db) {
                MemberStatusHistoryTable
                    .selectAll()
                    .where { MemberStatusHistoryTable.memberId eq id }
                    .orderBy(MemberStatusHistoryTable.effectiveFrom to SortOrder.ASC)
                    .map {
                        Triple(
                            it[MemberStatusHistoryTable.status],
                            it[MemberStatusHistoryTable.previousStatus],
                            it[MemberStatusHistoryTable.sourceKind],
                        )
                    }
            }

        fun currentStatus(
            id: Uuid,
            db: Database? = null,
        ): MemberStatus = transaction(db) { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.status] }

        fun idByEmail(email: String): Uuid =
            transaction { MemberTable.selectAll().where { MemberTable.email eq email }.single()[MemberTable.id] }.also { createdIds += it }

        fun Route.routes() {
            fun registration(call: ApplicationCall) =
                RegistrationService(
                    call = call,
                    registrationRateLimiter = LoginRateLimiter(),
                    friendRegistrationRateLimiter = LoginRateLimiter(),
                    friendSignupIpRateLimiter = FederationInboxRateLimiter(),
                    friendVerificationMailer = FakeFriendVerificationMailer(),
                    keycloakConfig = KeycloakConfig.load(env = { null }),
                )
            post("/test/register") {
                val q = call.request.queryParameters
                registration(call).registerApplication(
                    RegistrationInput(
                        displayName = "Verlauf Test",
                        email = q["email"]!!,
                        password = WRITE_PATH_PASSWORD,
                        agreementVersion = MembershipAgreementDisclaimer.VERSION,
                        agreementSha256 = MembershipAgreementDisclaimer.SHA256,
                        regionalChapterId = null,
                    ),
                )
                call.respondText("OK")
            }
            post("/test/register-friend") {
                registration(call).registerFriend(
                    FriendRegistrationInput(
                        displayName = "Verlauf Freund",
                        email = call.request.queryParameters["email"]!!,
                        password = WRITE_PATH_PASSWORD,
                        termsVersion = FriendTermsDisclaimer.VERSION,
                        termsSha256 = FriendTermsDisclaimer.SHA256,
                    ),
                )
                call.respondText("OK")
            }
            post("/test/approve/{id}") { call.respondText(registration(call).approveApplication(call.parameters["id"]!!).status.name) }
            post("/test/reject/{id}") {
                call.respondText(
                    registration(call).rejectApplication(memberId = call.parameters["id"]!!, reason = "nicht passend").status.name,
                )
            }
            post("/test/create-direct") {
                val dto =
                    registration(call).createMemberDirect(
                        AdminCreateMemberInput(
                            displayName = "Direkt Verlauf",
                            email = call.request.queryParameters["email"]!!,
                            role = AccountRole.MEMBER,
                            temporaryPassword = WRITE_PATH_PASSWORD,
                        ),
                    )
                call.respondText(dto.id)
            }
            post("/test/apply") {
                call.respondText(
                    registration(call)
                        .applyForMembership(
                            agreementVersion = MembershipAgreementDisclaimer.VERSION,
                            agreementSha256 = MembershipAgreementDisclaimer.SHA256,
                            regionalChapterId = null,
                        ).status.name,
                )
            }
            post("/test/leave") { call.respondText(registration(call).leaveMembership().status.name) }
            post("/test/status/{id}") {
                val service = memberServiceForWritePathTests(call)
                val q = call.request.queryParameters
                val dto =
                    service.updateMemberStatus(
                        memberId = call.parameters["id"]!!,
                        newStatus = MemberStatus.valueOf(q["newStatus"]!!),
                        reason = q["reason"] ?: "Verlauf-Test",
                        dateOfDeath = q["dateOfDeath"]?.let { LocalDate.parse(it) },
                    )
                call.respondText(dto.status.name)
            }
        }

        fun <T> withApp(block: suspend (HttpClient) -> T) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { c, e -> c.respondText(e.message, status = HttpStatusCode.Unauthorized) }
                        exception<ForbiddenException> { c, e -> c.respondText(e.message, status = HttpStatusCode.Forbidden) }
                        exception<NotFoundException> { c, e -> c.respondText(e.message, status = HttpStatusCode.NotFound) }
                        exception<ConflictException> { c, e -> c.respondText(e.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { routes() }
                }
                block(client)
            }
        }

        test("registration lifecycle: registerApplication, approveApplication, leaveMembership each append one LIVE row") {
            withApp { client ->
                val admin = fixtures.member(role = AccountRole.ADMIN)
                val email = "history-lifecycle-${Uuid.random()}@example.org"
                client.post("/test/register?email=$email").status shouldBe HttpStatusCode.OK
                val id = idByEmail(email)
                chain(id) shouldBe listOf(Triple("APPLICATION", null, "LIVE"))

                client.post("/test/approve/$id") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.OK
                chain(id) shouldBe listOf(Triple("APPLICATION", null, "LIVE"), Triple("ACTIVE", "APPLICATION", "LIVE"))

                // a second approval of an already decided application is a conflict and writes nothing
                client.post("/test/approve/$id") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.Conflict
                chain(id).size shouldBe 2

                client.post("/test/leave") { header("X-Member-Id", id.toString()) }.status shouldBe HttpStatusCode.OK
                chain(id) shouldBe
                    listOf(
                        Triple("APPLICATION", null, "LIVE"),
                        Triple("ACTIVE", "APPLICATION", "LIVE"),
                        Triple("WITHDRAWN", "ACTIVE", "LIVE"),
                    )
                // leaving twice: not an active member any more -> conflict, no row
                client.post("/test/leave") { header("X-Member-Id", id.toString()) }.status shouldBe HttpStatusCode.Conflict
                chain(id).size shouldBe 3
                currentStatus(id) shouldBe MemberStatus.WITHDRAWN
            }
        }

        test("rejectApplication: a plain applicant ends REJECTED, a former FRIEND falls back to FRIEND") {
            withApp { client ->
                val admin = fixtures.member(role = AccountRole.ADMIN)
                val plain = "history-reject-${Uuid.random()}@example.org"
                client.post("/test/register?email=$plain").status shouldBe HttpStatusCode.OK
                val plainId = idByEmail(plain)
                client.post("/test/reject/$plainId") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.OK
                chain(plainId) shouldBe listOf(Triple("APPLICATION", null, "LIVE"), Triple("REJECTED", "APPLICATION", "LIVE"))

                val friend = "history-friend-${Uuid.random()}@example.org"
                client.post("/test/register-friend?email=$friend").status shouldBe HttpStatusCode.OK
                val friendId = idByEmail(friend)
                chain(friendId) shouldBe listOf(Triple("FRIEND", null, "LIVE"))
                client.post("/test/apply") { header("X-Member-Id", friendId.toString()) }.status shouldBe HttpStatusCode.OK
                client.post("/test/reject/$friendId") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.OK
                chain(friendId) shouldBe
                    listOf(Triple("FRIEND", null, "LIVE"), Triple("APPLICATION", "FRIEND", "LIVE"), Triple("FRIEND", "APPLICATION", "LIVE"))
                currentStatus(friendId) shouldBe MemberStatus.FRIEND
                // applying again as an APPLICATION-less member that is not a FRIEND any more is not possible; applying twice is a conflict
                client.post("/test/apply") { header("X-Member-Id", friendId.toString()) }.status shouldBe HttpStatusCode.OK
                client.post("/test/apply") { header("X-Member-Id", friendId.toString()) }.status shouldBe HttpStatusCode.Conflict
                chain(friendId).size shouldBe 4
            }
        }

        test("createMemberDirect starts the chain with ACTIVE; updateMemberStatus appends, a no-op and an illegal transition do not") {
            withApp { client ->
                val admin = fixtures.member(role = AccountRole.ADMIN)
                val email = "history-direct-${Uuid.random()}@example.org"
                val id =
                    Uuid.parse(
                        client.post("/test/create-direct?email=$email") { header("X-Member-Id", admin.toString()) }.bodyAsText(),
                    )
                createdIds += id
                chain(id) shouldBe listOf(Triple("ACTIVE", null, "LIVE"))

                client.post("/test/status/$id?newStatus=DONOR") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                chain(id) shouldBe listOf(Triple("ACTIVE", null, "LIVE"), Triple("DONOR", "ACTIVE", "LIVE"))

                // same status again: no row. APPLICATION as a target is not allowed: no row.
                val same = client.post("/test/status/$id?newStatus=DONOR") { header("X-Member-Id", admin.toString()) }.status
                (same == HttpStatusCode.OK || same == HttpStatusCode.Conflict) shouldBe true
                client.post("/test/status/$id?newStatus=APPLICATION") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
                chain(id).size shouldBe 2

                client
                    .post(
                        "/test/status/$id?newStatus=DECEASED&dateOfDeath=2026-01-02",
                    ) { header("X-Member-Id", admin.toString()) }
                    .status shouldBe
                    HttpStatusCode.OK
                chain(id).last() shouldBe Triple("DECEASED", "DONOR", "LIVE")
                currentStatus(id) shouldBe MemberStatus.DECEASED
            }
        }

        test("CSV import writes IMPORT rows from the start of the join day in the organization zone, one per member") {
            val db = TestDatabaseFactory.freshMigratedH2Database("history-csv-${Uuid.random()}")

            fun prepared(
                n: Int,
                status: MemberStatus,
                joined: LocalDate,
            ) = PreparedMember(
                recordNumber = n,
                externalReference = "P-$n",
                displayName = "Import $n",
                email = "history-import-$n@example.org",
                status = status,
                sourceStatus = "Mitglied",
                joinedAt = joined,
                street = null,
                postalCode = null,
                city = null,
                country = null,
                dateOfBirth = null,
                nationality = null,
            )

            val plan =
                MemberCsvPlan(
                    totalRecords = 2,
                    prepared =
                        listOf(
                            prepared(1, MemberStatus.ACTIVE, LocalDate(2019, 3, 15)),
                            prepared(2, MemberStatus.WITHDRAWN, LocalDate(2021, 7, 1)),
                        ),
                    skipped = emptyList(),
                )
            runImport(plan = plan, commit = true, database = db).insertedCount shouldBe 2
            transaction(db) {
                MemberTable.selectAll().forEach { member ->
                    val rows =
                        MemberStatusHistoryTable
                            .selectAll()
                            .where { MemberStatusHistoryTable.memberId eq member[MemberTable.id] }
                            .toList()
                    rows.size shouldBe 1
                    val row = rows.single()
                    row[MemberStatusHistoryTable.status] shouldBe member[MemberTable.status].name
                    row[MemberStatusHistoryTable.previousStatus] shouldBe null
                    row[MemberStatusHistoryTable.sourceKind] shouldBe "IMPORT"
                    (row[MemberStatusHistoryTable.recordedAt] != null) shouldBe true
                    // Europe/Berlin (the default zone of a fresh database): the start of the join day, expressed in UTC
                    val expected =
                        member[MemberTable.joinedAt].atStartOfDayIn(TimeZone.of("Europe/Berlin")).toLocalDateTime(TimeZone.UTC)
                    row[MemberStatusHistoryTable.effectiveFrom] shouldBe expected
                }
            }
        }

        test("first-admin bootstrap writes the ACTIVE row") {
            val db = TestDatabaseFactory.freshMigratedH2Database("history-bootstrap-${Uuid.random()}")
            val result =
                AdminBootstrap.bootstrapFirstAdmin(
                    displayName = "Erster Admin",
                    email = "history-first-admin@example.org",
                    rawPassword = "a-genuinely-strong-password-1",
                    database = db,
                )
            (result is AdminBootstrap.BootstrapFirstAdminResult.Success) shouldBe true
            transaction(db) {
                val id = MemberTable.selectAll().single()[MemberTable.id]
                val rows = MemberStatusHistoryTable.selectAll().where { MemberStatusHistoryTable.memberId eq id }.toList()
                rows.map { it[MemberStatusHistoryTable.status] } shouldBe listOf("ACTIVE")
                rows.single()[MemberStatusHistoryTable.sourceKind] shouldBe "LIVE"
            }
        }

        test("OIDC guest creation writes the GUEST row once; a second visit of the same guest adds nothing") {
            val issuer = "https://home-${Uuid.random()}.example"
            val claims =
                OidcGuestClaims(
                    issuer = issuer,
                    subject = "history-guest-${Uuid.random()}",
                    name = "Verlauf Gast",
                    picture = null,
                    preferredUsername = "gast",
                    homeserverUrl = issuer,
                    membershipStatus = "AKTIV",
                )
            val id = OidcGuestMemberStore.resolveOrCreateGuestMember(claims = claims, grantedScope = "openid profile_basic")
            createdIds += id
            chain(id) shouldBe listOf(Triple("GUEST", null, "LIVE"))
            OidcGuestMemberStore.resolveOrCreateGuestMember(claims = claims, grantedScope = "openid profile_basic") shouldBe id
            chain(id).size shouldBe 1
        }

        test("dev seed: every demo member has exactly one SEED row equal to its status") {
            DevSeedData.demoMembers.forEach { seed ->
                val rows = chain(seed.id)
                rows.size shouldBe 1
                rows.single().third shouldBe "SEED"
                rows.single().first shouldBe currentStatus(seed.id).name
            }
        }

        test(
            "staging seed: every seeded member has a SEED chain (the status timeline, back-dated, strictly ascending) whose last row equals its status",
        ) {
            val db = TestDatabaseFactory.freshMigratedH2Database("history-staging-${Uuid.random()}")
            StagingSeedData.seedWith(seedPassword = "ein-starkes-testpasswort", database = db)
            transaction(db) {
                MemberTable.selectAll().forEach { member ->
                    val rows =
                        MemberStatusHistoryTable
                            .selectAll()
                            .where { MemberStatusHistoryTable.memberId eq member[MemberTable.id] }
                            .orderBy(MemberStatusHistoryTable.effectiveFrom to SortOrder.ASC)
                            .toList()
                    (rows.isNotEmpty()) shouldBe true
                    rows.forEach { it[MemberStatusHistoryTable.sourceKind] shouldBe "SEED" }
                    rows.last()[MemberStatusHistoryTable.status] shouldBe member[MemberTable.status].name
                    // the first row has no predecessor, every later row points at the one before it
                    rows.first()[MemberStatusHistoryTable.previousStatus] shouldBe null
                    rows.zipWithNext().forEach { (before, after) ->
                        after[MemberStatusHistoryTable.effectiveFrom] shouldBeGreaterThan before[MemberStatusHistoryTable.effectiveFrom]
                        after[MemberStatusHistoryTable.previousStatus] shouldBe before[MemberStatusHistoryTable.status]
                    }
                }
                // V1.9.63: the history is real, not one flat row per member -- someone has more than one step
                (MemberStatusHistoryTable.selectAll().count() > MemberTable.selectAll().count()) shouldBe true
            }
        }

        test(
            "recordLocked: no row when the status is unchanged, previous_status is the predecessor, and only LIVE/IMPORT/SEED may be written",
        ) {
            val m = fixtures.member(role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)
            val now = kotlinx.datetime.LocalDateTime.parse("2026-05-01T10:00:00")
            transaction {
                MemberStatusHistory.recordLocked(
                    memberId = m,
                    newStatus = MemberStatus.ACTIVE,
                    now = now,
                    source = MemberStatusHistorySource.LIVE,
                ) shouldBe
                    true
                MemberStatusHistory.recordLocked(
                    memberId = m,
                    newStatus = MemberStatus.ACTIVE,
                    now = now,
                    source = MemberStatusHistorySource.LIVE,
                ) shouldBe
                    false
                MemberStatusHistory.recordLocked(
                    memberId = m,
                    newStatus = MemberStatus.DONOR,
                    now = now,
                    source = MemberStatusHistorySource.LIVE,
                ) shouldBe
                    true
                val rejected =
                    runCatching {
                        MemberStatusHistory.recordLocked(
                            memberId = m,
                            newStatus = MemberStatus.FRIEND,
                            now = now,
                            source = MemberStatusHistorySource.BACKFILL_AUDIT,
                        )
                    }
                (rejected.exceptionOrNull() is IllegalArgumentException) shouldBe true
            }
            chain(m) shouldBe listOf(Triple("ACTIVE", null, "LIVE"), Triple("DONOR", "ACTIVE", "LIVE"))
        }

        test("after every scenario the consistency check finds no mismatch among the members this test created") {
            val mismatching =
                createdIds.distinct().filter { id ->
                    transaction { MemberTable.selectAll().where { MemberTable.id eq id }.empty() }.not() &&
                        chain(id).lastOrNull()?.first != currentStatus(id).name
                }
            mismatching shouldBe emptyList()
            // and the whole-table check runs without error (its figure depends on what other specs left behind, so it is not asserted)
            transaction { MemberStatusHistoryConsistency.countMismatches() >= 0 } shouldBe true
        }
    })

private fun memberServiceForWritePathTests(call: ApplicationCall) =
    MemberService(
        call = call,
        passwordResetMailer = FakePasswordResetMailer(),
        adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
        smtpConfigState = SmtpConfigState.NotConfigured,
        adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
        adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
        adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
        memberCardIssueRateLimiter = FederationInboxRateLimiter(),
        memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(),
    )

private fun cleanUpWritePathMembers(ids: List<Uuid>) {
    if (ids.isEmpty()) return
    transaction {
        AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList ids }) { it[actorMemberId] = null }
        MemberStatusHistoryTable.deleteWhere { memberId inList ids }
        OidcGuestProfileTable.deleteWhere { OidcGuestProfileTable.memberId inList ids }
        SessionTable.deleteWhere { SessionTable.memberId inList ids }
        MembershipAgreementAcknowledgmentTable.deleteWhere { MembershipAgreementAcknowledgmentTable.memberId inList ids }
        FriendTermsAcknowledgmentTable.deleteWhere { FriendTermsAcknowledgmentTable.memberId inList ids }
        FriendEmailVerificationTokenTable.deleteWhere { FriendEmailVerificationTokenTable.memberId inList ids }
        AccountTable.deleteWhere { AccountTable.memberId inList ids }
        MemberTable.deleteWhere { MemberTable.id inList ids }
    }
}
