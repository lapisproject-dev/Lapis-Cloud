package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * [AuditLogPersonalData] export/erase behavior -- see that object's KDoc for the "retained
 * unconditionally, snapshots deliberately excluded from export" rationale. The structural coverage
 * assertion itself (that `audit_log_entry.actor_member_id` is covered by SOME contributor) is
 * [PersonalDataCoverageTest]'s job, not this file's -- this file only exercises this one
 * contributor's own behavior in detail, mirroring how [AccountingPersonalData] et al. are otherwise
 * only exercised indirectly through [PersonalDataCoverageTest]; this Spec exists in addition
 * because [AuditLogPersonalData]'s export deliberately DROPS a field ([AuditLogEntryTable
 * .beforeSnapshot]/[AuditLogEntryTable.afterSnapshot]) that every other contributor's export does
 * not have an analogous drop for, which is worth a dedicated regression test.
 */
class AuditLogPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            if (createdMemberIds.isEmpty()) return@afterSpec
            transaction {
                AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "AuditLogPersonalData Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.TREASURER
                }
            }
            createdMemberIds += id
            return id
        }

        test("sectionKey/displayName/coveredTables are set and coveredTables is exactly {AuditLogEntryTable}") {
            AuditLogPersonalData.sectionKey shouldBe "auditLog"
            AuditLogPersonalData.coveredTables shouldBe setOf(AuditLogEntryTable)
        }

        test(
            "export returns one entry per row this member acted on, with id/sequenceNumber/entityType/entityId/action but NO snapshot payload",
        ) {
            val member = createTestMember("audit-pd-export@example.org")
            val entityId = Uuid.random()
            transaction {
                AuditLogRecorder.record(
                    actorMemberId = member,
                    actorRole = AccountRole.TREASURER,
                    entityType = AuditEntityType.RESOLUTION,
                    entityId = entityId,
                    action = AuditAction.CREATE,
                    before = null,
                    after = """{"secret":"some other person's data that must never leak into this export"}""",
                )
            }

            val export = transaction { AuditLogPersonalData.export(member) }
            export.size shouldBe 1
            val entry = export.single().jsonObject
            entry.keys shouldBe setOf("id", "sequenceNumber", "occurredAt", "entityType", "entityId", "action")
            entry.getValue("entityType").jsonPrimitive.content shouldBe "RESOLUTION"
            entry.getValue("entityId").jsonPrimitive.content shouldBe entityId.toString()
            entry.getValue("action").jsonPrimitive.content shouldBe "CREATE"
            // The critical regression this test guards: the raw afterSnapshot JSON (which could
            // legitimately reference an unrelated third party's data) must never appear anywhere in
            // this member's own export.
            export.toString() shouldNotContain "secret"
        }

        test("export for a member with no audit-log activity at all is an empty array") {
            val member = createTestMember("audit-pd-export-empty@example.org")
            val export = transaction { AuditLogPersonalData.export(member) }
            export.size shouldBe 0
        }

        test("erase retains every row unconditionally, for both ErasureMode values, and clears no field") {
            val member = createTestMember("audit-pd-erase@example.org")
            transaction {
                repeat(3) {
                    AuditLogRecorder.record(
                        actorMemberId = member,
                        actorRole = AccountRole.TREASURER,
                        entityType = AuditEntityType.RESOLUTION,
                        entityId = Uuid.random(),
                        action = AuditAction.CREATE,
                        before = null,
                        after = "payload-$it",
                    )
                }
            }

            listOf(ErasureMode.ANONYMIZE, ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED).forEach { mode ->
                val outcomes = transaction { AuditLogPersonalData.erase(memberId = member, mode = mode) }
                outcomes.size shouldBe 1
                val outcome = outcomes.single()
                outcome.table shouldBe "audit_log_entry"
                outcome.rowsRetained shouldBe 3
                outcome.rowsAnonymized shouldBe 0
                outcome.rowsDeleted shouldBe 0
                outcome.retentionReason?.isNotBlank() shouldBe true
            }

            // Rows must still exist afterward, completely untouched -- erase() never actually
            // mutates AuditLogEntryTable at all (see AuditLogRecorder/AuditLogImmutabilityTest).
            val remaining =
                transaction {
                    AuditLogEntryTable.selectAll().where { AuditLogEntryTable.actorMemberId eq member }.count()
                }
            remaining shouldBe 3L
        }
    })
