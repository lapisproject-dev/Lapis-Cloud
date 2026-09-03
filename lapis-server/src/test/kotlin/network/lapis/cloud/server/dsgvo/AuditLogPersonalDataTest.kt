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
                    it[status] = MemberStatus.ACTIVE
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

            val export = transaction { AuditLogPersonalData.exportMember(member) }
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

        test(
            "export includes MEMBER-entity rows where this member is the SUBJECT (entityId), not just rows where they were the actor, and surfaces status/role/reason for those",
        ) {
            // Security fix (2026-08-27, LOW DSGVO Art. 15) regression test: a board decision
            // recorded via MemberService.updateMemberStatus/updateMemberRole names the AFFECTED
            // member as entityId, not actorMemberId -- see AuditLogPersonalData's own "Security fix
            // (2026-08-27, LOW DSGVO Art. 15)" KDoc for the full scenario this closes.
            val board = createTestMember("audit-pd-subject-board@example.org")
            val subject = createTestMember("audit-pd-subject-target@example.org")
            transaction {
                AuditLogRecorder.record(
                    actorMemberId = board,
                    actorRole = AccountRole.TREASURER,
                    entityType = AuditEntityType.MEMBER,
                    entityId = subject,
                    action = AuditAction.UPDATE,
                    before = """{"displayNameChanged":false,"emailChanged":false,"status":"ACTIVE","role":null}""",
                    after =
                        """{"displayNameChanged":false,"emailChanged":false,"status":"WITHDRAWN",""" +
                            """"role":null,"reason":"Ausschluss wegen Beitragsrueckstand seit 2024"}""",
                )
            }

            val export = transaction { AuditLogPersonalData.exportMember(subject) }
            export.size shouldBe 1
            val entry = export.single().jsonObject
            entry.getValue("entityType").jsonPrimitive.content shouldBe "MEMBER"
            entry.getValue("entityId").jsonPrimitive.content shouldBe subject.toString()
            entry.getValue("status").jsonPrimitive.content shouldBe "WITHDRAWN"
            entry.getValue("reason").jsonPrimitive.content shouldBe "Ausschluss wegen Beitragsrueckstand seit 2024"

            // The board member's OWN export must not gain this row's snapshot content just because
            // they were the actor -- unchanged third-party-leak protection for non-subject rows.
            val boardExport = transaction { AuditLogPersonalData.exportMember(board) }
            boardExport.size shouldBe 1
            val boardEntry = boardExport.single().jsonObject
            boardEntry.keys shouldBe setOf("id", "sequenceNumber", "occurredAt", "entityType", "entityId", "action")
        }

        test("export for a member with no audit-log activity at all is an empty array") {
            val member = createTestMember("audit-pd-export-empty@example.org")
            val export = transaction { AuditLogPersonalData.exportMember(member) }
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
                val outcomes = transaction { AuditLogPersonalData.eraseMember(memberId = member, mode = mode) }
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

        test(
            "erase counts MEMBER-entity rows where this member is the SUBJECT too, not just rows where they were the actor",
        ) {
            // Security fix (2026-08-27, LOW DSGVO Art. 12/17) regression test: erase() used to
            // count only actorMemberId rows, understating what Art. 17's own erasure confirmation
            // reports relative to what export() (see the test above, "export includes MEMBER-entity
            // rows where this member is the SUBJECT") already discloses to the SAME member under
            // Art. 15. A board decision recorded via MemberService.updateMemberStatus/
            // updateMemberRole names the AFFECTED member as entityId, not actorMemberId -- exactly
            // the three rows created below, none of which name `subject` as the actor at all.
            val board = createTestMember("audit-pd-erase-subject-board@example.org")
            val subject = createTestMember("audit-pd-erase-subject-target@example.org")
            transaction {
                repeat(3) {
                    AuditLogRecorder.record(
                        actorMemberId = board,
                        actorRole = AccountRole.TREASURER,
                        entityType = AuditEntityType.MEMBER,
                        entityId = subject,
                        action = AuditAction.UPDATE,
                        before = """{"displayNameChanged":false,"emailChanged":false,"status":"ACTIVE","role":null}""",
                        after =
                            """{"displayNameChanged":false,"emailChanged":false,"status":"ACTIVE",""" +
                                """"role":null,"reason":"Vorstandsbeschluss $it"}""",
                    )
                }
            }

            // export() already surfaces exactly these 3 rows to `subject` under Art. 15 (see the
            // dedicated export test above) -- erase()'s Art. 17 confirmation must report the SAME
            // count, not zero.
            val export = transaction { AuditLogPersonalData.exportMember(subject) }
            export.size shouldBe 3

            val outcome = transaction { AuditLogPersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }.single()
            outcome.rowsRetained shouldBe 3
            outcome.rowsAnonymized shouldBe 0
            outcome.rowsDeleted shouldBe 0

            // `board`, the ACTOR, is unaffected by this fix -- its own erase() still counts these
            // same 3 rows too (it is actorMemberId on all of them), unchanged behavior.
            val boardOutcome = transaction { AuditLogPersonalData.eraseMember(memberId = board, mode = ErasureMode.ANONYMIZE) }.single()
            boardOutcome.rowsRetained shouldBe 3
        }
    })
