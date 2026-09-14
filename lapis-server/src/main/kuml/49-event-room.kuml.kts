// Welle V1.4.3.4 "Raumverwaltung für Veranstaltungen" -- see network.lapis.cloud.server.events
// .EventRoomStore/EventRoomCollisionGuard for the store/collision-checking logic,
// network.lapis.cloud.server.rpc.EventRoomService for the RPC surface, and
// network.lapis.cloud.shared.rpc.IEventRoomService for the authenticated RPC-facing interface.
//
// **This file models exactly one new table**, `event_room` -- simple room master data (name,
// optional capacity, free-text equipment tags, ACTIVE/INACTIVE lifecycle). Freetext tags are
// stored as ONE comma-joined `VARCHAR`, deliberately not a child table -- there is no search/
// filter requirement on tags this wave, only display, so a dedicated join table would be
// machinery this wave does not need.
//
// **Mehrfachraum-Nutzung pro Event (m:n) ist bewusst NICHT Teil dieser Welle.** `event.roomId`
// (see this file's own addendum in `39-events.kuml.kts`) is a single nullable FK -- one event has
// at most one room. A future wave that needs simultaneous/breakout rooms would need a join entity
// (`event_room_assignment` or similar); deliberately not built ahead of an actual requirement.
//
// **Collision-checking is deliberately NOT a DB constraint** -- H2 (this whole test suite's
// dialect, `DatabaseConfig.kt`) has no GiST/btree_gist range-exclusion support, and Postgres-only
// syntax would not be portable. Overlap is instead enforced serverside under a `FOR UPDATE` row
// lock on the `event_room` row (`EventRoomCollisionGuard`, mirrors `EventCapacityGuard`'s own
// "lock the row whose invariant is being protected" posture) -- see that object's KDoc.
//
// **Why a separate `IEventRoomService`, not new methods on `IEventService`** -- room master data
// (create/deactivate/list, exactly `CostCenter`'s own CRUD shape) is orthogonal to the event/
// registration fachlogik `IEventService` already owns; a room's own lifecycle has nothing to do
// with any specific event. See `IEventRoomService` KDoc for the full rationale.
//
// **Why no `audit_log_entry` coverage** -- same posture `39-events.kuml.kts`'s own file header
// already takes for `event`/`event_registration`: this is ordinary master data, not the GoBD
// hash-chained financial ledger.
//
// Cross-domain stubs: minimal id-only `Event` (owned by `39-events.kuml.kts`) and `Member` (owned
// by `00-foundation.kuml.kts`), same single-file-evaluation pattern every later domain file's own
// header documents (most recently `48-open-item.kuml.kts`'s own stubs) -- purely so
// `UmlToErmTransformer` can resolve this file's `event_room.created_by` FK, and so the
// `event.room_id` addendum in `39-events.kuml.kts` can resolve ITS FK back to `event_room` without
// this file needing to be evaluated first (the addendum re-declares its own minimal `EventRoom`
// stub there -- see that file's own addendum comment). Both stubs here carry EXACTLY the same
// `tableName`/`kotlinObjectName`/attribute declarations as their canonical originals -- never more
// attributes than the canonical version (id-only stays id-only), or `DomainModelMerger.merge`
// throws.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "EventRooms") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub (most
    // recently 48-open-item.kuml.kts). Resolves event_room.created_by's «Column».fkEntity override
    // within this file's own single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Events-owned stub -- id-only, mirrors 39-events.kuml.kts's own Event entity's id attribute
    // exactly. Purely documentary within THIS file's evaluation (event.room_id's own FK is
    // resolved on 39-events.kuml.kts's side, via that file's own EventRoom addendum stub) --
    // included here only so a future association FROM event_room TO event (none exists today)
    // would have a resolvable target.
    val event = classOf(name = "Event") {
        stereotype("Entity") { "tableName" to "event"; "kotlinObjectName" to "EventTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Longest literal INACTIVE (8) -> VARCHAR(8). Literal order matches
    // network.lapis.cloud.shared.domain.EventRoomStatus.
    val eventRoomStatus = enumOf(name = "EventRoomStatus") {
        literal(name = "ACTIVE")
        literal(name = "INACTIVE")
    }

    val eventRoom = classOf(name = "EventRoom") {
        stereotype("Entity") { "tableName" to "event_room"; "kotlinObjectName" to "EventRoomTable" }
        stereotype("Index") { "columns" to listOf("name"); "name" to "uq_event_room_name"; "unique" to true }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        // NULL = unbounded/no capacity limit recorded.
        attribute(name = "capacity", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "capacity" }
        }
        // Comma-joined free text -- see file header "This file models exactly one new table".
        attribute(name = "equipmentTags", type = "String") {
            defaultValue = ""
            stereotype("Column") { "columnName" to "equipment_tags"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "status", type = eventRoomStatus) {
            defaultValue = "ACTIVE"
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.EventRoomStatus" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
    }
}
