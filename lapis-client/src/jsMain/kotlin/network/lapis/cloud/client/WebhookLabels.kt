package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.WebhookDeactivationReason
import network.lapis.cloud.shared.domain.WebhookDeliveryStatus
import network.lapis.cloud.shared.domain.WebhookEventType
import network.lapis.cloud.shared.domain.WebhookFailureReason

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- Design-Team decision D9 (Terminologie) + D10
 * (Badge-Grammatik): "Signaturgeheimnis" statt Secret, "Zustellung" statt Delivery, "Endpunkt"
 * statt Endpoint, "Zustellungsprotokoll" statt Log; [statusBadge] (gefüllt) für Lebenszyklus-
 * Zustände, [typeBadge] (Kontur) für den Event-Typ -- siehe `StatusBadge.kt` KDoc für die
 * grundsätzliche Unterscheidung.
 */
fun Container.webhookDeliveryStatusBadge(status: WebhookDeliveryStatus): Span =
    when (status) {
        WebhookDeliveryStatus.DELIVERED -> statusBadge(tr("Zugestellt"), "success")
        WebhookDeliveryStatus.PENDING -> statusBadge(tr("Ausstehend"), "secondary")
        WebhookDeliveryStatus.DELIVERING -> statusBadge(tr("Wird zugestellt"), "secondary")
        WebhookDeliveryStatus.FAILED -> statusBadge(tr("Fehlgeschlagen"), "warning")
        WebhookDeliveryStatus.ABANDONED -> statusBadge(tr("Abgebrochen"), "danger")
    }

fun Container.webhookEventTypeBadge(eventType: WebhookEventType): Span = typeBadge(webhookEventTypeLabel(eventType), "info")

fun webhookEventTypeLabel(eventType: WebhookEventType): String =
    when (eventType) {
        WebhookEventType.COMMITTEE_CREATED -> tr("Gremium angelegt")
        WebhookEventType.COMMITTEE_UPDATED -> tr("Gremium geändert")
        WebhookEventType.MEETING_CREATED -> tr("Sitzung angelegt")
        WebhookEventType.MEETING_HELD -> tr("Sitzung abgehalten")
        WebhookEventType.RESOLUTION_ADOPTED -> tr("Beschluss gefasst")
        WebhookEventType.MOTION_SCHEDULED -> tr("Antrag terminiert")
        WebhookEventType.MEMBER_CREATED -> tr("Mitglied angelegt")
        WebhookEventType.CONTRIBUTION_PAID -> tr("Beitrag bezahlt")
        WebhookEventType.DONATION_RECEIVED -> tr("Spende erhalten")
        WebhookEventType.WEBHOOK_TEST -> tr("Test-Ereignis")
    }

fun webhookFailureReasonLabel(reason: WebhookFailureReason): String =
    when (reason) {
        WebhookFailureReason.TIMEOUT -> tr("Zeitüberschreitung")
        WebhookFailureReason.CONNECTION_REFUSED -> tr("Verbindung abgelehnt")
        WebhookFailureReason.DNS_OR_TLS -> tr("DNS- oder TLS-Fehler")
        WebhookFailureReason.HTTP_ERROR -> tr("HTTP-Fehler")
        WebhookFailureReason.URL_REJECTED -> tr("Adresse abgelehnt")
        WebhookFailureReason.ENDPOINT_DEACTIVATED -> tr("Endpunkt deaktiviert")
        WebhookFailureReason.RETRIES_EXHAUSTED -> tr("Wiederholungen erschöpft")
    }

fun webhookDeactivationReasonLabel(reason: WebhookDeactivationReason): String =
    when (reason) {
        WebhookDeactivationReason.DELIVERY_FAILURES -> tr("wiederholte Zustellfehler")
        WebhookDeactivationReason.MANUAL -> tr("manuell deaktiviert")
        WebhookDeactivationReason.KEY_REVOKED -> tr("API-Schlüssel widerrufen")
        WebhookDeactivationReason.RECEIVER_GONE -> tr("Ziel meldet dauerhaft nicht mehr erreichbar (410)")
    }
