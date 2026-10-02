package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditLogEntryDto
import network.lapis.cloud.shared.domain.AuditMarkers

/**
 * V1.9.35 -- translates the value-free audit markers for the protocol display. A marker only counts when BOTH the entity type
 * fits and the after-snapshot is EXACTLY the marker string; everything else (an unknown marker, the same text on another entity
 * type) falls back to the generic display. Pure and DOM-free.
 */
private fun markerOf(entry: AuditLogEntryDto): String? = entry.afterSnapshot?.takeIf { markerEntityType(it) == entry.entityType }

private fun markerEntityType(marker: String): AuditEntityType? =
    when (marker) {
        AuditMarkers.MEMBER_ADDRESS_READ,
        AuditMarkers.MEMBER_ADDRESS_UPDATED,
        AuditMarkers.MEMBER_BENEFICIAL_OWNER_UPDATED,
        -> AuditEntityType.MEMBER
        AuditMarkers.EVENT_REFUND_MARKED -> AuditEntityType.PAYMENT_TRANSACTION
        else -> null
    }

/** "Eingesehen" for a read marker, else the generic label of the action. */
internal fun auditEntryActionLabel(entry: AuditLogEntryDto): String =
    if (markerOf(entry) == AuditMarkers.MEMBER_ADDRESS_READ) gettext("Eingesehen") else auditActionLabel(entry.action)

internal fun auditEntryActionColor(entry: AuditLogEntryDto): String =
    if (markerOf(entry) == AuditMarkers.MEMBER_ADDRESS_READ) "info" else auditActionColor(entry.action)

/** The human sentence for a known marker string, or `null` (the caller then shows the raw snapshot as before). */
internal fun auditMarkerDescription(
    entityType: AuditEntityType,
    raw: String,
): String? {
    if (markerEntityType(raw) != entityType) return null
    return when (raw) {
        AuditMarkers.MEMBER_ADDRESS_READ -> gettext("Anschrift und GwG-Angaben eingesehen")
        AuditMarkers.MEMBER_ADDRESS_UPDATED -> gettext("Anschrift geändert")
        AuditMarkers.MEMBER_BENEFICIAL_OWNER_UPDATED -> gettext("GwG-Angaben geändert")
        AuditMarkers.EVENT_REFUND_MARKED -> gettext("Erstattung außerhalb von Lapis Cloud vermerkt")
        else -> null
    }
}
