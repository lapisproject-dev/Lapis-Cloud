package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.EventCaptureAcknowledgmentTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** Caps the export at this many newest rows: a member who attends every event of many years never approaches it, a runaway loop must not exhaust the heap. */
private const val MAX_EXPORTED_ACKNOWLEDGMENTS = 2_000

/**
 * V1.9.156 -- owns [EventCaptureAcknowledgmentTable]: the proof that a member acknowledged version X of the event-room capture consent for event Z on
 * day Y. Only the rows with a `member_id` belong to a data subject; the rows of persons WITHOUT an account (`guest_key`) name nobody and are no
 * personal data of any [DataSubject] this framework can reach.
 *
 * **Export**: the event title, the version label and the day -- never the hash (a hash proves nothing a person can read) and never another person's row.
 *
 * **Retained, never erased** -- the same accountability reasoning as `conference_guest_consent_acknowledgment` ([ConferencePersonalData]): the proof that a
 * person was shown and acknowledged the consent BEFORE their image and voice could be recorded is the organization's own Rechenschaftsnachweis
 * (Art. 5(2) / 7(1) GDPR). Erasing it would destroy the record that justifies a recording that already exists. The row carries a member id, a version
 * and a DATE; it carries no name (the member row itself is anonymized by the erasure) and no clock time. It disappears with its event (a deleted event
 * deletes its proofs first).
 */
object EventCapturePersonalData : MemberPersonalDataContributor {
    override val sectionKey = "eventCapture"
    override val displayName = "Veranstaltungen -- Einwilligung in Aufzeichnung und Übertragung"
    override val coveredTables = setOf(EventCaptureAcknowledgmentTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            val rows =
                EventCaptureAcknowledgmentTable
                    .selectAll()
                    .where { EventCaptureAcknowledgmentTable.memberId eq memberId }
                    .orderBy(EventCaptureAcknowledgmentTable.acknowledgedOn, SortOrder.DESC)
                    .limit(MAX_EXPORTED_ACKNOWLEDGMENTS + 1)
                    .toList()
            put("truncated", rows.size > MAX_EXPORTED_ACKNOWLEDGMENTS)
            putJsonArray("acknowledgments") {
                rows.take(MAX_EXPORTED_ACKNOWLEDGMENTS).forEach { row ->
                    val eventTitle =
                        EventTable
                            .selectAll()
                            .where { EventTable.id eq row[EventCaptureAcknowledgmentTable.eventId] }
                            .singleOrNull()
                            ?.get(EventTable.title)
                    add(
                        buildJsonObject {
                            put("eventTitle", eventTitle)
                            put("consentVersion", row[EventCaptureAcknowledgmentTable.consentVersion])
                            put("acknowledgedOn", row[EventCaptureAcknowledgmentTable.acknowledgedOn].toString())
                        },
                    )
                }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val count =
            EventCaptureAcknowledgmentTable
                .selectAll()
                .where { EventCaptureAcknowledgmentTable.memberId eq memberId }
                .count()
                .toInt()
        return listOf(
            TableErasureOutcome(
                table = "event_capture_acknowledgment",
                rowsRetained = count,
                retentionReason =
                    "Rechenschaftsnachweis (Art. 5 Abs. 2, Art. 7 Abs. 1 DSGVO): Nachweis, dass die Einwilligung in Aufzeichnung und " +
                        "Übertragung vor dem Betreten des Veranstaltungsraums bestätigt wurde. Enthält keinen Namen und keine Uhrzeit.",
            ),
        )
    }
}
