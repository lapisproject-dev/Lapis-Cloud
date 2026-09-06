// Welle V1.4.4.4 "Mitgliederlebenszyklus: Familienmitgliedschaften" -- see
// network.lapis.cloud.server.rpc.MemberFamilyService for the read/write path and
// network.lapis.cloud.server.dsgvo.MemberFamilyPersonalData for Auskunft/Löschung.
//
// **This file models exactly two new tables**: `member_family` (a household grouping of members)
// and `member_family_link` (which member belongs to which family, in which role -- PAYER or
// DEPENDENT).
//
// **No `payer_member_id` column on `member_family`**: the payer is expressed ONLY as a role on
// `member_family_link` -- a single source of truth for "who pays", never two places that could
// disagree.
//
// **Exactly two role literals, `PAYER`/`DEPENDENT` -- deliberately NEVER `GUARDIAN`/`CHILD`/
// `SPOUSE`**: a relationship/kinship classification is sensitive personal data this wave has no
// beitragswesen-relevant use for. The only fact this domain needs is "who is billed" vs. "who is
// covered by someone else's billing".
//
// **No new `MemberStatus` literal for a dependent** (e.g. `FAMILY_MEMBER`): `MemberStatusSets
// .ORGANIZATION_MEMBER = setOf(ACTIVE)` -- introducing a parallel status would silently strip a
// dependent of voting rights, LTR participation, and governance eligibility, none of which this
// wave intends to touch. A dependent keeps their EXISTING `member.status` unchanged; only their
// `membership_tier_id` is nulled (see `network.lapis.cloud.server.rpc.MembershipTierAssignment`).
//
// **`payer_family_id` (on `member_family_link`) -- the tragende Entscheidung**: the natural
// invariant is "genau ein Zahler pro Familie", ideally enforced by a PARTIAL unique index
// (`UNIQUE (family_id) WHERE role = 'PAYER'`). **That is not buildable in this codebase**: H2's
// `MODE=PostgreSQL` -- the mode the WHOLE test suite runs against (`DatabaseConfig.connect()`) --
// rejects `CREATE UNIQUE INDEX ... WHERE`, and the generated-column cross-dialect workaround fails
// too (Postgres requires `STORED`, H2 rejects `STORED`) -- both already verified empirically in
// `V8__sepa_mandates.sql`/`V9__dunning.sql`. This file instead uses the SAME application-maintained
// shadow-column pattern `event_registration.active_participant_key` already establishes
// (`39-events.kuml.kts`): `payer_family_id` is NULL for a `DEPENDENT` link and equals `family_id`
// for a `PAYER` link. A plain (non-partial) UNIQUE index on `payer_family_id` then enforces "at
// most one PAYER per family" -- multiple NULLs are allowed under a unique index on both H2 and
// Postgres (the same property `uq_crm_contact_email` already relies on). A CHECK constraint
// (`V23__member_family.sql`) additionally forbids the shadow value from ever disagreeing with
// `role`/`family_id` -- an invariant STRONGER than a bare partial index would have given, because
// it also rules out a forged/hand-edited shadow value, not merely a second PAYER row. Written
// EXCLUSIVELY by `MemberFamilyService`.
//
// **One link per member (`uq_member_family_link_member`)**: a member can belong to at most one
// family at a time -- this wave has no concept of overlapping households. A member who needs to
// change families is REMOVED first, then added to the new one (two separate, auditable steps).
//
// **No `audit_log_entry` write for the CRUD itself** -- same doctrine `38-crm.kuml.kts`'s own file
// header states at length for `crm_interaction`/`41-member-honor.kuml.kts` restates for
// `member_honor`: family grouping/roster membership is master data, not a GoBD-relevant financial
// or legal mutation. The one exception is the SIDE EFFECT this domain triggers on `member`:
// `MembershipTierAssignment.apply` (the shared, single write path for
// `member.membership_tier_id`) DOES write an `AuditEntityType.MEMBER`/`UPDATE` entry whenever a
// family operation actually changes a member's tier -- see that object's own KDoc.
//
// **This file carries a minimal id-only Member stub** (owned by Foundation), purely so
// `UmlToErmTransformer` can resolve this file's «Column».fkEntity overrides within this
// single-file evaluation -- same cross-domain-stub pattern every later domain file (37-webhook,
// 38-crm, 39-events, 40-bank-statement, 41-member-honor) already establishes.
//
// **FK-naming choice**: `family_id`/`member_id`/`linked_by`/`created_by` are plain «Column» UUID
// attributes with «Column».fkEntity, NEVER a UML association -- same domain-wide policy
// 21-auction.kuml.kts's own header documents at length, already followed by every later domain
// file. `payer_family_id` is the one deliberate EXCEPTION: it carries NO «Column».fkEntity at
// all (see `MemberFamilyLinkTable.kt`'s own header comment for why a real FK there would make
// Exposed's implicit-join inference ambiguous against `family_id`, which already references the
// same table).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "MemberFamily") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    // Resolves member_family.created_by / member_family_link.member_id/linked_by's «Column».
    // fkEntity overrides within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order load-bearing (MemberFamilySchemaDriftTest pins it against
    // network.lapis.cloud.shared.domain.FamilyMemberRole). Longest literal DEPENDENT (9) ->
    // VARCHAR(9). NEVER add a third literal without re-reading this file's own header
    // ("Exactly two role literals").
    val familyMemberRole = enumOf(name = "FamilyMemberRole") {
        literal(name = "PAYER")
        literal(name = "DEPENDENT")
    }

    val memberFamily = classOf(name = "MemberFamily") {
        stereotype("Entity") { "tableName" to "member_family"; "kotlinObjectName" to "MemberFamilyTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    val memberFamilyLink = classOf(name = "MemberFamilyLink") {
        stereotype("Entity") { "tableName" to "member_family_link"; "kotlinObjectName" to "MemberFamilyLinkTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "unique" to true; "name" to "uq_member_family_link_member" }
        stereotype("Index") { "columns" to listOf("payer_family_id"); "unique" to true; "name" to "uq_member_family_link_payer" }
        stereotype("Index") { "columns" to listOf("family_id"); "name" to "idx_member_family_link_family" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "familyId", type = "UUID") {
            stereotype("Column") { "columnName" to "family_id"; "fkEntity" to "MemberFamily" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "role", type = familyMemberRole) {
            stereotype("Column") {
                "columnName" to "role"
                "enumType" to "network.lapis.cloud.shared.domain.FamilyMemberRole"
            }
        }
        // Application-maintained shadow column, NO «Column».fkEntity -- see file header "the
        // tragende Entscheidung". NULL for DEPENDENT, = family_id for PAYER.
        attribute(name = "payerFamilyId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "payer_family_id" }
        }
        attribute(name = "linkedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "linked_at" }
        }
        attribute(name = "linkedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "linked_by"; "fkEntity" to "Member" }
        }
    }
}
