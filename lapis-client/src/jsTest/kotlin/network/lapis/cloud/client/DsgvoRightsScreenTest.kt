package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DsgvoAuditAction
import network.lapis.cloud.shared.domain.DsgvoAuditLogEntryDto
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureRequestDto
import network.lapis.cloud.shared.domain.ErasureStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compliance UI wave -- covers only the pure, DOM-independent functions factored out of
 * `DsgvoRightsScreen.kt`: D4's three-step tracker state machine, the requested-by/decided/executed
 * caption builders (and the two documented deviations from the design review's literal wording --
 * see those functions' own KDoc), D6's `legalHold`-adjacent audit-actor display text, and the
 * `dsgvoExportUrl` builder -- same scope posture as [AuditLogScreenTest]/[DsgvoComplianceScreenTest]
 * (no DOM/rendering test harness exists in this module).
 */
class DsgvoRightsScreenTest {
    private fun request(
        status: ErasureStatus,
        requestedBy: String = "member-subject",
        subjectMemberId: String = "member-subject",
        decidedBy: String? = null,
        decidedAt: LocalDateTime? = null,
        decisionNote: String? = null,
        executedAt: LocalDateTime? = null,
    ): ErasureRequestDto =
        ErasureRequestDto(
            id = "request-1",
            subjectMemberId = subjectMemberId,
            subjectDisplayName = "Maria Muster",
            requestedAt = LocalDateTime(2026, 1, 1, 10, 0),
            requestedBy = requestedBy,
            reason = "Ich möchte nicht mehr Mitglied sein.",
            mode = ErasureMode.ANONYMIZE,
            status = status,
            decidedBy = decidedBy,
            decidedAt = decidedAt,
            decisionNote = decisionNote,
            executedAt = executedAt,
            legalHold = false,
            outcome = emptyList(),
        )

    // ---- D4: erasureStepStates -----------------------------------------------------------------

    @Test
    fun erasureStepStates_requested_onlyFirstStepIsCurrentRestAreFuture() {
        assertEquals(
            listOf(ErasureStepState.CURRENT, ErasureStepState.FUTURE, ErasureStepState.FUTURE),
            erasureStepStates(ErasureStatus.REQUESTED),
        )
    }

    @Test
    fun erasureStepStates_approved_firstIsPastSecondIsCurrentThirdIsFuture() {
        assertEquals(
            listOf(ErasureStepState.PAST, ErasureStepState.CURRENT, ErasureStepState.FUTURE),
            erasureStepStates(ErasureStatus.APPROVED),
        )
    }

    @Test
    fun erasureStepStates_rejected_terminalWithThirdStepStayingFuture() {
        assertEquals(
            listOf(ErasureStepState.PAST, ErasureStepState.CURRENT, ErasureStepState.FUTURE),
            erasureStepStates(ErasureStatus.REJECTED),
        )
    }

    @Test
    fun erasureStepStates_completed_allThreeReachedThirdIsCurrent() {
        assertEquals(
            listOf(ErasureStepState.PAST, ErasureStepState.PAST, ErasureStepState.CURRENT),
            erasureStepStates(ErasureStatus.COMPLETED),
        )
    }

    // ---- erasureStep2Label / erasureStep2Color ------------------------------------------------

    @Test
    fun erasureStep2Label_reflectsTheActualVerdictNotTheRawEnumName() {
        assertEquals("Entschieden", erasureStep2Label(ErasureStatus.REQUESTED))
        assertEquals("Genehmigt", erasureStep2Label(ErasureStatus.APPROVED))
        assertEquals("Genehmigt", erasureStep2Label(ErasureStatus.COMPLETED))
        assertEquals("Abgelehnt", erasureStep2Label(ErasureStatus.REJECTED))
    }

    @Test
    fun erasureStep2Color_mirrorsErasureStatusColorScale() {
        assertEquals(erasureStatusColor(ErasureStatus.APPROVED), erasureStep2Color(ErasureStatus.APPROVED))
        assertEquals(erasureStatusColor(ErasureStatus.APPROVED), erasureStep2Color(ErasureStatus.COMPLETED))
        assertEquals(erasureStatusColor(ErasureStatus.REJECTED), erasureStep2Color(ErasureStatus.REJECTED))
    }

    // ---- erasureRequestedByDisplayText (documented deviation from the design's literal wording) --

    @Test
    fun erasureRequestedByDisplayText_selfRequest_returnsMitgliedSelbst() {
        val r = request(ErasureStatus.REQUESTED, requestedBy = "member-1", subjectMemberId = "member-1")
        assertEquals("Mitglied selbst", erasureRequestedByDisplayText(r))
    }

    @Test
    fun erasureRequestedByDisplayText_adminOnBehalf_returnsRawRequesterId() {
        val r = request(ErasureStatus.REQUESTED, requestedBy = "admin-42", subjectMemberId = "member-1")
        assertEquals("admin-42", erasureRequestedByDisplayText(r))
    }

    // ---- erasureRequestedCaption / erasureDecidedCaption / erasureExecutedCaption --------------

    @Test
    fun erasureRequestedCaption_namesActorAndTimestamp() {
        val r = request(ErasureStatus.REQUESTED, requestedBy = "member-1", subjectMemberId = "member-1")
        val caption = erasureRequestedCaption(r)
        assertTrue(caption.contains("Mitglied selbst"))
        assertTrue(caption.contains("2026-01-01"))
    }

    @Test
    fun erasureDecidedCaption_nullWhenNotYetDecided() {
        val r = request(ErasureStatus.REQUESTED)
        assertNull(erasureDecidedCaption(r))
    }

    @Test
    fun erasureDecidedCaption_includesNoteWhenPresent() {
        val r =
            request(
                ErasureStatus.APPROVED,
                decidedBy = "admin-1",
                decidedAt = LocalDateTime(2026, 1, 2, 9, 0),
                decisionNote = "Identität geprüft.",
            )
        val caption = erasureDecidedCaption(r)
        assertEquals("Entschieden von admin-1 am 2026-01-02T09:00: Identität geprüft.", caption)
    }

    @Test
    fun erasureDecidedCaption_omitsNoteSuffixWhenNoteAbsent() {
        val r = request(ErasureStatus.APPROVED, decidedBy = "admin-1", decidedAt = LocalDateTime(2026, 1, 2, 9, 0))
        assertEquals("Entschieden von admin-1 am 2026-01-02T09:00", erasureDecidedCaption(r))
    }

    @Test
    fun erasureExecutedCaption_nullWhenNotYetExecuted() {
        val r = request(ErasureStatus.APPROVED)
        assertNull(erasureExecutedCaption(r))
    }

    @Test
    fun erasureExecutedCaption_usesAmNotVon_noExecutedByFieldExistsOnTheDto() {
        val r = request(ErasureStatus.COMPLETED, executedAt = LocalDateTime(2026, 1, 3, 12, 0))
        val caption = erasureExecutedCaption(r)
        assertTrue(caption != null && caption.startsWith("Ausgeführt am "))
    }

    // ---- dsgvoAuditActorDisplayText -------------------------------------------------------------

    @Test
    fun dsgvoAuditActorDisplayText_nullActor_readsAsSystemvorgang() {
        val entry = auditEntry(actorMemberId = null, actorRole = null)
        assertEquals("Systemvorgang (kein Akteur hinterlegt)", dsgvoAuditActorDisplayText(entry))
    }

    @Test
    fun dsgvoAuditActorDisplayText_realActor_showsRawIdAndRole() {
        val entry = auditEntry(actorMemberId = "admin-1", actorRole = AccountRole.ADMIN)
        assertEquals("admin-1 (ADMIN)", dsgvoAuditActorDisplayText(entry))
    }

    private fun auditEntry(
        actorMemberId: String?,
        actorRole: AccountRole?,
    ): DsgvoAuditLogEntryDto =
        DsgvoAuditLogEntryDto(
            id = "audit-1",
            occurredAt = LocalDateTime(2026, 1, 1, 10, 0),
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            action = DsgvoAuditAction.EXPORT,
            subjectMemberId = "member-1",
            requestId = null,
            outcome = emptyList(),
            legalBasis = "Art. 15/20 DSGVO",
        )

    // ---- dsgvoExportUrl ---------------------------------------------------------------------

    @Test
    fun dsgvoExportUrl_buildsTheExactHttpRoutePath() {
        assertEquals("/api/dsgvo/members/member-1/export", dsgvoExportUrl("member-1"))
    }

    // ---- ERASURE_SELF_STATUS_VISIBILITY_CAPTION ------------------------------------------------

    @Test
    fun selfStatusVisibilityCaption_namesTheSessionOnlyLimitation() {
        assertTrue(ERASURE_SELF_STATUS_VISIBILITY_CAPTION.contains("aktuelle Sitzung"))
        assertTrue(ERASURE_SELF_STATUS_VISIBILITY_CAPTION.contains("Administrat"))
    }
}
