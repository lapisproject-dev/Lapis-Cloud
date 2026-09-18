// Welle "Digitaler Mitgliedsausweis (PDF)" -- see network.lapis.cloud.server.member.MemberCardStore
// for the store logic, network.lapis.cloud.server.member.MemberNumberAllocator for
// member.member_number allocation (00-foundation.kuml.kts), and
// network.lapis.cloud.server.pdf.MemberCardPdfGenerator for the rendered PDF itself.
//
// **This file models exactly two new tables.** `member_number_sequence` is the per-Beitrittsjahr
// laufender Zähler that backs `member.member_number` ("M-<Beitrittsjahr>-<5-stellig>",
// 00-foundation.kuml.kts). `member_card_code` is the bearer-code table that authorizes the
// unauthenticated public verification page ("/ausweis") -- one row per issued/rotated code,
// analogous to `event_registration.ticket_code_sha256` (39-events.kuml.kts) but its own table
// (not a column on `member`) so ROTATION HISTORY survives: revoking a lost/compromised card
// leaves the old row with `revoked_at` set rather than overwriting it.
//
// **Why `member_number_sequence` has no `year` FK to anything, and no `member` FK at all.** A
// year is not itself an entity this schema otherwise models -- the column IS the key. No member
// PII flows through this table (see DSGVO note below).
//
// **Why no partial unique index ("at most one ACTIVE code per member").** Same H2
// MODE=PostgreSQL portability limitation `event_registration.active_participant_key`
// (39-events.kuml.kts) and `event_volunteer_signup.active_member_key` (51-event-volunteer.kuml.kts)
// already document -- H2's PostgreSQL compatibility mode rejects both a partial unique index
// (`CREATE UNIQUE INDEX ... WHERE`) and the generated-column workaround. Unlike those two tables,
// this invariant is deliberately NOT re-derived via a shadow "active key" column either: the only
// writers of `member_card_code` are `MemberCardStore.issueNewCode`/`.revokeAndReissue` (the latter
// reached through `MemberCardIssuance.rotate`, the one production issuance path), both of
// which run under a `forUpdate()` lock on the owning `member` row for their whole read-then-write
// -- the lock, not a database constraint, is what serializes concurrent issuance for one member.
// `code_hash` itself IS globally unique (mirrors `uq_event_registration_ticket_code`,
// `V1__baseline.sql`), which is the one invariant that DOES need database enforcement (a hash
// collision across two different members' codes would let one member's card authenticate as
// another's on the public verification page).
//
// **The raw bearer code is never persisted** -- only its SHA-256 hex digest (`code_hash`), same
// "hash, never store the bearer secret itself" posture `event_registration.ticket_code_sha256`
// already establishes. See `network.lapis.cloud.server.member.MemberCardPolicy`/`.MemberCardStore`
// for the minting/hashing/lookup logic.
//
// **DSGVO**: `member_card_code` carries a `member_id` FK -> new `MemberCardPersonalData`
// contributor, `PersonalDataRegistry`. `code_hash` itself is never exported (a hash gives the
// data subject no readable information and is a credential derivative); erasure is a hard DELETE
// of the member's rows (an issued card code has no retention obligation and must become invalid
// immediately on Art. 17 erasure -- posture `CrmPersonalData` already establishes for
// `crm_interaction`, not the retain-and-redact posture most of this codebase's other tables take).
// `member_number_sequence` carries neither a member FK nor any personal data -- no contributor,
// no allowlist entry needed for it.
//
// Cross-domain stub: minimal id-only `Member` (owned by `00-foundation.kuml.kts`), same
// single-file-evaluation pattern every later domain file's own header documents (most recently
// `51-event-volunteer.kuml.kts`'s own stub) -- purely so `UmlToErmTransformer` can resolve this
// file's `member_card_code.member_id` FK.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "MemberCard") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val memberNumberSequence = classOf(name = "MemberNumberSequence") {
        stereotype("Entity") { "tableName" to "member_number_sequence"; "kotlinObjectName" to "MemberNumberSequenceTable" }

        // Deliberately the primary key ITSELF, not a separate surrogate id -- see file header
        // "no `year` FK to anything". Mirrors network.lapis.cloud.server.member.MemberNumberAllocator.
        // Named "allocationYear"/"allocation_year", not "year" -- "YEAR" is a reserved word in
        // H2's SQL grammar and a bare `year INT` column definition fails to parse.
        attribute(name = "allocationYear", type = "Int") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "allocation_year" }
        }
        attribute(name = "nextValue", type = "Int") {
            stereotype("Column") { "columnName" to "next_value" }
        }
    }

    val memberCardCode = classOf(name = "MemberCardCode") {
        stereotype("Entity") { "tableName" to "member_card_code"; "kotlinObjectName" to "MemberCardCodeTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // Global uniqueness -- see file header "The raw bearer code is never persisted".
        attribute(name = "codeHash", type = "String") {
            stereotype("Column") { "columnName" to "code_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        attribute(name = "issuedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "issued_at" }
        }
        attribute(name = "revokedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revoked_at" }
        }
    }
}
