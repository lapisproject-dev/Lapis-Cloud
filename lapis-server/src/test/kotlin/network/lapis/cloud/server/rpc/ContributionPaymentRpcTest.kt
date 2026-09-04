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
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
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
import kotlin.uuid.Uuid

/**
 * Review Round 1 (2026-08-19) regression coverage for CRITICAL-1/CRITICAL-2: exercises the actual
 * RPC surface ([OrganizationSettingsService]/[ContributionService]) end to end, unlike
 * [ContributionPostingBridgeTest] (which calls [ContributionPostingBridge] directly and therefore
 * never exercised the broken `updateOrganizationSettings` write-set/read-mapper this file's first
 * test targets) and unlike [OrganizationSettingsServiceTest] (which never touched the three
 * payment-account fields at all before this wave).
 *
 * Same "own freshly created fixtures, throwaway routes calling the service classes directly, direct
 * `OrganizationSettingsTable`/`LedgerAccountTable` inserts for setup" house style
 * [ContributionPostingBridgeTest]/[OrganizationSettingsServiceTest] both already establish.
 */
class ContributionPaymentRpcTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentBankAccountId] = null
                    it[paymentFeeAccountId] = null
                    it[contributionIncomeAccountId] = null
                    // eventIncomeAccountId (Review MAJOR fix regression coverage below) -- must be
                    // reset BEFORE the LedgerAccountTable delete further down, same FK reasoning as
                    // the three fields above.
                    it[eventIncomeAccountId] = null
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                val journalEntryIds =
                    if (createdMemberIds.isNotEmpty()) {
                        JournalEntryTable.selectAll().where { JournalEntryTable.createdBy inList createdMemberIds }.map {
                            it[JournalEntryTable.id]
                        }
                    } else {
                        emptyList()
                    }
                if (journalEntryIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                }
                if (createdContributionIds.isNotEmpty()) {
                    ContributionTable.deleteWhere { ContributionTable.id inList createdContributionIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    // MemberTable.membershipTierId references MembershipTierTable -- must be deleted
                    // BEFORE the tiers below, or the FK constraint rejects the tier delete.
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                if (createdTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole,
            membershipTierId: Uuid? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "RPC-Fixture Mitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[MemberTable.membershipTierId] = membershipTierId
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

        fun createLedgerAccount(
            number: String,
            type: LedgerAccountType,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "RPC-Fixture Konto $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        /**
         * Security Round 1 (2026-08-19, MAJOR-1/SHOULD-1): a cash-register (Kassenbuch)
         * [LedgerAccountType.ASSET] account, for asserting that [OrganizationSettingsService
         * .updateOrganizationSettings] refuses to map one as a payment-account mapping target.
         */
        fun createCashRegisterLedgerAccount(number: String): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "RPC-Fixture Kasse $number"
                    it[accountClass] = 0
                    it[type] = LedgerAccountType.ASSET
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = true
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun createTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "RPC-Fixture Tarif ${id.toString().take(6)}"
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

        fun createOpenContribution(
            memberId: Uuid,
            tierId: Uuid,
            amountDue: BigDecimal,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[ContributionTable.amountDue] = amountDue
                    it[status] = ContributionStatus.OPEN
                    it[ContributionTable.createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[ContributionTable.memberId] = memberId
                    it[ContributionTable.membershipTierId] = tierId
                    it[dueDate] = LocalDate(2026, 1, 15)
                }
            }
            createdContributionIds += id
            return id
        }

        test("updateOrganizationSettings via the real RPC round-trips the payment account mapping (CRITICAL-1a/b)") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing { registerContributionPaymentTestRoutes() }
                }

                val adminId = createMember(email = "rpc-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val bankAccountId = createLedgerAccount(number = "R1${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val feeAccountId = createLedgerAccount(number = "R2${Uuid.random().toString().take(6)}", type = LedgerAccountType.EXPENSE)
                val incomeAccountId = createLedgerAccount(number = "R3${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)

                val updateResponse =
                    client.post(
                        "/test/org-settings/update?bankAccountId=$bankAccountId&feeAccountId=$feeAccountId&incomeAccountId=$incomeAccountId",
                    ) { header("X-Member-Id", adminId.toString()) }
                updateResponse.status shouldBe HttpStatusCode.OK
                // The update RESPONSE itself already reflects the fix (toOrganizationSettingsDto is
                // the same mapper both endpoints share) -- assert it here too, not only on a
                // follow-up GET, since CRITICAL-1 was specifically about this mapper never being
                // populated on read.
                updateResponse.bodyAsText() shouldBe "$bankAccountId:$feeAccountId:$incomeAccountId"

                val getResponse = client.get("/test/org-settings") { header("X-Member-Id", adminId.toString()) }
                getResponse.status shouldBe HttpStatusCode.OK
                getResponse.bodyAsText() shouldBe "$bankAccountId:$feeAccountId:$incomeAccountId"
            }
        }

        test(
            "markContributionPaid end-to-end through ContributionService posts a real journal entry " +
                "once the mapping is configured via the RPC (CRITICAL-1c)",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                    }
                    routing { registerContributionPaymentTestRoutes() }
                }

                val adminId = createMember(email = "rpc-admin2-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val tierId = createTier()
                val memberId =
                    createMember(email = "rpc-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER, membershipTierId = tierId)
                val treasurerId = createMember(email = "rpc-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val bankAccountId = createLedgerAccount(number = "R4${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "R5${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val contributionId = createOpenContribution(memberId = memberId, tierId = tierId, amountDue = BigDecimal("50.00"))

                client.post(
                    "/test/org-settings/update?bankAccountId=$bankAccountId&feeAccountId=&incomeAccountId=$incomeAccountId",
                ) { header("X-Member-Id", adminId.toString()) }

                val journalEntryCountBefore = transaction { JournalEntryTable.selectAll().count() }

                val markPaidResponse =
                    client.post("/test/contribution/mark-paid?contributionId=$contributionId&amount=50.00") {
                        header("X-Member-Id", treasurerId.toString())
                    }
                markPaidResponse.status shouldBe HttpStatusCode.OK
                markPaidResponse.bodyAsText() shouldBe "PAID"

                val journalEntryCountAfter = transaction { JournalEntryTable.selectAll().count() }
                journalEntryCountAfter shouldBe journalEntryCountBefore + 1L

                val postedAmount =
                    transaction {
                        (PostingTable innerJoin JournalEntryTable)
                            .selectAll()
                            .where { (PostingTable.ledgerAccountId eq bankAccountId) and (JournalEntryTable.createdBy eq treasurerId) }
                            .single()[PostingTable.amount]
                    }
                postedAmount.compareTo(BigDecimal("50.00")) shouldBe 0
            }
        }

        test(
            "markContributionPaid twice on the same contribution: second call throws ConflictException, " +
                "only ONE journal entry exists afterward (CRITICAL-2)",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerContributionPaymentTestRoutes() }
                }

                val adminId = createMember(email = "rpc-admin3-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val tierId = createTier()
                val memberId =
                    createMember(email = "rpc-member2-${Uuid.random()}@example.org", role = AccountRole.MEMBER, membershipTierId = tierId)
                val treasurerId = createMember(email = "rpc-treasurer2-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val bankAccountId = createLedgerAccount(number = "R6${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "R7${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val contributionId = createOpenContribution(memberId = memberId, tierId = tierId, amountDue = BigDecimal("50.00"))

                client.post(
                    "/test/org-settings/update?bankAccountId=$bankAccountId&feeAccountId=&incomeAccountId=$incomeAccountId",
                ) { header("X-Member-Id", adminId.toString()) }

                val firstCall =
                    client.post("/test/contribution/mark-paid?contributionId=$contributionId&amount=50.00") {
                        header("X-Member-Id", treasurerId.toString())
                    }
                firstCall.status shouldBe HttpStatusCode.OK

                val journalEntryCountAfterFirst = transaction { JournalEntryTable.selectAll().count() }

                val secondCall =
                    client.post("/test/contribution/mark-paid?contributionId=$contributionId&amount=50.00") {
                        header("X-Member-Id", treasurerId.toString())
                    }
                secondCall.status shouldBe HttpStatusCode.Conflict

                val journalEntryCountAfterSecond = transaction { JournalEntryTable.selectAll().count() }
                journalEntryCountAfterSecond shouldBe journalEntryCountAfterFirst
            }
        }

        test(
            "markContributionWaived on an already-PAID contribution throws ConflictException, status stays " +
                "PAID, and no phantom journal entry is created or reversed (Review Round 2, MAJOR)",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerContributionPaymentTestRoutes() }
                }

                val adminId = createMember(email = "rpc-admin4-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val tierId = createTier()
                val memberId =
                    createMember(email = "rpc-member3-${Uuid.random()}@example.org", role = AccountRole.MEMBER, membershipTierId = tierId)
                val treasurerId = createMember(email = "rpc-treasurer3-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val boardId = createMember(email = "rpc-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val bankAccountId = createLedgerAccount(number = "R8${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId = createLedgerAccount(number = "R9${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                val contributionId = createOpenContribution(memberId = memberId, tierId = tierId, amountDue = BigDecimal("50.00"))

                client.post(
                    "/test/org-settings/update?bankAccountId=$bankAccountId&feeAccountId=&incomeAccountId=$incomeAccountId",
                ) { header("X-Member-Id", adminId.toString()) }

                // Review Round 3 (2026-08-19, SHOULD-2): capture the count BEFORE mark-paid too, and
                // assert it actually increases by exactly one -- mirroring the sibling round-1
                // idempotency test above ("markContributionPaid end-to-end ... CRITICAL-1c"). Without
                // this, a regression in the account-mapping setup above would make the subsequent
                // "unchanged after waive attempt" assertion pass vacuously (0 -> 0 -> 0) while no
                // longer testing the orphaned-entry scenario this test exists for.
                val journalEntryCountBeforePaid = transaction { JournalEntryTable.selectAll().count() }

                val markPaidResponse =
                    client.post("/test/contribution/mark-paid?contributionId=$contributionId&amount=50.00") {
                        header("X-Member-Id", treasurerId.toString())
                    }
                markPaidResponse.status shouldBe HttpStatusCode.OK

                val journalEntryCountAfterPaid = transaction { JournalEntryTable.selectAll().count() }
                journalEntryCountAfterPaid shouldBe journalEntryCountBeforePaid + 1L

                val waiveResponse =
                    client.post("/test/contribution/mark-waived?contributionId=$contributionId") {
                        header("X-Member-Id", boardId.toString())
                    }
                waiveResponse.status shouldBe HttpStatusCode.Conflict

                val statusAfterWaiveAttempt =
                    transaction {
                        ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                    }
                statusAfterWaiveAttempt shouldBe ContributionStatus.PAID

                val journalEntryCountAfterWaiveAttempt = transaction { JournalEntryTable.selectAll().count() }
                journalEntryCountAfterWaiveAttempt shouldBe journalEntryCountAfterPaid
            }
        }

        test(
            "markContributionWaived on a never-paid OPEN contribution succeeds, status becomes WAIVED, " +
                "no journal entry is created (Review Round 3, 2026-08-19, SHOULD-2 -- legitimate path was " +
                "previously unpinned by any test in this file)",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerContributionPaymentTestRoutes() }
                }

                val tierId = createTier()
                val memberId =
                    createMember(email = "rpc-member4-${Uuid.random()}@example.org", role = AccountRole.MEMBER, membershipTierId = tierId)
                val boardId = createMember(email = "rpc-board2-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val contributionId = createOpenContribution(memberId = memberId, tierId = tierId, amountDue = BigDecimal("50.00"))

                val journalEntryCountBefore = transaction { JournalEntryTable.selectAll().count() }

                val waiveResponse =
                    client.post("/test/contribution/mark-waived?contributionId=$contributionId") {
                        header("X-Member-Id", boardId.toString())
                    }
                waiveResponse.status shouldBe HttpStatusCode.OK
                waiveResponse.bodyAsText() shouldBe "WAIVED"

                val statusAfterWaive =
                    transaction {
                        ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.single()[ContributionTable.status]
                    }
                statusAfterWaive shouldBe ContributionStatus.WAIVED

                val journalEntryCountAfter = transaction { JournalEntryTable.selectAll().count() }
                journalEntryCountAfter shouldBe journalEntryCountBefore
            }
        }

        test(
            "updateOrganizationSettings rejects a cash-register account, a wrong-typed account, an inactive " +
                "account, and a malformed id as a payment-account mapping target, leaving the existing mapping " +
                "unchanged (Security Round 1, 2026-08-19, MAJOR-1/SHOULD-1)",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                        exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerContributionPaymentTestRoutes() }
                }

                val adminId = createMember(email = "rpc-admin-should1-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val cashAccountId = createCashRegisterLedgerAccount(number = "RA${Uuid.random().toString().take(6)}")
                val wrongTypeAccountId =
                    createLedgerAccount(number = "RB${Uuid.random().toString().take(6)}", type = LedgerAccountType.EXPENSE)
                val inactiveAccountId =
                    createLedgerAccount(number = "RC${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                transaction { LedgerAccountTable.update({ LedgerAccountTable.id eq inactiveAccountId }) { it[active] = false } }

                val beforeAttempts = client.get("/test/org-settings") { header("X-Member-Id", adminId.toString()) }

                // paymentBankAccountId must be ASSET -- a cash-register account is ASSET-typed but
                // must still be rejected (isCashRegister=true).
                val cashRegisterAttempt =
                    client.post("/test/org-settings/update?bankAccountId=$cashAccountId&feeAccountId=&incomeAccountId=") {
                        header("X-Member-Id", adminId.toString())
                    }
                cashRegisterAttempt.status shouldBe HttpStatusCode.Conflict

                // paymentBankAccountId must be ASSET, not EXPENSE.
                val wrongTypeAttempt =
                    client.post("/test/org-settings/update?bankAccountId=$wrongTypeAccountId&feeAccountId=&incomeAccountId=") {
                        header("X-Member-Id", adminId.toString())
                    }
                wrongTypeAttempt.status shouldBe HttpStatusCode.Conflict

                val inactiveAttempt =
                    client.post("/test/org-settings/update?bankAccountId=$inactiveAccountId&feeAccountId=&incomeAccountId=") {
                        header("X-Member-Id", adminId.toString())
                    }
                inactiveAttempt.status shouldBe HttpStatusCode.Conflict

                val malformedAttempt =
                    client.post("/test/org-settings/update?bankAccountId=not-a-uuid&feeAccountId=&incomeAccountId=") {
                        header("X-Member-Id", adminId.toString())
                    }
                malformedAttempt.status shouldBe HttpStatusCode.NotFound

                // None of the four rejected attempts changed the existing mapping.
                val afterAttempts = client.get("/test/org-settings") { header("X-Member-Id", adminId.toString()) }
                afterAttempts.bodyAsText() shouldBe beforeAttempts.bodyAsText()
            }
        }

        test(
            "updateOrganizationSettings writes an ORGANIZATION_SETTINGS audit entry when the payment-account " +
                "mapping actually changes, and none when it does not (Security Round 1, 2026-08-19, MAJOR-2)",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing { registerContributionPaymentTestRoutes() }
                }

                val adminId = createMember(email = "rpc-admin-major2-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val bankAccountId = createLedgerAccount(number = "RD${Uuid.random().toString().take(6)}", type = LedgerAccountType.ASSET)
                val incomeAccountId =
                    createLedgerAccount(number = "RE${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)

                fun auditCountFor(memberId: Uuid): Long =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.actorMemberId eq memberId) and
                                    (AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS)
                            }.count()
                    }

                auditCountFor(adminId) shouldBe 0L

                // First call actually changes the mapping (null -> configured) -- one audit entry expected.
                client.post(
                    "/test/org-settings/update?bankAccountId=$bankAccountId&feeAccountId=&incomeAccountId=$incomeAccountId",
                ) { header("X-Member-Id", adminId.toString()) }
                auditCountFor(adminId) shouldBe 1L

                // Second call with the IDENTICAL mapping must NOT write a second entry -- see
                // updateOrganizationSettings KDoc "MAJOR-2" for why this method only audits an actual
                // mapping change, not every wholesale-replace call.
                client.post(
                    "/test/org-settings/update?bankAccountId=$bankAccountId&feeAccountId=&incomeAccountId=$incomeAccountId",
                ) { header("X-Member-Id", adminId.toString()) }
                auditCountFor(adminId) shouldBe 1L

                // Third call actually repoints the income account -- a new audit entry is expected again.
                val incomeAccountId2 =
                    createLedgerAccount(number = "RF${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)
                client.post(
                    "/test/org-settings/update?bankAccountId=$bankAccountId&feeAccountId=&incomeAccountId=$incomeAccountId2",
                ) { header("X-Member-Id", adminId.toString()) }
                auditCountFor(adminId) shouldBe 2L
            }
        }

        test(
            "updateOrganizationSettings round-trips eventIncomeAccountId/eventIncomeSphere via the real RPC " +
                "(Review MAJOR fix -- these two organization_settings columns existed since V18__events.sql " +
                "but had no write path anywhere in this codebase before this fix)",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerContributionPaymentTestRoutes() }
                }

                val adminId = createMember(email = "rpc-event-income-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val eventIncomeAccountId =
                    createLedgerAccount(number = "RG${Uuid.random().toString().take(6)}", type = LedgerAccountType.INCOME)

                val before = client.get("/test/org-settings/event-income") { header("X-Member-Id", adminId.toString()) }
                before.bodyAsText() shouldBe "null:ZWECKBETRIEB"

                val updateResponse =
                    client.post(
                        "/test/org-settings/update?bankAccountId=&feeAccountId=&incomeAccountId=&eventAccountId=$eventIncomeAccountId",
                    ) {
                        header("X-Member-Id", adminId.toString())
                    }
                updateResponse.status shouldBe HttpStatusCode.OK

                val after = client.get("/test/org-settings/event-income") { header("X-Member-Id", adminId.toString()) }
                after.bodyAsText() shouldBe "$eventIncomeAccountId:ZWECKBETRIEB"
            }
        }

        test(
            "updateOrganizationSettings rejects a non-INCOME LedgerAccount as eventIncomeAccountId, same MAJOR-1 " +
                "guard the three pre-existing mapping fields already get",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerContributionPaymentTestRoutes() }
                }

                val adminId =
                    createMember(email = "rpc-event-income-wrongtype-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val wrongTypeAccountId =
                    createLedgerAccount(number = "RH${Uuid.random().toString().take(6)}", type = LedgerAccountType.EXPENSE)

                // `organization_settings` is a process-wide singleton row (see `ORGANIZATION_SETTINGS_ID`
                // KDoc) shared by every test in this Spec -- captures whatever the PREVIOUS test left
                // behind rather than assuming an absolute "unset" baseline, same "before/after, not a
                // literal" idiom the pre-existing rejection test above already uses.
                val before = client.get("/test/org-settings/event-income") { header("X-Member-Id", adminId.toString()) }

                val response =
                    client.post(
                        "/test/org-settings/update?bankAccountId=&feeAccountId=&incomeAccountId=&eventAccountId=$wrongTypeAccountId",
                    ) {
                        header("X-Member-Id", adminId.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict

                val after = client.get("/test/org-settings/event-income") { header("X-Member-Id", adminId.toString()) }
                after.bodyAsText() shouldBe before.bodyAsText()
            }
        }
    })

