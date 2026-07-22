// Politiker-Profile und Politiker-Ranking domain (V0.6.4) -- see the concept document
// ("03 Bereiche/Lapis Cloud/Meritokratisches System und Libertaler.md", "Ausnahme: Politiker sind
// nie pseudonym" and "Politiker-Profile und Politiker-Ranking" sections, vault) for the full
// fachlich specification this implements.
//
// **Single shared LTR-weight pool, split by ratio -- not a per-politician isolated sum.** The
// concept text ("Der LTR-Gewichts-Pool wird gebildet aus den aktuellen LTR-Bestaenden aller
// Bewerter... Der Pool wird proportional zu den Korb-Inhalten auf die Politiker verteilt") is
// structurally identical to 17-crowdfunding.kuml.kts's monthly EUR pool -- same
// largest-remainder-apportionment split, different pool source. See
// network.lapis.cloud.server.rpc.PoliticianTrustWeightCalculator KDoc for the exact algorithm;
// `member_trust_weight`/`member_like_count`/`member_dislike_count` on
// `politician_weight_snapshot` are the only PERSISTED trace of a computation -- the live number
// shown on a profile is always recomputed on read from `politician_reaction` plus the current
// LTR ledger, never cached (same "derive, don't cache" idiom `ltr_ledger_entry`/
// `CrowdfundingWeightDecay.currentWeight` already establish).
//
// **`politician_weight_snapshot` is a manually-triggered historical record, not a live cache** --
// this codebase has no scheduler/cron-job infrastructure anywhere (verified, same absence
// 17-crowdfunding.kuml.kts's own header documents for its monthly EUR distribution). One row per
// (politician, calendar month), written by an explicit BOARD/ADMIN
// `IPoliticianService.snapshotWeights` call, unique-indexed so the same month can never be
// snapshotted twice for the same politician.
//
// **Scope-cut: member-only rating, no Gast basket.** The concept document's Mitglied/Gast
// two-basket mechanic needs an operational Gast (guest) identity -- `MemberStatus.GAST`
// (00-foundation.kuml.kts) is an inert enum literal nothing in this codebase ever sets, queries,
// or transitions a member into (verified, same absence 18-peer-transfer.kuml.kts's own header
// documents when scope-cutting a Gast transfer recipient). Building a permanently-empty guest
// basket against a status nothing can reach would be decorative, not a real feature. This wave
// ships member-only rating; no `guest_*` columns exist anywhere in this file -- a future wave
// (dependent on a real member-management overhaul introducing an operational Gast status) adds
// them additively once there is something real to compute over. See
// network.lapis.cloud.shared.rpc.IPoliticianService KDoc for the same scope-cut restated at the
// RPC-contract level.
//
// **Scope-cut: real-name enforcement is a documented no-op.** The concept document's "Politiker
// sind nie pseudonym" is a rule about overriding a pseudonym-display layer -- this codebase has no
// pseudonym-display layer at all (`MemberDto.displayName` is already the only, always-shown name
// for every member, politician or not, see 18-peer-transfer.kuml.kts's own "no pseudonym-display
// layer" scope-cut). There is therefore nothing to override; `PoliticianProfileDto.displayName`
// denormalizes the same `member.display_name` every other domain already shows.
//
// **Re-grant reactivates the SAME profile row, never a second one.** `politician_profile.member_id`
// is UNIQUE -- `IPoliticianService.grantPoliticianStatus` on a member with an existing FORMER
// profile flips it back to ACTIVE (fresh granted_at/granted_by, cleared revoked_at/revoked_by)
// rather than inserting a new row. Combined with `revokePoliticianStatus` deleting every
// `politician_reaction`/`politician_weight_snapshot` row for the profile (see that RPC method's own
// KDoc), a re-granted politician always starts back at Korb=0 -- directly implementing the concept
// document's "kein strategischer Vorteil durch Status-Reset" claim structurally, not just by
// convention.
//
// **FK-naming choice**: every FK to `member` in this file -- including
// `politician_reaction.rater_member_id` -- is a plain «Column» UUID attribute with
// «Column».fkEntity="Member", NOT a UML association. `politician_profile` alone has THREE
// (`member_id`, `granted_by_member_id`, `revoked_by_member_id`), and an association's
// class-derived default FK column name could only ever match one of them. `rater_member_id` is
// the ONLY member FK on `politician_reaction`, but a real UML association still doesn't fit: an
// association's derived column name comes from the association's own default (which would be
// "member_id", matching `crowdfunding_reaction.member_id`'s case), NOT from the target-role label
// -- `role = "raterMemberId"` does not rename the generated column, it is purely a UML-level
// label (confirmed the hard way: UmlToErmTransformer produced `member_id`, not `rater_member_id`,
// which broke `PoliticianSchemaDriftTest` until this was switched to the plain-«Column» idiom).
// Exactly the same idiom `crowdfunding_project.submitter_member_id`/`reviewed_by`,
// `motion.submitter_member_id`, and `peer_transfer`'s three member FKs already use (see
// 05-governance.kuml.kts, 17-crowdfunding.kuml.kts and 18-peer-transfer.kuml.kts file headers) --
// a UML association is only safe here when its association-derived default name is *already* the
// real column name, never as a way to request a custom one.
//
// This file, symmetrically, carries a minimal id-only Member stub (owned by Foundation) purely so
// UmlToErmTransformer can resolve every «Column».fkEntity override within this single-file
// evaluation -- same cross-domain-stub pattern every other domain in this codebase already
// establishes.
//
// **11-organization-settings.kuml.kts also gains one new field this wave**:
// `politicianRankingEnabled` (NOT NULL, defaults FALSE) -- see that file's own header addendum.
// Modelled as an independent opt-in flag, NOT folded into `isPoliticalParty` -- same "legal-
// classification flag and feature-visibility toggle are different concerns that happen to
// correlate for most real deployments" reasoning `postalMailEnabled` already established in
// V0.4.2 (see that field's own comment in 11-organization-settings.kuml.kts).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Politician") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by every
    // prior domain's own Member stub. Resolves member_id/granted_by_member_id/revoked_by_member_id/
    // computed_by_member_id/rater_member_id's «Column».fkEntity overrides, all within this
    // single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing: PoliticianSchemaDriftTest asserts ErmDataType.Enum.values in
    // exactly this order, matching network.lapis.cloud.shared.domain.PoliticianProfileStatus.
    val politicianProfileStatus = enumOf(name = "PoliticianProfileStatus") {
        literal(name = "ACTIVE")
        literal(name = "FORMER")
    }

    // Literal order is load-bearing, same reason as above -- matches
    // network.lapis.cloud.shared.domain.PoliticianReactionValue.
    val politicianReactionValue = enumOf(name = "PoliticianReactionValue") {
        literal(name = "LIKE")
        literal(name = "DISLIKE")
    }

    val politicianProfile = classOf(name = "PoliticianProfile") {
        stereotype("Entity") { "tableName" to "politician_profile"; "kotlinObjectName" to "PoliticianProfileTable" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_politician_profile_status" }
        // Enforces "exactly one profile per member, ever" -- see file header "Re-grant reactivates
        // the SAME profile row".
        stereotype("Index") { "columns" to listOf("member_id"); "unique" to true; "name" to "uq_politician_profile_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> member (id), NOT NULL, UNIQUE (via the class-level «Index» above). Plain
        // «Column» UUID attribute -- see file header "FK-naming choice".
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "status", type = politicianProfileStatus) {
            defaultValue = "ACTIVE"
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.PoliticianProfileStatus"
            }
        }
        // "Funktion/Mandat" free text, e.g. "Bundestagsabgeordneter, Wahlkreis X".
        attribute(name = "mandateText", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "mandate_text"; "sqlType" to "VARCHAR(2000)" }
        }
        attribute(name = "grantedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "granted_at" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // "FK-naming choice".
        attribute(name = "grantedByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "granted_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "revokedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revoked_at" }
        }
        // Real FK -> member (id), nullable -- null while ACTIVE, set on revocation. Plain «Column»
        // UUID attribute -- see file header "FK-naming choice".
        attribute(name = "revokedByMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revoked_by_member_id"; "fkEntity" to "Member" }
        }
    }

    val politicianReaction = classOf(name = "PoliticianReaction") {
        stereotype("Entity") { "tableName" to "politician_reaction"; "kotlinObjectName" to "PoliticianReactionTable" }
        stereotype("Index") {
            "columns" to listOf("politician_profile_id", "rater_member_id")
            "unique" to true
            "name" to "uq_politician_reaction_profile_rater"
        }
        stereotype("Index") { "columns" to listOf("politician_profile_id"); "name" to "idx_politician_reaction_profile" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> politician_profile (id), NOT NULL. Plain «Column» UUID attribute -- same
        // "want a different column name than the association default" trigger
        // `crowdfunding_reaction.project_id` already uses, applied to a non-Member target class
        // here.
        attribute(name = "politicianProfileId", type = "UUID") {
            stereotype("Column") { "columnName" to "politician_profile_id"; "fkEntity" to "PoliticianProfile" }
        }
        // Named "reactionValue"/"reaction_value", not "value" -- VALUE is a reserved SQL keyword
        // (H2 rejects an unquoted column literally named "value"; ANSI SQL reserves it too), same
        // reasoning `crowdfunding_reaction.reaction_value` already documents.
        attribute(name = "reactionValue", type = politicianReactionValue) {
            stereotype("Column") {
                "columnName" to "reaction_value"
                "enumType" to "network.lapis.cloud.shared.domain.PoliticianReactionValue"
            }
        }
        attribute(name = "castAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "cast_at" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute, not a UML association
        // -- see file header "FK-naming choice" for why an association can't produce this name.
        attribute(name = "raterMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "rater_member_id"; "fkEntity" to "Member" }
        }
    }

    val politicianWeightSnapshot = classOf(name = "PoliticianWeightSnapshot") {
        stereotype("Entity") {
            "tableName" to "politician_weight_snapshot"
            "kotlinObjectName" to "PoliticianWeightSnapshotTable"
        }
        stereotype("Index") {
            "columns" to listOf("politician_profile_id", "period_month")
            "unique" to true
            "name" to "uq_politician_weight_snapshot_profile_period"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> politician_profile (id), NOT NULL. Plain «Column» UUID attribute -- same
        // reasoning as politician_reaction.politician_profile_id above.
        attribute(name = "politicianProfileId", type = "UUID") {
            stereotype("Column") { "columnName" to "politician_profile_id"; "fkEntity" to "PoliticianProfile" }
        }
        // First-of-month sentinel, same idiom as crowdfunding_distribution.period_start.
        attribute(name = "periodMonth", type = "LocalDate") {
            stereotype("Column") { "columnName" to "period_month" }
        }
        attribute(name = "memberTrustWeight", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "member_trust_weight"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "memberLikeCount", type = "Int") {
            stereotype("Column") { "columnName" to "member_like_count" }
        }
        attribute(name = "memberDislikeCount", type = "Int") {
            stereotype("Column") { "columnName" to "member_dislike_count" }
        }
        attribute(name = "computedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "computed_at" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // "FK-naming choice".
        attribute(name = "computedByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "computed_by_member_id"; "fkEntity" to "Member" }
        }
    }
}
