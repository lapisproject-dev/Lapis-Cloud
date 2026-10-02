package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditLogEntryDto
import network.lapis.cloud.shared.domain.AuditMarkers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun entry(
    entityType: AuditEntityType,
    after: String?,
    action: AuditAction = AuditAction.UPDATE,
) = AuditLogEntryDto(
    id = "a1",
    sequenceNumber = 1,
    occurredAt = LocalDateTime(2026, 10, 2, 12, 0),
    actorMemberId = "m1",
    actorMemberDisplayName = "Vorstand",
    actorRole = null,
    entityType = entityType,
    entityId = "e1",
    action = action,
    beforeSnapshot = null,
    afterSnapshot = after,
    entryHash = "h",
    previousEntryHash = null,
)

/** V1.9.35 -- the audit-marker translation is pure and strict: entity type AND exact marker. */
class AuditMarkerLabelsTest {
    @Test
    fun anAddressReadMarker_isLabelledViewed_withItsOwnColourAndSentence() {
        val e = entry(AuditEntityType.MEMBER, AuditMarkers.MEMBER_ADDRESS_READ)
        assertEquals("Eingesehen", auditEntryActionLabel(e))
        assertEquals("info", auditEntryActionColor(e))
        assertEquals("Anschrift und GwG-Angaben eingesehen", auditMarkerDescription(e.entityType, AuditMarkers.MEMBER_ADDRESS_READ))
    }

    @Test
    fun theWriteMarkers_keepTheGenericActionButGetASentence() {
        val addr = entry(AuditEntityType.MEMBER, AuditMarkers.MEMBER_ADDRESS_UPDATED)
        assertEquals(auditActionLabel(AuditAction.UPDATE), auditEntryActionLabel(addr))
        assertEquals("Anschrift geändert", auditMarkerDescription(AuditEntityType.MEMBER, AuditMarkers.MEMBER_ADDRESS_UPDATED))
        assertEquals(
            "GwG-Angaben geändert",
            auditMarkerDescription(AuditEntityType.MEMBER, AuditMarkers.MEMBER_BENEFICIAL_OWNER_UPDATED),
        )
        assertEquals(
            "Erstattung außerhalb von Lapis Cloud vermerkt",
            auditMarkerDescription(AuditEntityType.PAYMENT_TRANSACTION, AuditMarkers.EVENT_REFUND_MARKED),
        )
    }

    @Test
    fun theSameStringOnAForeignEntityType_isNotTranslated() {
        val foreign = entry(AuditEntityType.JOURNAL_ENTRY, AuditMarkers.MEMBER_ADDRESS_READ)
        assertEquals(auditActionLabel(AuditAction.UPDATE), auditEntryActionLabel(foreign))
        assertEquals(auditActionColor(AuditAction.UPDATE), auditEntryActionColor(foreign))
        assertNull(auditMarkerDescription(AuditEntityType.JOURNAL_ENTRY, AuditMarkers.MEMBER_ADDRESS_READ))
        assertNull(auditMarkerDescription(AuditEntityType.MEMBER, AuditMarkers.EVENT_REFUND_MARKED))
    }

    @Test
    fun anUnknownMarker_orNoSnapshot_fallsBackToTheGenericDisplay() {
        assertNull(auditMarkerDescription(AuditEntityType.MEMBER, "SOMETHING_ELSE"))
        assertNull(auditMarkerDescription(AuditEntityType.MEMBER, AuditMarkers.MEMBER_ADDRESS_READ + " "))
        assertEquals(auditActionLabel(AuditAction.CREATE), auditEntryActionLabel(entry(AuditEntityType.MEMBER, null, AuditAction.CREATE)))
    }
}
