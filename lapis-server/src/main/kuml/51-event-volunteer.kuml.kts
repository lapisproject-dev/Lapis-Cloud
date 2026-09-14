// Welle V1.4.3.7 "Helfer-/Schichtplanung für Veranstaltungen" -- see network.lapis.cloud.server
// .events.EventVolunteerStore for the store logic, network.lapis.cloud.server.events
// .EventVolunteerCapacityGuard for the capacity lock, network.lapis.cloud.server.rpc
// .EventVolunteerService for the RPC surface, and network.lapis.cloud.shared.rpc
// .IEventVolunteerService for the authenticated RPC-facing interface.
//
// **This file models exactly two new tables**, `event_volunteer_shift` (die Schicht-Definition:
// Beschreibung, Zeitraum, benötigte Anzahl, ACTIVE/CANCELLED-Lifecycle) und
// `event_volunteer_signup` (eine einzelne Mitglieds-Zusage zu genau einer Schicht,
// CONFIRMED/CANCELLED-Lifecycle).
//
// **Warum zwei Tabellen, nicht eine.** Eine Schicht-Definition und eine einzelne Zusage haben
// unabhängige Lifecycles: eine Schicht kann storniert werden, ohne dass ihre historischen Zusagen
// verloren gehen (Soft-Cancel auf `event_volunteer_shift`), und ein einzelnes Mitglied kann seine
// Zusage zurückziehen, ohne die Schicht selbst anzufassen (Soft-Cancel auf
// `event_volunteer_signup`). Eine einzelne Tabelle müsste beide Konzepte vermischen (n Mitglieder
// pro Schicht, m:n).
//
// **Warum eine separate `IEventVolunteerService`, nicht neue Methoden auf `IEventService`** --
// welche Helferschichten zu einem Event existieren und wer sich dafür eingetragen hat ist
// orthogonal zur event/registration Fachlogik `IEventService` bereits besitzt -- dieselbe
// Begründung, die `IEventRoomService`/`ICateringService` für Raumverwaltung/Catering bereits
// etablieren (siehe `49-event-room.kuml.kts`/`50-event-catering.kuml.kts` file header).
//
// **Warum `event_volunteer_signup.active_member_key`, nicht ein partieller Unique-Index.** Exakt
// dasselbe Portabilitätsproblem, das `event_registration.active_participant_key` bereits löst
// (siehe `39-events.kuml.kts` file header "active_participant_key"): H2 MODE=PostgreSQL (die
// gesamte Testsuite) lehnt sowohl einen partiellen Unique-Index (`CREATE UNIQUE INDEX ... WHERE`)
// als auch den Generated-Column-Workaround ab. `active_member_key` ist NULL genau dann, wenn
// `status = CANCELLED`, sonst der Mitglieds-UUID-String -- der eindeutige Index liegt auf
// `(shift_id, active_member_key)`, mirrors `uq_event_registration_active_participant` exakt. Nur
// `EventVolunteerStore` schreibt diese Spalte.
//
// **Warum Soft-Cancel bei BEIDEN Entitäten, kein DELETE.** `event_volunteer_shift` ist FK-Ziel
// jeder zugehörigen `event_volunteer_signup`-Zeile -- ein DELETE würde entweder die Zusagen
// verwaisen lassen oder eine ON-DELETE-CASCADE-Regel brauchen (in diesem Codebase bewusst nie
// verwendet, siehe jede vorherige Migration). `event_volunteer_signup` selbst ist reine
// Fachhistorie ("wer hat wann zu-/abgesagt") -- ein zurückgezogenes CONFIRMED->CANCELLED bleibt als
// Datensatz erhalten, statt gelöscht zu werden, exakt wie `event_registration.status = CANCELLED`
// es bereits für Anmeldungen vormacht.
//
// **Keine neue "Helfer"-Rolle.** `AccountRole` bleibt `{MEMBER, BOARD, TREASURER, ADMIN}` --
// unverändert von diesem File. Eine Schicht-Zusage ist Fachdaten, keine Berechtigungsänderung; sie
// hinterlässt hier auch keine eigene Spalte auf `member`/`account`.
//
// Cross-domain stubs: minimal id-only `Event` (owned by `39-events.kuml.kts`) and `Member` (owned
// by `00-foundation.kuml.kts`), same single-file-evaluation pattern every later domain file's own
// header documents (most recently `50-event-catering.kuml.kts`'s own stubs) -- purely so
// `UmlToErmTransformer` can resolve this file's `event_volunteer_shift.event_id`/`.created_by` and
// `event_volunteer_signup.member_id` FKs. Both stubs here carry EXACTLY the same
// `tableName`/`kotlinObjectName`/attribute declarations as their canonical originals -- never more
// attributes than the canonical version (id-only stays id-only), or `DomainModelMerger.merge`
// throws.
//
// Cross-field CHECK constraints (`active_member_key`/`status`-consistency) are SQL-only, not
// modelled here -- same posture every later domain file's own header documents (most recently
// `39-events.kuml.kts`'s own "Cross-field CHECK constraints are SQL-only" section). The
// `(shift_id, active_member_key)` unique index is likewise SQL-only, same posture
// `EventRegistrationTable`'s own "index(es)... not emitted" comment already documents for Exposed.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "EventVolunteer") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub (most
    // recently 50-event-catering.kuml.kts). Resolves event_volunteer_shift.created_by's/
    // event_volunteer_signup.member_id's «Column».fkEntity overrides within this file's own
    // single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Events-owned stub -- id-only, mirrors 39-events.kuml.kts's own Event entity's id attribute
    // exactly. Resolves event_volunteer_shift.event_id's «Column».fkEntity override within this
    // file's own single-file evaluation.
    val event = classOf(name = "Event") {
        stereotype("Entity") { "tableName" to "event"; "kotlinObjectName" to "EventTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Longest literal CANCELLED (9) -> VARCHAR(9).
    val shiftStatus = enumOf(name = "EventVolunteerShiftStatus") {
        literal(name = "ACTIVE")
        literal(name = "CANCELLED")
    }

    val signupStatus = enumOf(name = "EventVolunteerSignupStatus") {
        literal(name = "CONFIRMED")
        literal(name = "CANCELLED")
    }

    val eventVolunteerShift = classOf(name = "EventVolunteerShift") {
        stereotype("Entity") { "tableName" to "event_volunteer_shift"; "kotlinObjectName" to "EventVolunteerShiftTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "eventId", type = "UUID") {
            stereotype("Column") { "columnName" to "event_id"; "fkEntity" to "Event" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "startsAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "starts_at" }
        }
        attribute(name = "endsAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "ends_at" }
        }
        attribute(name = "neededCount", type = "Int") {
            stereotype("Column") { "columnName" to "needed_count" }
        }
        attribute(name = "status", type = shiftStatus) {
            defaultValue = "ACTIVE"
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.EventVolunteerShiftStatus" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
    }

    val eventVolunteerSignup = classOf(name = "EventVolunteerSignup") {
        stereotype("Entity") { "tableName" to "event_volunteer_signup"; "kotlinObjectName" to "EventVolunteerSignupTable" }
        stereotype("Index") {
            "columns" to listOf("shift_id", "active_member_key")
            "name" to "uq_event_volunteer_signup_active"
            "unique" to true
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "shiftId", type = "UUID") {
            stereotype("Column") { "columnName" to "shift_id"; "fkEntity" to "EventVolunteerShift" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "status", type = signupStatus) {
            defaultValue = "CONFIRMED"
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.EventVolunteerSignupStatus" }
        }
        attribute(name = "signedUpAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "signed_up_at" }
        }
        attribute(name = "cancelledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cancelled_at" }
        }
        // See file header "active_member_key" -- NULL exactly when status is CANCELLED.
        attribute(name = "activeMemberKey", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "active_member_key"; "sqlType" to "VARCHAR(36)" }
        }
    }
}
