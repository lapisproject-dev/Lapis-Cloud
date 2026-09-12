package network.lapis.cloud.server.contribution

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionReliefRequestTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefReason
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" -- T-12: [ContributionReliefRedaction
 * .redactDueReasonTexts]. Rein datengetrieben ueber einen fest uebergebenen `now` -- keine
 * Abhaengigkeit von der echten Wanduhr, siehe Klassen-KDoc.
 */
class ContributionReliefRedactionTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val fixedNow = LocalDateTime(2028, 1, 1, 0, 0, 0)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                ContributionReliefRequestTable.deleteWhere { subjectMemberId inList createdMemberIds }
                ContributionTable.deleteWhere { memberId inList createdMemberIds }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Redaction Testmitglied"
                    it[email] = "relief-redaction-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
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

        fun newContributionFor(memberId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = DevSeedData.standardTierId
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 1, 31)
                    it[amountDue] = java.math.BigDecimal("10.00")
                    it[status] = ContributionStatus.OPEN
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0, 0)
                    it[dueDate] = LocalDate(2026, 6, 1)
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                }
            }
            return id
        }

        fun insertRequest(
            status: ContributionReliefStatus,
            kind: ContributionReliefKind,
            decidedAt: LocalDateTime? = null,
            requestedAt: LocalDateTime = LocalDateTime(2027, 1, 1, 0, 0, 0),
            deferralNewDueDate: LocalDate? = null,
            exemptionUntil: LocalDate? = null,
            reviewDueOn: LocalDate? = null,
            executedAt: LocalDateTime? = null,
        ): Uuid {
            val subjectId = newMember()
            val id = Uuid.random()
            val contributionId = if (kind == ContributionReliefKind.DEFERRAL) newContributionFor(subjectId) else null
            transaction {
                ContributionReliefRequestTable.insert {
                    it[ContributionReliefRequestTable.id] = id
                    it[ContributionReliefRequestTable.subjectMemberId] = subjectId
                    it[ContributionReliefRequestTable.kind] = kind
                    it[ContributionReliefRequestTable.status] = status
                    it[reasonCategory] = ContributionReliefReason.OTHER
                    it[reasonText] = "Sensible Angabe"
                    it[ContributionReliefRequestTable.requestedAt] = requestedAt
                    it[requestedBy] = subjectId
                    it[ContributionReliefRequestTable.decidedAt] = decidedAt
                    it[decidedBy] = if (decidedAt != null) subjectId else null
                    it[decisionNote] = if (decidedAt != null) "Notiz" else null
                    it[deferralContributionId] = contributionId
                    it[ContributionReliefRequestTable.deferralNewDueDate] =
                        if (kind == ContributionReliefKind.DEFERRAL) (deferralNewDueDate ?: LocalDate(2026, 6, 1)) else null
                    it[ContributionReliefRequestTable.exemptionUntil] = exemptionUntil
                    it[exemptionFrom] = if (kind == ContributionReliefKind.EXEMPTION) LocalDate(2026, 1, 1) else null
                    it[reductionTargetTierId] = if (kind == ContributionReliefKind.REDUCTION) DevSeedData.standardTierId else null
                    it[ContributionReliefRequestTable.reviewDueOn] = reviewDueOn
                    it[ContributionReliefRequestTable.executedAt] = executedAt
                    it[activeRequestKey] = null
                }
            }
            return id
        }

        fun reasonTextOf(id: Uuid): String? =
            transaction {
                ContributionReliefRequestTable
                    .selectAll()
                    .where {
                        ContributionReliefRequestTable.id eq id
                    }.single()[ContributionReliefRequestTable.reasonText]
            }

        test("REJECTED more than 12 months before `now` (via decidedAt) is redacted") {
            val id =
                insertRequest(
                    status = ContributionReliefStatus.REJECTED,
                    kind = ContributionReliefKind.DEFERRAL,
                    decidedAt = LocalDateTime(2026, 1, 1, 0, 0, 0),
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(id) shouldBe null
        }

        test("REJECTED less than 12 months before `now` is NOT redacted yet") {
            val id =
                insertRequest(
                    status = ContributionReliefStatus.REJECTED,
                    kind = ContributionReliefKind.DEFERRAL,
                    decidedAt = LocalDateTime(2027, 12, 1, 0, 0, 0),
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(id) shouldBe "Sensible Angabe"
        }

        test("EXECUTED DEFERRAL is redacted once deferralNewDueDate is 12+ months in the past") {
            val id =
                insertRequest(
                    status = ContributionReliefStatus.EXECUTED,
                    kind = ContributionReliefKind.DEFERRAL,
                    decidedAt = LocalDateTime(2026, 1, 1, 0, 0, 0),
                    deferralNewDueDate = LocalDate(2026, 6, 1),
                    executedAt = LocalDateTime(2026, 1, 1, 0, 0, 0),
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(id) shouldBe null
        }

        test(
            "EXECUTED EXEMPTION with exemptionUntil = null (unbefristet) falls back to executedAt and is NOT " +
                "redacted before 12 months have passed since execution (Review fix: a permanent `null` anchor " +
                "here used to let these rows occupy a candidate slot on every tick forever, see class KDoc)",
        ) {
            val id =
                insertRequest(
                    status = ContributionReliefStatus.EXECUTED,
                    kind = ContributionReliefKind.EXEMPTION,
                    decidedAt = LocalDateTime(2027, 6, 1, 0, 0, 0),
                    exemptionUntil = null,
                    executedAt = LocalDateTime(2027, 6, 1, 0, 0, 0),
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(id) shouldBe "Sensible Angabe"
        }

        test(
            "EXECUTED EXEMPTION with exemptionUntil = null (unbefristet) IS redacted once 12 months have passed " +
                "since executedAt, even though the exemption's effect itself never ends (Review fix)",
        ) {
            val id =
                insertRequest(
                    status = ContributionReliefStatus.EXECUTED,
                    kind = ContributionReliefKind.EXEMPTION,
                    decidedAt = LocalDateTime(2020, 1, 1, 0, 0, 0),
                    exemptionUntil = null,
                    executedAt = LocalDateTime(2020, 1, 1, 0, 0, 0),
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(id) shouldBe null
        }

        test("EXECUTED EXEMPTION with a SET exemptionUntil is redacted once exemptionUntil is 12+ months in the past") {
            val id =
                insertRequest(
                    status = ContributionReliefStatus.EXECUTED,
                    kind = ContributionReliefKind.EXEMPTION,
                    decidedAt = LocalDateTime(2026, 1, 1, 0, 0, 0),
                    exemptionUntil = LocalDate(2026, 6, 1),
                    executedAt = LocalDateTime(2026, 1, 1, 0, 0, 0),
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(id) shouldBe null
        }

        test("EXECUTED EXEMPTION with a SET exemptionUntil less than 12 months in the past is NOT redacted yet") {
            val id =
                insertRequest(
                    status = ContributionReliefStatus.EXECUTED,
                    kind = ContributionReliefKind.EXEMPTION,
                    decidedAt = LocalDateTime(2027, 6, 1, 0, 0, 0),
                    exemptionUntil = LocalDate(2027, 12, 1),
                    executedAt = LocalDateTime(2027, 6, 1, 0, 0, 0),
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(id) shouldBe "Sensible Angabe"
        }

        test("WITHDRAWN more than 12 months before `now` (via decidedAt) is redacted") {
            val id =
                insertRequest(
                    status = ContributionReliefStatus.WITHDRAWN,
                    kind = ContributionReliefKind.DEFERRAL,
                    decidedAt = LocalDateTime(2026, 1, 1, 0, 0, 0),
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(id) shouldBe null
        }

        test("WITHDRAWN less than 12 months before `now` is NOT redacted yet") {
            val id =
                insertRequest(
                    status = ContributionReliefStatus.WITHDRAWN,
                    kind = ContributionReliefKind.DEFERRAL,
                    decidedAt = LocalDateTime(2027, 12, 1, 0, 0, 0),
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(id) shouldBe "Sensible Angabe"
        }

        test(
            "EXECUTED REDUCTION falls back to executedAt when reviewDueOn is null, and IS redacted once that " +
                "fallback anchor is 12+ months in the past (the fallback-operator branch review finding #8 flagged " +
                "as untested)",
        ) {
            val dueYet =
                insertRequest(
                    status = ContributionReliefStatus.EXECUTED,
                    kind = ContributionReliefKind.REDUCTION,
                    decidedAt = LocalDateTime(2026, 1, 1, 0, 0, 0),
                    reviewDueOn = null,
                    executedAt = LocalDateTime(2026, 1, 1, 0, 0, 0),
                )
            val notDueYet =
                insertRequest(
                    status = ContributionReliefStatus.EXECUTED,
                    kind = ContributionReliefKind.REDUCTION,
                    decidedAt = LocalDateTime(2027, 12, 1, 0, 0, 0),
                    reviewDueOn = null,
                    executedAt = LocalDateTime(2027, 12, 1, 0, 0, 0),
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(dueYet) shouldBe null
            reasonTextOf(notDueYet) shouldBe "Sensible Angabe"
        }

        test("EXECUTED REDUCTION with a SET reviewDueOn uses that instead of executedAt") {
            val id =
                insertRequest(
                    status = ContributionReliefStatus.EXECUTED,
                    kind = ContributionReliefKind.REDUCTION,
                    decidedAt = LocalDateTime(2020, 1, 1, 0, 0, 0),
                    reviewDueOn = LocalDate(2027, 12, 1), // less than 12 months before fixedNow
                    executedAt = LocalDateTime(2020, 1, 1, 0, 0, 0), // 12+ months before fixedNow -- must be ignored
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(id) shouldBe "Sensible Angabe"
        }

        test("REQUESTED and APPROVED are never redacted, regardless of age") {
            val requested =
                insertRequest(
                    status = ContributionReliefStatus.REQUESTED,
                    kind = ContributionReliefKind.DEFERRAL,
                    requestedAt = LocalDateTime(2020, 1, 1, 0, 0, 0),
                )
            val approved =
                insertRequest(
                    status = ContributionReliefStatus.APPROVED,
                    kind = ContributionReliefKind.DEFERRAL,
                    decidedAt = LocalDateTime(2020, 1, 1, 0, 0, 0),
                )
            ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            reasonTextOf(requested) shouldBe "Sensible Angabe"
            reasonTextOf(approved) shouldBe "Sensible Angabe"
        }

        test("decisionNote/kind/status are never touched by redaction, and a second run is idempotent (0 additional rows)") {
            val id =
                insertRequest(
                    status = ContributionReliefStatus.REJECTED,
                    kind = ContributionReliefKind.DEFERRAL,
                    decidedAt = LocalDateTime(2026, 1, 1, 0, 0, 0),
                )
            val firstRun = ContributionReliefRedaction.redactDueReasonTexts(fixedNow)
            (firstRun >= 1) shouldBe true
            val row = transaction { ContributionReliefRequestTable.selectAll().where { ContributionReliefRequestTable.id eq id }.single() }
            row[ContributionReliefRequestTable.decisionNote] shouldBe "Notiz"
            row[ContributionReliefRequestTable.kind] shouldBe ContributionReliefKind.DEFERRAL
            row[ContributionReliefRequestTable.status] shouldBe ContributionReliefStatus.REJECTED

            val secondRunCandidates =
                transaction {
                    ContributionReliefRequestTable
                        .selectAll()
                        .where { (ContributionReliefRequestTable.id eq id) and ContributionReliefRequestTable.reasonText.isNotNull() }
                        .count()
                }
            secondRunCandidates shouldBe 0L
        }
    })
