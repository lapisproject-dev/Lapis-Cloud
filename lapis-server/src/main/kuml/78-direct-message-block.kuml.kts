// Welle V1.9.237 "Neue Nachricht" -- see network.lapis.cloud.server.messaging.DirectMessageBlockStore, V128__direct_message_compose.sql and
// docs/architecture/direct-messages.adoc for the full rationale.
//
// **This file models exactly one new table.** `direct_message_block` says "blocker does not want to receive direct messages from blocked". One row per
// ordered pair (unique constraint uq_direct_message_block_pair), never a self-block (chk_direct_message_block_not_self -- plain SQL CHECKs in V128, not
// expressible in the ERM profile). The new column `member.dm_discoverable` is modelled in 00-foundation.kuml.kts.
//
// **Own table, NOT member_finder_block**: the finder table is emptied when the finder switch goes off, can only be managed while it is on, belongs to the
// Art. 9 finder data and is excluded from the backup. A DM block must survive all of that. The DM rule additionally honours finder blocks (read only).
//
// **No CASCADE on the member FKs** -- the application layer is authoritative for cleanup (DSGVO erasure deletes both sides).
// **Backup**: stays IN the backup (not in OrganizationSchemaCatalog.EXCLUDED_TABLES): direct messages are backed up too, and a restore without the
// blocks would silently lift the protection.
//
// Cross-domain stub: minimal id-only `Member` (owned by `00-foundation.kuml.kts`), same single-file-evaluation pattern every domain file documents.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "DirectMessageBlock") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val directMessageBlock = classOf(name = "DirectMessageBlock") {
        stereotype("Entity") { "tableName" to "direct_message_block"; "kotlinObjectName" to "DirectMessageBlockTable" }

        stereotype("Index") { "columns" to listOf("blocker_member_id", "blocked_member_id"); "unique" to true; "name" to "uq_direct_message_block_pair" }
        stereotype("Index") { "columns" to listOf("blocked_member_id"); "name" to "idx_direct_message_block_blocked" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "blockerMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "blocker_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "blockedMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "blocked_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }
}
