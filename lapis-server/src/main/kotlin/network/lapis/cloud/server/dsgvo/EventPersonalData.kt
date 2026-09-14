package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.EventCateringOrderTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventRoomTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [EventTable]/[EventRegistrationTable] (Welle V1.4.3.1 "Veranstaltungen"). See
 * `39-events.kuml.kts` file header for why this contributor only ever handles
 * [network.lapis.cloud.shared.domain.DsgvoSubjectKind.MEMBER] subjects (a `MemberPersonalDataContributor`,
 * not the raw interface) -- `event_registration.member_id`/`.checked_in_by`/`.invoice_issued_by`/
 * `event.created_by` are the only FOUR member-FK-bearing columns in this domain (Welle V1.4.3.2
 * added `checked_in_by`, Welle V1.4.3.6 added `invoice_issued_by`, alongside the original two). A
 * GUEST registration (`guest_name`/`guest_email`, no `member_id`) carries PII of a person who is
 * NOT a member and is therefore invisible to this contributor entirely -- see
 * [PersonalDataRegistry.knownUncoveredSubjectRoots]'s `event_registration` entry for that
 * documented, deliberate gap. **Welle V1.4.3.6's `billing_street`/`billing_postal_code`/
 * `billing_city`/`billing_country`** fall into the SAME gap for a GUEST registration (a guest's
 * billing address is exactly as much "PII of a person who is not a member" as their name/email
 * already are); for a MEMBER registration these columns may duplicate/override that member's own
 * address on file, which IS covered elsewhere (`FoundationPersonalData`'s own `member` coverage) --
 * so no separate handling is added here either way.
 *
 * **Retained, not deleted -- for BOTH tables, regardless of [ErasureMode].** Neither table stores
 * a member's name/email/address directly; `event_registration.member_id`/`.checked_in_by`/
 * `event.created_by` are the ONLY member-identifying data either row carries, and all three survive
 * erasure as ordinary FK anchors (the referenced `member` row itself is anonymized elsewhere -- see
 * `FoundationPersonalData` KDoc for that invariant every other retain-with-reason contributor in
 * this codebase already relies on). `checked_in_by`'s own retention reason is organisatorische
 * Nachvollziehbarkeit ("who let this person in and when" -- same posture `event.created_by`
 * already establishes for "who created this event"), not accounting.
 * Deleting/nulling the FK instead would either orphan a `payment_transaction`/`journal_entry`'s own
 * accounting trail (for a CONFIRMED, possibly PAID registration) or corrupt capacity/waitlist
 * accounting for an event this member is still `PENDING_PAYMENT`/`WAITLISTED` on -- so this
 * contributor takes the same "retain, only who-touched-it is a trace" posture
 * [AccountingPersonalData]/[ApiKeyPersonalData]/[WebhookPersonalData] already establish, rather than
 * `CrmPersonalData`'s real-DELETE posture (that entity carries no such downstream accounting/
 * capacity dependents).
 *
 * **Welle V1.4.3.4 "Raumverwaltung" addendum.** [EventRoomTable] gained a fourth member-FK-bearing
 * column, `created_by` -- same retain-with-reason posture as [EventTable.createdBy] ("who created
 * this room", pure organisatorische Nachvollziehbarkeit, the room itself describes no person).
 * Extending this existing contributor rather than adding a new `EventRoomPersonalData` one: the
 * room domain is a small satellite of the event domain (see `49-event-room.kuml.kts` file header),
 * not an independent PII surface of its own.
 *
 * **Welle V1.4.3.5 "Catering-Management" addendum.** [EventCateringOrderTable] gained a fifth
 * member-FK-bearing column, `created_by` -- same retain-with-reason posture as
 * [EventRoomTable.createdBy]/[EventTable.createdBy] ("who created this catering order", pure
 * organisatorische Nachvollziehbarkeit; the order itself describes no person -- see
 * `CateringOrderInput.allergenNotes` KDoc for why the aggregated Bestellposition carries no
 * Personenbezug at all, Art.-9-DSGVO included). Extending this existing contributor rather than
 * adding a new `CateringPersonalData` one: same "small satellite of the event domain" posture the
 * Room addendum above already establishes (see `50-event-catering.kuml.kts` file header).
 *
 * **Welle V1.4.3.6 "Externe Rechnungsstellung" addendum.** [EventRegistrationTable] gained a
 * fourth member-FK-bearing column, `invoice_issued_by` -- same retain-with-reason posture as
 * `.checked_in_by` ("who issued this invoice", organisatorische/buchhalterische
 * Nachvollziehbarkeit -- the resulting `open_item`/`journal_entry` is itself part of the GoBD
 * ledger and must stay traceable to who booked it, same as every other `OpenItemService` write).
 * Counted in [eraseMember] below, same "createdBy"-style outcome the Room/Catering addenda above
 * already establish (unlike `.checked_in_by`, which -- pre-existing gap, not introduced here --
 * has no dedicated [eraseMember] outcome of its own).
 */
object EventPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "events"
    override val displayName = "Veranstaltungen"
    override val coveredTables = setOf(EventTable, EventRegistrationTable, EventRoomTable, EventCateringOrderTable)

    /** Export bundle cap -- same posture `CrmPersonalData.MAX_EXPORTED_INTERACTIONS` establishes. */
    internal const val MAX_EXPORTED_REGISTRATIONS = 2_000

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            put(
                "registrations",
                buildJsonArray {
                    EventRegistrationTable
                        .selectAll()
                        .where { EventRegistrationTable.memberId eq memberId }
                        .limit(MAX_EXPORTED_REGISTRATIONS)
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[EventRegistrationTable.id].toString())
                                    put("eventId", row[EventRegistrationTable.eventId].toString())
                                    put("status", row[EventRegistrationTable.status].name)
                                    put("feeAmount", row[EventRegistrationTable.feeAmount].toString())
                                    put("registeredAt", row[EventRegistrationTable.registeredAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "createdEvents",
                buildJsonArray {
                    EventTable
                        .selectAll()
                        .where { EventTable.createdBy eq memberId }
                        .limit(MAX_EXPORTED_REGISTRATIONS)
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[EventTable.id].toString())
                                    put("slug", row[EventTable.slug])
                                    put("title", row[EventTable.title])
                                    put("startsAt", row[EventTable.startsAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "createdEventRooms",
                buildJsonArray {
                    EventRoomTable
                        .selectAll()
                        .where { EventRoomTable.createdBy eq memberId }
                        .limit(MAX_EXPORTED_REGISTRATIONS)
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[EventRoomTable.id].toString())
                                    put("name", row[EventRoomTable.name])
                                    put("createdAt", row[EventRoomTable.createdAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "createdCateringOrders",
                buildJsonArray {
                    EventCateringOrderTable
                        .selectAll()
                        .where { EventCateringOrderTable.createdBy eq memberId }
                        .limit(MAX_EXPORTED_REGISTRATIONS)
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[EventCateringOrderTable.id].toString())
                                    put("eventId", row[EventCateringOrderTable.eventId].toString())
                                    put("description", row[EventCateringOrderTable.description])
                                    put("createdAt", row[EventCateringOrderTable.createdAt].toString())
                                },
                            )
                        }
                },
            )
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val registrationCount =
            EventRegistrationTable
                .selectAll()
                .where { EventRegistrationTable.memberId eq memberId }
                .count()
                .toInt()
        val createdEventCount =
            EventTable
                .selectAll()
                .where { EventTable.createdBy eq memberId }
                .count()
                .toInt()
        val createdEventRoomCount =
            EventRoomTable
                .selectAll()
                .where { EventRoomTable.createdBy eq memberId }
                .count()
                .toInt()
        val createdCateringOrderCount =
            EventCateringOrderTable
                .selectAll()
                .where { EventCateringOrderTable.createdBy eq memberId }
                .count()
                .toInt()
        val issuedInvoiceCount =
            EventRegistrationTable
                .selectAll()
                .where { EventRegistrationTable.invoiceIssuedBy eq memberId }
                .count()
                .toInt()
        val outcomes = mutableListOf<TableErasureOutcome>()
        if (registrationCount > 0) {
            outcomes +=
                TableErasureOutcome(
                    table = "event_registration",
                    rowsRetained = registrationCount,
                    retentionReason =
                        "member_id-FK bleibt als Anker erhalten -- die Zeile speichert keinen Namen/keine " +
                            "E-Mail-Adresse direkt, und eine bestätigte/bezahlte Anmeldung ist über " +
                            "payment_transaction/journal_entry buchhalterisch nachvollziehbar. Das " +
                            "referenzierte member-Datum wird an anderer Stelle anonymisiert.",
                )
        }
        if (createdEventCount > 0) {
            outcomes +=
                TableErasureOutcome(
                    table = "event",
                    rowsRetained = createdEventCount,
                    retentionReason =
                        "Organisatorische Nachvollziehbarkeit, wer eine Veranstaltung angelegt hat -- " +
                            "created_by bleibt als FK-Anker erhalten, die Veranstaltung selbst beschreibt " +
                            "keine Person.",
                )
        }
        if (createdEventRoomCount > 0) {
            outcomes +=
                TableErasureOutcome(
                    table = "event_room",
                    rowsRetained = createdEventRoomCount,
                    retentionReason =
                        "Organisatorische Nachvollziehbarkeit, wer einen Raum angelegt hat -- created_by " +
                            "bleibt als FK-Anker erhalten, der Raum selbst beschreibt keine Person.",
                )
        }
        if (createdCateringOrderCount > 0) {
            outcomes +=
                TableErasureOutcome(
                    table = "event_catering_order",
                    rowsRetained = createdCateringOrderCount,
                    retentionReason =
                        "Organisatorische Nachvollziehbarkeit, wer eine Catering-Bestellposition angelegt hat -- " +
                            "created_by bleibt als FK-Anker erhalten, die Bestellposition selbst beschreibt keine " +
                            "Person (aggregierte Planungsangabe, kein Personenbezug).",
                )
        }
        if (issuedInvoiceCount > 0) {
            outcomes +=
                TableErasureOutcome(
                    table = "event_registration",
                    rowsRetained = issuedInvoiceCount,
                    retentionReason =
                        "Buchhalterische/organisatorische Nachvollziehbarkeit, wer eine externe Rechnung " +
                            "ausgestellt hat -- invoice_issued_by bleibt als FK-Anker erhalten, der resultierende " +
                            "offene Posten ist Teil der GoBD-Buchführung.",
                )
        }
        return outcomes
    }
}
