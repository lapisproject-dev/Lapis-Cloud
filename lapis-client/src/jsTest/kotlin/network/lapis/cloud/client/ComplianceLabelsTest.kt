package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AvvStatus
import network.lapis.cloud.shared.domain.BackupOperationStatus
import network.lapis.cloud.shared.domain.BackupOperationType
import network.lapis.cloud.shared.domain.BoardChangeType
import network.lapis.cloud.shared.domain.BreachDeadlineStatus
import network.lapis.cloud.shared.domain.BreachStatus
import network.lapis.cloud.shared.domain.DpiaRiskBand
import network.lapis.cloud.shared.domain.DsfaStatus
import network.lapis.cloud.shared.domain.DsgvoAuditAction
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureStatus
import network.lapis.cloud.shared.domain.RiskLevel
import network.lapis.cloud.shared.domain.TomCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compliance UI wave -- covers the pure, DOM-independent label/color functions in
 * `ComplianceLabels.kt`, same scope/assertion posture as [CommitteesScreenTest]/[AccountingLabelsTest]
 * (no DOM/rendering test harness exists in this module).
 */
class ComplianceLabelsTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark", "light")

    @Test
    fun auditActionLabel_isNonBlankForEveryValue() {
        AuditAction.entries.forEach { action ->
            assertTrue(auditActionLabel(action).isNotBlank(), "expected a non-blank label for $action")
        }
    }

    @Test
    fun auditActionColor_isARealBootstrapHue() {
        AuditAction.entries.forEach { action ->
            val color = auditActionColor(action)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $action, got \"$color\"")
        }
    }

    @Test
    fun auditEntityTypeLabel_isNonBlankForEveryValue() {
        AuditEntityType.entries.forEach { type ->
            assertTrue(auditEntityTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun auditEntityTypeColor_isARealBootstrapHue() {
        AuditEntityType.entries.forEach { type ->
            val color = auditEntityTypeColor(type)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $type, got \"$color\"")
        }
    }

    @Test
    fun auditActionLabel_postIsGebucht() {
        assertEquals("Gebucht", auditActionLabel(AuditAction.POST))
    }

    @Test
    fun auditEntityTypeLabel_journalEntryIsBuchung() {
        assertEquals("Buchung", auditEntityTypeLabel(AuditEntityType.JOURNAL_ENTRY))
    }

    // ---- Screen 2 of 5 (BackupScreen.kt) -----------------------------------------------------

    @Test
    fun backupOperationTypeLabel_isNonBlankForEveryValue() {
        BackupOperationType.entries.forEach { type ->
            assertTrue(backupOperationTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun backupOperationTypeColor_isARealBootstrapHue() {
        BackupOperationType.entries.forEach { type ->
            val color = backupOperationTypeColor(type)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $type, got \"$color\"")
        }
    }

    @Test
    fun backupOperationStatusLabel_succeededIsErfolgreich() {
        assertEquals("Erfolgreich", backupOperationStatusLabel(BackupOperationStatus.SUCCEEDED))
    }

    @Test
    fun backupOperationStatusLabel_failedIsFehlgeschlagen() {
        assertEquals("Fehlgeschlagen", backupOperationStatusLabel(BackupOperationStatus.FAILED))
    }

    @Test
    fun backupOperationStatusColor_succeededIsSuccessFailedIsDanger() {
        assertEquals("success", backupOperationStatusColor(BackupOperationStatus.SUCCEEDED))
        assertEquals("danger", backupOperationStatusColor(BackupOperationStatus.FAILED))
    }

    // ---- Screen 3 of 5 (DsgvoComplianceScreen.kt) --------------------------------------------

    @Test
    fun avvStatusLabel_isNonBlankForEveryValue() {
        AvvStatus.entries.forEach { status ->
            assertTrue(avvStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun avvStatusColor_isARealBootstrapHue() {
        AvvStatus.entries.forEach { status ->
            val color = avvStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun avvStatusLabel_signedIsUnterzeichnet() {
        assertEquals("Unterzeichnet", avvStatusLabel(AvvStatus.SIGNED))
    }

    @Test
    fun tomCategoryLabel_isNonBlankForEveryValue() {
        TomCategory.entries.forEach { category ->
            assertTrue(tomCategoryLabel(category).isNotBlank(), "expected a non-blank label for $category")
        }
    }

    @Test
    fun tomCategoryColor_isARealBootstrapHue() {
        TomCategory.entries.forEach { category ->
            val color = tomCategoryColor(category)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $category, got \"$color\"")
        }
    }

    @Test
    fun dsfaStatusLabel_isNonBlankForEveryValue() {
        DsfaStatus.entries.forEach { status ->
            assertTrue(dsfaStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun dsfaStatusColor_isARealBootstrapHue() {
        DsfaStatus.entries.forEach { status ->
            val color = dsfaStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun breachStatusLabel_isNonBlankForEveryValue() {
        BreachStatus.entries.forEach { status ->
            assertTrue(breachStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun breachStatusColor_isARealBootstrapHue() {
        BreachStatus.entries.forEach { status ->
            val color = breachStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun breachDeadlineStatusLabel_isNonBlankForEveryValue() {
        BreachDeadlineStatus.entries.forEach { status ->
            assertTrue(breachDeadlineStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun breachDeadlineStatusColor_isARealBootstrapHue() {
        BreachDeadlineStatus.entries.forEach { status ->
            val color = breachDeadlineStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    /** D7/D12: SATISFIED must never read as "success" -- it only means "notification recorded",
     * not "on time". */
    @Test
    fun breachDeadlineStatusColor_satisfiedIsSecondaryNeverSuccess() {
        assertEquals("secondary", breachDeadlineStatusColor(BreachDeadlineStatus.SATISFIED))
    }

    @Test
    fun breachDeadlineStatusColor_overdueIsDanger() {
        assertEquals("danger", breachDeadlineStatusColor(BreachDeadlineStatus.OVERDUE))
    }

    @Test
    fun dpiaRiskBandLabel_isNonBlankForEveryValue() {
        DpiaRiskBand.entries.forEach { band ->
            assertTrue(dpiaRiskBandLabel(band).isNotBlank(), "expected a non-blank label for $band")
        }
    }

    @Test
    fun dpiaRiskBandColor_isARealBootstrapHue() {
        DpiaRiskBand.entries.forEach { band ->
            val color = dpiaRiskBandColor(band)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $band, got \"$color\"")
        }
    }

    @Test
    fun riskLevelLabel_isNonBlankForEveryValue() {
        RiskLevel.entries.forEach { level ->
            assertTrue(riskLevelLabel(level).isNotBlank(), "expected a non-blank label for $level")
        }
    }

    @Test
    fun riskLevelColor_isARealBootstrapHue() {
        RiskLevel.entries.forEach { level ->
            val color = riskLevelColor(level)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $level, got \"$color\"")
        }
    }

    // ---- Screen 4 of 5 -- DsgvoRightsScreen.kt ------------------------------------------------

    @Test
    fun erasureStatusLabel_isNonBlankForEveryValue() {
        ErasureStatus.entries.forEach { status ->
            assertTrue(erasureStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun erasureStatusColor_isARealBootstrapHue() {
        ErasureStatus.entries.forEach { status ->
            val color = erasureStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun erasureStatusColor_completedIsDangerNeverSuccess() {
        // D12: data loss is not a "success" color even though the workflow completed as designed.
        assertEquals("danger", erasureStatusColor(ErasureStatus.COMPLETED))
    }

    @Test
    fun erasureStatusColor_approvedIsWarningOneClickFromIrreversible() {
        assertEquals("warning", erasureStatusColor(ErasureStatus.APPROVED))
    }

    @Test
    fun erasureModeLabel_isNonBlankForEveryValue() {
        ErasureMode.entries.forEach { mode ->
            assertTrue(erasureModeLabel(mode).isNotBlank(), "expected a non-blank label for $mode")
        }
    }

    @Test
    fun erasureModeColor_isARealBootstrapHue() {
        ErasureMode.entries.forEach { mode ->
            val color = erasureModeColor(mode)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $mode, got \"$color\"")
        }
    }

    @Test
    fun erasureModeDescription_isNonBlankAndDistinctForEveryValue() {
        val descriptions = ErasureMode.entries.map { erasureModeDescription(it) }
        descriptions.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(descriptions.size, descriptions.toSet().size, "expected every mode to have a distinct description")
    }

    @Test
    fun erasureModeDescription_hardDeleteMentionsReceivedMessagesStayUntouched() {
        val text = erasureModeDescription(ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED)
        assertTrue(text.contains("empfangene"))
        assertTrue(text.contains("unberührt"))
    }

    @Test
    fun dsgvoAuditActionLabel_isNonBlankForEveryValue() {
        DsgvoAuditAction.entries.forEach { action ->
            assertTrue(dsgvoAuditActionLabel(action).isNotBlank(), "expected a non-blank label for $action")
        }
    }

    @Test
    fun dsgvoAuditActionColor_isARealBootstrapHue() {
        DsgvoAuditAction.entries.forEach { action ->
            val color = dsgvoAuditActionColor(action)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $action, got \"$color\"")
        }
    }

    @Test
    fun dsgvoAuditActionColor_mirrorsErasureStatusColorScaleForTheSameTransition() {
        assertEquals(erasureStatusColor(ErasureStatus.REQUESTED), dsgvoAuditActionColor(DsgvoAuditAction.ERASURE_REQUESTED))
        assertEquals(erasureStatusColor(ErasureStatus.APPROVED), dsgvoAuditActionColor(DsgvoAuditAction.ERASURE_APPROVED))
        assertEquals(erasureStatusColor(ErasureStatus.REJECTED), dsgvoAuditActionColor(DsgvoAuditAction.ERASURE_REJECTED))
        assertEquals(erasureStatusColor(ErasureStatus.COMPLETED), dsgvoAuditActionColor(DsgvoAuditAction.ERASURE_EXECUTED))
    }

    // ---- Screen 5 of 5 -- BoardMembershipScreen.kt --------------------------------------------

    @Test
    fun boardChangeTypeLabel_isNonBlankForEveryValue() {
        BoardChangeType.entries.forEach { type ->
            assertTrue(boardChangeTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun boardChangeTypeColor_isARealBootstrapHue() {
        BoardChangeType.entries.forEach { type ->
            val color = boardChangeTypeColor(type)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $type, got \"$color\"")
        }
    }

    /** D12: LEFT deliberately does NOT get a negative-flavored hue -- "leaving isn't inherently
     * negative -- term expiry is routine". */
    @Test
    fun boardChangeTypeColor_leftIsSecondaryNotDangerOrWarning() {
        assertEquals("secondary", boardChangeTypeColor(BoardChangeType.LEFT))
    }

    @Test
    fun boardChangeTypeColor_joinedIsSuccess() {
        assertEquals("success", boardChangeTypeColor(BoardChangeType.JOINED))
    }
}
