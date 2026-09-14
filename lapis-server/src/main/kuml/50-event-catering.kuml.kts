// Welle V1.4.3.5 "Catering-Management für Veranstaltungen" -- see network.lapis.cloud.server
// .events.EventCateringStore for the store logic, network.lapis.cloud.server.rpc.CateringService
// for the RPC surface, and network.lapis.cloud.shared.rpc.ICateringService for the
// authenticated RPC-facing interface.
//
// **This file models exactly one new table**, `event_catering_order` -- aggregierte, freie
// Bestellpositionen pro Event (Beschreibung, Menge, optionale Allergen-/Hinweis-Freitext,
// PLANNED/ORDERED/DELIVERED-Lifecycle).
//
// **Warum aggregiert auf Event-Ebene, KEINE Erweiterung von `EventRegistration`/pro-Person-
// Erfassung** -- Allergie-/Ernährungsangaben, die einer identifizierbaren Person zugeordnet sind,
// wären eine Art.-9-DSGVO-Sonderkategorie (Gesundheitsdaten) und bräuchten eine eigene
// Rechtsgrundlage/Löschfrist-Maschinerie, die in diesem Codebase heute nicht existiert. Eine
// aggregierte Bestellposition auf Event-Ebene ("20x vegetarisch, Allergene: Nüsse bei Position X")
// ist reine Planungsangabe des Veranstalters -- sie beschreibt eine BESTELLUNG, nicht eine Person
// -- und ist deshalb keine Personendaten im Sinne der DSGVO. `allergen_notes` trägt aus genau
// diesem Grund keinen Personenbezug (siehe `CateringOrderInput` KDoc für die volle Begründung).
//
// **Warum eine separate `ICateringService`, nicht neue Methoden auf `IEventService`** -- welche
// Catering-Bestellpositionen zu einem Event existieren ist orthogonal zur event/registration
// Fachlogik `IEventService` bereits besitzt -- dieselbe Begründung, die `IEventRoomService` für
// die Raumverwaltung bereits etabliert (siehe `49-event-room.kuml.kts` file header).
//
// **Warum kein Anbieter-/Preis-/Rechnungsfeld** -- Anbindung an einen konkreten
// Catering-Dienstleister inkl. Kosten ist Gegenstand einer späteren, separaten Welle (V1.4.3.6),
// nicht dieser. Diese Welle deckt ausschließlich die interne Planungsangabe ab.
//
// **Warum DELETE statt Deaktivieren** -- anders als `event_room` (FK-Ziel jedes Events, das den
// Raum je zugeordnet hatte) ist eine Catering-Bestellposition niemals FK-Ziel von außerhalb dieser
// Domäne -- es gibt also keinen "bestehende Referenzen bleiben gültig"-Grund für ein Soft-Delete.
// `deleteCateringOrder` ist ein echtes DELETE.
//
// **Warum kein `audit_log_entry`-Coverage** -- same posture `49-event-room.kuml.kts`'s own file
// header already takes: this is ordinary Planungsdaten, not the GoBD hash-chained financial
// ledger.
//
// Cross-domain stubs: minimal id-only `Event` (owned by `39-events.kuml.kts`) and `Member` (owned
// by `00-foundation.kuml.kts`), same single-file-evaluation pattern every later domain file's own
// header documents (most recently `49-event-room.kuml.kts`'s own stubs) -- purely so
// `UmlToErmTransformer` can resolve this file's `event_catering_order.event_id`/`.created_by` FKs.
// Both stubs here carry EXACTLY the same `tableName`/`kotlinObjectName`/attribute declarations as
// their canonical originals -- never more attributes than the canonical version (id-only stays
// id-only), or `DomainModelMerger.merge` throws. Unlike the Room wave, `event` itself gains NO new
// column here -- `event_catering_order.event_id` is an ordinary 1:n FK, not a new column on
// `event`.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "EventCatering") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub (most
    // recently 49-event-room.kuml.kts). Resolves event_catering_order.created_by's «Column».fkEntity
    // override within this file's own single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Events-owned stub -- id-only, mirrors 39-events.kuml.kts's own Event entity's id attribute
    // exactly. Resolves event_catering_order.event_id's «Column».fkEntity override within this
    // file's own single-file evaluation.
    val event = classOf(name = "Event") {
        stereotype("Entity") { "tableName" to "event"; "kotlinObjectName" to "EventTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Longest literal DELIVERED (9) -> VARCHAR(9). Literal order matches
    // network.lapis.cloud.shared.domain.CateringOrderStatus.
    val cateringOrderStatus = enumOf(name = "CateringOrderStatus") {
        literal(name = "PLANNED")
        literal(name = "ORDERED")
        literal(name = "DELIVERED")
    }

    val eventCateringOrder = classOf(name = "EventCateringOrder") {
        stereotype("Entity") { "tableName" to "event_catering_order"; "kotlinObjectName" to "EventCateringOrderTable" }

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
        attribute(name = "quantity", type = "Int") {
            stereotype("Column") { "columnName" to "quantity" }
        }
        // Keine Personendaten -- siehe file header "Warum aggregiert auf Event-Ebene" oben.
        attribute(name = "allergenNotes", type = "String") {
            multiplicity = dev.kuml.uml.Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "allergen_notes"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "status", type = cateringOrderStatus) {
            defaultValue = "PLANNED"
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.CateringOrderStatus" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
    }
}
