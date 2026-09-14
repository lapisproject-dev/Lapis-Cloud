package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.CateringOrderDto
import network.lapis.cloud.shared.domain.CateringOrderInput
import network.lapis.cloud.shared.domain.CateringOrderStatus

/**
 * Welle V1.4.3.5 "Catering-Management für Veranstaltungen" -- aggregierte, freie
 * Bestellpositionen-CRUD pro Event, deliberately its OWN RPC interface rather than a set of
 * methods on [IEventService]: welche Catering-Bestellpositionen zu einem Event existieren ist
 * orthogonal zur event/registration Fachlogik `IEventService` bereits besitzt -- genau dieselbe
 * Begründung, die [IEventRoomService] für die Raumverwaltung bereits etabliert.
 *
 * Role: BOARD/ADMIN for every method -- Catering-Planung ist Management/back-office data, niemals
 * member-public (same tier as [IEventRoomService]/[ICrmService]).
 *
 * Deliberately out of scope for this wave: kein Anbieter-/Preis-/Rechnungsfeld (V1.4.3.6, separate
 * spätere Welle). Siehe `network.lapis.cloud.shared.domain.CateringOrderInput` KDoc für die
 * Art.-9-DSGVO-Begründung, warum diese Bestellpositionen aggregiert auf Event-Ebene bleiben statt
 * einer Erweiterung von `EventRegistration`.
 */
@RpcService
interface ICateringService {
    /** Role: BOARD/ADMIN. */
    suspend fun listCateringOrders(eventId: String): List<CateringOrderDto>

    /** Role: BOARD/ADMIN. Rejects a blank/too-long [CateringOrderInput.description], a
     * non-positive [CateringOrderInput.quantity], a too-long [CateringOrderInput.allergenNotes],
     * or an unknown [CateringOrderInput.eventId] (`NotFoundException`). */
    suspend fun createCateringOrder(input: CateringOrderInput): CateringOrderDto

    /** Role: BOARD/ADMIN. Same validation as [createCateringOrder]. [CateringOrderInput.eventId]
     * is not just validated but actually APPLIED -- passing a different (existing) event id
     * reassigns the order to that event, moving it out of the original event's order list. This
     * is a deliberate supported edit (e.g. correcting an order that was created under the wrong
     * event), not a no-op field. */
    suspend fun updateCateringOrder(
        id: String,
        input: CateringOrderInput,
    ): CateringOrderDto

    /** Role: BOARD/ADMIN. */
    suspend fun setCateringOrderStatus(
        id: String,
        status: CateringOrderStatus,
    ): CateringOrderDto

    /** Role: BOARD/ADMIN. Real DELETE -- unlike `IEventRoomService.deactivateRoom`, a catering
     * order is never a FK target from outside this domain, so there is no "keeps existing
     * references valid" reason to soft-delete/deactivate instead. */
    suspend fun deleteCateringOrder(id: String)
}
