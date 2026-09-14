package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventCateringOrderTable
import network.lapis.cloud.server.events.EventCateringStore
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CateringOrderDto
import network.lapis.cloud.shared.domain.CateringOrderInput
import network.lapis.cloud.shared.domain.CateringOrderStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ICateringService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

internal val CATERING_MANAGE_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/** Mirrors `event_catering_order.description VARCHAR(500)` (`V37__event_catering.sql`) --
 * same "friendly first gate, DB is the backstop" posture `EventRoomService`'s own
 * `MAX_EQUIPMENT_TAGS_CSV_LENGTH` KDoc documents. */
private const val MAX_DESCRIPTION_LENGTH = 500

/** Mirrors `event_catering_order.allergen_notes VARCHAR(1000)`. */
private const val MAX_ALLERGEN_NOTES_LENGTH = 1000

/**
 * Welle V1.4.3.5 "Catering-Management für Veranstaltungen" -- implements [ICateringService].
 * Aggregierte, freie Bestellpositionen pro Event -- KEINE Personendaten (siehe
 * [CateringOrderInput] KDoc für die Art.-9-DSGVO-Begründung, warum diese Welle bewusst nicht
 * `EventRegistration` um eine pro-Person-Allergie-/Ernährungserfassung erweitert, sondern
 * aggregiert auf Event-Ebene bleibt).
 */
class CateringService(
    private val call: ApplicationCall,
) : ICateringService {
    override suspend fun listCateringOrders(eventId: String): List<CateringOrderDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*CATERING_MANAGE_ROLES)
        val eventUuid = eventId.toEventUuid()
        return transaction {
            EventStore.getEventOrThrow(eventUuid)
            EventCateringStore.listOrders(eventUuid).map { it.toCateringOrderDto() }
        }
    }

    override suspend fun createCateringOrder(input: CateringOrderInput): CateringOrderDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CATERING_MANAGE_ROLES)
        val description = requireValidDescription(input.description)
        requireValidQuantity(input.quantity)
        val allergenNotes = requireValidAllergenNotes(input.allergenNotes)
        val eventUuid = input.eventId.toEventUuid()
        return transaction {
            EventStore.getEventOrThrow(eventUuid)
            val id = Uuid.random()
            EventCateringStore.insertOrder(
                id = id,
                eventId = eventUuid,
                description = description,
                quantity = input.quantity,
                allergenNotes = allergenNotes,
                createdAt = DbClock.nowLocalDateTime(),
                createdBy = current.memberId,
            )
            EventCateringStore.getOrderOrThrow(id).toCateringOrderDto()
        }
    }

    override suspend fun updateCateringOrder(
        id: String,
        input: CateringOrderInput,
    ): CateringOrderDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CATERING_MANAGE_ROLES)
        val description = requireValidDescription(input.description)
        requireValidQuantity(input.quantity)
        val allergenNotes = requireValidAllergenNotes(input.allergenNotes)
        val orderId = id.toCateringOrderUuid()
        val eventUuid = input.eventId.toEventUuid()
        return transaction {
            EventCateringStore.getOrderOrThrow(orderId)
            EventStore.getEventOrThrow(eventUuid)
            EventCateringStore.updateOrder(
                id = orderId,
                eventId = eventUuid,
                description = description,
                quantity = input.quantity,
                allergenNotes = allergenNotes,
            )
            EventCateringStore.getOrderOrThrow(orderId).toCateringOrderDto()
        }
    }

    override suspend fun setCateringOrderStatus(
        id: String,
        status: CateringOrderStatus,
    ): CateringOrderDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*CATERING_MANAGE_ROLES)
        val orderId = id.toCateringOrderUuid()
        return transaction {
            EventCateringStore.getOrderOrThrow(orderId)
            EventCateringStore.setStatus(id = orderId, status = status)
            EventCateringStore.getOrderOrThrow(orderId).toCateringOrderDto()
        }
    }

    override suspend fun deleteCateringOrder(id: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(*CATERING_MANAGE_ROLES)
        val orderId = id.toCateringOrderUuid()
        transaction {
            EventCateringStore.getOrderOrThrow(orderId)
            EventCateringStore.deleteOrder(orderId)
        }
    }

    private fun requireValidDescription(description: String): String {
        val trimmed = description.trim()
        if (trimmed.isBlank()) throw BadRequestException("Die Beschreibung darf nicht leer sein.")
        if (trimmed.length > MAX_DESCRIPTION_LENGTH) {
            throw BadRequestException("Die Beschreibung darf höchstens $MAX_DESCRIPTION_LENGTH Zeichen lang sein.")
        }
        return trimmed
    }

    private fun requireValidQuantity(quantity: Int) {
        if (quantity <= 0) throw BadRequestException("Die Menge muss größer als 0 sein.")
    }

    /** Serverseitige Längenprüfung statt die DB entscheiden zu lassen -- same "friendly first
     * gate, DB is the backstop" posture `EventRoomService.requireValidEquipmentTags` KDoc
     * documents, hier einfacher da nur ein Freitextfeld (kein CSV-Join-Fall). */
    private fun requireValidAllergenNotes(allergenNotes: String?): String? {
        val trimmed = allergenNotes?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.length > MAX_ALLERGEN_NOTES_LENGTH) {
            throw BadRequestException("Die Allergen-/Hinweisangabe darf höchstens $MAX_ALLERGEN_NOTES_LENGTH Zeichen lang sein.")
        }
        return trimmed
    }
}

private fun String.toEventUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid event id: $this") }

private fun String.toCateringOrderUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

private fun ResultRow.toCateringOrderDto(): CateringOrderDto =
    CateringOrderDto(
        id = this[EventCateringOrderTable.id].toString(),
        eventId = this[EventCateringOrderTable.eventId].toString(),
        description = this[EventCateringOrderTable.description],
        quantity = this[EventCateringOrderTable.quantity],
        allergenNotes = this[EventCateringOrderTable.allergenNotes],
        status = this[EventCateringOrderTable.status],
        createdAt = this[EventCateringOrderTable.createdAt],
        createdBy = this[EventCateringOrderTable.createdBy].toString(),
    )
