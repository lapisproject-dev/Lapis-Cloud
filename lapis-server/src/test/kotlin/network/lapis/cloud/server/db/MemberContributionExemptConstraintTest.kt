package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.ContributionReliefRequestTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" -- T-16: `chk_member_contribution_exempt_range` /
 * `chk_member_contribution_exempt_source` (`V28__contribution_relief.sql`) actually fire against
 * the real migrated H2 schema, plus `chk_crr_payload_shape` / `chk_crr_approved_needs_note` on
 * `contribution_relief_request`. Same "CHECK-Sonde" pattern [MemberDateOfDeathConstraintTest]
 * already establishes.
 */
class MemberContributionExemptConstraintTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRequestIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdRequestIds.isNotEmpty()) {
                    ContributionReliefRequestTable.deleteWhere { ContributionReliefRequestTable.id inList createdRequestIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Exempt-Constraint Testmitglied"
                    it[email] = "exempt-constraint-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
            }
            createdMemberIds += id
            return id
        }

        fun probe(sql: String): Throwable? = runCatching { transaction { exec(sql) } }.exceptionOrNull()

        // ── chk_member_contribution_exempt_range ─────────────────────────────────────────

        test("chk_member_contribution_exempt_range rejects contribution_exempt_until before contribution_exempt_from") {
            val id = newMember()
            val exception =
                probe(
                    "UPDATE member SET contribution_exempt_from = DATE '2027-06-01', " +
                        "contribution_exempt_until = DATE '2027-01-01' WHERE id = '$id'",
                )
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_member_contribution_exempt_range", ignoreCase = true) shouldBe true
        }

        test("chk_member_contribution_exempt_range rejects contribution_exempt_until set without contribution_exempt_from") {
            val id = newMember()
            val exception = probe("UPDATE member SET contribution_exempt_until = DATE '2027-01-01' WHERE id = '$id'")
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_member_contribution_exempt_range", ignoreCase = true) shouldBe true
        }

        test("contribution_exempt_from with a NULL until (unbefristet) is accepted") {
            val id = newMember()
            val exception = probe("UPDATE member SET contribution_exempt_from = DATE '2027-01-01' WHERE id = '$id'")
            exception shouldBe null
        }

        // ── chk_member_contribution_exempt_source ────────────────────────────────────────

        test("chk_member_contribution_exempt_source rejects a contribution_exempt_request_id without contribution_exempt_from") {
            val id = newMember()
            val requestId = Uuid.random()
            val exception = probe("UPDATE member SET contribution_exempt_request_id = '$requestId' WHERE id = '$id'")
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_member_contribution_exempt_source", ignoreCase = true) shouldBe true
        }

        // ── chk_crr_payload_shape ─────────────────────────────────────────────────────────

        test("chk_crr_payload_shape rejects a DEFERRAL row that also carries a reduction_target_tier_id") {
            val subjectId = newMember()
            val requestedById = newMember()
            val requestId = Uuid.random()
            val exception =
                probe(
                    "INSERT INTO contribution_relief_request (id, subject_member_id, kind, status, reason_category, " +
                        "requested_at, requested_by, deferral_contribution_id, deferral_new_due_date, reduction_target_tier_id) " +
                        "VALUES ('$requestId', '$subjectId', 'DEFERRAL', 'REQUESTED', 'OTHER', TIMESTAMP '2027-01-01 00:00:00', " +
                        "'$requestedById', NULL, DATE '2027-06-01', (SELECT id FROM membership_tier LIMIT 1))",
                )
            createdRequestIds += requestId
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_crr_payload_shape", ignoreCase = true) shouldBe true
        }

        // ── chk_crr_approved_needs_note ───────────────────────────────────────────────────

        test("chk_crr_approved_needs_note rejects an APPROVED row with a NULL decision_note") {
            val subjectId = newMember()
            val requestedById = newMember()
            val requestId = Uuid.random()
            val exception =
                probe(
                    "INSERT INTO contribution_relief_request (id, subject_member_id, kind, status, reason_category, " +
                        "exemption_from, requested_at, requested_by, decision_note) " +
                        "VALUES ('$requestId', '$subjectId', 'EXEMPTION', 'APPROVED', 'OTHER', DATE '2027-01-01', " +
                        "TIMESTAMP '2027-01-01 00:00:00', '$requestedById', NULL)",
                )
            createdRequestIds += requestId
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_crr_approved_needs_note", ignoreCase = true) shouldBe true
        }

        test("an APPROVED row WITH a decision_note is accepted") {
            val subjectId = newMember()
            val requestedById = newMember()
            val requestId = Uuid.random()
            val exception =
                probe(
                    "INSERT INTO contribution_relief_request (id, subject_member_id, kind, status, reason_category, " +
                        "exemption_from, requested_at, requested_by, decision_note) " +
                        "VALUES ('$requestId', '$subjectId', 'EXEMPTION', 'APPROVED', 'OTHER', DATE '2027-01-01', " +
                        "TIMESTAMP '2027-01-01 00:00:00', '$requestedById', 'Genehmigt')",
                )
            createdRequestIds += requestId
            exception shouldBe null
        }
    })
