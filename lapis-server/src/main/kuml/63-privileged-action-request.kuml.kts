// Welle V1.9.57 "Admin-Peer-Schutz" -- see network.lapis.cloud.server.member.PrivilegedActionService,
// network.lapis.cloud.server.security.PeerProtection and docs/architecture/admin-peer-protection.adoc for the full
// rationale (V71__privileged_action_request.sql).
//
// **This file models exactly one new table.** `privileged_action_request` holds ONE request of an ADMIN against another
// ADMIN (temporary password, demotion, suspension) from request to resolution; a second ADMIN must approve it.
//
// **At most one open request per (target, action)**, expressed without a partial unique index (H2 cannot):
// `open_target_member_id` is the target member id while the request is open (PENDING or APPROVED_WAITING) and NULL
// otherwise, with a plain UNIQUE constraint on (open_target_member_id, action) (uq_privileged_action_request_open --
// several NULLs are legal). chk_privileged_action_request_open ties the column to the status. Plain SQL constraints in
// V71, not expressible in the ERM profile.
//
// **Token**: `veto_token_hash` holds a SHA-256 hex digest only, never the raw token (temporary password only).
// `action`, `status`, `target_role_at_request`, `requested_role` and `requested_status` are plain VARCHAR columns with
// CHECK constraints (strings, not Exposed enumerations -- same as every other status column of this schema family).
//
// **No CASCADE on the FKs** -- the application layer is authoritative for cleanup.
//
// **DSGVO**: carries three member FKs -> registered with PrivilegedActionPersonalData. Erase hard-deletes the rows in
// which the subject is the TARGET and replaces the free-text `reason` in rows where the subject was actor or approver.
// The export carries the rows the subject is part of -- never a token hash.
//
// **Audit**: the hash-chained, non-erasable audit log carries NEVER the reason, a token or an address -- only event,
// action, request id and target role. The reason lives only in this erasable table.
//
// Cross-domain stub: minimal id-only `Member` (owned by `00-foundation.kuml.kts`), same single-file-evaluation
// pattern every later domain file's own header documents.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "PrivilegedActionRequest") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val privilegedActionRequest = classOf(name = "PrivilegedActionRequest") {
        stereotype("Entity") { "tableName" to "privileged_action_request"; "kotlinObjectName" to "PrivilegedActionRequestTable" }

        stereotype("Index") { "columns" to listOf("status", "expires_at"); "name" to "idx_privileged_action_request_status_due" }
        stereotype("Index") { "columns" to listOf("target_member_id"); "name" to "idx_privileged_action_request_target" }
        stereotype("Index") { "columns" to listOf("actor_member_id"); "name" to "idx_privileged_action_request_actor" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // TEMP_PASSWORD | DEMOTE | SUSPEND
        attribute(name = "action", type = "String") {
            stereotype("Column") { "columnName" to "action"; "sqlType" to "VARCHAR(24)" }
        }
        attribute(name = "actorMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "actor_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "targetMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "target_member_id"; "fkEntity" to "Member" }
        }
        // = target_member_id while open, NULL otherwise -- the H2-capable substitute for a partial unique index.
        attribute(name = "openTargetMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "open_target_member_id" }
        }
        attribute(name = "targetRoleAtRequest", type = "String") {
            stereotype("Column") { "columnName" to "target_role_at_request"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "requestedRole", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "requested_role"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "requestedStatus", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "requested_status"; "sqlType" to "VARCHAR(24)" }
        }
        attribute(name = "reason", type = "String") {
            stereotype("Column") { "columnName" to "reason"; "sqlType" to "VARCHAR(500)" }
        }
        // PENDING | APPROVED_WAITING | EXECUTED | REJECTED | WITHDRAWN | VETOED | EXPIRED | INVALIDATED
        attribute(name = "status", type = "String") {
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "approverMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "approver_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "vetoTokenHash", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "veto_token_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "expires_at" }
        }
        attribute(name = "notBefore", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "not_before" }
        }
        attribute(name = "executeUntil", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "execute_until" }
        }
        attribute(name = "decidedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "decided_at" }
        }
        attribute(name = "resolvedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "resolved_at" }
        }
    }
}
