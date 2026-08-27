// V1.3.0 "Öffentliche Transparenz-Startseite" (`GET /transparenz`) -- see
// `network.lapis.cloud.server.routes.PublicTransparencyRoutes`/`PublicTransparencyReader`/
// `PublicTransparencyHtml` for the rendering pipeline this table feeds, and
// `network.lapis.cloud.server.rpc.PublicRankingConsentStore`/`PublicRankingConsentDisclaimer` for
// the write path (`IDsgvoService.grantPublicRankingConsent`/`.revokePublicRankingConsent`) it backs.
//
// **This file models exactly one new table.** `public_ranking_consent_event` is the APPEND-ONLY
// event log of a member opting a named ranking ("wie viel LTR halte ich" / "wie viel habe ich
// gespendet") IN or OUT of the public transparency page's two opt-in leaderboards. Never a mutable
// boolean column on `member` -- every grant/revoke writes a NEW row, and the previously-current row
// for that `(member_id, ranking_kind)` pair gets its `superseded_at` set (same append-only,
// "current row" idiom `session.revoked_at`/`committee_membership.until` already establish elsewhere
// in this domain). Two DIFFERENT `ranking_kind` values (`LTR_HOLDINGS`/`DONATIONS`) are tracked
// SEPARATELY and revocable independently -- an LTR-holdings figure is an internal-currency fact, a
// EUR donation figure is a statement about wealth and political affiliation for a political party;
// bundling the two consents would misrepresent what a member actually agreed to.
//
// **No partial UNIQUE index for "exactly one current row per (member_id, ranking_kind)".** H2 in
// PostgreSQL-compatibility mode (this codebase's test path, see `DatabaseConfig.kt`) does not
// support partial indexes. The invariant is instead enforced in code
// (`PublicRankingConsentStore.grant`/`.revoke`, under a `MemberTable` row lock -- same
// `SELECT ... FOR UPDATE` idiom `LtrBalanceProvider.lockForDebit` already establishes) and pinned by
// a concurrency test, not by the schema.
//
// **`consent_version`/`consent_sha256`** echo the exact versioned disclosure text
// (`PublicRankingConsentDisclaimer.of(kind)`) the member was shown at grant time -- same
// two-column "which exact wording did they see" shape `conference_guest_consent_acknowledgment
// .consent_version`/`.consent_sha256` already establishes (30-conference-guest-access.kuml.kts). A
// later wording change bumps the disclaimer's `VERSION`, which makes every EXISTING grant
// ineffective until the member re-consents to the new wording -- see `PublicRankingConsentStore
// .currentState`'s own KDoc.
//
// **Erased on a DSGVO deletion request** (unlike `conference_guest_consent_acknowledgment`, which
// is retained as an accountability record) -- see `PublicRankingConsentPersonalData`'s own KDoc for
// why: this table's only purpose is to gate an ONGOING public disclosure, not to prove a past,
// already-completed act of processing someone else's data. Once the member is gone, so is the
// consent that gated their own visibility.
//
// **FK-naming choice**: `member_id` is a plain «Column» UUID attribute with «Column».fkEntity,
// NEVER a UML association -- same domain-wide policy 21-auction.kuml.kts's own header documents at
// length.
//
// **This file carries a minimal id-only Member stub** (owned by Foundation), purely so
// `UmlToErmTransformer` can resolve this file's «Column».fkEntity override within this single-file
// evaluation -- same cross-domain-stub pattern 30-conference-guest-access.kuml.kts already
// establishes.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "PublicRankingConsent") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain's own Member stub. Resolves
    // public_ranking_consent_event.member_id's «Column».fkEntity override within this single-file
    // evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val publicRankingConsentEvent = classOf(name = "PublicRankingConsentEvent") {
        stereotype("Entity") {
            "tableName" to "public_ranking_consent_event"
            "kotlinObjectName" to "PublicRankingConsentEventTable"
        }
        stereotype("Index") { "columns" to listOf("ranking_kind", "member_id", "superseded_at"); "name" to "idx_prce_current" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_prce_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // "FK-naming choice".
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // 'LTR_HOLDINGS' (12 chars) | 'DONATIONS' (9 chars) -- see network.lapis.cloud.shared.domain.PublicRankingKind.
        attribute(name = "rankingKind", type = "String") {
            stereotype("Column") { "columnName" to "ranking_kind"; "sqlType" to "VARCHAR(12)" }
        }
        // 'GRANTED' (7) | 'REVOKED' (7) -- see network.lapis.cloud.shared.domain.PublicRankingConsentEventType.
        attribute(name = "eventType", type = "String") {
            stereotype("Column") { "columnName" to "event_type"; "sqlType" to "VARCHAR(7)" }
        }
        attribute(name = "occurredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "occurred_at" }
        }
        // NULL for the current row of this (member_id, ranking_kind) pair, set to the timestamp of
        // the NEXT event once one is written -- append-only "current row" marker, see file header.
        attribute(name = "supersededAt", type = "LocalDateTime") {
            multiplicity = dev.kuml.uml.Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "superseded_at" }
        }
        attribute(name = "consentVersion", type = "String") {
            stereotype("Column") { "columnName" to "consent_version"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "consentSha256", type = "String") {
            stereotype("Column") { "columnName" to "consent_sha256"; "sqlType" to "VARCHAR(64)" }
        }
    }
}
