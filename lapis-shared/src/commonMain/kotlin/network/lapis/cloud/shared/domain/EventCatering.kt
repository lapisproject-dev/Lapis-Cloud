package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.3.5 "Catering-Management für Veranstaltungen" -- aggregierte, freie Bestellpositionen
 * pro Event, verwaltet durch BOARD/ADMIN. Orthogonal zur `event`/`event_registration`-Fachlogik
 * `IEventService` bereits besitzt (see `ICateringService` KDoc for why this is a separate RPC
 * interface, same posture `IEventRoomService` already establishes). See `50-event-catering.kuml.kts`
 * file header for the full entity rationale.
 *
 * **Kern-Design-Entscheidung: aggregiert auf Event-Ebene, KEINE pro-Person-Erfassung.** Eine
 * Erweiterung von `EventRegistration`/pro-Person-Allergie-/Ernährungsangaben würde eine
 * Art.-9-DSGVO-Sonderkategorie (Gesundheitsdaten) begründen, sobald sie einer identifizierbaren
 * Person zugeordnet ist -- das bräuchte eine eigene Rechtsgrundlage/Löschfrist-Maschinerie, die
 * heute in diesem Codebase nicht existiert. Eine aggregierte Bestellposition auf Event-Ebene
 * ("20x vegetarisch, Allergene: Nüsse bei Position X") ist reine Planungsangabe des Veranstalters
 * -- sie beschreibt eine BESTELLUNG, nicht eine Person -- und ist deshalb keine Personendaten im
 * Sinne der DSGVO. [CateringOrderInput.allergenNotes] trägt aus genau diesem Grund KEINEN
 * Personenbezug: der Freitext beschreibt ausschließlich die Bestellposition selbst (z. B. "enthält
 * Nüsse"), niemals wer welche Unverträglichkeit hat.
 *
 * Deliberately out of scope for this wave: kein Anbieter-/Preis-/Rechnungsfeld -- Anbindung an
 * einen konkreten Catering-Dienstleister inkl. Kosten ist Gegenstand einer späteren Welle
 * (V1.4.3.6), nicht dieser.
 */
@Serializable
enum class CateringOrderStatus { PLANNED, ORDERED, DELIVERED }

/**
 * Role: BOARD/ADMIN. [description] ist der einzige Pflicht-Freitext (z. B. "Vegetarisches Buffet"),
 * [allergenNotes] optional und -- siehe Klassen-KDoc oben -- bewusst NICHT personenbezogen: reine
 * Bestellpositions-Eigenschaft, keine Zuordnung zu einer identifizierbaren Person.
 */
@Serializable
data class CateringOrderInput(
    val eventId: String,
    val description: String,
    val quantity: Int,
    val allergenNotes: String? = null,
)

@Serializable
data class CateringOrderDto(
    val id: String,
    val eventId: String,
    val description: String,
    val quantity: Int,
    val allergenNotes: String?,
    val status: CateringOrderStatus,
    val createdAt: LocalDateTime,
    val createdBy: String,
)
