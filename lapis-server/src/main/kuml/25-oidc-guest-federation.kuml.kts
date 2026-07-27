// V0.8.2 OIDC Guest-Identity Federation -- individual-member federation (Authorization Code +
// PKCE, RFC 7591 Dynamic Client Registration), a DIFFERENT mechanism from 24-federation.kuml.kts's
// server-to-server content-federation protocol (see that file's own header + IFederationService
// KDoc "Scope boundary"). This wave builds the OIDC Issuer + Relying Party + guest identity/session
// substrate only -- no LTR-economy wiring, no UI/badge (V0.8.4), no Trust-Anchor governance
// (V0.8.3). See network.lapis.cloud.server.routes.OidcRoutes KDoc for the full scope-boundary
// statement.
//
// **Guest identity = a real Member row, status=GAST** -- account.oidc_issuer/oidc_subject
// (oidc_subject already existed, reserved since V0.7.1; oidc_issuer is new this wave) jointly
// identify the federated principal (iss+sub, globally unique per OIDC spec). member.email is
// synthesized (guest+sha256(iss|sub)@federation.invalid, RFC 2606 non-resolvable placeholder TLD)
// since the concept's minimum ID-token claim set has no email claim. See 00-foundation.kuml.kts
// for the member/account entities this file extends (oidc_issuer column) rather than redefines.
//
// **oidc_signing_key is a singleton row, same "genesis-singleton" shape as federation_actor_key**
// (24-federation.kuml.kts) -- but a SEPARATE keypair for a SEPARATE cryptographic purpose (JWS
// signing of ID/logout tokens, RS256) from federation_actor_key's HTTP-Signature RSA key. Same
// "genuinely round-trippable secret, DB-is-the-trust-boundary" posture, flagged the same way.
//
// **oidc_authorization_code/oidc_issued_token carry member_id (OUR OWN local member acting as
// Issuer's subject)** -- NOT the guest. **oidc_rp_login_attempt carries NO member FK** (pre-auth
// CSRF/replay scratch state, resolved to a guest member only after successful verification).
// **oidc_client_registration/oidc_home_server_registration carry no member FK** -- they describe
// remote SERVERS, same "actor = organization, not member" federation precedent as
// federation_relationship. **oidc_guest_profile carries member_id (the GAST row this time).**
//
// **oidc_guest_login_event is deliberately a NEW, non-hash-chained forensic log, NOT an
// audit_log_entry row** -- AuditEntityType's literal set is explicitly, deliberately bounded to
// GoBD financial/legal scope (its own KDoc: "EXPLICITLY OUT OF SCOPE: ... Member CRUD"), the exact
// same reasoning 24-federation.kuml.kts already documents for federation_inbox_delivery_log.
// Allowlisted in PersonalDataRegistry (not a PersonalDataContributor) -- same "references the
// subject only by UUID, accountability is its own legal basis" treatment dsgvo_audit_log gets.
// member_id is DELIBERATELY a plain «Column» UUID attribute with NO fkEntity tag -- the one place
// this wave intentionally does NOT create a DB-level FK to member, precisely so it stays out of
// PersonalDataCoverageTest's information_schema FK walk (pinned by OidcGuestFederationSchemaDriftTest
// asserting the real schema has no such FK).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "OidcGuestFederation") {
    applyProfile(ermMappingProfile)

    // Minimal id-only Member stub (owned by Foundation) purely so UmlToErmTransformer can resolve
    // the «Column».fkEntity="Member" overrides below within this single-file evaluation -- same
    // cross-domain-stub pattern 22-session.kuml.kts/18-peer-transfer.kuml.kts already establish.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") { stereotype("Id"); stereotype("Column") { "columnName" to "id" } }
    }

    val loginEventType = enumOf(name = "OidcLoginEventType") {
        literal(name = "RP_LOGIN_SUCCESS")
        literal(name = "RP_LOGIN_FAILED")
        literal(name = "ISSUER_TOKEN_ISSUED")
        literal(name = "ISSUER_TOKEN_ISSUE_FAILED")
        literal(name = "BACKCHANNEL_LOGOUT_RECEIVED")
        literal(name = "BACKCHANNEL_LOGOUT_SENT")
    }

    // Singleton row -- this server's own OIDC JWS signing keypair. Separate from
    // federation_actor_key (24-federation.kuml.kts) -- see file header.
    val signingKey = classOf(name = "OidcSigningKey") {
        stereotype("Entity") { "tableName" to "oidc_signing_key"; "kotlinObjectName" to "OidcSigningKeyTable" }
        attribute(name = "id", type = "UUID") { stereotype("Id"); stereotype("Column") { "columnName" to "id" } }
        attribute(name = "kid", type = "String") {
            stereotype("Column") { "columnName" to "kid"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        attribute(name = "publicKeyPem", type = "String") { stereotype("Column") { "columnName" to "public_key_pem"; "sqlType" to "TEXT" } }
        attribute(name = "privateKeyPem", type = "String") { stereotype("Column") { "columnName" to "private_key_pem"; "sqlType" to "TEXT" } }
        attribute(name = "createdAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "created_at" } }
    }

    // Issuer side: registered RPs (other Lapis-Cloud instances registering against US as their IdP).
    val clientRegistration = classOf(name = "OidcClientRegistration") {
        stereotype("Entity") { "tableName" to "oidc_client_registration"; "kotlinObjectName" to "OidcClientRegistrationTable" }
        attribute(name = "id", type = "UUID") { stereotype("Id"); stereotype("Column") { "columnName" to "id" } }
        attribute(name = "clientId", type = "String") {
            stereotype("Column") { "columnName" to "client_id"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        attribute(name = "clientSecretHash", type = "String") { stereotype("Column") { "columnName" to "client_secret_hash"; "sqlType" to "VARCHAR(64)" } }
        attribute(name = "clientName", type = "String") { stereotype("Column") { "columnName" to "client_name"; "sqlType" to "VARCHAR(200)" } }
        attribute(name = "backchannelLogoutUri", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "backchannel_logout_uri"; "sqlType" to "VARCHAR(2048)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "created_at" } }
    }

    val clientRedirectUri = classOf(name = "OidcClientRedirectUri") {
        stereotype("Entity") { "tableName" to "oidc_client_redirect_uri"; "kotlinObjectName" to "OidcClientRedirectUriTable" }
        stereotype("Index") { "columns" to listOf("client_registration_id"); "name" to "idx_oidc_client_redirect_uri_client" }
        attribute(name = "id", type = "UUID") { stereotype("Id"); stereotype("Column") { "columnName" to "id" } }
        attribute(name = "clientRegistrationId", type = "UUID") {
            stereotype("Column") { "columnName" to "client_registration_id"; "fkEntity" to "OidcClientRegistration" }
        }
        attribute(name = "redirectUri", type = "String") { stereotype("Column") { "columnName" to "redirect_uri"; "sqlType" to "VARCHAR(2048)" } }
    }

    // Issuer side: single-use authorization codes, minted for OUR OWN local member (the subject).
    val authorizationCode = classOf(name = "OidcAuthorizationCode") {
        stereotype("Entity") { "tableName" to "oidc_authorization_code"; "kotlinObjectName" to "OidcAuthorizationCodeTable" }
        stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_oidc_authorization_code_expires_at" }
        attribute(name = "id", type = "UUID") { stereotype("Id"); stereotype("Column") { "columnName" to "id" } }
        attribute(name = "codeHash", type = "String") { stereotype("Column") { "columnName" to "code_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true } }
        attribute(name = "clientRegistrationId", type = "UUID") {
            stereotype("Column") { "columnName" to "client_registration_id"; "fkEntity" to "OidcClientRegistration" }
        }
        attribute(name = "memberId", type = "UUID") { stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" } }
        attribute(name = "redirectUri", type = "String") { stereotype("Column") { "columnName" to "redirect_uri"; "sqlType" to "VARCHAR(2048)" } }
        attribute(name = "scope", type = "String") { stereotype("Column") { "columnName" to "scope"; "sqlType" to "VARCHAR(500)" } }
        attribute(name = "codeChallenge", type = "String") { stereotype("Column") { "columnName" to "code_challenge"; "sqlType" to "VARCHAR(128)" } }
        attribute(name = "nonce", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "nonce"; "sqlType" to "VARCHAR(255)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "created_at" } }
        attribute(name = "expiresAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "expires_at" } }
        attribute(name = "consumedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "consumed_at" }
        }
    }

    // Issuer side: access+refresh token pair issued to one RP for one of our own local members.
    val issuedToken = classOf(name = "OidcIssuedToken") {
        stereotype("Entity") { "tableName" to "oidc_issued_token"; "kotlinObjectName" to "OidcIssuedTokenTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_oidc_issued_token_member" }
        attribute(name = "id", type = "UUID") { stereotype("Id"); stereotype("Column") { "columnName" to "id" } }
        attribute(name = "clientRegistrationId", type = "UUID") {
            stereotype("Column") { "columnName" to "client_registration_id"; "fkEntity" to "OidcClientRegistration" }
        }
        attribute(name = "memberId", type = "UUID") { stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" } }
        attribute(name = "accessTokenHash", type = "String") { stereotype("Column") { "columnName" to "access_token_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true } }
        attribute(name = "refreshTokenHash", type = "String") { stereotype("Column") { "columnName" to "refresh_token_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true } }
        attribute(name = "scope", type = "String") { stereotype("Column") { "columnName" to "scope"; "sqlType" to "VARCHAR(500)" } }
        attribute(name = "issuedAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "issued_at" } }
        attribute(name = "accessExpiresAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "access_expires_at" } }
        attribute(name = "refreshExpiresAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "refresh_expires_at" } }
        attribute(name = "revokedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revoked_at" }
        }
    }

    // RP side: this server's own DCR registration against a guest's claimed home server.
    val homeServerRegistration = classOf(name = "OidcHomeServerRegistration") {
        stereotype("Entity") { "tableName" to "oidc_home_server_registration"; "kotlinObjectName" to "OidcHomeServerRegistrationTable" }
        attribute(name = "id", type = "UUID") { stereotype("Id"); stereotype("Column") { "columnName" to "id" } }
        attribute(name = "issuerUrl", type = "String") { stereotype("Column") { "columnName" to "issuer_url"; "sqlType" to "VARCHAR(2048)"; "unique" to true } }
        attribute(name = "authorizationEndpoint", type = "String") { stereotype("Column") { "columnName" to "authorization_endpoint"; "sqlType" to "VARCHAR(2048)" } }
        attribute(name = "tokenEndpoint", type = "String") { stereotype("Column") { "columnName" to "token_endpoint"; "sqlType" to "VARCHAR(2048)" } }
        attribute(name = "jwksUri", type = "String") { stereotype("Column") { "columnName" to "jwks_uri"; "sqlType" to "VARCHAR(2048)" } }
        attribute(name = "clientId", type = "String") { stereotype("Column") { "columnName" to "client_id"; "sqlType" to "VARCHAR(200)" } }
        // Genuinely round-trippable secret (needed at every token-refresh call) -- same posture as
        // federation_actor_key.private_key_pem, see file header.
        attribute(name = "clientSecret", type = "String") { stereotype("Column") { "columnName" to "client_secret"; "sqlType" to "VARCHAR(500)" } }
        attribute(name = "registeredAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "registered_at" } }
    }

    // RP side: short-lived, single-use PKCE/state/nonce scratch state -- NO member FK, see file header.
    val rpLoginAttempt = classOf(name = "OidcRpLoginAttempt") {
        stereotype("Entity") { "tableName" to "oidc_rp_login_attempt"; "kotlinObjectName" to "OidcRpLoginAttemptTable" }
        stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_oidc_rp_login_attempt_expires_at" }
        attribute(name = "id", type = "UUID") { stereotype("Id"); stereotype("Column") { "columnName" to "id" } }
        attribute(name = "stateHash", type = "String") { stereotype("Column") { "columnName" to "state_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true } }
        attribute(name = "homeServerRegistrationId", type = "UUID") {
            stereotype("Column") { "columnName" to "home_server_registration_id"; "fkEntity" to "OidcHomeServerRegistration" }
        }
        attribute(name = "codeVerifier", type = "String") { stereotype("Column") { "columnName" to "code_verifier"; "sqlType" to "VARCHAR(128)" } }
        attribute(name = "nonce", type = "String") { stereotype("Column") { "columnName" to "nonce"; "sqlType" to "VARCHAR(255)" } }
        attribute(name = "redirectUri", type = "String") { stereotype("Column") { "columnName" to "redirect_uri"; "sqlType" to "VARCHAR(2048)" } }
        attribute(name = "createdAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "created_at" } }
        attribute(name = "expiresAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "expires_at" } }
        attribute(name = "consumedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "consumed_at" }
        }
    }

    // RP side: guest-specific profile fields that don't belong on the shared member/account tables.
    // member_id FK -- carries personal data, covered by a PersonalDataContributor (not allowlisted).
    val guestProfile = classOf(name = "OidcGuestProfile") {
        stereotype("Entity") { "tableName" to "oidc_guest_profile"; "kotlinObjectName" to "OidcGuestProfileTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "unique" to true; "name" to "uq_oidc_guest_profile_member_id" }
        attribute(name = "id", type = "UUID") { stereotype("Id"); stereotype("Column") { "columnName" to "id" } }
        attribute(name = "memberId", type = "UUID") { stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" } }
        attribute(name = "pictureUrl", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "picture_url"; "sqlType" to "VARCHAR(2048)" }
        }
        attribute(name = "homeserverUrl", type = "String") { stereotype("Column") { "columnName" to "homeserver_url"; "sqlType" to "VARCHAR(2048)" } }
        attribute(name = "membershipStatus", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "membership_status"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "grantedScope", type = "String") { stereotype("Column") { "columnName" to "granted_scope"; "sqlType" to "VARCHAR(500)" } }
        attribute(name = "lastLoginAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "last_login_at" } }
    }

    // Forensic, non-hash-chained login/logout event log -- see file header. member_id nullable
    // (unresolvable/failed attempts still get a row for security monitoring), NO fkEntity tag
    // (deliberate, see file header).
    val loginEvent = classOf(name = "OidcGuestLoginEvent") {
        stereotype("Entity") { "tableName" to "oidc_guest_login_event"; "kotlinObjectName" to "OidcGuestLoginEventTable" }
        stereotype("Index") { "columns" to listOf("occurred_at"); "name" to "idx_oidc_guest_login_event_occurred_at" }
        attribute(name = "id", type = "UUID") { stereotype("Id"); stereotype("Column") { "columnName" to "id" } }
        attribute(name = "occurredAt", type = "LocalDateTime") { stereotype("Column") { "columnName" to "occurred_at" } }
        attribute(name = "eventType", type = loginEventType) {
            stereotype("Column") { "columnName" to "event_type"; "enumType" to "network.lapis.cloud.shared.domain.OidcLoginEventType" }
        }
        attribute(name = "memberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "member_id" } // deliberately NO fkEntity -- see file header
        }
        attribute(name = "remoteParty", type = "String") {
            multiplicity = Multiplicity(0, 1)
            // home-server issuer URL OR RP client_id, direction-dependent.
            stereotype("Column") { "columnName" to "remote_party"; "sqlType" to "VARCHAR(2048)" }
        }
        attribute(name = "reason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reason"; "sqlType" to "VARCHAR(255)" }
        }
    }
}
