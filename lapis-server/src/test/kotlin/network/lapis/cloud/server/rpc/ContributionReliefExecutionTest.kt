package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ContributionReliefRequestTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionReliefSnapshot
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.MemberStatus
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

private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" -- execution outcomes (T-8) and the APPROVED-with-
 * executionError / retry cycle (T-10) for [ContributionReliefExecution] via
 * [ContributionReliefService.decideReliefRequest]/[ContributionReliefService.retryReliefExecution].
 */
class ContributionReliefExecutionTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                ContributionReliefRequestTable.deleteWhere { subjectMemberId inList createdMemberIds }
                ContributionTable.deleteWhere { memberId inList createdMemberIds }
                // MEMBER self-requests write AuditLogEntryTable rows with actorMemberId = the
                // fresh member itself -- fk_audit_log_entry_actor_member_id would otherwise block
                // the MemberTable delete below. Same "null the actor reference, never delete the
                // append-only row" idiom MemberFamilyServiceTest's own afterSpec establishes.
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                    it[actorMemberId] = null
                }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
                MembershipTierTable.deleteWhere { id inList createdTierIds }
            }
        }

        fun newTier(amount: BigDecimal = BigDecimal("100.00")): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "Execution-Testtarif-$id"
                    it[description] = "Nur fuer ContributionReliefExecutionTest"
                    it[contributionAmount] = amount
                    it[billingInterval] = BillingInterval.MONTHLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
            }
            createdTierIds += id
            return id
        }

        fun newMember(tierId: Uuid?): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Execution Testmitglied"
                    it[email] = "relief-exec-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = tierId
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun newContribution(
            memberId: Uuid,
            tierId: Uuid,
            dueDate: LocalDate,
            status: ContributionStatus = ContributionStatus.OPEN,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = tierId
                    it[periodStart] = dueDate
                    it[periodEnd] = dueDate
                    it[amountDue] = BigDecimal("100.00")
                    it[ContributionTable.status] = status
                    it[createdAt] = LocalDateTime(2027, 1, 1, 0, 0)
                    it[ContributionTable.dueDate] = dueDate
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                }
            }
            return id
        }

        fun contributionStatus(id: Uuid): ContributionStatus =
            transaction { ContributionTable.selectAll().where { ContributionTable.id eq id }.single()[ContributionTable.status] }

        fun setContributionStatus(
            id: Uuid,
            status: ContributionStatus,
        ) {
            transaction { ContributionTable.update({ ContributionTable.id eq id }) { it[ContributionTable.status] = status } }
        }

        test("DEFERRAL execution flips a previously-OVERDUE contribution back to OPEN when the new due date is in the future") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, LocalDate(2026, 1, 1), status = ContributionStatus.OVERDUE)

                val requestId =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")[0]
                client.post("/test/relief/decide?id=$requestId&approve=true&note=Ok") { header("X-Member-Id", BOARD_ID) }

                contributionStatus(contributionId) shouldBe ContributionStatus.OPEN
            }
        }

        test(
            "decideReliefRequest(approve=true) on a DEFERRAL writes a CONTRIBUTION_RELIEF_REQUEST audit entry " +
                "whose 'after' snapshot correctly carries BOTH the old and the new due date (Review fix: " +
                "previousDueDate/newDueDate used to be swapped -- the requested date landed in previousDueDate " +
                "and newDueDate stayed null, so the GoBD audit trail never recorded the actual old due date)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, LocalDate(2027, 3, 1), status = ContributionStatus.OPEN)

                val requestId =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")[0]
                client.post("/test/relief/decide?id=$requestId&approve=true&note=Ok") { header("X-Member-Id", BOARD_ID) }

                val afterSnapshotJson =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.CONTRIBUTION_RELIEF_REQUEST) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(requestId)) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.single()[AuditLogEntryTable.afterSnapshot]
                    }
                val snapshot = Json.decodeFromString(ContributionReliefSnapshot.serializer(), afterSnapshotJson!!)
                snapshot.status shouldBe ContributionReliefStatus.EXECUTED
                snapshot.previousDueDate shouldBe LocalDate(2027, 3, 1)
                snapshot.newDueDate shouldBe LocalDate(2027, 6, 1)
            }
        }

        test("REDUCTION execution writes a MemberMembershipTierSnapshot MEMBER audit entry via MembershipTierAssignment") {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val expensiveTier = newTier(amount = BigDecimal("100.00"))
                val cheapTier = newTier(amount = BigDecimal("40.00"))
                val memberId = newMember(expensiveTier)

                val requestId =
                    client
                        .post("/test/relief/request?subjectMemberId=$memberId&kind=REDUCTION&reason=OTHER&targetTierId=$cheapTier") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                client.post("/test/relief/decide?id=$requestId&approve=true&note=Ok") { header("X-Member-Id", BOARD_ID) }

                val count =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and (AuditLogEntryTable.entityId eq memberId)
                            }.count()
                    }
                (count > 0) shouldBe true
            }
        }

        test(
            "REDUCTION execution rechecks the target tier under lock: a tier change to the SAME tier between " +
                "request and decision fails execution instead of applying a no-op via the stale precheck (Review " +
                "fix: executeReduction used to skip both of validatePayload's fachlich preconditions entirely)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val expensiveTier = newTier(amount = BigDecimal("100.00"))
                val cheapTier = newTier(amount = BigDecimal("40.00"))
                val memberId = newMember(expensiveTier)

                val requestId =
                    client
                        .post("/test/relief/request?subjectMemberId=$memberId&kind=REDUCTION&reason=OTHER&targetTierId=$cheapTier") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]

                // Zwischen Antrag und Entscheidung: ein ADMIN versetzt das Mitglied bereits auf die
                // beantragte Zielstufe (z. B. ueber einen anderen Kanal) -- der Antrag ist jetzt ein No-op.
                transaction { MemberTable.update({ MemberTable.id eq memberId }) { it[membershipTierId] = cheapTier } }

                val decided =
                    client
                        .post("/test/relief/decide?id=$requestId&approve=true&note=Ok") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[3] shouldBe ContributionReliefStatus.APPROVED.name
                decided[16] shouldBe "target_tier_already_current" // executionError
                transaction {
                    MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.membershipTierId]
                } shouldBe cheapTier
            }
        }

        test(
            "REDUCTION execution rechecks the target tier under lock: a tier change to something CHEAPER than the " +
                "target between request and decision fails execution rather than raising the member's contribution",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val expensiveTier = newTier(amount = BigDecimal("100.00"))
                val targetTier = newTier(amount = BigDecimal("50.00"))
                val familyTier = newTier(amount = BigDecimal("20.00"))
                val memberId = newMember(expensiveTier)

                val requestId =
                    client
                        .post("/test/relief/request?subjectMemberId=$memberId&kind=REDUCTION&reason=OTHER&targetTierId=$targetTier") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]

                // Zwischen Antrag und Entscheidung: ein ADMIN versetzt das Mitglied auf eine noch
                // guenstigere Stufe (z. B. Familienmitgliedschaft) -- die beantragte Zielstufe waere
                // jetzt eine Verteuerung, keine Ermaessigung mehr.
                transaction { MemberTable.update({ MemberTable.id eq memberId }) { it[membershipTierId] = familyTier } }

                val decided =
                    client
                        .post("/test/relief/decide?id=$requestId&approve=true&note=Ok") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[3] shouldBe ContributionReliefStatus.APPROVED.name
                decided[16] shouldBe "target_tier_not_cheaper_than_current" // executionError
                transaction {
                    MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.membershipTierId]
                } shouldBe familyTier // unchanged -- the member was NOT switched to the more expensive target
            }
        }

        test(
            "REDUCTION execution rechecks the target tier under lock: the member becomes a family DEPENDENT " +
                "(membershipTierId -> null) between request and decision fails execution instead of assigning " +
                "them a tier and billing them directly IN ADDITION to the family invoice (Review fix: " +
                "`currentTierId != null` used to hide this recheck entirely for a null current tier)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val expensiveTier = newTier(amount = BigDecimal("100.00"))
                val cheapTier = newTier(amount = BigDecimal("40.00"))
                val memberId = newMember(expensiveTier)

                val requestId =
                    client
                        .post("/test/relief/request?subjectMemberId=$memberId&kind=REDUCTION&reason=OTHER&targetTierId=$cheapTier") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]

                // Zwischen Antrag und Entscheidung: das Mitglied wird Familien-Angehoeriger und
                // dadurch tier-los (ueber den Zahler der Familie abgerechnet, siehe
                // MemberFamilyService.addFamilyMember).
                transaction { MemberTable.update({ MemberTable.id eq memberId }) { it[membershipTierId] = null } }

                val decided =
                    client
                        .post("/test/relief/decide?id=$requestId&approve=true&note=Ok") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[3] shouldBe ContributionReliefStatus.APPROVED.name
                decided[16] shouldBe "member_has_no_tier_of_their_own" // executionError
                transaction {
                    MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.membershipTierId]
                } shouldBe null // unchanged -- the member was NOT assigned a tier of their own
            }
        }

        test(
            "T-10: stunning a PAID contribution fails execution (APPROVED+executionError, dueDate unchanged), then a later retry after it becomes OPEN again succeeds",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, LocalDate(2027, 1, 1), status = ContributionStatus.OPEN)

                val requestId =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")[0]

                // Zwischen Antrag und Entscheidung: die Beitragszeile wird bezahlt -- nicht mehr stundbar.
                setContributionStatus(contributionId, ContributionStatus.PAID)

                val decided =
                    client
                        .post("/test/relief/decide?id=$requestId&approve=true&note=Ok") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .split("|")
                decided[3] shouldBe ContributionReliefStatus.APPROVED.name
                decided[16] shouldBe "contribution_not_deferrable:PAID" // executionError
                contributionStatus(contributionId) shouldBe ContributionStatus.PAID // unchanged

                // Der Beitrag wird (Testfixture) wieder OPEN -- ein Retry darf jetzt durchgehen.
                setContributionStatus(contributionId, ContributionStatus.OPEN)
                val retried = client.post("/test/relief/retry?id=$requestId") { header("X-Member-Id", BOARD_ID) }.bodyAsText().split("|")
                retried[3] shouldBe ContributionReliefStatus.EXECUTED.name
                contributionStatus(contributionId) shouldBe ContributionStatus.OPEN
            }
        }

        test(
            "retryReliefExecution's Failed branch writes an audit entry whose snapshot actually carries the " +
                "executionError (Review fix: previously before/after were byte-identical for a repeated failed " +
                "retry -- APPROVED -> APPROVED, and ContributionReliefSnapshot did not carry executionError at " +
                "all -- making every retry attempt indistinguishable from a no-op audit event)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installContributionReliefExceptionHandlers() }
                    routing { registerContributionReliefTestRoutes() }
                }
                val tierId = newTier()
                val memberId = newMember(tierId)
                val contributionId = newContribution(memberId, tierId, LocalDate(2027, 1, 1), status = ContributionStatus.OPEN)

                val requestId =
                    client
                        .post(
                            "/test/relief/request?subjectMemberId=$memberId&kind=DEFERRAL&reason=OTHER" +
                                "&contributionId=$contributionId&newDueDate=2027-06-01",
                        ) { header("X-Member-Id", memberId.toString()) }
                        .bodyAsText()
                        .split("|")[0]

                setContributionStatus(contributionId, ContributionStatus.PAID)
                client.post("/test/relief/decide?id=$requestId&approve=true&note=Ok") { header("X-Member-Id", BOARD_ID) }

                // Still PAID -- this retry fails again, for the SAME reason as the initial decide.
                client.post("/test/relief/retry?id=$requestId") { header("X-Member-Id", BOARD_ID) }

                val afterSnapshotJsons =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.CONTRIBUTION_RELIEF_REQUEST) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(requestId)) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.orderBy(AuditLogEntryTable.occurredAt)
                            .map { it[AuditLogEntryTable.afterSnapshot]!! }
                    }
                // One UPDATE entry from decide's own Failed branch, one from the retry's Failed branch.
                afterSnapshotJsons.size shouldBe 2
                afterSnapshotJsons.forEach { json ->
                    val snapshot = Json.decodeFromString(ContributionReliefSnapshot.serializer(), json)
                    snapshot.status shouldBe ContributionReliefStatus.APPROVED
                    snapshot.executionError shouldBe "contribution_not_deferrable:PAID"
                }
            }
        }
    })
