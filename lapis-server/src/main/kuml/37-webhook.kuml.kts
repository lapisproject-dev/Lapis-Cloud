// Welle V1.3.2 "Webhooks" (ausgehend) -- see `network.lapis.cloud.server.webhook.*` for the
// signature/delivery/retry mechanics these two tables back, and
// `network.lapis.cloud.server.rpc.WebhookService` for the RPC-facing write path.
//
// **This file models exactly two new tables.** `webhook_endpoint` is 1:1 with an `api_key` row
// (see `uq_webhook_endpoint_api_key`) -- an org configures at most one outbound webhook destination
// per issued API key, mirroring that key's own BOARD/ADMIN-only lifecycle. `webhook_delivery` is
// the outbox/retry-queue table: one row per (endpoint, event) pair, `uq_webhook_delivery_event`
// enforcing idempotent insertion even if `WebhookEventPublisher.publish` were ever accidentally
// called twice for the same fact.
//
// **FK-naming choice**: every `*_member_id`/`api_key_id` reference is a plain «Column» UUID
// attribute with «Column».fkEntity, NEVER a UML association -- same domain-wide policy
// 21-auction.kuml.kts's own header documents at length, already followed by 36-api-key.kuml.kts.
//
// **This file carries minimal id-only Member and ApiKey stubs** (owned by Foundation resp. by
// 36-api-key.kuml.kts), purely so `UmlToErmTransformer` can resolve this file's «Column».fkEntity
// overrides within this single-file evaluation -- same cross-domain-stub pattern
// 36-api-key.kuml.kts already establishes for its own Member stub.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Webhook") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors 36-api-key.kuml.kts's own Member stub. Resolves
    // webhook_endpoint.created_by_member_id/updated_by_member_id/deactivated_by_member_id's
    // «Column».fkEntity override within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // 36-api-key.kuml.kts-owned stub -- id-only. Resolves webhook_endpoint.api_key_id's
    // «Column».fkEntity override within this single-file evaluation.
    val apiKey = classOf(name = "ApiKey") {
        stereotype("Entity") { "tableName" to "api_key"; "kotlinObjectName" to "ApiKeyTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val webhookEndpoint = classOf(name = "WebhookEndpoint") {
        stereotype("Entity") { "tableName" to "webhook_endpoint"; "kotlinObjectName" to "WebhookEndpointTable" }
        stereotype("Index") { "columns" to listOf("api_key_id"); "name" to "uq_webhook_endpoint_api_key"; "unique" to true }
        stereotype("Index") { "columns" to listOf("active"); "name" to "idx_webhook_endpoint_active" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "apiKeyId", type = "UUID") {
            stereotype("Column") { "columnName" to "api_key_id"; "fkEntity" to "ApiKey" }
        }
        attribute(name = "url", type = "String") {
            stereotype("Column") { "columnName" to "url"; "sqlType" to "VARCHAR(2048)" }
        }
        // SecretBox.seal(...) wire-format ciphertext -- never the plaintext secret. See
        // WebhookSigner KDoc "Secret".
        attribute(name = "secretSealed", type = "String") {
            stereotype("Column") { "columnName" to "secret_sealed"; "sqlType" to "VARCHAR(512)" }
        }
        // First 16 characters of the raw "whsec_lapis_..." secret -- display-only, same
        // non-secret-prefix idiom api_key.key_prefix already establishes.
        attribute(name = "secretPrefix", type = "String") {
            stereotype("Column") { "columnName" to "secret_prefix"; "sqlType" to "VARCHAR(24)" }
        }
        attribute(name = "active", type = "Boolean") {
            stereotype("Column") { "columnName" to "active" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdByMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedByMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "deactivatedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "deactivated_at" }
        }
        attribute(name = "deactivatedByMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "deactivated_by_member_id"; "fkEntity" to "Member" }
        }
        // WebhookDeactivationReason.name -- NULL while active. See that enum's own KDoc.
        attribute(name = "deactivationReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "deactivation_reason"; "sqlType" to "VARCHAR(32)" }
        }
    }

    val webhookDelivery = classOf(name = "WebhookDelivery") {
        stereotype("Entity") { "tableName" to "webhook_delivery"; "kotlinObjectName" to "WebhookDeliveryTable" }
        stereotype("Index") { "columns" to listOf("endpoint_id", "event_id"); "name" to "uq_webhook_delivery_event"; "unique" to true }
        // Hot-path index for WebhookDeliveryPoller's own candidate scan -- see that class's own
        // KDoc "Phase A". Deliberately checked against a REAL information_schema introspection by
        // WebhookSchemaDriftTest, not merely against the generated Kotlin (S11 -- the m2m-exposed
        // codegen is known to drop index {} declarations, see 36-api-key.kuml.kts's own comment).
        stereotype("Index") { "columns" to listOf("status", "next_attempt_at"); "name" to "idx_webhook_delivery_due" }
        stereotype("Index") { "columns" to listOf("endpoint_id", "created_at"); "name" to "idx_webhook_delivery_endpoint" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "endpointId", type = "UUID") {
            stereotype("Column") { "columnName" to "endpoint_id"; "fkEntity" to "WebhookEndpoint" }
        }
        // Stable across every retry attempt of the SAME delivery -- the `Lapis-Webhook-Id` header
        // value, the receiver's idempotency key. Deliberately NOT the primary key `id` itself
        // (kept separate so a future replay/requeue feature could mint a new `id` while preserving
        // the receiver-visible identity) -- see WebhookSigner KDoc "Ausgehende Header".
        attribute(name = "eventId", type = "UUID") {
            stereotype("Column") { "columnName" to "event_id" }
        }
        // WebhookEventType.name (Kotlin enum name, e.g. "RESOLUTION_ADOPTED") -- NOT the dotted
        // wire name ("resolution.adopted") that travels in the JSON payload/header. See that
        // enum's own KDoc for why the two representations deliberately differ.
        attribute(name = "eventType", type = "String") {
            stereotype("Column") { "columnName" to "event_type"; "sqlType" to "VARCHAR(48)" }
        }
        attribute(name = "entityId", type = "UUID") {
            stereotype("Column") { "columnName" to "entity_id" }
        }
        attribute(name = "occurredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "occurred_at" }
        }
        // The exact signed body -- serialized once at publish time and never rebuilt on retry
        // (S4 in the plan's Stolperfallen list: rebuilding per-attempt risks a BigDecimal-scale/
        // field-order drift that would silently break the signature a receiver already saw on a
        // prior attempt).
        attribute(name = "payload", type = "String") {
            stereotype("Column") { "columnName" to "payload"; "sqlType" to "TEXT" }
        }
        // WebhookDeliveryStatus.name -- see that enum's own status-semantics KDoc.
        attribute(name = "status", type = "String") {
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "attemptCount", type = "Int") {
            stereotype("Column") { "columnName" to "attempt_count" }
        }
        attribute(name = "nextAttemptAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "next_attempt_at" }
        }
        attribute(name = "lastAttemptAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_attempt_at" }
        }
        attribute(name = "lastHttpStatus", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_http_status" }
        }
        // WebhookFailureReason.name ONLY -- never an exception message/hostname/IP. See that
        // enum's own KDoc.
        attribute(name = "lastError", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_error"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "deliveredAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "delivered_at" }
        }
    }
}
