// Welle V1.9.56 "E-Mail-Aenderung absichern" -- see network.lapis.cloud.server.member.EmailChangeService,
// network.lapis.cloud.server.member.EmailChangeStore and docs/architecture/member-email-change.adoc for the full
// rationale (V70__member_email_change.sql).
//
// **This file models exactly one new table.** `member_email_change` holds ONE change of a member's login address
// from request to resolution. `member.email` stays the only login and password-reset identity; a pending change
// redirects neither. The address only ever changes through EmailChangeStore.applyLocked.
//
// **At most one open change per member**, expressed without a partial unique index (H2 cannot): `open_member_id` is
// the member id while the change is PENDING and NULL otherwise, with a plain UNIQUE constraint on it
// (uq_member_email_change_open) -- several NULLs are legal. chk_member_email_change_open ties the column to the
// status. Plain SQL constraints in V70, not expressible in the ERM profile.
//
// **Tokens**: `confirm_token_hash` (link to the NEW address) and `revoke_token_hash` (link to the OLD address) hold
// SHA-256 hex digests only, never the raw token. `kind` SELF | PROPOSAL | PROPOSAL_NO_ACCOUNT | ADMIN_OVERRIDE and
// `status` PENDING | APPLIED | REVOKED | WITHDRAWN | EXPIRED | SUPERSEDED | CONFLICT are plain VARCHAR columns with
// CHECK constraints (strings, not Exposed enumerations -- same as every other status column of this schema family).
//
// **No CASCADE on the FKs** -- the application layer is authoritative for cleanup.
//
// **DSGVO**: carries `member_id` and `requested_by` FKs -> registered with RegistrationPersonalData. Erase hard-deletes
// the member's rows in every ErasureMode (transient access-control artifact) and nulls `requested_by` where the erased
// member was the requester. The export carries the pending address, kind, status and timestamps -- never a token hash.
//
// **Audit**: the hash-chained, non-erasable audit log carries NEVER an address (not even masked or hashed) -- only the
// change id, kind and event. The addresses live only in this erasable table.
//
// **Backup**: deliberately NOT excluded from OrganizationSchemaCatalog -- the table is audit-relevant and travels with
// the whole-organization backup.
//
// Cross-domain stub: minimal id-only `Member` (owned by `00-foundation.kuml.kts`), same single-file-evaluation
// pattern every later domain file's own header documents.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "MemberEmailChange") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val memberEmailChange = classOf(name = "MemberEmailChange") {
        stereotype("Entity") { "tableName" to "member_email_change"; "kotlinObjectName" to "MemberEmailChangeTable" }

        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_member_email_change_member" }
        stereotype("Index") { "columns" to listOf("status", "effective_at"); "name" to "idx_member_email_change_status_due" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // = member_id while PENDING, NULL otherwise -- the H2-capable substitute for a partial unique index.
        attribute(name = "openMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "open_member_id" }
        }
        // Lowercased, normalized new address.
        attribute(name = "pendingEmail", type = "String") {
            stereotype("Column") { "columnName" to "pending_email"; "sqlType" to "VARCHAR(320)" }
        }
        attribute(name = "kind", type = "String") {
            stereotype("Column") { "columnName" to "kind"; "sqlType" to "VARCHAR(24)" }
        }
        attribute(name = "requestedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "requested_by"; "fkEntity" to "Member" }
        }
        attribute(name = "reason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reason"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "confirmTokenHash", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "confirm_token_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        attribute(name = "revokeTokenHash", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revoke_token_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        attribute(name = "status", type = "String") {
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "expires_at" }
        }
        attribute(name = "effectiveAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "effective_at" }
        }
        attribute(name = "newEmailConfirmedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "new_email_confirmed_at" }
        }
        attribute(name = "resolvedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "resolved_at" }
        }
    }
}
