// V0.8.1 Federation-Grundgerüst -- ActivityPub-compatible core + custom JSON-LD extension fields.
// This wave is deliberately scoped to the federation PROTOCOL layer only -- no existing content
// type is wired into outbound federation yet, see IFederationService KDoc "Scope boundary".
//
// **Actor = Organization, not Member**: this codebase is single-tenant (exactly one
// organization_settings row, see 11-organization-settings.kuml.kts) -- federation happens between
// whole server instances, so the federated ActivityPub Actor is the organization itself.
// federation_actor_key is a singleton table, same "genesis-singleton row" shape as
// organization_settings/audit_log_chain_state/crowdfunding_submission_gate/price_oracle_config.
//
// **federation_actor_key.actor_uri is NOT Flyway-seeded**, unlike every other singleton row --
// it depends on LAPIS_PUBLIC_BASE_URL, unknown when this migration is authored.
// FederationActorKeyProvisioner inserts the single row (fixed sentinel id
// '...-0000-0000000000f6', next unused slot after price_oracle_config's own '...-f5')
// idempotently on first Application.module() boot instead. Still registered in
// OrganizationRestoreService.SEEDED_SINGLETON_ROWS for the same "fresh restore target" reasoning
// every other singleton row already documents.
//
// **private_key_pem is the first genuinely round-trippable secret in this codebase** -- unlike
// account.password_hash/session.token_hash (one-way digests), the actor's private key must be
// read back to sign every outbound Activity. Stored as plaintext PEM: same DB-is-the-trust-
// boundary posture organization_settings.bank_iban already has. Flagged as an open question in
// the V0.8.1 plan, not a silent decision.
//
// **FK-naming**: federation_relationship_event.relationship_id is a plain «Column» UUID
// attribute with «Column».fkEntity="FederationRelationship", NOT a UML association -- same idiom
// 22-session.kuml.kts documents at length (CLAUDE.md kUML-Repo-Konventionen "FK-naming pitfall").
// Resolvable within this single-file evaluation since FederationRelationship is defined in this
// same file (no cross-file stub needed, unlike the Member-referencing FKs elsewhere).
//
// **federation_relationship_event is deliberately NOT hash-chained** like audit_log_entry
// (V0.5.3) -- the actor is a remote server, not a Member, so audit_log_entry.actor_member_id
// doesn't fit, and AuditEntityType's literal order is pinned to a bounded, unrelated financial/
// legal scope. Append-only by convention only (no update/delete call site). Flagged as an open
// question whether a later wave should hash-chain this too.
//
// **federation_inbox_delivery_log has no member FK** -- remote_host identifies a remote SERVER,
// not a member of this organization -- outside the existing member-scoped PersonalDataContributor
// model. No new PersonalDataContributor needed this wave.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Federation") {
    applyProfile(ermMappingProfile)

    val direction =
        enumOf(name = "FederationRelationshipDirection") {
            literal(name = "OUTBOUND")
            literal(name = "INBOUND")
        }

    val status =
        enumOf(name = "FederationRelationshipStatus") {
            literal(name = "PENDING")
            literal(name = "ACTIVE")
            literal(name = "REJECTED")
            literal(name = "UNDONE")
        }

    val eventType =
        enumOf(name = "FederationEventType") {
            literal(name = "FOLLOW_SENT")
            literal(name = "FOLLOW_RECEIVED")
            literal(name = "ACCEPT_SENT")
            literal(name = "ACCEPT_RECEIVED")
            literal(name = "REJECT_SENT")
            literal(name = "REJECT_RECEIVED")
            literal(name = "UNDO_SENT")
            literal(name = "UNDO_RECEIVED")
        }

    // Singleton row -- this server's own Actor keypair. See file header.
    val actorKey =
        classOf(name = "FederationActorKey") {
            stereotype("Entity") { "tableName" to "federation_actor_key"; "kotlinObjectName" to "FederationActorKeyTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "actorUri", type = "String") {
                stereotype("Column") { "columnName" to "actor_uri"; "sqlType" to "VARCHAR(2048)"; "unique" to true }
            }
            attribute(name = "publicKeyPem", type = "String") {
                stereotype("Column") { "columnName" to "public_key_pem"; "sqlType" to "TEXT" }
            }
            attribute(name = "privateKeyPem", type = "String") {
                stereotype("Column") { "columnName" to "private_key_pem"; "sqlType" to "TEXT" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
        }

    // One row per established/attempted inter-organization Follow relationship, either direction.
    val relationship =
        classOf(name = "FederationRelationship") {
            stereotype("Entity") { "tableName" to "federation_relationship"; "kotlinObjectName" to "FederationRelationshipTable" }
            stereotype("Index") { "columns" to listOf("status"); "name" to "idx_federation_relationship_status" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "direction", type = direction) {
                stereotype("Column") {
                    "columnName" to "direction"
                    "enumType" to "network.lapis.cloud.shared.domain.FederationRelationshipDirection"
                }
            }
            attribute(name = "status", type = status) {
                stereotype("Column") {
                    "columnName" to "status"
                    "enumType" to "network.lapis.cloud.shared.domain.FederationRelationshipStatus"
                }
            }
            attribute(name = "remoteActorUri", type = "String") {
                stereotype("Column") { "columnName" to "remote_actor_uri"; "sqlType" to "VARCHAR(2048)"; "unique" to true }
            }
            attribute(name = "remoteInboxUri", type = "String") {
                stereotype("Column") { "columnName" to "remote_inbox_uri"; "sqlType" to "VARCHAR(2048)" }
            }
            attribute(name = "remotePublicKeyPem", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "remote_public_key_pem"; "sqlType" to "TEXT" }
            }
            attribute(name = "initiatedActivityId", type = "String") {
                stereotype("Column") { "columnName" to "initiated_activity_id"; "sqlType" to "VARCHAR(2048)" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            attribute(name = "updatedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "updated_at" }
            }
        }

    // Append-only audit trail of every Follow/Accept/Reject/Undo sent or received for a given
    // relationship. Deliberately NOT hash-chained, see file header.
    val relationshipEvent =
        classOf(name = "FederationRelationshipEvent") {
            stereotype("Entity") {
                "tableName" to "federation_relationship_event"
                "kotlinObjectName" to "FederationRelationshipEventTable"
            }
            stereotype("Index") { "columns" to listOf("relationship_id"); "name" to "idx_federation_relationship_event_relationship" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "relationshipId", type = "UUID") {
                stereotype("Column") { "columnName" to "relationship_id"; "fkEntity" to "FederationRelationship" }
            }
            attribute(name = "eventType", type = eventType) {
                stereotype("Column") {
                    "columnName" to "event_type"
                    "enumType" to "network.lapis.cloud.shared.domain.FederationEventType"
                }
            }
            attribute(name = "activityId", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "activity_id"; "sqlType" to "VARCHAR(2048)" }
            }
            attribute(name = "activityJson", type = "String") {
                stereotype("Column") { "columnName" to "activity_json"; "sqlType" to "TEXT" }
            }
            attribute(name = "occurredAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "occurred_at" }
            }
        }

    // Forensic log of EVERY request the public inbox ever receives, verified or not -- see file
    // header "no member FK" paragraph.
    val inboxDeliveryLog =
        classOf(name = "FederationInboxDeliveryLog") {
            stereotype("Entity") {
                "tableName" to "federation_inbox_delivery_log"
                "kotlinObjectName" to "FederationInboxDeliveryLogTable"
            }
            stereotype("Index") { "columns" to listOf("received_at"); "name" to "idx_federation_inbox_delivery_log_received_at" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "receivedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "received_at" }
            }
            attribute(name = "remoteHost", type = "String") {
                stereotype("Column") { "columnName" to "remote_host"; "sqlType" to "VARCHAR(255)" }
            }
            attribute(name = "keyId", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "key_id"; "sqlType" to "VARCHAR(2048)" }
            }
            attribute(name = "signatureVerified", type = "Boolean") {
                stereotype("Column") { "columnName" to "signature_verified" }
            }
            attribute(name = "rejectReason", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "reject_reason"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "activityType", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "activity_type"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "activityId", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "activity_id"; "sqlType" to "VARCHAR(2048)" }
            }
            attribute(name = "bodySha256", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "body_sha256"; "sqlType" to "VARCHAR(64)" }
            }
            attribute(name = "bodyByteSize", type = "Int") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "body_byte_size" }
            }
        }
}