/** Shared throwaway routes for [ContributionPaymentRpcTest]. */
private fun Route.registerContributionPaymentTestRoutes() {
    get("/test/org-settings") {
        val dto = OrganizationSettingsService(call).getOrganizationSettings()
        call.respondText("${dto.paymentBankAccountId}:${dto.paymentFeeAccountId}:${dto.contributionIncomeAccountId}")
    }
    // Review MAJOR fix regression coverage: eventIncomeAccountId/eventIncomeSphere are read back
    // via their OWN route (rather than folded into the response string above) so the pre-existing
    // exact-string-match assertions on `/test/org-settings`/`/test/org-settings/update` above stay
    // untouched.
    get("/test/org-settings/event-income") {
        val dto = OrganizationSettingsService(call).getOrganizationSettings()
        call.respondText("${dto.eventIncomeAccountId}:${dto.eventIncomeSphere}")
    }
    post("/test/org-settings/update") {
        val q = call.request.queryParameters
        val dto =
            OrganizationSettingsService(call).updateOrganizationSettings(
                OrganizationSettingsInput(
                    name = "RPC-Fixture Verein",
                    paymentBankAccountId = q["bankAccountId"]?.takeIf { it.isNotBlank() },
                    paymentFeeAccountId = q["feeAccountId"]?.takeIf { it.isNotBlank() },
                    contributionIncomeAccountId = q["incomeAccountId"]?.takeIf { it.isNotBlank() },
                    eventIncomeAccountId = q["eventAccountId"]?.takeIf { it.isNotBlank() },
                ),
            )
        call.respondText("${dto.paymentBankAccountId}:${dto.paymentFeeAccountId}:${dto.contributionIncomeAccountId}")
    }
    post("/test/contribution/mark-paid") {
        val q = call.request.queryParameters
        val contributionId = q["contributionId"]!!
        val amount = BigDecimal(q["amount"]!!)
        val now = DbClock.nowLocalDateTime()
        val dto =
            ContributionService(call).markContributionPaid(
                contributionId = contributionId,
                paidAt = now,
                paidAmount = amount,
                note = null,
            )
        call.respondText(dto.status.name)
    }
    post("/test/contribution/mark-waived") {
        val q = call.request.queryParameters
        val contributionId = q["contributionId"]!!
        val dto = ContributionService(call).markContributionWaived(contributionId = contributionId, note = null)
        call.respondText(dto.status.name)
    }
}
