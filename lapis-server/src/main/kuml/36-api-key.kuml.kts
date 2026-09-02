// V1.3.1 "API-Fundament, lesend" -- see `network.lapis.cloud.server.security.ApiKeyStore` for the
// write path and `network.lapis.cloud.server.routes.PublicApiRoutes` for the read surface this
// table authenticates.
//
// **This file models exactly one new table.** `api_key` is a long-lived, organization-issued
// Bearer credential (prefix `lapis_`, see `ApiKeyStore.API_KEY_TOKEN_PREFIX`) that a BOARD/ADMIN
// member mints for machine-to-machine access to the read-only `/api/v1/*` REST surface -- a
// SEPARATE credential namespace from `session` (see that table's own file header), never
// interchangeable with a session token (enforced in code, see `RequestContext.extractSessionToken`/
// `ApiKeyAuth.extractApiKeyToken`).
//
// **Only a hash of the raw key is ever stored** -- same "hash, never the bearer-usable secret
// itself" discipline `session.token_hash` already establishes (see `SessionStore` KDoc). `key_prefix`
// (the raw key's first 8 characters, e.g. `lapis_xx`) is stored in the clear purely so an admin can
// recognize/distinguish keys in a list without ever seeing the full secret again after issuance.
//
// **FK-naming choice**: `created_by_member_id`/`revoked_by_member_id` are plain «Column» UUID
// attributes with «Column».fkEntity, NEVER a UML association -- same domain-wide policy
// 21-auction.kuml.kts's own header documents at length.
//
// **This file carries a minimal id-only Member stub** (owned by Foundation), purely so
// `UmlToErmTransformer` can resolve this file's two «Column».fkEntity overrides within this
// single-file evaluation -- same cross-domain-stub pattern 35-public-ranking-consent.kuml.kts
// already establishes.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "ApiKey") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain's own Member stub. Resolves
    // api_key.created_by_member_id/revoked_by_member_id's «Column».fkEntity override within this
    // single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val apiKey = classOf(name = "ApiKey") {
        stereotype("Entity") { "tableName" to "api_key"; "kotlinObjectName" to "ApiKeyTable" }
        stereotype("Index") { "columns" to listOf("revoked_at", "expires_at"); "name" to "idx_api_key_active" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "label", type = "String") {
            stereotype("Column") { "columnName" to "label"; "sqlType" to "VARCHAR(100)" }
        }
        // SHA-256 hex of the full raw key (including the lapis_ prefix) -- UNIQUE, same
        // "resolve by hash lookup" idiom session.token_hash already establishes.
        attribute(name = "tokenHash", type = "String") {
            stereotype("Column") { "columnName" to "token_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        // First 8 characters of the raw key (e.g. "lapis_xx") -- display-only, never
        // security-relevant on its own.
        attribute(name = "keyPrefix", type = "String") {
            stereotype("Column") { "columnName" to "key_prefix"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "expires_at" }
        }
        attribute(name = "revokedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revoked_at" }
        }
        attribute(name = "revokedByMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revoked_by_member_id"; "fkEntity" to "Member" }
        }
        // Best-effort last-use marker -- written by ApiKeyStore.touchLastUsed, throttled to a
        // 5-minute resolution (never on every single request) so a busy integration does not turn
        // every read into an extra write. See that function's own KDoc.
        attribute(name = "lastUsedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_used_at" }
        }
    }
}
