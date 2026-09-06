// Welle V1.4.4.3 "Mitgliederlebenszyklus: Ehrungsverwaltung" -- see
// network.lapis.cloud.server.rpc.MemberHonorService for the read/write path and
// network.lapis.cloud.server.dsgvo.MemberHonorPersonalData for Auskunft/Loeschung.
//
// **This file models exactly one new table**: `member_honor`. A record that the organization
// awarded a member an honor -- an Ehrenmitgliedschaft, a Verdienst-/Treueauszeichnung, or a
// free-form "sonstige" category -- on a given date, optionally naming who/what body awarded it,
// with a free-text note a board member can attach.
//
// **Why `category` (enum) AND `title` (free text) both exist**: `category` is the stable,
// filterable/reportable axis (four fixed literals, never edited after the fact in a way that
// changes its meaning) -- `title` is the volatile, human-authored display text ("25 Jahre
// Mitgliedschaft", "Ehrenmitgliedschaft auf Antrag des Vorstands vom ..."). Collapsing these into
// one free-text field would make filtering/reporting by kind impossible; collapsing them into one
// closed enum would force every award into one of four generic labels with no room for the actual
// wording a board minute used. Same two-column shape `crm_contact.contact_type` (closed) vs.
// `crm_interaction.summary` (free text) already establishes one hop over in 38-crm.kuml.kts, for
// the analogous "stable classification + free human text" split.
//
// **Why there is NO `revoked_at`/`revoked_reason` column**: aberkennnung (revoking an honor) is
// rare, exceptional, and governance-relevant enough that it is a Vorstandsbeschluss-driven
// PROCESS, not a flag flip on this row -- exactly the reasoning `crm_contact.archived_at`'s own
// "out of sight, never a deletion path" KDoc rejects for a lighter-weight case. Modelling
// aberkennung would additionally require deciding whether a revoked honor still counts in a public
// member roster, a printed Urkunde, etc. -- none of which this wave needs to answer. If aberkennung
// is ever built, it is its own later wave's decision, not a column added speculatively here.
//
// **Why NOT `audit_log_entry`**: same doctrine `38-crm.kuml.kts`'s own file header states at length
// for `crm_interaction` -- that table (14-audit-log.kuml.kts) is the GoBD hash-chained,
// append-only ledger for financially/legally load-bearing mutations, and explicitly excludes
// ordinary master-data CRUD and free-text content. `member_honor.note`/`title` are exactly that
// kind of free-text master data; `member_honor` writes are never routed through
// `AuditLogRecorder`.
//
// **`awardedBy` is a plain free-text column, NEVER a «Column».fkEntity reference**: unlike
// `recordedBy` (who USED this software to enter the row -- always a member with an account), the
// entity that AWARDED the honor is very often not representable as a single `member` row at all --
// "der Vorstand", "die Mitgliederversammlung", a named external body, or simply nobody remembered
// to note it down (nullable). Forcing this into a member FK would either be wrong (a body of
// people is not one member) or would silently drop the common "issued by the organization as a
// whole" case. VARCHAR(200), nullable.
//
// **`recorded_by` IS a «Column».fkEntity reference to Member**: this is the operator who entered
// this record into the system (always BOARD/ADMIN, see MemberHonorService), tracked for the same
// accountability reason every other domain file's own `created_by`/`recorded_by` column exists.
//
// **This file carries a minimal id-only Member stub** (owned by Foundation), purely so
// `UmlToErmTransformer` can resolve this file's «Column».fkEntity overrides within this
// single-file evaluation -- same cross-domain-stub pattern every later domain file (37-webhook,
// 38-crm, 39-events, 40-bank-statement) already establishes.
//
// **FK-naming choice**: `member_id`/`recorded_by` are plain «Column» UUID attributes with
// «Column».fkEntity, NEVER a UML association -- same domain-wide policy 21-auction.kuml.kts's own
// header documents at length, already followed by every later domain file.
//
// **No unique constraint**: a member can legitimately be honored more than once on the same day
// (e.g. an Ehrenmitgliedschaft AND a Verdienstauszeichnung voted on in the same Mitgliederver-
// sammlung) -- deliberately not prevented.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "MemberHonor") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    // Resolves member_honor.member_id/recorded_by's «Column».fkEntity overrides within this
    // single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order load-bearing (MemberHonorSchemaDriftTest pins it against
    // network.lapis.cloud.shared.domain.MemberHonorCategory). Longest literal
    // HONORARY_MEMBERSHIP (19) -> VARCHAR(19).
    val memberHonorCategory = enumOf(name = "MemberHonorCategory") {
        literal(name = "HONORARY_MEMBERSHIP")
        literal(name = "SERVICE_AWARD")
        literal(name = "LOYALTY_AWARD")
        literal(name = "OTHER")
    }

    val memberHonor = classOf(name = "MemberHonor") {
        stereotype("Entity") { "tableName" to "member_honor"; "kotlinObjectName" to "MemberHonorTable" }
        stereotype("Index") { "columns" to listOf("member_id", "awarded_at"); "name" to "idx_member_honor_member" }
        stereotype("Index") { "columns" to listOf("awarded_at"); "name" to "idx_member_honor_awarded_at" }
        stereotype("Index") { "columns" to listOf("category"); "name" to "idx_member_honor_category" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "category", type = memberHonorCategory) {
            stereotype("Column") {
                "columnName" to "category"
                "enumType" to "network.lapis.cloud.shared.domain.MemberHonorCategory"
            }
        }
        // The actual display text -- see file header "Why category AND title both exist".
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "awardedAt", type = "LocalDate") {
            stereotype("Column") { "columnName" to "awarded_at" }
        }
        // Free text, no FK -- see file header "awardedBy is a plain free-text column".
        attribute(name = "awardedBy", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "awarded_by"; "sqlType" to "VARCHAR(200)" }
        }
        // Vorstandskommentar über die Person -- genulled on the honoree's Art. 17 erasure, see
        // MemberHonorPersonalData KDoc.
        attribute(name = "note", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "note"; "sqlType" to "VARCHAR(4000)" }
        }
        attribute(name = "recordedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "recorded_by"; "fkEntity" to "Member" }
        }
        attribute(name = "recordedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "recorded_at" }
        }
    }
}
