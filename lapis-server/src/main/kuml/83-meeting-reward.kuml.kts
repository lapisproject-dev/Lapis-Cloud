// Meeting reward domain -- meeting_reward_policy / meeting_reward_policy_change / meeting_reward_committee / meeting_reward_settlement /
// meeting_reward_line (V164__meeting_attendance_recognition.sql).
//
// Welle V1.9.316 "LTR-Anerkennung fuer Sitzungsteilnahme". Recognition for attending a Sitzung is an ISSUANCE of LTR (one credit row MEETING_REWARD per
// recognised person, written only by MeetingRewardPayout.kt), never a payment, never EUR. The tax and legal classification has NOT been reviewed.
//
// DECISIONS THAT SHAPE THE TABLES -- do not "repair" any of them:
//   * meeting_reward_policy is a singleton (fixed id ...f9, seeded OFF by V164). last_change_id / open_change_id have NO foreign key (no cycle with
//     meeting_reward_policy_change, the organisation backup could not restore one); the code keeps them consistent.
//   * The rules change only through a CHANGE that cites an ADOPTED resolution of the general assembly (CHECK) and is confirmed by a second,
//     tenured administrator (CHECK decided_by <> proposed_by); DISABLE needs one administrator. Every step is a row of meeting_reward_policy_change
//     with the old and the new values.
//   * meeting_reward_committee is the snapshot of the committees that take part (replaced by a confirmed CHANGE).
//   * meeting_reward_settlement is one row per meeting (UNIQUE meeting_id), prepared by one and released by another administrator.
//   * meeting_reward_line: one line per member and meeting (UNIQUE meeting_id + member_id, UNIQUE idempotency_key); ledger_entry_id is UNIQUE (NULL
//     until released or for an unpaid line). amount <= base_amount + bonus_amount (CHECK).
//   * Enum-like columns carry the width in sqlType and the shared enum in enumType. The CHECK constraints are plain SQL in V164. No «Column» association(...) blocks: plain UUID attributes with fkEntity (same reasoning as 74-tender.kuml.kts).
//   * audit_log_entry.entity_type gets MEETING_REWARD_POLICY / MEETING_REWARD_SETTLEMENT in V164 (14-audit-log.kuml.kts is knowingly behind since
//     V1.9.230 and is not extended here).
//
// DSGVO -- proposed_by / decided_by / prepared_by / released_by / meeting_reward_line.member_id are personal data;
// network.lapis.cloud.server.dsgvo.MeetingRewardPersonalData exports them and retains the booking evidence (GoBD).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "MeetingReward") {
    applyProfile(ermMappingProfile)

    val member =
        classOf(name = "Member") {
            stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val meeting =
        classOf(name = "Meeting") {
            stereotype("Entity") { "tableName" to "meeting"; "kotlinObjectName" to "MeetingTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val committee =
        classOf(name = "Committee") {
            stereotype("Entity") { "tableName" to "committee"; "kotlinObjectName" to "CommitteeTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val resolution =
        classOf(name = "Resolution") {
            stereotype("Entity") { "tableName" to "resolution"; "kotlinObjectName" to "ResolutionTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val protocolVersion =
        classOf(name = "ProtocolVersion") {
            stereotype("Entity") { "tableName" to "protocol_version"; "kotlinObjectName" to "ProtocolVersionTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val ltrLedgerEntry =
        classOf(name = "LtrLedgerEntry") {
            stereotype("Entity") { "tableName" to "ltr_ledger_entry"; "kotlinObjectName" to "LtrLedgerEntryTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    // Literal order is load-bearing: the drift test pins the values in exactly this order (= network.lapis.cloud.shared.domain.*).
    val changeKind =
        enumOf(name = "MeetingRewardPolicyChangeKind") {
            literal(name = "CHANGE")
            literal(name = "DISABLE")
        }

    val changeStatus =
        enumOf(name = "MeetingRewardPolicyChangeStatus") {
            literal(name = "PROPOSED")
            literal(name = "CONFIRMED")
            literal(name = "WITHDRAWN")
        }

    val settlementStatus =
        enumOf(name = "MeetingRewardSettlementStatus") {
            literal(name = "PREPARED")
            literal(name = "RELEASED")
        }

    val lineReason =
        enumOf(name = "MeetingRewardLineReason") {
            literal(name = "EXCUSED")
            literal(name = "REPRESENTED")
            literal(name = "NOT_PRESENT")
            literal(name = "GUEST")
            literal(name = "CAP_REACHED")
            literal(name = "CAP_REDUCED")
            literal(name = "ALREADY_SETTLED")
            literal(name = "MEMBER_NOT_ELIGIBLE")
        }

    val attendanceStatus =
        enumOf(name = "AttendanceStatus") {
            literal(name = "PRESENT")
            literal(name = "EXCUSED")
            literal(name = "UNEXCUSED")
            literal(name = "REPRESENTED")
            literal(name = "GUEST")
        }

    val committeeRole =
        enumOf(name = "CommitteeRole") {
            literal(name = "CHAIR")
            literal(name = "DEPUTY_CHAIR")
            literal(name = "SECRETARY")
            literal(name = "MEMBER")
            literal(name = "ASSESSOR")
            literal(name = "GENERAL_SECRETARY")
            literal(name = "PRESS_SPOKESPERSON")
            literal(name = "MANAGING_DIRECTOR")
            literal(name = "TREASURER")
        }

    val policy =
        classOf(name = "MeetingRewardPolicy") {
            stereotype("Entity") { "tableName" to "meeting_reward_policy"; "kotlinObjectName" to "MeetingRewardPolicyTable" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "enabled", type = "Boolean") {
                defaultValue = "false"
                stereotype("Column") { "columnName" to "enabled" }
            }
            attribute(name = "attendanceAmount", type = "BigDecimal") {
                defaultValue = "0"
                stereotype("Column") { "columnName" to "attendance_amount"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "bonusChair", type = "BigDecimal") {
                defaultValue = "0"
                stereotype("Column") { "columnName" to "bonus_chair"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "bonusDeputyChair", type = "BigDecimal") {
                defaultValue = "0"
                stereotype("Column") { "columnName" to "bonus_deputy_chair"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "bonusSecretary", type = "BigDecimal") {
                defaultValue = "0"
                stereotype("Column") { "columnName" to "bonus_secretary"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "bonusManagingDirector", type = "BigDecimal") {
                defaultValue = "0"
                stereotype("Column") { "columnName" to "bonus_managing_director"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "capPerPersonPerYear", type = "BigDecimal") {
                defaultValue = "240.00"
                stereotype("Column") { "columnName" to "cap_per_person_per_year"; "sqlType" to "DECIMAL(12,2)" }
            }
            attribute(name = "capPerMeeting", type = "BigDecimal") {
                defaultValue = "1000.00"
                stereotype("Column") { "columnName" to "cap_per_meeting"; "sqlType" to "DECIMAL(12,2)" }
            }
            attribute(name = "resolutionId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "resolution_id"; "fkEntity" to "Resolution" }
            }
            attribute(name = "resolutionReference", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "resolution_reference"; "sqlType" to "VARCHAR(200)" }
            }
            attribute(name = "lastChangeId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "last_change_id" }
            }
            attribute(name = "openChangeId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "open_change_id" }
            }
            attribute(name = "updatedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "updated_at" }
            }
        }

    val change =
        classOf(name = "MeetingRewardPolicyChange") {
            stereotype("Entity") { "tableName" to "meeting_reward_policy_change"; "kotlinObjectName" to "MeetingRewardPolicyChangeTable" }
            stereotype("Index") { "columns" to listOf("proposed_at"); "name" to "ix_meeting_reward_change_proposed_at" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "kind", type = changeKind) {
                stereotype("Column") { "columnName" to "kind"; "sqlType" to "VARCHAR(7)"; "enumType" to "network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeKind" }
            }
            attribute(name = "status", type = changeStatus) {
                stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(9)"; "enumType" to "network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeStatus" }
            }
            attribute(name = "proposedBy", type = "UUID") {
                stereotype("Column") { "columnName" to "proposed_by"; "fkEntity" to "Member" }
            }
            attribute(name = "proposedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "proposed_at" }
            }
            attribute(name = "decidedBy", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "decided_by"; "fkEntity" to "Member" }
            }
            attribute(name = "decidedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "decided_at" }
            }
            attribute(name = "resolutionId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "resolution_id"; "fkEntity" to "Resolution" }
            }
            attribute(name = "resolutionReference", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "resolution_reference"; "sqlType" to "VARCHAR(200)" }
            }
            attribute(name = "oldEnabled", type = "Boolean") {
                stereotype("Column") { "columnName" to "old_enabled" }
            }
            attribute(name = "oldAttendanceAmount", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "old_attendance_amount"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "oldBonusChair", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "old_bonus_chair"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "oldBonusDeputyChair", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "old_bonus_deputy_chair"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "oldBonusSecretary", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "old_bonus_secretary"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "oldBonusManagingDirector", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "old_bonus_managing_director"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "oldCapPerPersonPerYear", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "old_cap_per_person_per_year"; "sqlType" to "DECIMAL(12,2)" }
            }
            attribute(name = "oldCapPerMeeting", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "old_cap_per_meeting"; "sqlType" to "DECIMAL(12,2)" }
            }
            attribute(name = "newEnabled", type = "Boolean") {
                stereotype("Column") { "columnName" to "new_enabled" }
            }
            attribute(name = "newAttendanceAmount", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "new_attendance_amount"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "newBonusChair", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "new_bonus_chair"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "newBonusDeputyChair", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "new_bonus_deputy_chair"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "newBonusSecretary", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "new_bonus_secretary"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "newBonusManagingDirector", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "new_bonus_managing_director"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "newCapPerPersonPerYear", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "new_cap_per_person_per_year"; "sqlType" to "DECIMAL(12,2)" }
            }
            attribute(name = "newCapPerMeeting", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "new_cap_per_meeting"; "sqlType" to "DECIMAL(12,2)" }
            }
            attribute(name = "oldCommitteeIds", type = "String") {
                stereotype("Column") { "columnName" to "old_committee_ids"; "sqlType" to "VARCHAR(4000)" }
            }
            attribute(name = "newCommitteeIds", type = "String") {
                stereotype("Column") { "columnName" to "new_committee_ids"; "sqlType" to "VARCHAR(4000)" }
            }
        }

    val rewardCommittee =
        classOf(name = "MeetingRewardCommittee") {
            stereotype("Entity") { "tableName" to "meeting_reward_committee"; "kotlinObjectName" to "MeetingRewardCommitteeTable" }

            attribute(name = "committeeId", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "committee_id"; "fkEntity" to "Committee" }
            }
            attribute(name = "enabledByChangeId", type = "UUID") {
                stereotype("Column") { "columnName" to "enabled_by_change_id"; "fkEntity" to "MeetingRewardPolicyChange" }
            }
            attribute(name = "enabledAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "enabled_at" }
            }
        }

    val settlement =
        classOf(name = "MeetingRewardSettlement") {
            stereotype("Entity") { "tableName" to "meeting_reward_settlement"; "kotlinObjectName" to "MeetingRewardSettlementTable" }
            stereotype("Index") { "columns" to listOf("meeting_id"); "unique" to true; "name" to "uq_meeting_reward_settlement_meeting" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "meetingId", type = "UUID") {
                stereotype("Column") { "columnName" to "meeting_id"; "fkEntity" to "Meeting" }
            }
            attribute(name = "status", type = settlementStatus) {
                stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(8)"; "enumType" to "network.lapis.cloud.shared.domain.MeetingRewardSettlementStatus" }
            }
            attribute(name = "preparedBy", type = "UUID") {
                stereotype("Column") { "columnName" to "prepared_by"; "fkEntity" to "Member" }
            }
            attribute(name = "preparedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "prepared_at" }
            }
            attribute(name = "releasedBy", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "released_by"; "fkEntity" to "Member" }
            }
            attribute(name = "releasedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "released_at" }
            }
            attribute(name = "previewHash", type = "String") {
                stereotype("Column") { "columnName" to "preview_hash"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "policyChangeId", type = "UUID") {
                stereotype("Column") { "columnName" to "policy_change_id"; "fkEntity" to "MeetingRewardPolicyChange" }
            }
            attribute(name = "protocolVersionId", type = "UUID") {
                stereotype("Column") { "columnName" to "protocol_version_id"; "fkEntity" to "ProtocolVersion" }
            }
            attribute(name = "totalAmount", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "total_amount"; "sqlType" to "DECIMAL(14,2)" }
            }
        }

    val line =
        classOf(name = "MeetingRewardLine") {
            stereotype("Entity") { "tableName" to "meeting_reward_line"; "kotlinObjectName" to "MeetingRewardLineTable" }
            stereotype("Index") { "columns" to listOf("meeting_id", "member_id"); "unique" to true; "name" to "uq_meeting_reward_line_meeting_member" }
            stereotype("Index") { "columns" to listOf("idempotency_key"); "unique" to true; "name" to "uq_meeting_reward_line_idempotency" }
            stereotype("Index") { "columns" to listOf("ledger_entry_id"); "unique" to true; "name" to "uq_meeting_reward_line_ledger_entry" }
            stereotype("Index") { "columns" to listOf("member_id", "period_year"); "name" to "ix_meeting_reward_line_member_year" }
            stereotype("Index") { "columns" to listOf("settlement_id"); "name" to "ix_meeting_reward_line_settlement" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "settlementId", type = "UUID") {
                stereotype("Column") { "columnName" to "settlement_id"; "fkEntity" to "MeetingRewardSettlement" }
            }
            attribute(name = "meetingId", type = "UUID") {
                stereotype("Column") { "columnName" to "meeting_id"; "fkEntity" to "Meeting" }
            }
            attribute(name = "memberId", type = "UUID") {
                stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
            }
            attribute(name = "attendanceStatus", type = attendanceStatus) {
                stereotype("Column") { "columnName" to "attendance_status"; "sqlType" to "VARCHAR(11)"; "enumType" to "network.lapis.cloud.shared.domain.AttendanceStatus" }
            }
            attribute(name = "reason", type = lineReason) {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "reason"; "sqlType" to "VARCHAR(19)"; "enumType" to "network.lapis.cloud.shared.domain.MeetingRewardLineReason" }
            }
            attribute(name = "functionRole", type = committeeRole) {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "function_role"; "sqlType" to "VARCHAR(20)"; "enumType" to "network.lapis.cloud.shared.domain.CommitteeRole" }
            }
            attribute(name = "baseAmount", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "base_amount"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "bonusAmount", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "bonus_amount"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "amount", type = "BigDecimal") {
                stereotype("Column") { "columnName" to "amount"; "sqlType" to "DECIMAL(9,2)" }
            }
            attribute(name = "reduced", type = "Boolean") {
                defaultValue = "false"
                stereotype("Column") { "columnName" to "reduced" }
            }
            attribute(name = "periodYear", type = "Int") {
                stereotype("Column") { "columnName" to "period_year" }
            }
            attribute(name = "idempotencyKey", type = "String") {
                stereotype("Column") { "columnName" to "idempotency_key"; "sqlType" to "VARCHAR(100)" }
            }
            attribute(name = "ledgerEntryId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "ledger_entry_id"; "fkEntity" to "LtrLedgerEntry" }
            }
        }
}
