// Welle V1.9.59 "Mitgliederzahlen ueber Zeit" -- see network.lapis.cloud.server.member.MemberStatusHistory,
// network.lapis.cloud.server.member.MemberCountAggregation and docs/architecture/member-status-history.adoc for the full
// rationale (V72__member_status_history.sql).
//
// **This file models exactly one new table.** `member_status_history` is an APPEND-ONLY log of status changes: one row per
// change of `member.status`. Rows are never updated and never deleted (the one exception is the V72 backfill itself, which
// drops rows that repeat their predecessor's status).
//
// **`previous_status` invariant**: the status of the member's previous row (NULL = the member's first row). A count at an
// instant is therefore the sum over all rows before that instant of (+1 for `status`, -1 for `previous_status`) -- one
// GROUP BY, no window function, and no `member_id` in the result.
//
// **Primary key (member_id, effective_from), no surrogate id.** The V72 backfill must create rows in portable SQL
// (`gen_random_uuid()` and `RANDOM_UUID()` are not portable), and the composite key makes the per-member order deterministic.
// `effective_from` is a class-A timestamp (UTC): the moment the change was RECORDED, not the legal effect.
//
// **`source`**: LIVE (a service wrote it), IMPORT (CSV import), SEED (dev/staging seed), BACKFILL_AUDIT / BACKFILL_RECORD /
// BACKFILL_ASSUMED (V72 reconstruction from the audit log / from records / assumed). `recorded_at` is NULL exactly for the
// BACKFILL_* rows, set for the others -- chk_member_status_history_recorded.
//
// **No `changed_by`**: the actor is already in the hash-chained audit log; a second personal reference here would only add
// erasure special cases. **No CASCADE on the FK** -- the application layer is authoritative. The CHECK constraints (status,
// previous_status, source, recorded_at) are plain SQL in V72, not expressible in the ERM profile. `status`, `previous_status`
// and `source` are plain VARCHAR columns (strings, not Exposed enumerations -- same as every other status column of this
// schema family introduced since V1.9.57).
//
// **DSGVO**: the rows are KEPT on erasure (anonymous member statistics: only status and instant; the member row itself is
// anonymised, never deleted). Registered with MemberStatusHistoryPersonalData.
//
// Cross-domain stub: minimal id-only `Member` (owned by `00-foundation.kuml.kts`), same single-file-evaluation pattern every
// later domain file's own header documents.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "MemberStatusHistory") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val memberStatusHistory = classOf(name = "MemberStatusHistory") {
        stereotype("Entity") { "tableName" to "member_status_history"; "kotlinObjectName" to "MemberStatusHistoryTable" }

        stereotype("Index") { "columns" to listOf("effective_from"); "name" to "idx_member_status_history_effective_from" }

        // Composite primary key (member_id, effective_from).
        attribute(name = "memberId", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "effectiveFrom", type = "LocalDateTime") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "effective_from" }
        }
        // APPLICATION | ACTIVE | GUEST | WITHDRAWN | REJECTED | FRIEND | DONOR | DECEASED
        attribute(name = "status", type = "String") {
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(11)" }
        }
        // Status of the previous row of the same member; NULL = first row.
        attribute(name = "previousStatus", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "previous_status"; "sqlType" to "VARCHAR(11)" }
        }
        // LIVE | IMPORT | SEED | BACKFILL_AUDIT | BACKFILL_RECORD | BACKFILL_ASSUMED
        attribute(name = "source", type = "String") {
            stereotype("Column") { "columnName" to "source"; "sqlType" to "VARCHAR(16)" }
        }
        // NULL exactly for the V72 backfill rows.
        attribute(name = "recordedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "recorded_at" }
        }
    }
}
