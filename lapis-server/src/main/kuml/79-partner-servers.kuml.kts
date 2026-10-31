// Welle V1.9.244 "Partnerserver P1" -- see network.lapis.cloud.server.partner.PartnerServerStore, V131__partner_server_feed.sql,
// docs/features/partner-servers.adoc and docs/architecture/partner-server-feed.adoc for the full rationale.
//
// **This file models three tables of P1 plus (V1.9.245 "Partnerserver P2", V132__partner_recursion.sql) `partner_discovered_server`**:
//   * `partner_discovered_server`: servers reached THROUGH a direct partner (hop >= 2), a pure cache rooted at the direct partner
//     (ON DELETE CASCADE) and excluded from the backup. `partner_event` gained the provenance columns discovered_server_id / hops / origin_host / via_hosts
//     and its unique constraint is now (remote_server_id, remote_event_id).
// **P1:**
//   * `partner_server`: one row per DIRECTLY connected partner host (hosts only, normalised by PartnerHost). `remote_server_id` is the id the
//     partner announces in its feed; it is bound to exactly one host (uq_partner_server_remote_id), so a malicious partner cannot claim the id
//     of another one. `remote_partners_json` holds the partner's own partner list read-only for a later wave (P2) and is never interpreted or fetched.
//   * `partner_event`: the cache of the partners' public events, refilled hourly. Unique per (partner row, remote event id) -- the PARTNER ROW scopes
//     the event id, not the claimed server id. Deleted together with the partner (ON DELETE CASCADE, plain SQL in V131).
//     starts_at / ends_at are UTC instants (time class A); `time_zone` is the partner's IANA zone for the display only.
//     **Backup**: excluded (OrganizationSchemaCatalog.EXCLUDED_TABLES) -- a refilled cache of foreign data.
//   * `partner_blocklist`: host patterns (`host` or `*.host`, lower case) that are never fetched and never shown.
//
// The new columns `partner_list_hidden` / `partner_server_id` of `organization_settings` are modelled in 11-organization-settings.kuml.kts.
// CHECK constraints, the CASCADE and the audit CHECK widening are plain SQL in V131 (not expressible in the ERM profile).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "PartnerServers") {
    applyProfile(ermMappingProfile)

    classOf(name = "PartnerServer") {
        stereotype("Entity") { "tableName" to "partner_server"; "kotlinObjectName" to "PartnerServerTable" }

        stereotype("Index") { "columns" to listOf("host"); "unique" to true; "name" to "uq_partner_server_host" }
        stereotype("Index") { "columns" to listOf("remote_server_id"); "unique" to true; "name" to "uq_partner_server_remote_id" }
        stereotype("Index") { "columns" to listOf("active", "next_fetch_at"); "name" to "idx_partner_server_due" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "host", type = "String") {
            stereotype("Column") { "columnName" to "host"; "sqlType" to "VARCHAR(253)" }
        }
        attribute(name = "displayName", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "display_name"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "remoteServerId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "remote_server_id" }
        }
        attribute(name = "remoteName", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "remote_name"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "active", type = "Boolean") {
            defaultValue = "TRUE"
            stereotype("Column") { "columnName" to "active" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "lastFetchAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_fetch_at" }
        }
        attribute(name = "lastStatus", type = "String") {
            defaultValue = "NEVER"
            stereotype("Column") { "columnName" to "last_status"; "sqlType" to "VARCHAR(5)" }
        }
        attribute(name = "lastError", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_error"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "eventCount", type = "Int") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "event_count" }
        }
        attribute(name = "consecutiveFailures", type = "Int") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "consecutive_failures" }
        }
        attribute(name = "nextFetchAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "next_fetch_at" }
        }
        attribute(name = "etag", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "etag"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "remotePartnersJson", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "remote_partners_json"; "sqlType" to "VARCHAR(65536)" }
        }
    }

    classOf(name = "PartnerEvent") {
        stereotype("Entity") { "tableName" to "partner_event"; "kotlinObjectName" to "PartnerEventTable" }

        stereotype("Index") { "columns" to listOf("remote_server_id", "remote_event_id"); "unique" to true; "name" to "uq_partner_event_remote" }
        stereotype("Index") { "columns" to listOf("discovered_server_id"); "name" to "idx_partner_event_discovered" }
        stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_partner_event_expires" }
        stereotype("Index") { "columns" to listOf("starts_at", "remote_event_id"); "name" to "idx_partner_event_start" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "partnerServerId", type = "UUID") {
            stereotype("Column") { "columnName" to "partner_server_id"; "fkEntity" to "PartnerServer" }
        }
        attribute(name = "remoteServerId", type = "UUID") {
            stereotype("Column") { "columnName" to "remote_server_id" }
        }
        attribute(name = "remoteEventId", type = "String") {
            stereotype("Column") { "columnName" to "remote_event_id"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "startsAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "starts_at" }
        }
        attribute(name = "endsAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "ends_at" }
        }
        attribute(name = "timeZone", type = "String") {
            stereotype("Column") { "columnName" to "time_zone"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "online", type = "Boolean") {
            stereotype("Column") { "columnName" to "online" }
        }
        attribute(name = "place", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "place"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "summary", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "summary"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "recurrenceSentence", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "recurrence_sentence"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "url", type = "String") {
            stereotype("Column") { "columnName" to "url"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "fetchedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "fetched_at" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "expires_at" }
        }
        // Welle V1.9.245 "Partnerserver P2" (V132): provenance. partnerServerId stays the ROOT (the direct partner); hops 1 = the direct partner itself.
        attribute(name = "discoveredServerId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "discovered_server_id"; "fkEntity" to "PartnerDiscoveredServer" }
        }
        attribute(name = "hops", type = "Int") {
            defaultValue = "1"
            stereotype("Column") { "columnName" to "hops"; "sqlType" to "SMALLINT" }
        }
        attribute(name = "originHost", type = "String") {
            stereotype("Column") { "columnName" to "origin_host"; "sqlType" to "VARCHAR(253)" }
        }
        attribute(name = "viaHosts", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "via_hosts"; "sqlType" to "VARCHAR(800)" }
        }
    }

    classOf(name = "PartnerDiscoveredServer") {
        stereotype("Entity") { "tableName" to "partner_discovered_server"; "kotlinObjectName" to "PartnerDiscoveredServerTable" }

        stereotype("Index") { "columns" to listOf("host"); "unique" to true; "name" to "uq_partner_discovered_host" }
        stereotype("Index") { "columns" to listOf("remote_server_id"); "unique" to true; "name" to "uq_partner_discovered_remote_id" }
        stereotype("Index") { "columns" to listOf("next_fetch_at"); "name" to "idx_partner_discovered_due" }
        stereotype("Index") { "columns" to listOf("root_partner_server_id"); "name" to "idx_partner_discovered_root" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "host", type = "String") {
            stereotype("Column") { "columnName" to "host"; "sqlType" to "VARCHAR(253)" }
        }
        attribute(name = "remoteServerId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "remote_server_id" }
        }
        attribute(name = "remoteName", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "remote_name"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "rootPartnerServerId", type = "UUID") {
            stereotype("Column") { "columnName" to "root_partner_server_id"; "fkEntity" to "PartnerServer" }
        }
        attribute(name = "hops", type = "Int") {
            stereotype("Column") { "columnName" to "hops"; "sqlType" to "SMALLINT" }
        }
        attribute(name = "viaHosts", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "via_hosts"; "sqlType" to "VARCHAR(800)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "lastReachedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_reached_at" }
        }
        attribute(name = "lastFetchAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_fetch_at" }
        }
        attribute(name = "lastStatus", type = "String") {
            defaultValue = "NEVER"
            stereotype("Column") { "columnName" to "last_status"; "sqlType" to "VARCHAR(5)" }
        }
        attribute(name = "lastError", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_error"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "eventCount", type = "Int") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "event_count" }
        }
        attribute(name = "consecutiveFailures", type = "Int") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "consecutive_failures" }
        }
        attribute(name = "nextFetchAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "next_fetch_at" }
        }
        attribute(name = "etag", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "etag"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "remotePartnersJson", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "remote_partners_json"; "sqlType" to "VARCHAR(65536)" }
        }
        attribute(name = "partnersHidden", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "partners_hidden" }
        }
    }

    classOf(name = "PartnerBlocklist") {
        stereotype("Entity") { "tableName" to "partner_blocklist"; "kotlinObjectName" to "PartnerBlocklistTable" }

        stereotype("Index") { "columns" to listOf("pattern"); "unique" to true; "name" to "uq_partner_blocklist_pattern" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "pattern", type = "String") {
            stereotype("Column") { "columnName" to "pattern"; "sqlType" to "VARCHAR(255)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }
}
