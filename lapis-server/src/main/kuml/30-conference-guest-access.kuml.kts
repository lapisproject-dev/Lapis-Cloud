// V1.0 Videokonferenzen (Kleinsitzung), Wave 5 "Föderations-Gastbeitritt" -- see the concept
// document ("03 Bereiche/Lapis Cloud/Videokonferenzen.md", vault, "Gäste werden vor Beitritt auf
// Aufzeichnung/Stream und die geltende Datenschutzerklärung des besuchten Servers hingewiesen
// (Klick-Bestätigung)") for the fachlich requirement this table proves compliance with, and
// 27-conference.kuml.kts's own "Wave 5 addition" note for the companion `allowFederationGuests`
// opt-in column that gates who is even allowed to reach this table.
//
// **This file models exactly one new table.** `conference_guest_consent_acknowledgment` is the
// append-only, per-join proof that a federated OIDC guest (`MemberStatus.GUEST`) was shown the
// current, versioned+hashed DSGVO consent text
// (`network.lapis.cloud.server.rpc.ConferenceGuestConsentDisclaimer`) before `joinRoom` minted them
// a LiveKit token -- same three-column `version`/`sha256`/`acknowledged_at` shape
// `auction_compliance_acknowledgment` (21-auction.kuml.kts) and
// `membership_agreement_acknowledgment` (23-registration.kuml.kts) already establish, extended
// with `room_id` (consent here is PER-ROOM, PER-JOIN, not organization-wide -- a guest re-consents
// on every join, see `member_id`+`room_id` both carried, never deduplicated) plus two SNAPSHOTTED
// fields that would otherwise silently go stale on read: `homeserver_url` (because
// `oidc_guest_profile.homeserver_url` is overwritten on every re-login,
// `OidcGuestMemberStore.resolveOrCreateGuestMember`'s own upsert) and `organization_name` (because
// `organization_settings.name` is a live, ADMIN-editable field -- a DSGVO Rechenschaftsnachweis
// must name the controller as it was PRESENTED at consent time, not as it reads today; design
// review D15). A re-join writes a SECOND row, `left_at` on the prior `conference_participation`
// row now set -- consent is never upserted in place, same append-only idiom
// 27-conference.kuml.kts's own `conference_participation` header already establishes for
// `session.revoked_at`.
//
// **Never erased on a DSGVO deletion request.** See `ConferencePersonalData`'s own retain-with-
// reason entry for this table -- the proof that a guest was shown and acknowledged this room's
// consent text before joining is the organization's own accountability record under
// Art. 5(2)/7(1) DSGVO; erasing it would destroy the very record that documents the lawfulness of
// processing that data subject's audio/video in that meeting.
//
// **FK-naming choice**: both `member_id`/`room_id` are modelled as plain «Column» UUID attributes
// with «Column».fkEntity, NEVER a UML association -- same domain-wide policy 21-auction.kuml.kts's
// own header documents at length, already followed throughout 27/28/29-conference*.kuml.kts.
//
// **This file carries minimal id-only Member/ConferenceRoom stubs** (owned by Foundation/this same
// Conference domain respectively), purely so `UmlToErmTransformer` can resolve this file's
// «Column».fkEntity overrides within this single-file evaluation -- same cross-domain-stub pattern
// 28-conference-recording.kuml.kts/29-conference-streaming.kuml.kts already establish.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "ConferenceGuestAccess") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors every other domain's own Member stub. Resolves
    // conference_guest_consent_acknowledgment.member_id's «Column».fkEntity override within this
    // single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Conference-domain-owned stub (27-conference.kuml.kts owns the real table) — id-only, same
    // cross-domain-stub pattern 28/29 already establish. Resolves
    // conference_guest_consent_acknowledgment.room_id's «Column».fkEntity override.
    val conferenceRoom = classOf(name = "ConferenceRoom") {
        stereotype("Entity") { "tableName" to "conference_room"; "kotlinObjectName" to "ConferenceRoomTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val conferenceGuestConsentAcknowledgment = classOf(name = "ConferenceGuestConsentAcknowledgment") {
        stereotype("Entity") {
            "tableName" to "conference_guest_consent_acknowledgment"
            "kotlinObjectName" to "ConferenceGuestConsentAcknowledgmentTable"
        }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_conference_guest_consent_ack_member" }
        stereotype("Index") { "columns" to listOf("room_id"); "name" to "idx_conference_guest_consent_ack_room" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // "FK-naming choice".
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // Real FK -> conference_room (id), NOT NULL. Plain «Column» UUID attribute -- see file
        // header "FK-naming choice".
        attribute(name = "roomId", type = "UUID") {
            stereotype("Column") { "columnName" to "room_id"; "fkEntity" to "ConferenceRoom" }
        }
        attribute(name = "acknowledgedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "acknowledged_at" }
        }
        attribute(name = "consentVersion", type = "String") {
            stereotype("Column") { "columnName" to "consent_version"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "consentSha256", type = "String") {
            stereotype("Column") { "columnName" to "consent_sha256"; "sqlType" to "VARCHAR(64)" }
        }
        // The home server the consenting guest came from, snapshotted at consent time -- NOT
        // resolved live from oidc_guest_profile on read, because that row is overwritten on every
        // subsequent login (see OidcGuestMemberStore.resolveOrCreateGuestMember's upsert). The
        // DSGVO proof must say which home server the guest was on WHEN they consented. See file
        // header.
        //
        // V0.11.0 FRIEND self-registration: made nullable (was NOT NULL) -- a FRIEND has no
        // federated home server at all (no oidc_guest_profile row), yet still writes exactly the
        // same one acknowledgment-row-per-join proof a GUEST does, see
        // `network.lapis.cloud.server.rpc.ConferenceService.joinRoom`'s own "FRIEND: no home
        // server, guestHomeserver stays null by design" comment and
        // `V3__member_status_english_and_friend.sql`'s "conference_guest_consent_acknowledgment
        // .homeserver_url must become nullable" step.
        attribute(name = "homeserverUrl", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "homeserver_url"; "sqlType" to "VARCHAR(2048)" }
        }
        // The controlling organization's display name, snapshotted at consent time for the
        // identical reason homeserverUrl is snapshotted above -- organization_settings.name is a
        // live, ADMIN-editable field; the DSGVO Rechenschaftsnachweis must name the controller as
        // it was PRESENTED to the guest, not as it reads today. Design review D15.
        attribute(name = "organizationName", type = "String") {
            stereotype("Column") { "columnName" to "organization_name"; "sqlType" to "VARCHAR(300)" }
        }
    }
}
