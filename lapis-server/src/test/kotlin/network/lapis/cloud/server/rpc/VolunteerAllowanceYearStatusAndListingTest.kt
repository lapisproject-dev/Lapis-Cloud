package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.VolunteerAllowancePaymentTable
import network.lapis.cloud.server.db.generated.VolunteerAllowanceSelfDeclarationTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- closes the review-flagged coverage gap
 * on [VolunteerAllowanceService.getYearStatus] (the IDOR gate + the negative-clamp on
 * `remainingInThisOrganization`, both previously entirely unexercised) and
 * [VolunteerAllowanceService.listPayments]/[VolunteerAllowanceService.listMyPayments] (the
 * `status=DRAFT` rejection, the `submittedAt IS NOT NULL` visibility gate, and the keyset
 * pagination cursor). Mirrors the other `VolunteerAllowance*Test` files' house style (throwaway
 * routes via [registerVolunteerAllowanceTestRoutes], pipe-string bodies).
 */
class VolunteerAllowanceYearStatusAndListingTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdPaymentIds = mutableListOf<Uuid>()
        val createdJournalEntryIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // FK order: payments (-> journal_entry, -> member) before journal entries before members.
                val paymentIds =
                    (
                        VolunteerAllowancePaymentTable
                            .selectAll()
                            .where {
                                (VolunteerAllowancePaymentTable.subjectMemberId inList createdMemberIds) or
                                    (VolunteerAllowancePaymentTable.requestedBy inList createdMemberIds)
                            }.map { it[VolunteerAllowancePaymentTable.id] } + createdPaymentIds
                    ).distinct()
                VolunteerAllowancePaymentTable.deleteWhere { VolunteerAllowancePaymentTable.id inList paymentIds }
                VolunteerAllowanceSelfDeclarationTable.deleteWhere {
                    VolunteerAllowanceSelfDeclarationTable.memberId inList createdMemberIds
                }
                if (createdJournalEntryIds.isNotEmpty()) {
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList createdJournalEntryIds }
                }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Year-Status Testmitglied"
                    it[email] = "volunteer-yearstatus-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
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

        /** Directly inserted (not via createDraft/submitPayment) so `submittedAt` is fully controlled. */
        fun insertPayment(
            subject: Uuid,
            status: VolunteerAllowancePaymentStatus,
            amount: BigDecimal = BigDecimal("50.00"),
            paymentDate: LocalDate = LocalDate(2026, 6, 1),
            submittedAt: LocalDateTime? = null,
            postedJournalEntryId: Uuid? = null,
        ): Uuid {
            val id = Uuid.random()
            val isDraftOrRequested =
                status == VolunteerAllowancePaymentStatus.DRAFT || status == VolunteerAllowancePaymentStatus.REQUESTED
            transaction {
                VolunteerAllowancePaymentTable.insert {
                    it[VolunteerAllowancePaymentTable.id] = id
                    it[subjectMemberId] = subject
                    it[category] = VolunteerAllowanceCategory.HONORARY
                    it[VolunteerAllowancePaymentTable.status] = status
                    it[VolunteerAllowancePaymentTable.amount] = amount
                    it[activityDescription] = "Vereinshelfer beim Sommerfest"
                    it[VolunteerAllowancePaymentTable.paymentDate] = paymentDate
                    it[createdAt] = submittedAt ?: DbClock.nowLocalDateTime()
                    it[requestedBy] = subject
                    it[VolunteerAllowancePaymentTable.submittedAt] = submittedAt
                    it[decisionNote] = if (isDraftOrRequested) null else "Genehmigt"
                    it[VolunteerAllowancePaymentTable.postedJournalEntryId] = postedJournalEntryId
                    it[priorTotalSnapshot] = if (isDraftOrRequested) null else BigDecimal("0.00")
                    it[freeAmountSnapshot] = if (isDraftOrRequested) null else amount
                    it[exceedingAmountSnapshot] = if (isDraftOrRequested) null else BigDecimal("0.00")
                }
            }
            createdPaymentIds += id
            return id
        }

        fun newFakeJournalEntry(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                JournalEntryTable.insert {
                    it[JournalEntryTable.id] = id
                    it[entryDate] = LocalDate(2026, 6, 1)
                    it[description] = "Test-Fixture"
                    it[voucherReference] = "TEST-FIXTURE-$id"
                    it[JournalEntryTable.createdBy] = createdBy
                    it[status] = JournalEntryStatus.POSTED
                    it[postedAt] = DbClock.nowLocalDateTime()
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[donorMemberId] = null
                    it[externalDonorId] = null
                    it[donorCategory] = null
                }
            }
            createdJournalEntryIds += id
            return id
        }

        // ── getYearStatus: IDOR gate ───────────────────────────────────────────────

        test("getYearStatus: a member may query their own year status, but not a foreign member's without BOARD/ADMIN") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                val other = newMember()

                client
                    .get("/test/volunteerallowance/year-status?memberId=$member&category=HONORARY&year=2026") {
                        header("X-Member-Id", member.toString())
                    }.status shouldBe HttpStatusCode.OK

                client
                    .get("/test/volunteerallowance/year-status?memberId=$member&category=HONORARY&year=2026") {
                        header("X-Member-Id", other.toString())
                    }.status shouldBe HttpStatusCode.Forbidden

                client
                    .get("/test/volunteerallowance/year-status?memberId=$member&category=HONORARY&year=2026") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe HttpStatusCode.OK
                client
                    .get("/test/volunteerallowance/year-status?memberId=$member&category=HONORARY&year=2026") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test("getYearStatus: remainingInThisOrganization clamps to 0.00, never negative, once the posted total exceeds the cap") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                val journalEntryId = newFakeJournalEntry(member)
                // HONORARY_ANNUAL_CAP_EUR is 960.00 -- 1200.00 EXECUTED overshoots it by 240.00.
                insertPayment(
                    member,
                    status = VolunteerAllowancePaymentStatus.EXECUTED,
                    amount = BigDecimal("1200.00"),
                    submittedAt = LocalDateTime(2026, 6, 1, 8, 0),
                    postedJournalEntryId = journalEntryId,
                )

                val body =
                    client
                        .get("/test/volunteerallowance/year-status?memberId=$member&category=HONORARY&year=2026") {
                            header("X-Member-Id", member.toString())
                        }.bodyAsText()
                        .split("|")
                body[0] shouldBe "960.00" // annualCap
                body[1] shouldBe "1200.00" // postedTotalInThisOrganization
                body[2] shouldBe "0.00" // remainingInThisOrganization -- clamped, not -240.00
            }
        }

        test("getYearStatus: an out-of-range calendarYear is rejected with BadRequestException, not an unhandled exception") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                client
                    .get("/test/volunteerallowance/year-status?memberId=$member&category=HONORARY&year=2147483647") {
                        header("X-Member-Id", member.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── listPayments ───────────────────────────────────────────────────────────

        test("listPayments: status=DRAFT is rejected -- a draft is private") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                client
                    .get("/test/volunteerallowance/list?status=DRAFT") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("listPayments: a DRAFT payment is never visible, even without a status filter") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                val draftId = insertPayment(member, status = VolunteerAllowancePaymentStatus.DRAFT, submittedAt = null)
                val requestedId =
                    insertPayment(
                        member,
                        status = VolunteerAllowancePaymentStatus.REQUESTED,
                        submittedAt = LocalDateTime(2026, 6, 1, 10, 0),
                    )

                val body = client.get("/test/volunteerallowance/list") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                body.contains(draftId.toString()) shouldBe false
                body.contains(requestedId.toString()) shouldBe true
            }
        }

        test("listPayments: keyset pagination (afterSubmittedAt/afterId) returns strictly the payments after the cursor") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                val t1 = LocalDateTime(2026, 6, 1, 8, 0)
                val t2 = LocalDateTime(2026, 6, 1, 9, 0)
                val t3 = LocalDateTime(2026, 6, 1, 10, 0)
                val id1 = insertPayment(member, status = VolunteerAllowancePaymentStatus.REQUESTED, submittedAt = t1)
                val id2 = insertPayment(member, status = VolunteerAllowancePaymentStatus.REQUESTED, submittedAt = t2)
                val id3 = insertPayment(member, status = VolunteerAllowancePaymentStatus.REQUESTED, submittedAt = t3)

                val firstPage =
                    client.get("/test/volunteerallowance/list") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                listOf(id1, id2, id3).forEach { firstPage.contains(it.toString()) shouldBe true }

                val secondPage =
                    client
                        .get("/test/volunteerallowance/list?afterSubmittedAt=$t1&afterId=$id1") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                secondPage.contains(id1.toString()) shouldBe false
                secondPage.contains(id2.toString()) shouldBe true
                secondPage.contains(id3.toString()) shouldBe true
            }
        }

        // ── listMyPayments ─────────────────────────────────────────────────────────

        test("listMyPayments: returns the caller's own payments, including DRAFT, regardless of role") {
            testApplication {
                application {
                    install(StatusPages) { installVolunteerAllowanceExceptionHandlers() }
                    routing { registerVolunteerAllowanceTestRoutes() }
                }
                val member = newMember()
                val other = newMember()
                val ownDraftId = insertPayment(member, status = VolunteerAllowancePaymentStatus.DRAFT, submittedAt = null)
                val foreignId = insertPayment(other, status = VolunteerAllowancePaymentStatus.DRAFT, submittedAt = null)

                val body = client.get("/test/volunteerallowance/mine") { header("X-Member-Id", member.toString()) }.bodyAsText()
                body.contains(ownDraftId.toString()) shouldBe true
                body.contains(foreignId.toString()) shouldBe false
            }
        }
    })
