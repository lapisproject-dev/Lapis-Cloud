// V0.8.3 Trust-Anchor-Governance -- a deliberately-scoped, single-level CORE subset of OpenID
// Federation 1.0 (RFC 9678), layered on top of 25-oidc-guest-federation.kuml.kts's individual-
// member OIDC federation. See network.lapis.cloud.shared.domain.TrustAnchorEventType KDoc
// "CRITICAL FRAMING" -- Trust-Anchor-pool membership is UX comfort, NOT a security mechanism; it
// never gates federation, guest login, or Dynamic Client Registration (V0.8.2 already made those
// fully open by default). A Trust Anchor's only effect is a positive, purely-informational signal.
//
// **Deliberate scope cut vs. the full RFC 9678 spec**: single-level only (a Trust Anchor vouches
// DIRECTLY for its pool members/leaf entities -- no nested Trust-Anchor -> Intermediate -> Leaf
// authority chains), no Trust Marks, no Metadata Policy Language. If a later wave needs multi-level
// chains, that is a genuinely new modeling exercise, not an incremental extension of this file.
//
// **trust_anchor_signing_key is NOT a genesis-singleton table** like federation_actor_key/
// oidc_signing_key -- it is rotation-capable: MULTIPLE rows can be ACTIVE-adjacent (exactly one
// ACTIVE at a time, any number RETIRED/REVOKED) to support real key-rollover (an old key keeps
// verifying already-issued, still-unexpired statements during a grace period) and a real,
// ADMIN-triggered "revoke this key now" compromise-response path. The FIRST row is still
// provisioned exactly like its two predecessors -- idempotently, at first Application.module()
// boot, via TrustAnchorSigningKeyProvisioner, fixed sentinel id '...-0000-0000000000f8' (next
// unused slot after oidc_signing_key's own '...-f7') -- and is STILL registered in
// OrganizationRestoreService.SEEDED_SINGLETON_ROWS for the same "fresh restore target" reasoning
// (a server that has since rotated will correctly show up as non-empty on that table, which is the
// correct/desired behavior, not a false positive).
//
// **Why revocation needs more than expiry alone** (the concept's own explicitly-flagged open
// question): a removed POOL MEMBER is fully handled by expiry alone -- Subordinate Statements are
// generated fresh, on demand, at fetch time (see TrustAnchorStatements KDoc), so removing a pool
// member simply makes the next fetch 404 and lets any already-fetched statement expire unrenewed,
// no separate revocation list needed there. A compromised SIGNING KEY is different: an attacker (or
// a legitimate holder of an already-fetched, cached statement) could still present an
// already-issued, cryptographically valid, NOT-YET-EXPIRED statement signed by that key even after
// the operator "revokes" it, if revocation only meant "stop signing new things with it" -- expiry
// alone would let that statement go on verifying successfully until its own natural exp. The real
// fix implemented here: this server's own published JWKS (embedded in its Entity Configuration)
// excludes REVOKED keys entirely, and every verifier (including this server's own
// TrustAnchorResolver, when acting as a Relying Party toward SOME OTHER anchor) re-fetches that
// JWKS FRESH at verification time rather than caching it -- so a revoked key's public key
// disappears from the trust set immediately, and ANY statement signed by it (past or future,
// expired or not) stops verifying the moment a verifier next fetches. RETIRED keys, by contrast,
// stay in the published JWKS indefinitely (no automatic purge-after-grace-period this wave -- a
// deliberate simplification, flagged, not silently decided) until an ADMIN explicitly revokes them.
//
// **No "is Trust Anchor role enabled" flag/table**: opt-in is expressed structurally by an empty
// trust_anchor_pool_member table -- see network.lapis.cloud.server.routes.registerTrustAnchorRoutes
// KDoc. Avoids a fourth near-duplicate singleton-config row for what a simple "is the pool
// non-empty" check already answers honestly.
//
// **No "at most one ACTIVE row" DB-level constraint** (e.g. a Postgres partial unique index) --
// mirrors this codebase's existing house style of enforcing singleton-ish invariants through
// application-level row-locking discipline (see TrustAnchorSigningKeyStore KDoc "Concurrency"),
// same as audit_log_chain_state's single-row invariant, rather than a portability-fragile DB
// constraint (H2-vs-Postgres partial-index syntax differs) for something the write path already
// guards correctly.
//
// **trust_anchor_event is deliberately NOT audit_log_entry** (V0.5.3's hash-chained GoBD trail) --
// AuditEntityType's literal set is explicitly, deliberately bounded to GoBD financial/legal scope
// (see 24-federation.kuml.kts file header, which already established this exact reasoning for
// federation_relationship_event). Mirrors federation_relationship_event's shape more closely than
// oidc_guest_login_event's: no member-actor column at all (every event here describes an
// organization-level governance action -- who clicked ADMIN is not tracked here, same as
// federation_relationship_event never names which ADMIN clicked Accept), just event type + a single
// event-type-dependent subject string (a key's kid, a home-server URI, or an anchor entity URI) --
// no FK to member needed anywhere in this file, so (unlike 25-oidc-guest-federation.kuml.kts) this
// domain needs no cross-file Member stub at all.
//
// **No new PersonalDataContributor needed**: no table in this file has any FK (real or otherwise)
// to member -- Trust-Anchor governance is entirely server-to-server/organization-level, exactly
// like federation_relationship/federation_actor_key.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "TrustAnchor") {
    applyProfile(ermMappingProfile)

    val signingKeyStatus =
        enumOf(name = "TrustAnchorSigningKeyStatus") {
            literal(name = "ACTIVE")
            literal(name = "RETIRED")
            literal(name = "REVOKED")
        }

    val eventType =
        enumOf(name = "TrustAnchorEventType") {
            literal(name = "KEY_PROVISIONED")
            literal(name = "KEY_ROTATED")
            literal(name = "KEY_REVOKED")
            literal(name = "POOL_MEMBER_ADDED")
            literal(name = "POOL_MEMBER_REMOVED")
            literal(name = "TRUSTED_ANCHOR_ADDED")
            literal(name = "TRUSTED_ANCHOR_REMOVED")
        }

    // Rotation-capable -- see file header. Exactly one ACTIVE row at a time (application-enforced).
    val signingKey =
        classOf(name = "TrustAnchorSigningKey") {
            stereotype("Entity") { "tableName" to "trust_anchor_signing_key"; "kotlinObjectName" to "TrustAnchorSigningKeyTable" }
            stereotype("Index") { "columns" to listOf("status"); "name" to "idx_trust_anchor_signing_key_status" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "kid", type = "String") {
                stereotype("Column") { "columnName" to "kid"; "sqlType" to "VARCHAR(64)"; "unique" to true }
            }
            attribute(name = "publicKeyPem", type = "String") {
                stereotype("Column") { "columnName" to "public_key_pem"; "sqlType" to "TEXT" }
            }
            attribute(name = "privateKeyPem", type = "String") {
                stereotype("Column") { "columnName" to "private_key_pem"; "sqlType" to "TEXT" }
            }
            attribute(name = "status", type = signingKeyStatus) {
                stereotype("Column") {
                    "columnName" to "status"
                    "enumType" to "network.lapis.cloud.shared.domain.TrustAnchorSigningKeyStatus"
                }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            attribute(name = "retiredAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "retired_at" }
            }
            attribute(name = "revokedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "revoked_at" }
            }
        }

    // This server's own Trust-Anchor pool (publishing side, opt-in via non-empty table -- see file header).
    val poolMember =
        classOf(name = "TrustAnchorPoolMember") {
            stereotype("Entity") { "tableName" to "trust_anchor_pool_member"; "kotlinObjectName" to "TrustAnchorPoolMemberTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "homeServerUri", type = "String") {
                stereotype("Column") { "columnName" to "home_server_uri"; "sqlType" to "VARCHAR(2048)"; "unique" to true }
            }
            attribute(name = "addedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "added_at" }
            }
        }

    // External Trust Anchors THIS server has chosen to trust (consuming side).
    val trustedAnchor =
        classOf(name = "TrustedExternalAnchor") {
            stereotype("Entity") { "tableName" to "trusted_external_anchor"; "kotlinObjectName" to "TrustedExternalAnchorTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "anchorEntityUri", type = "String") {
                stereotype("Column") { "columnName" to "anchor_entity_uri"; "sqlType" to "VARCHAR(2048)"; "unique" to true }
            }
            attribute(name = "addedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "added_at" }
            }
        }

    // Append-only forensic log of every key/pool/trusted-anchor governance action -- see file
    // header "trust_anchor_event is deliberately NOT audit_log_entry" paragraph.
    val event =
        classOf(name = "TrustAnchorEvent") {
            stereotype("Entity") { "tableName" to "trust_anchor_event"; "kotlinObjectName" to "TrustAnchorEventTable" }
            stereotype("Index") { "columns" to listOf("occurred_at"); "name" to "idx_trust_anchor_event_occurred_at" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "occurredAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "occurred_at" }
            }
            attribute(name = "eventType", type = eventType) {
                stereotype("Column") {
                    "columnName" to "event_type"
                    "enumType" to "network.lapis.cloud.shared.domain.TrustAnchorEventType"
                }
            }
            attribute(name = "subject", type = "String") {
                stereotype("Column") { "columnName" to "subject"; "sqlType" to "VARCHAR(2048)" }
            }
        }
}
