package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.MemberFamilyLinkTable
import network.lapis.cloud.server.db.generated.MemberFamilyTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.4.4.4 "Mitgliederlebenszyklus: Familienmitgliedschaften" --
 * `V23__member_family.sql`'s CHECK/UNIQUE constraints actually fire against the real migrated H2
 * schema. Same "CHECK-Sonde" pattern [EventMigrationTest] already establishes: a raw `exec()`
 * INSERT with an invalid-but-column-width-fitting value, expecting an [ExposedSQLException] naming
 * the violated constraint.
 */
class MemberFamilyConstraintTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdFamilyIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdFamilyIds.isNotEmpty()) {
                    MemberFamilyLinkTable.deleteWhere { MemberFamilyLinkTable.familyId inList createdFamilyIds }
                    MemberFamilyTable.deleteWhere { MemberFamilyTable.id inList createdFamilyIds }
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
                    it[displayName] = "Constraint Testmitglied"
                    it[email] = "constraint-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
            }
            createdMemberIds += id
            return id
        }

        fun newFamily(createdBy: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                MemberFamilyTable.insert {
                    it[MemberFamilyTable.id] = id
                    it[name] = "Constraint-Testfamilie"
                    it[MemberFamilyTable.createdBy] = createdBy
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }
            createdFamilyIds += id
            return id
        }

        fun probeInsert(sql: String): Throwable? = runCatching { transaction { exec(sql) } }.exceptionOrNull()

        fun linkColumns(
            id: Uuid,
            familyId: Uuid,
            memberId: Uuid,
            role: String,
            payerFamilyId: Uuid?,
            linkedBy: Uuid,
        ): String {
            val payerSql = payerFamilyId?.let { "'$it'" } ?: "NULL"
            return "INSERT INTO member_family_link (id, family_id, member_id, role, payer_family_id, linked_at, linked_by) " +
                "VALUES ('$id', '$familyId', '$memberId', '$role', $payerSql, TIMESTAMP '2026-01-01 00:00:00', '$linkedBy')"
        }

        test("uq_member_family_link_payer rejects a second PAYER row in the same family") {
            val actor = newMember()
            val familyId = newFamily(actor)
            val payer1 = newMember()
            val payer2 = newMember()
            val first = probeInsert(linkColumns(Uuid.random(), familyId, payer1, "PAYER", familyId, actor))
            first shouldBe null
            val second = probeInsert(linkColumns(Uuid.random(), familyId, payer2, "PAYER", familyId, actor))
            (second is ExposedSQLException) shouldBe true
            (second?.message ?: "").contains("uq_member_family_link_payer", ignoreCase = true) shouldBe true
        }

        test("uq_member_family_link_member rejects a second link row for the same member, even in a different family") {
            val actor = newMember()
            val familyA = newFamily(actor)
            val familyB = newFamily(actor)
            val member = newMember()
            val first = probeInsert(linkColumns(Uuid.random(), familyA, member, "DEPENDENT", null, actor))
            first shouldBe null
            val second = probeInsert(linkColumns(Uuid.random(), familyB, member, "DEPENDENT", null, actor))
            (second is ExposedSQLException) shouldBe true
            (second?.message ?: "").contains("uq_member_family_link_member", ignoreCase = true) shouldBe true
        }

        test("two PAYER links in different families are both allowed") {
            val actor = newMember()
            val familyA = newFamily(actor)
            val familyB = newFamily(actor)
            val payerA = newMember()
            val payerB = newMember()
            val first = probeInsert(linkColumns(Uuid.random(), familyA, payerA, "PAYER", familyA, actor))
            val second = probeInsert(linkColumns(Uuid.random(), familyB, payerB, "PAYER", familyB, actor))
            first shouldBe null
            second shouldBe null
        }

        test("chk_member_family_link_payer_shadow rejects a DEPENDENT row with a non-null payer_family_id") {
            val actor = newMember()
            val familyId = newFamily(actor)
            val member = newMember()
            val exception = probeInsert(linkColumns(Uuid.random(), familyId, member, "DEPENDENT", familyId, actor))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_member_family_link_payer_shadow", ignoreCase = true) shouldBe true
        }

        test("chk_member_family_link_payer_shadow rejects a PAYER row with a NULL payer_family_id") {
            val actor = newMember()
            val familyId = newFamily(actor)
            val payer = newMember()
            val exception = probeInsert(linkColumns(Uuid.random(), familyId, payer, "PAYER", null, actor))
            (exception is ExposedSQLException) shouldBe true
            (exception?.message ?: "").contains("chk_member_family_link_payer_shadow", ignoreCase = true) shouldBe true
        }

        test("chk_member_family_link_role rejects an invalid literal that still fits VARCHAR(9)") {
            val actor = newMember()
            val familyId = newFamily(actor)
            val member = newMember()
            val exception = probeInsert(linkColumns(Uuid.random(), familyId, member, "SPOUSE", null, actor))
            (exception is ExposedSQLException) shouldBe true
        }

        test("a valid DEPENDENT row (payer_family_id NULL) is accepted") {
            val actor = newMember()
            val familyId = newFamily(actor)
            val member = newMember()
            val exception = probeInsert(linkColumns(Uuid.random(), familyId, member, "DEPENDENT", null, actor))
            exception shouldBe null
        }
    })
