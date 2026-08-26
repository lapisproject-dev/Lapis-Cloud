package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.DunningComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.DunningNoticeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.payment.dunning.DunningConfig
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DunningNoticeStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.2.7 "Automatisiertes Mahnwesen". RPC-level regression coverage for [DunningService],
 * mirrors [SepaServiceTest]'s own house style: `testApplication` + a plain-Ktor
 * `registerDunningTestRoutes` shim over the real service (kilua-rpc's own generated dispatch is not
 * exercised here, same trade-off [SepaServiceTest] already documents).
 */
class DunningServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdLevelIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                val noticeIds =
                    DunningNoticeTable
                        .selectAll()
                        .where {
                            DunningNoticeTable.contributionId inList createdContributionIds
                        }.map { it[DunningNoticeTable.id] }
                val documentIds =
                    DunningNoticeTable
                        .selectAll()
                        .where {
                            DunningNoticeTable.id inList noticeIds
                        }.mapNotNull { it[DunningNoticeTable.documentId] }
                if (noticeIds.isNotEmpty()) DunningNoticeTable.deleteWhere { DunningNoticeTable.id inList noticeIds }
                if (documentIds.isNotEmpty()) {
                    DocumentVersionTable.deleteWhere { DocumentVersionTable.documentId inList documentIds }
                    DocumentTable.deleteWhere { DocumentTable.id inList documentIds }
                }
                if (createdContributionIds.isNotEmpty()) {
                    ContributionTable.deleteWhere {
                        ContributionTable.id inList createdContributionIds
                    }
                }
                if (createdLevelIds.isNotEmpty()) DunningLevelTable.deleteWhere { DunningLevelTable.id inList createdLevelIds }
                DocumentFolderTable.deleteWhere { DocumentFolderTable.name eq "Mahnungen" }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                    DunningComplianceAcknowledgmentTable.deleteWhere {
                        DunningComplianceAcknowledgmentTable.acknowledgedByMemberId inList
                            createdMemberIds
                    }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                if (createdTierIds.isNotEmpty()) MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                OrganizationSettingsTable.update(
                    { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID },
                ) { it[dunningEnabled] = false }
            }
            createdMemberIds.clear()
            createdTierIds.clear()
            createdContributionIds.clear()
            createdLevelIds.clear()
        }

        fun createTestMember(
            email: String,
            role: AccountRole = AccountRole.ADMIN,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "DunningService Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[street] = "Teststr. 1"
                    it[postalCode] = "38100"
                    it[city] = "Braunschweig"
                    it[country] = "DE"
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

        fun createTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "DunningService Fixture Tarif ${id.toString().take(6)}"
                    it[description] = "Test-Tarif"
                    it[contributionAmount] = BigDecimal("50.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
            }
            createdTierIds += id
            return id
        }

        // uq_contribution_member_tier_period forbids two contribution rows for the same
        // (member, tier, period) tuple -- periodStart is varied per call via [periodCounter] so
        // tests creating several contributions for the SAME member+tier (e.g. the rate-limiter
        // test) never collide.
        val periodCounter =
            java.util.concurrent.atomic
                .AtomicInteger(0)

        fun createContribution(
            memberId: Uuid,
            tierId: Uuid,
            status: ContributionStatus = ContributionStatus.OVERDUE,
            dueDate: LocalDate = LocalDate(2026, 1, 1),
        ): Uuid {
            val id = Uuid.random()
            val periodIndex = periodCounter.getAndIncrement()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[periodStart] = LocalDate(2020, 1, 1).plus(periodIndex, kotlinx.datetime.DateTimeUnit.MONTH)
                    it[periodEnd] = LocalDate(2020, 1, 1).plus(periodIndex + 1, kotlinx.datetime.DateTimeUnit.MONTH)
                    it[amountDue] = BigDecimal("50.00")
                    it[ContributionTable.status] = status
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = tierId
                    it[ContributionTable.dueDate] = dueDate
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                    it[sepaMandateId] = null
                    it[paidAt] = null
                    it[paidAmount] = null
                    it[note] = null
                }
            }
            createdContributionIds += id
            return id
        }

        fun createLevel(
            levelNumber: Int,
            graceDays: Int = 1,
            responseDays: Int = 14,
            feeAmount: BigDecimal? = if (levelNumber == 1) null else BigDecimal("5.00"),
        ): Uuid {
            val id = Uuid.random()
            transaction {
                DunningLevelTable.insert {
                    it[DunningLevelTable.id] = id
                    it[DunningLevelTable.levelNumber] = levelNumber
                    it[name] = "Stufe $levelNumber"
                    it[DunningLevelTable.graceDays] = graceDays
                    it[DunningLevelTable.responseDays] = responseDays
                    it[DunningLevelTable.feeAmount] = feeAmount
                    it[active] = true
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdLevelIds += id
            return id
        }

        fun enableDunningForOrg(ackByMemberId: Uuid) {
            transaction {
                DunningComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = ackByMemberId
                    it[acknowledgedAt] = DbClock.nowLocalDateTime()
                    it[disclaimerVersion] = DunningComplianceDisclaimer.VERSION
                    it[disclaimerSha256] = DunningComplianceDisclaimer.SHA256
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) { it[dunningEnabled] = true }
            }
        }

        test(
            "role matrix: MEMBER is forbidden everywhere, BOARD can read but not write, TREASURER can write but not configure levels, ADMIN can do everything",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-role-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val treasurer = createTestMember("dun-role-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val board = createTestMember("dun-role-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val member = createTestMember("dun-role-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableDunningForOrg(admin)
                createLevel(1)
                val tierId = createTier()
                val contributionId = createContribution(member, tierId)

                client.get("/test/dunning/cases") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/dunning/cases") { header("X-Member-Id", board.toString()) }.status shouldBe HttpStatusCode.OK
                client
                    .post(
                        "/test/dunning/issue?contributionId=$contributionId",
                    ) { header("X-Member-Id", board.toString()) }
                    .status shouldBe
                    HttpStatusCode.Forbidden
                client
                    .post(
                        "/test/dunning/issue?contributionId=$contributionId",
                    ) { header("X-Member-Id", treasurer.toString()) }
                    .status shouldBe
                    HttpStatusCode.OK
                createdContributionIds += contributionId

                client
                    .post("/test/dunning/create-level?levelNumber=9&name=X&graceDays=1&responseDays=1") {
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/dunning/create-level?levelNumber=9&name=X&graceDays=1&responseDays=1") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "enableDunning: wrong disclaimer version/sha256 -> Conflict, dunning_enabled stays false; correct pair -> enabled + acknowledgment row",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-disclaimer-${Uuid.random()}@example.org", role = AccountRole.ADMIN)

                val wrong =
                    client.post("/test/dunning/enable?version=bogus&sha256=${"0".repeat(64)}") { header("X-Member-Id", admin.toString()) }
                wrong.status shouldBe HttpStatusCode.Conflict
                transaction {
                    OrganizationSettingsTable
                        .selectAll()
                        .where {
                            OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID
                        }.single()[OrganizationSettingsTable.dunningEnabled]
                } shouldBe false

                val right =
                    client.post(
                        "/test/dunning/enable?version=${DunningComplianceDisclaimer.VERSION}&sha256=${DunningComplianceDisclaimer.SHA256}",
                    ) { header("X-Member-Id", admin.toString()) }
                right.status shouldBe HttpStatusCode.OK
                right.bodyAsText().split("|")[0] shouldBe "true"
            }
        }

        test(
            "createDunningLevel validation: duplicate levelNumber, graceDays=0, responseDays=400, feeAmount=-1, feeAmount=99.99, fee on level 1 -> each Conflict, no row inserted",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-level-validate-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                createLevel(1)

                client
                    .post("/test/dunning/create-level?levelNumber=1&name=Dup&graceDays=1&responseDays=1") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post("/test/dunning/create-level?levelNumber=2&name=X&graceDays=0&responseDays=1") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post("/test/dunning/create-level?levelNumber=2&name=X&graceDays=1&responseDays=400") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post("/test/dunning/create-level?levelNumber=2&name=X&graceDays=1&responseDays=1&feeAmount=-1") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post("/test/dunning/create-level?levelNumber=2&name=X&graceDays=1&responseDays=1&feeAmount=99.99") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post("/test/dunning/create-level?levelNumber=1&name=X&graceDays=1&responseDays=1&feeAmount=1.00") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                // Security review LOW finding -- levelNumber itself was never validated (see
                // `DunningService.validateLevelInput`'s own KDoc: an active level with
                // levelNumber <= 0 counts towards `requireDunningUsable`'s "at least one active
                // level" check but can never be selected as `nextLevel`, a silent config dead end).
                // levelNumber=0 and levelNumber > MAX_LEVEL_NUMBER (1000) must both be rejected.
                client
                    .post("/test/dunning/create-level?levelNumber=0&name=X&graceDays=1&responseDays=1") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post("/test/dunning/create-level?levelNumber=-1&name=X&graceDays=1&responseDays=1") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post("/test/dunning/create-level?levelNumber=1001&name=X&graceDays=1&responseDays=1") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.Conflict

                transaction { DunningLevelTable.selectAll().where { DunningLevelTable.levelNumber eq 2 }.count() } shouldBe 0L
            }
        }

        test("createDunningLevel: a successful creation writes an ORGANIZATION_SETTINGS audit CREATE entry") {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-level-audit-${Uuid.random()}@example.org", role = AccountRole.ADMIN)

                val response =
                    client.post("/test/dunning/create-level?levelNumber=1&name=Zahlungserinnerung&graceDays=1&responseDays=1") {
                        header("X-Member-Id", admin.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                // Track the level created via the RPC route (unlike `createLevel()`, this path
                // does not self-register) so `afterEach` actually deletes it -- otherwise it
                // survives as an orphaned levelNumber=1 row and collides with every later test's
                // own `createLevel(1)` fixture call.
                createdLevelIds += Uuid.parse(response.bodyAsText())

                val auditCount =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.actorMemberId eq admin) and
                                    (AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS) and
                                    (AuditLogEntryTable.action eq AuditAction.CREATE)
                            }.count()
                    }
                (auditCount >= 1L) shouldBe true
            }
        }

        test("issueDunningNotice: PAID contribution -> Conflict, no row; dunning disabled -> Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-issue-paid-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("dun-issue-paid-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val tierId = createTier()
                val paidId = createContribution(member, tierId, status = ContributionStatus.PAID)

                val disabledAttempt =
                    client.post("/test/dunning/issue?contributionId=$paidId") { header("X-Member-Id", admin.toString()) }
                disabledAttempt.status shouldBe HttpStatusCode.Conflict

                enableDunningForOrg(admin)
                createLevel(1)
                val paidAttempt =
                    client.post("/test/dunning/issue?contributionId=$paidId") { header("X-Member-Id", admin.toString()) }
                paidAttempt.status shouldBe HttpStatusCode.Conflict
                transaction { DunningNoticeTable.selectAll().where { DunningNoticeTable.contributionId eq paidId }.count() } shouldBe 0L
            }
        }

        test("skipDunningLevel then real issuance takes the FOLLOWING level") {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-skip-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("dun-skip-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableDunningForOrg(admin)
                createLevel(1)
                createLevel(2, feeAmount = BigDecimal("5.00"))
                val tierId = createTier()
                val contributionId = createContribution(member, tierId)

                client
                    .post("/test/dunning/skip?contributionId=$contributionId&reason=Kulanz") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.OK
                val issueResponse =
                    client.post("/test/dunning/issue?contributionId=$contributionId") { header("X-Member-Id", admin.toString()) }
                issueResponse.status shouldBe HttpStatusCode.OK

                val notices =
                    transaction { DunningNoticeTable.selectAll().where { DunningNoticeTable.contributionId eq contributionId }.toList() }
                notices.size shouldBe 2
                notices.map { it[DunningNoticeTable.levelNumber] }.sorted() shouldBe listOf(1, 2)
            }
        }

        test("resetDunning: all notices CANCELLED, contribution back to OVERDUE; next issuance starts a NEW cycle at level 1") {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-reset-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("dun-reset-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableDunningForOrg(admin)
                createLevel(1)
                val tierId = createTier()
                val contributionId = createContribution(member, tierId)

                client.post("/test/dunning/issue?contributionId=$contributionId") { header("X-Member-Id", admin.toString()) }
                client
                    .post("/test/dunning/reset?contributionId=$contributionId&reason=Irrtum") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.OK

                transaction {
                    ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                } shouldBe ContributionStatus.OVERDUE
                val allCancelled =
                    transaction {
                        DunningNoticeTable.selectAll().where { DunningNoticeTable.contributionId eq contributionId }.all {
                            it[DunningNoticeTable.status].name ==
                                "CANCELLED"
                        }
                    }
                allCancelled shouldBe true

                client
                    .post(
                        "/test/dunning/issue?contributionId=$contributionId",
                    ) { header("X-Member-Id", admin.toString()) }
                    .status shouldBe
                    HttpStatusCode.OK
                val newNotice =
                    transaction {
                        DunningNoticeTable
                            .selectAll()
                            .where {
                                DunningNoticeTable.contributionId eq contributionId
                            }.toList()
                            .maxByOrNull { it[DunningNoticeTable.cycleNumber] }
                    }!!
                newNotice[DunningNoticeTable.cycleNumber] shouldBe 2
                newNotice[DunningNoticeTable.levelNumber] shouldBe 1
            }
        }

        test("resetDunning: PAID contribution -> Conflict, dunning history and contribution status untouched") {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-reset-paid-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("dun-reset-paid-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableDunningForOrg(admin)
                createLevel(1)
                val tierId = createTier()
                val contributionId = createContribution(member, tierId)

                client.post("/test/dunning/issue?contributionId=$contributionId") { header("X-Member-Id", admin.toString()) }
                transaction {
                    ContributionTable.update({ ContributionTable.id eq contributionId }) { it[status] = ContributionStatus.PAID }
                }

                client
                    .post("/test/dunning/reset?contributionId=$contributionId&reason=Irrtum") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.Conflict

                transaction {
                    ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                } shouldBe ContributionStatus.PAID
                transaction {
                    DunningNoticeTable
                        .selectAll()
                        .where { DunningNoticeTable.contributionId eq contributionId }
                        .single()[DunningNoticeTable.status]
                        .name
                } shouldBe "ISSUED"
            }
        }

        test(
            "cancelDunningNotice: cancelling a lower-level notice while a higher one is live cancels the WHOLE cycle -- next issuance starts a fresh cycle instead of the ladder freezing",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-cancel-partial-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("dun-cancel-partial-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableDunningForOrg(admin)
                createLevel(1)
                createLevel(2, feeAmount = BigDecimal("5.00"))
                val tierId = createTier()
                val contributionId = createContribution(member, tierId)

                client.post("/test/dunning/issue?contributionId=$contributionId") { header("X-Member-Id", admin.toString()) }
                client.post("/test/dunning/issue?contributionId=$contributionId") { header("X-Member-Id", admin.toString()) }

                val level2NoticeId =
                    transaction {
                        DunningNoticeTable
                            .selectAll()
                            .where { DunningNoticeTable.contributionId eq contributionId }
                            .single { it[DunningNoticeTable.levelNumber] == 2 }[DunningNoticeTable.id]
                    }

                client
                    .post("/test/dunning/cancel-notice?noticeId=$level2NoticeId&reason=Falscher+Betrag") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.OK

                // BOTH notices of the cycle are CANCELLED now -- not just the targeted level-2 one --
                // so level 1's slot is never left ISSUED next to a CANCELLED level 2.
                transaction {
                    DunningNoticeTable.selectAll().where { DunningNoticeTable.contributionId eq contributionId }.all {
                        it[DunningNoticeTable.status].name == "CANCELLED"
                    }
                } shouldBe true

                // Re-issuing must succeed with a FRESH cycle at level 1 -- if `cancelDunningNotice`
                // had only cancelled the targeted level-2 row (the pre-fix behaviour), level 1 would
                // still be live, `currentCycleNumber()` would stay on the SAME cycle, and this
                // issuance would collide with the still-occupied level-2 slot forever.
                client
                    .post("/test/dunning/issue?contributionId=$contributionId") { header("X-Member-Id", admin.toString()) }
                    .status shouldBe HttpStatusCode.OK
                val newNotice =
                    transaction {
                        DunningNoticeTable
                            .selectAll()
                            .where { DunningNoticeTable.contributionId eq contributionId }
                            .toList()
                            .maxByOrNull { it[DunningNoticeTable.cycleNumber] }
                    }!!
                newNotice[DunningNoticeTable.cycleNumber] shouldBe 2
                newNotice[DunningNoticeTable.levelNumber] shouldBe 1
            }
        }

        test("cancelDunningNotice: cancelling the highest ISSUED notice falls the contribution back to OVERDUE") {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-cancel-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("dun-cancel-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableDunningForOrg(admin)
                createLevel(1)
                val tierId = createTier()
                val contributionId = createContribution(member, tierId)

                client.post("/test/dunning/issue?contributionId=$contributionId") { header("X-Member-Id", admin.toString()) }
                transaction {
                    ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                } shouldBe ContributionStatus.IN_DUNNING
                val noticeId =
                    transaction {
                        DunningNoticeTable
                            .selectAll()
                            .where {
                                DunningNoticeTable.contributionId eq contributionId
                            }.single()[DunningNoticeTable.id]
                    }

                client
                    .post("/test/dunning/cancel-notice?noticeId=$noticeId&reason=Fehler") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.OK

                transaction {
                    ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                } shouldBe ContributionStatus.OVERDUE
            }
        }

        test(
            "resetDunning rate limiter: 11th rapid reset within the window is rejected -- MAJOR security review finding (resetDunning re-arms the poller-driven letter path, so its own call rate must be budgeted too)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing {
                        registerDunningTestRoutes(
                            issueRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
                        )
                    }
                }
                val admin = createTestMember("dun-reset-rate-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("dun-reset-rate-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableDunningForOrg(admin)
                createLevel(1)
                val tierId = createTier()

                val statuses =
                    (1..11).map {
                        val cId = createContribution(member, tierId)
                        client
                            .post("/test/dunning/reset?contributionId=$cId&reason=Testlauf") {
                                header("X-Member-Id", admin.toString())
                            }.status
                    }
                statuses.last() shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "cancelDunningNotice rate limiter: 11th rapid cancel within the window is rejected -- same MAJOR finding as resetDunning's own rate limit",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing {
                        registerDunningTestRoutes(
                            issueRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
                        )
                    }
                }
                val admin = createTestMember("dun-cancel-rate-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("dun-cancel-rate-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableDunningForOrg(admin)
                createLevel(1)
                val tierId = createTier()

                // Rate-limited calls in this test are always the CANCEL call itself -- issuance
                // happens directly against the DB (bypassing the shared rate limiter, same as
                // `insertIssuedNotice` elsewhere) so only cancelDunningNotice's own budget is
                // exercised, not issueDunningNotice's.
                val statuses =
                    (1..11).map {
                        val cId = createContribution(member, tierId)
                        val noticeId = Uuid.random()
                        transaction {
                            DunningNoticeTable.insert {
                                it[id] = noticeId
                                it[DunningNoticeTable.contributionId] = cId
                                it[dunningLevelId] = createdLevelIds.first()
                                it[cycleNumber] = 1
                                it[levelNumber] = 1
                                it[levelName] = "Stufe 1"
                                it[feeAmount] = null
                                it[amountDue] = BigDecimal("50.00")
                                it[status] = DunningNoticeStatus.ISSUED
                                it[issuedAt] = DbClock.nowLocalDateTime()
                                it[respondBy] = LocalDate(2026, 2, 1)
                                it[documentId] = null
                                it[postalDeliveryLogId] = null
                                it[createdBy] = admin
                                it[cancelledAt] = null
                                it[cancellationReason] = null
                            }
                        }
                        client
                            .post("/test/dunning/cancel-notice?noticeId=$noticeId&reason=Testlauf") {
                                header("X-Member-Id", admin.toString())
                            }.status
                    }
                statuses.last() shouldBe HttpStatusCode.Conflict
            }
        }

        test("issue rate limiter: 11th rapid call within the window is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing {
                        registerDunningTestRoutes(
                            issueRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
                        )
                    }
                }
                val admin = createTestMember("dun-rate-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("dun-rate-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableDunningForOrg(admin)
                createLevel(1)
                val tierId = createTier()

                val statuses =
                    (1..11).map {
                        val cId = createContribution(member, tierId)
                        client.post("/test/dunning/issue?contributionId=$cId") { header("X-Member-Id", admin.toString()) }.status
                    }
                statuses.last() shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "getDunningCase: nextLevelDueOn after a whole-cycle cancellation is anchored on cancelledAt + graceDays, " +
                "NOT the naive (already past) dueDate -- security review LOW finding, toDunningCaseDto's own " +
                "third, previously-unfixed mirror of dunningReferenceDate",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-refdate-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("dun-refdate-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableDunningForOrg(admin)
                // graceDays = 14, dueDate far in the past -- exactly the shape the finding's own
                // failure scenario describes.
                createLevel(levelNumber = 1, graceDays = 14)
                val tierId = createTier()
                val contributionId = createContribution(member, tierId, dueDate = LocalDate(2026, 1, 1))

                // A whole-cycle-cancelled level-1 notice, the exact row shape
                // DunningService.resetDunning/cancelDunningNotice leaves behind -- cancelledAt is
                // well AFTER the long-past dueDate, which is the whole point: the naive
                // `?: dueDate` fallback this test guards against would anchor the window on the
                // original dueDate instead, showing the case as immediately due again.
                val noticeId = Uuid.random()
                transaction {
                    DunningNoticeTable.insert {
                        it[id] = noticeId
                        it[DunningNoticeTable.contributionId] = contributionId
                        it[dunningLevelId] = createdLevelIds.first()
                        it[cycleNumber] = 1
                        it[levelNumber] = 1
                        it[levelName] = "Stufe 1"
                        it[feeAmount] = null
                        it[amountDue] = BigDecimal("50.00")
                        it[status] = DunningNoticeStatus.CANCELLED
                        it[issuedAt] = LocalDateTime(2026, 1, 2, 9, 0)
                        it[respondBy] = LocalDate(2026, 1, 16)
                        it[documentId] = null
                        it[postalDeliveryLogId] = null
                        it[createdBy] = admin
                        it[cancelledAt] = LocalDateTime(2026, 3, 1, 9, 0)
                        it[cancellationReason] = "Irrtum (test fixture)"
                    }
                }

                val text =
                    client
                        .get("/test/dunning/case-next-level-due-on?contributionId=$contributionId") {
                            header("X-Member-Id", admin.toString())
                        }.bodyAsText()

                // cancelledAt.date (2026-03-01) + graceDays (14) = 2026-03-15 -- NOT
                // dueDate (2026-01-01) + graceDays = 2026-01-15, which is what the pre-fix `?:
                // dueDate` fallback would have shown here, weeks in the (test-fixture) past.
                text shouldBe "2026-03-15"
            }
        }

        test("getDunningCase: unknown contribution id -> empty list, not NotFoundException") {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val board = createTestMember("dun-getcase-${Uuid.random()}@example.org", role = AccountRole.BOARD)

                val response = client.get("/test/dunning/case?contributionId=${Uuid.random()}") { header("X-Member-Id", board.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe ""
            }
        }

        test(
            "listDunningCases: onlyOpen=true excludes a PAID-after-dunning contribution; onlyOpen=false's rewritten inSubQuery still surfaces it",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDunningExceptionHandlers() }
                    routing { registerDunningTestRoutes() }
                }
                val admin = createTestMember("dun-historic-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val member = createTestMember("dun-historic-target-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                enableDunningForOrg(admin)
                createLevel(1)
                val tierId = createTier()
                val contributionId = createContribution(member, tierId)

                client
                    .post("/test/dunning/issue?contributionId=$contributionId") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.OK
                // No longer DUNNABLE -- paid off after being dunned. Regression target: the former
                // `DunningNoticeTable.selectAll().map { contributionId }.toSet()` +
                // `ContributionTable.id inList historicIds` pair, now rewritten as a correlated
                // `inSubQuery` (see the security review finding this fixes) -- must still surface
                // exactly this contribution in the historical view.
                transaction {
                    ContributionTable.update({ ContributionTable.id eq contributionId }) { it[status] = ContributionStatus.PAID }
                }

                val openList =
                    client.get("/test/dunning/cases?onlyOpen=true") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                openList.contains(contributionId.toString()) shouldBe false

                val historicList =
                    client.get("/test/dunning/cases?onlyOpen=false") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                historicList.contains(contributionId.toString()) shouldBe true
            }
        }
    })

