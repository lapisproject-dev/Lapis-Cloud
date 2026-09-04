// Welle V1.4.3.1 "Veranstaltungen: Kernschleife + Anmeldegebuehren-Zahlung" -- see
// network.lapis.cloud.server.events.* for the store/policy/submission/waitlist logic,
// network.lapis.cloud.server.rpc.EventFeePostingBridge for the accounting bridge,
// network.lapis.cloud.server.dsgvo.EventPersonalData for Auskunft/Loeschung, and
// network.lapis.cloud.shared.rpc.IEventService for the authenticated RPC-facing surface (the
// unauthenticated public registration path is `network.lapis.cloud.server.routes
// .registerEventPublicRoutes`, a classic server-rendered <form>, no RPC involved -- see that file's
// own KDoc).
//
// **This file models exactly two new tables.** `event` is a publicly announceable happening with an
// optional participation fee and an optional capacity; `event_registration` is one person's claim on
// a seat (or a waitlist slot), either a member or a name+email guest.
//
// **Why NOT `meeting`** (05-governance.kuml.kts, see also 22-session.kuml.kts): a meeting has a
// mandatory `committee_id` FK and models a governance BODY's sitting -- agenda, resolutions, minutes,
// attendance duty. A public event (a Sommerfest, an Infostand, a public lecture) belongs to no
// committee, produces no resolution, and carries no attendance duty. Making `committee_id` nullable
// to also cover this case would weaken the governance invariant "every meeting belongs to a
// committee" for a use case that was never that in the first place.
//
// **Why NOT `external_donor` for a guest registration** (10-accounting.kuml.kts): that entity exists
// for §25 PartG/§10b EStG donation bookkeeping (Spendenquittungen, Rechenschaftsbericht) -- routing
// a Sommerfest guest through it would create a spurious §25 PartG compliance record with a twelve-
// month lag before anyone notices it is wrong. It is also structurally incompatible:
// `chk_payment_checkout_session_embed_anonymous` requires `donor_category = 'ANONYMOUS'` for the
// embed/donation path, which means storing NEITHER name NOR email -- the exact opposite of what an
// event registration needs to record.
//
// **Why `event_registration.active_participant_key`, not a Postgres partial unique index.** H2's
// MODE=PostgreSQL -- the mode the WHOLE test suite runs against (`DatabaseConfig.kt`) -- rejects a
// partial unique index (`CREATE UNIQUE INDEX ... WHERE`, verified empirically in
// `V8__sepa_mandates.sql`/`V9__dunning.sql`'s own Review-Round-1 comment) and rejects the generated-
// column workaround too (Postgres requires `STORED`, H2 rejects `STORED` outright -- mutually
// exclusive, no single statement satisfies both engines). `V8__sepa_mandates.sql` names the only
// portable alternative -- an application-maintained shadow column -- and explicitly deferred it there
// only because `SepaMandateTable.kt` is codegen-generated and the column was not in the kUML model.
// That objection does not apply to a brand-new entity: `active_participant_key` is modelled here from
// the start, so no drift between the model and the hand-maintained Exposed table can occur. See
// `EventStore`/`EventPolicy.activeParticipantKey` for how it is computed and maintained -- only
// `EventStore` ever writes it.
//
// **Why no `audit_log_entry` coverage** -- same posture `38-crm.kuml.kts`'s own file header takes:
// `event`/`event_registration` are ordinary master data and a fachlich workflow state machine, not
// the GoBD hash-chained financial ledger. The one genuinely money-relevant fact here --
// `event_registration.fee_amount`, a frozen snapshot of `event.fee_amount` at registration time --
// is protected differently: `EventPolicy` forbids editing `event.fee_amount` once any non-CANCELLED/
// EXPIRED registration exists, so the snapshot can never silently diverge from what was actually
// charged, and the payment itself is separately, fully audited via `payment_transaction`/
// `journal_entry` (`EventFeePostingBridge`) the moment money actually moves.
//
// **A participation fee is a Leistungsentgelt, not a donation -- and this system does not verify
// that boundary.** `EVENT_FEE` intentionally bypasses `PartyDonationComplianceCalculator` (no
// `donor_category`, no §25 PartG duty check, no anonymity threshold) -- correct for a fee that
// roughly covers the event's own cost. If a fee is set materially ABOVE that cost, the excess is, by
// prevailing legal opinion, a disguised party donation -- a distinction this system cannot and does
// not detect. Flagged here and in the treasurer-facing UI; see the design-team plan's open question
// OF-2 for the full reasoning. Get this reviewed by counsel before relying on it.
//
// Cross-domain stub: minimal id-only Member (owned by 00-foundation.kuml.kts), same single-file-
// evaluation pattern every later domain file's own header documents (most recently
// 38-crm.kuml.kts's own Member/ExternalDonor stubs) -- purely so `UmlToErmTransformer` can resolve
// this file's `event.created_by`/`event_registration.member_id` FKs within this file's own
// evaluation.
//
// Cross-field CHECK constraints (identity XOR, active-key/status consistency, the three-way payer-
// identity CHECK on `payment_checkout_session`) are SQL-only, not modelled here -- same posture every
// later domain file's header documents (most recently `38-crm.kuml.kts`'s own "Cross-field CHECK
// constraints are SQL-only" section); the erm-to-exposed codegen has no typed representation for a
// multi-column CHECK. `V18__events.sql` carries the actual constraints.
//
// `payment_checkout_session.eventRegistrationId` (the third payer identity, alongside member/
// external_donor) and `organization_settings.eventIncomeAccountId`/`eventIncomeSphere` are modelled
// as addenda in `33-payments.kuml.kts`/`11-organization-settings.kuml.kts` respectively -- those
// files own those tables, not this one; see their own file header addenda.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Events") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub. Resolves
    // event.created_by/event_registration.member_id's «Column».fkEntity overrides within this file's
    // own single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Longest literal PUBLISHED (9) -> VARCHAR(9). Literal order matches
    // network.lapis.cloud.shared.domain.EventStatus.
    val eventStatus = enumOf(name = "EventStatus") {
        literal(name = "DRAFT")
        literal(name = "PUBLISHED")
        literal(name = "CANCELLED")
    }

    // Longest literal MEMBERS_ONLY (12) -> VARCHAR(12). Literal order matches
    // network.lapis.cloud.shared.domain.EventVisibility.
    val eventVisibility = enumOf(name = "EventVisibility") {
        literal(name = "MEMBERS_ONLY")
        literal(name = "PUBLIC")
    }

    // Longest literal PENDING_PAYMENT (15) -> VARCHAR(15). Literal order matches
    // network.lapis.cloud.shared.domain.EventRegistrationStatus.
    val eventRegistrationStatus = enumOf(name = "EventRegistrationStatus") {
        literal(name = "PENDING_PAYMENT")
        literal(name = "CONFIRMED")
        literal(name = "WAITLISTED")
        literal(name = "CANCELLED")
        literal(name = "EXPIRED")
    }

    val event = classOf(name = "Event") {
        stereotype("Entity") { "tableName" to "event"; "kotlinObjectName" to "EventTable" }
        stereotype("Index") { "columns" to listOf("slug"); "name" to "uq_event_slug"; "unique" to true }
        stereotype("Index") { "columns" to listOf("status", "starts_at"); "name" to "idx_event_status_starts_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "slug", type = "String") {
            stereotype("Column") { "columnName" to "slug"; "sqlType" to "VARCHAR(120)" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(8000)" }
        }
        // At least one of locationText/onlineUrl is required (CHECK, SQL-only, see file header) --
        // hybrid (both) is allowed.
        attribute(name = "locationText", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "location_text"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "onlineUrl", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "online_url"; "sqlType" to "VARCHAR(2048)" }
        }
        attribute(name = "startsAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "starts_at" }
        }
        attribute(name = "endsAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "ends_at" }
        }
        // NULL = unbounded capacity.
        attribute(name = "capacity", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "capacity" }
        }
        attribute(name = "feeAmount", type = "BigDecimal") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "fee_amount"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "feeCurrency", type = "String") {
            defaultValue = "EUR"
            stereotype("Column") { "columnName" to "fee_currency"; "sqlType" to "VARCHAR(3)" }
        }
        attribute(name = "status", type = eventStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.EventStatus" }
        }
        attribute(name = "visibility", type = eventVisibility) {
            stereotype("Column") { "columnName" to "visibility"; "enumType" to "network.lapis.cloud.shared.domain.EventVisibility" }
        }
        attribute(name = "registrationClosesAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "registration_closes_at" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "cancelledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancelled_at" }
        }
    }

    val eventRegistration = classOf(name = "EventRegistration") {
        stereotype("Entity") { "tableName" to "event_registration"; "kotlinObjectName" to "EventRegistrationTable" }
        stereotype("Index") {
            "columns" to listOf("event_id", "active_participant_key")
            "name" to "uq_event_registration_active_participant"
            "unique" to true
        }
        stereotype("Index") { "columns" to listOf("event_id", "status"); "name" to "idx_event_registration_event_status" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_event_registration_member" }
        stereotype("Index") { "columns" to listOf("event_id", "waitlist_position"); "name" to "idx_event_registration_waitlist" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "eventId", type = "UUID") {
            stereotype("Column") { "columnName" to "event_id"; "fkEntity" to "Event" }
        }
        // Exactly one of memberId/(guestName+guestEmail) is set (CHECK, SQL-only, see file header).
        attribute(name = "memberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "guestName", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "guest_name"; "sqlType" to "VARCHAR(300)" }
        }
        // Server-side lowercase-normalized (see EventPolicy.normalizeGuestEmail, same idiom
        // CrmContactPolicy.normalizeEmail already establishes).
        attribute(name = "guestEmail", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "guest_email"; "sqlType" to "VARCHAR(320)" }
        }
        // See file header "active_participant_key" -- NULL exactly when status is CANCELLED/EXPIRED.
        attribute(name = "activeParticipantKey", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "active_participant_key"; "sqlType" to "VARCHAR(320)" }
        }
        attribute(name = "status", type = eventRegistrationStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.EventRegistrationStatus"
            }
        }
        // Snapshot of event.feeAmount at the moment this registration was created -- see file header
        // "Why no audit_log_entry coverage" for why a snapshot (not a live re-read) is the correct
        // shape here.
        attribute(name = "feeAmount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "fee_amount"; "sqlType" to "DECIMAL(12,2)" }
        }
        // Set for PENDING_PAYMENT only (CHECK, SQL-only) -- the lazily-checked hold expiry, see
        // EventCapacityGuard/EventWaitlist KDoc ("this codebase has no scheduler").
        attribute(name = "holdExpiresAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "hold_expires_at" }
        }
        attribute(name = "waitlistPosition", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "waitlist_position" }
        }
        // SHA-256 hex of the cancellation token -- the raw token is NEVER persisted, only handed to
        // the registrant once (mail/redirect). See EventRegistrationSubmission KDoc.
        attribute(name = "cancelTokenSha256", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancel_token_sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "registeredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "registered_at" }
        }
        attribute(name = "confirmedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "confirmed_at" }
        }
        attribute(name = "cancelledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancelled_at" }
        }
        // Set when this row is offered a waitlist seat (status flips back to PENDING_PAYMENT) --
        // the anchor for the 48h waitlist-offer window, see EventWaitlist KDoc.
        attribute(name = "waitlistOfferedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "waitlist_offered_at" }
        }
    }
}
