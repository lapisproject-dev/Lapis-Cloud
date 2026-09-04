package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [EventTable]/[EventRegistrationTable] (Welle V1.4.3.1 "Veranstaltungen"). See
 * `39-events.kuml.kts` file header for why this contributor only ever handles
 * [network.lapis.cloud.shared.domain.DsgvoSubjectKind.MEMBER] subjects (a `MemberPersonalDataContributor`,
 * not the raw interface) -- `event_registration.member_id`/`event.created_by` are the only two
 * member-FK-bearing columns in this domain. A GUEST registration (`guest_name`/`guest_email`, no
 * `member_id`) carries PII of a person who is NOT a member and is therefore invisible to this
 * contributor entirely -- see [PersonalDataRegistry.knownUncoveredSubjectRoots]'s `event_registration`
 * entry for that documented, deliberate gap.
 *
 * **Retained, not deleted -- for BOTH tables, regardless of [ErasureMode].** Neither table stores
 * a member's name/email/address directly; `event_registration.member_id`/`event.created_by` are the
 * ONLY member-identifying data either row carries, and both survive erasure as ordinary FK anchors
 * (the referenced `member` row itself is anonymized elsewhere -- see `FoundationPersonalData` KDoc
 * for that invariant every other retain-with-reason contributor in this codebase already relies on).
 * Deleting/nulling the FK instead would either orphan a `payment_transaction`/`journal_entry`'s own
 * accounting trail (for a CONFIRMED, possibly PAID registration) or corrupt capacity/waitlist
 * accounting for an event this member is still `PENDING_PAYMENT`/`WAITLISTED` on -- so this
 * contributor takes the same "retain, only who-touched-it is a trace" posture
 * [AccountingPersonalData]/[ApiKeyPersonalData]/[WebhookPersonalData] already establish, rather than
 * `CrmPersonalData`'s real-DELETE posture (that entity carries no such downstream accounting/
 * capacity dependents).
 */
object EventPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "events"
    override val displayName = "Veranstaltungen"
    override val coveredTables = setOf(EventTable, EventRegistrationTable)

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
        return outcomes
    }
}