private fun Route.registerDunningTestRoutes(
    dunningConfig: DunningConfig = DunningConfig.load { null },
    issueRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
) {
    fun service(callCtx: io.ktor.server.application.ApplicationCall) =
        DunningService(call = callCtx, dunningConfig = dunningConfig, issueRateLimiter = issueRateLimiter)

    post("/test/dunning/enable") {
        val q = call.request.queryParameters
        val dto =
            service(call).enableDunning(
                network.lapis.cloud.shared.domain.DunningComplianceAcknowledgmentInput(
                    disclaimerVersion = q["version"]!!,
                    disclaimerSha256 = q["sha256"]!!,
                ),
            )
        call.respondText("${dto.dunningEnabled}")
    }
    post("/test/dunning/create-level") {
        val q = call.request.queryParameters
        val dto =
            service(call).createDunningLevel(
                network.lapis.cloud.shared.domain.DunningLevelInput(
                    levelNumber = q["levelNumber"]!!.toInt(),
                    name = q["name"]!!,
                    graceDays = q["graceDays"]!!.toInt(),
                    responseDays = q["responseDays"]!!.toInt(),
                    feeAmount = q["feeAmount"]?.let { java.math.BigDecimal(it) },
                ),
            )
        call.respondText(dto.id)
    }
    get("/test/dunning/cases") {
        val onlyOpen = call.request.queryParameters["onlyOpen"]?.toBooleanStrictOrNull() ?: true
        val dtos = service(call).listDunningCases(onlyOpen = onlyOpen)
        call.respondText(dtos.joinToString(";") { it.contributionId })
    }
    get("/test/dunning/case") {
        val dtos = service(call).getDunningCase(call.request.queryParameters["contributionId"]!!)
        call.respondText(dtos.joinToString(";") { it.case.contributionId })
    }
    // Security review LOW finding (round 5, `toDunningCaseDto`'s own `nextLevelDueOn` -- see
    // that function's comment): exposes the field the RPC-level [DunningCaseDto] itself does not
    // let this route's siblings introspect, so the regression test below can assert it directly
    // instead of only its downstream effects.
    get("/test/dunning/case-next-level-due-on") {
        val dtos = service(call).getDunningCase(call.request.queryParameters["contributionId"]!!)
        call.respondText(
            dtos
                .firstOrNull()
                ?.case
                ?.nextLevelDueOn
                ?.toString() ?: "null",
        )
    }
    post("/test/dunning/issue") {
        val dto = service(call).issueDunningNotice(call.request.queryParameters["contributionId"]!!)
        call.respondText(dto.case.contributionStatus.name)
    }
    post("/test/dunning/skip") {
        val q = call.request.queryParameters
        val dto = service(call).skipDunningLevel(contributionId = q["contributionId"]!!, reason = q["reason"]!!)
        call.respondText(dto.case.contributionStatus.name)
    }
    post("/test/dunning/reset") {
        val q = call.request.queryParameters
        val dto = service(call).resetDunning(contributionId = q["contributionId"]!!, reason = q["reason"]!!)
        call.respondText(dto.case.contributionStatus.name)
    }
    post("/test/dunning/cancel-notice") {
        val q = call.request.queryParameters
        val dto = service(call).cancelDunningNotice(noticeId = q["noticeId"]!!, reason = q["reason"]!!)
        call.respondText(dto.case.contributionStatus.name)
    }
}

private fun StatusPagesConfig.installDunningExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
}
