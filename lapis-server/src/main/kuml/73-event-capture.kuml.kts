// Welle V1.9.156 "Aufzeichnung und Live-Uebertragung in Veranstaltungsraeumen" -- see network.lapis.cloud.server.events.EventCaptureAcknowledgments (the only
// package code that reads or writes this table, apart from the GDPR contributor), docs/features/event-capture.adoc (V110__event_capture.sql).
//
// **One new table.** `event_capture_acknowledgment` is the proof that a participant acknowledged version X of the event-room capture consent for event Z on day Y.
// It stores as little as possible (Art. 9 GDPR: participation in a political or religious event):
//   * **No name, no room, no clock time** -- `acknowledged_on` is a DATE on purpose (no time of attendance).
//   * **Exactly one subject**: `member_id` (a person with an account, FK) OR `guest_key` (SHA-256 hex of the LiveKit identity `evg-...` of a person WITHOUT an
//     account; no name; it can be matched with the track rows of a recording, which is what the proof is for). The CHECK is SQL-only.
//   * **FK naming**: plain «Column» UUID attributes with «Column».fkEntity, never a UML association (the domain-wide policy 21-auction.kuml.kts documents).
//     Minimal id-only stubs (owned by Events and Foundation) resolve them. No ON DELETE CASCADE: the application deletes explicitly.
//   * **Time fields**: `acknowledged_on` is a class-B-style wall-clock DATE of the organization zone, see lapis-server/src/test/resources/time-fields.tsv.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "EventCapture") {
    applyProfile(ermMappingProfile)

    // 39-events.kuml.kts-owned stub -- id-only.
    val event = classOf(name = "Event") {
        stereotype("Entity") { "tableName" to "event"; "kotlinObjectName" to "EventTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Foundation-owned stub -- id-only.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val acknowledgment = classOf(name = "EventCaptureAcknowledgment") {
        stereotype("Entity") { "tableName" to "event_capture_acknowledgment"; "kotlinObjectName" to "EventCaptureAcknowledgmentTable" }
        stereotype("Index") { "columns" to listOf("event_id"); "name" to "idx_event_capture_ack_event" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "eventId", type = "UUID") {
            stereotype("Column") { "columnName" to "event_id"; "fkEntity" to "Event" }
        }
        attribute(name = "memberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "guestKey", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "guest_key"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "consentVersion", type = "String") {
            stereotype("Column") { "columnName" to "consent_version"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "consentSha256", type = "String") {
            stereotype("Column") { "columnName" to "consent_sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "acknowledgedOn", type = "LocalDate") {
            stereotype("Column") { "columnName" to "acknowledged_on" }
        }
    }
}
