// Welle V1.9.297 "Schwarzes Brett" -- see network.lapis.cloud.server.bulletin / network.lapis.cloud.server.rpc.NoticeBoardService,
// V149__bulletin_board.sql, docs/features/bulletin-board.adoc and docs/architecture/bulletin-board.adoc for the full rationale.
//
// Two tables: `bulletin_notice` (a member's notice with category, title, text, expiry, pin, status and removal) and `bulletin_notice_boost`
// (one LTR stake on a notice, exactly like `social_post_boost`: a boost is a payment, so there is NO unique pair and NO cascade; the weight is
// derived at read time with the social network's decay, never stored). The names are `bulletin_*` on purpose: `carpool_posting`,
// `CarpoolNoticeKind` and `activism_notice_text` already use "notice".
//
// Enum-like columns (`category`, `status`, `removal_reason_code`) are Strings here with the width in `sqlType`; the hand-written Exposed objects map
// them to the shared enums. CHECK constraints (lengths, the 90 day expiry window, the removal / withdrawal coherence), the ledger / image / audit
// CHECK widening and the foreign keys beyond the declared ones are plain SQL in V149. `author_member_id`, `pinned_by`, `removed_by` and
// `member_id` are plain «Column» UUIDs with fkEntity, NO association(...) block (same reasoning as 57-carpool.kuml.kts).
//
// DSGVO: the notice is personal data of its author; network.lapis.cloud.server.dsgvo.NoticePersonalData exports and erases it.
// Cross-domain stub (id-only): Member, owned by its own model file.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "BulletinBoard") {
    applyProfile(ermMappingProfile)

    classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    classOf(name = "BulletinNotice") {
        stereotype("Entity") { "tableName" to "bulletin_notice"; "kotlinObjectName" to "BulletinNoticeTable" }
        stereotype("Index") { "columns" to listOf("status", "expires_at"); "name" to "idx_bulletin_notice_status_expires" }
        stereotype("Index") { "columns" to listOf("author_member_id", "status"); "name" to "idx_bulletin_notice_author_status" }
        stereotype("Index") { "columns" to listOf("pinned_at"); "name" to "idx_bulletin_notice_pinned" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "authorMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "author_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "category", type = "String") {
            stereotype("Column") { "columnName" to "category"; "sqlType" to "VARCHAR(8)" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(120)" }
        }
        attribute(name = "body", type = "String") {
            stereotype("Column") { "columnName" to "body"; "sqlType" to "VARCHAR(2000)" }
        }
        // Class A (UTC): created, last edited (NULL = never edited), last moment it is shown, pinned, withdrawn, removed.
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "expires_at" }
        }
        attribute(name = "pinnedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "pinned_at" }
        }
        attribute(name = "pinnedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "pinned_by"; "fkEntity" to "Member" }
        }
        attribute(name = "status", type = "String") {
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(9)" }
        }
        attribute(name = "withdrawnAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "withdrawn_at" }
        }
        attribute(name = "removedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "removed_at" }
        }
        attribute(name = "removedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "removed_by"; "fkEntity" to "Member" }
        }
        attribute(name = "removalReasonCode", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "removal_reason_code"; "sqlType" to "VARCHAR(9)" }
        }
        attribute(name = "removalNote", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "removal_note"; "sqlType" to "VARCHAR(500)" }
        }
    }

    classOf(name = "BulletinNoticeBoost") {
        stereotype("Entity") { "tableName" to "bulletin_notice_boost"; "kotlinObjectName" to "BulletinNoticeBoostTable" }
        stereotype("Index") { "columns" to listOf("notice_id"); "name" to "idx_bulletin_notice_boost_notice" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_bulletin_notice_boost_member" }
        stereotype("Index") {
            "columns" to listOf("notice_id", "member_id", "boosted_at")
            "name" to "idx_bulletin_notice_boost_dup"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "noticeId", type = "UUID") {
            stereotype("Column") { "columnName" to "notice_id"; "fkEntity" to "BulletinNotice" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "amountLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        // Own decay anchor, exactly like social_post_boost.boosted_at.
        attribute(name = "boostedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "boosted_at" }
        }
    }
}
