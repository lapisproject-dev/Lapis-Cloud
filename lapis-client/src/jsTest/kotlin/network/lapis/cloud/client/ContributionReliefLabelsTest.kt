package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefReason
import network.lapis.cloud.shared.domain.ContributionReliefRequestDto
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.ContributionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Welle V1.4.10.1 "Beitragsvergünstigungen: Bedienoberfläche" -- covers every pure, DOM-independent
 * function in `ContributionReliefLabels.kt`, same scope posture as [SocialModerationScreenTest]/
 * [DsgvoRightsScreenTest] (no rendering harness exists in this module).
 */
class ContributionReliefLabelsTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark", "light")

    private fun sampleRequest(
        kind: ContributionReliefKind = ContributionReliefKind.DEFERRAL,
        status: ContributionReliefStatus = ContributionReliefStatus.REQUESTED,
        deferralNewDueDate: LocalDate? = LocalDate(2026, 6, 15),
        deferralPreviousDueDate: LocalDate? = null,
        exemptionFrom: LocalDate? = null,
        exemptionUntil: LocalDate? = null,
        reductionTargetTierName: String? = null,
    ): ContributionReliefRequestDto =
        ContributionReliefRequestDto(
            id = "req-1",
            subjectMemberId = "member-1",
            subjectDisplayName = "Test Mitglied",
            kind = kind,
            status = status,
            reasonCategory = ContributionReliefReason.FINANCIAL_HARDSHIP,
            reasonText = null,
            deferralContributionId = if (kind == ContributionReliefKind.DEFERRAL) "contribution-1" else null,
            deferralNewDueDate = deferralNewDueDate,
            deferralPreviousDueDate = deferralPreviousDueDate,
            exemptionFrom = exemptionFrom,
            exemptionUntil = exemptionUntil,
            reductionTargetTierId = if (kind == ContributionReliefKind.REDUCTION) "tier-1" else null,
            reductionTargetTierName = reductionTargetTierName,
            requestedAt = LocalDateTime(2026, 1, 1, 10, 0),
            requestedBy = "member-1",
            requestedByDisplayName = "Test Mitglied",
        )

    // ---- reliefKindLabel / reliefKindColor -------------------------------------------------------

    @Test
    fun reliefKindLabel_isNonBlankForEveryValue() {
        ContributionReliefKind.entries.forEach { kind ->
            assertTrue(reliefKindLabel(kind).isNotBlank(), "expected a non-blank label for $kind")
        }
    }

    @Test
    fun reliefKindLabel_isPairwiseDistinct() {
        val labels = ContributionReliefKind.entries.map { reliefKindLabel(it) }
        assertEquals(labels.size, labels.toSet().size, "expected pairwise-distinct kind labels, got $labels")
    }

    @Test
    fun reliefKindColor_isARealBootstrapHueForEveryValue() {
        ContributionReliefKind.entries.forEach { kind ->
            val color = reliefKindColor(kind)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $kind, got \"$color\"")
        }
    }

    // ---- reliefStatusLabel / reliefStatusColor ---------------------------------------------------

    @Test
    fun reliefStatusLabel_isNonBlankForEveryValue() {
        ContributionReliefStatus.entries.forEach { status ->
            assertTrue(reliefStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun reliefStatusLabel_neverSaysGenehmigt() {
        // Jobs' Ruling (Plan Abschnitt "reliefStatusLabel"): "Genehmigt" kommt nirgends vor, auch
        // nicht fuer APPROVED -- ein APPROVED-Antrag hat laut Zustandsautomat immer einen
        // executionError (DB-CHECK), ist also nie ein reiner Erfolgszustand.
        ContributionReliefStatus.entries.forEach { status ->
            assertFalse(reliefStatusLabel(status).contains("Genehmigt"), "status label for $status must never say \"Genehmigt\"")
        }
    }

    @Test
    fun reliefStatusColor_isARealBootstrapHueForEveryValue() {
        ContributionReliefStatus.entries.forEach { status ->
            val color = reliefStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    // ---- reliefReasonLabel ------------------------------------------------------------------------

    @Test
    fun reliefReasonLabel_isNonBlankForEveryValue() {
        ContributionReliefReason.entries.forEach { reason ->
            assertTrue(reliefReasonLabel(reason).isNotBlank(), "expected a non-blank label for $reason")
        }
    }

    @Test
    fun reliefReasonLabel_isPairwiseDistinct() {
        val labels = ContributionReliefReason.entries.map { reliefReasonLabel(it) }
        assertEquals(labels.size, labels.toSet().size, "expected pairwise-distinct reason labels, got $labels")
    }

    // ---- reliefStepStates -------------------------------------------------------------------------

    @Test
    fun reliefStepStates_requested_onlyFirstStepIsCurrentRestAreFuture() {
        assertEquals(
            listOf(ReliefStepState.CURRENT, ReliefStepState.FUTURE, ReliefStepState.FUTURE),
            reliefStepStates(ContributionReliefStatus.REQUESTED),
        )
    }

    @Test
    fun reliefStepStates_approved_firstIsPastSecondIsCurrentThirdIsFuture() {
        // F1: APPROVED ist ein Zwischenzustand (retryable), keine Endstation -- Pille 3 bleibt FUTURE.
        assertEquals(
            listOf(ReliefStepState.PAST, ReliefStepState.CURRENT, ReliefStepState.FUTURE),
            reliefStepStates(ContributionReliefStatus.APPROVED),
        )
    }

    @Test
    fun reliefStepStates_rejected_terminalWithThirdStepStayingFuture() {
        assertEquals(
            listOf(ReliefStepState.PAST, ReliefStepState.CURRENT, ReliefStepState.FUTURE),
            reliefStepStates(ContributionReliefStatus.REJECTED),
        )
    }

    @Test
    fun reliefStepStates_withdrawn_terminalWithThirdStepStayingFuture() {
        assertEquals(
            listOf(ReliefStepState.PAST, ReliefStepState.CURRENT, ReliefStepState.FUTURE),
            reliefStepStates(ContributionReliefStatus.WITHDRAWN),
        )
    }

    @Test
    fun reliefStepStates_executed_allThreeReachedThirdIsCurrent() {
        assertEquals(
            listOf(ReliefStepState.PAST, ReliefStepState.PAST, ReliefStepState.CURRENT),
            reliefStepStates(ContributionReliefStatus.EXECUTED),
        )
    }

    @Test
    fun reliefStepStates_hasExactlyThreeStepsForEveryStatus() {
        ContributionReliefStatus.entries.forEach { status ->
            assertEquals(3, reliefStepStates(status).size, "expected exactly 3 steps for $status")
        }
    }

    // ---- reliefStep2Label / reliefStep2Color -------------------------------------------------------

    @Test
    fun reliefStep2Label_executed_isNeutralNeverGenehmigt() {
        assertFalse(reliefStep2Label(ContributionReliefStatus.EXECUTED).contains("Genehmigt"))
    }

    @Test
    fun reliefStep2Color_isARealBootstrapHueForEveryValue() {
        ContributionReliefStatus.entries.forEach { status ->
            val color = reliefStep2Color(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    // ---- reliefEffectDescription (F5) ---------------------------------------------------------------

    @Test
    fun reliefEffectDescription_deferral_beforeExecution_showsOnlyNewDueDate() {
        // F5: deferralPreviousDueDate is null before EXECUTED -- must not crash, must not show "null".
        val request = sampleRequest(deferralNewDueDate = LocalDate(2026, 6, 15), deferralPreviousDueDate = null)
        val description = reliefEffectDescription(request, tierAmountLabel = null)
        assertTrue(description.contains("2026-06-15"), "expected the new due date in \"$description\"")
        assertFalse(description.contains("→"), "must not show the before/after arrow before EXECUTED, got \"$description\"")
    }

    @Test
    fun reliefEffectDescription_deferral_afterExecution_showsFullBeforeAfterLine() {
        val request =
            sampleRequest(
                status = ContributionReliefStatus.EXECUTED,
                deferralNewDueDate = LocalDate(2026, 6, 15),
                deferralPreviousDueDate = LocalDate(2026, 3, 15),
            )
        val description = reliefEffectDescription(request, tierAmountLabel = null)
        assertTrue(description.contains("2026-03-15"), "expected the previous due date in \"$description\"")
        assertTrue(description.contains("2026-06-15"), "expected the new due date in \"$description\"")
    }

    @Test
    fun reliefEffectDescription_exemption_openEnded_saysUnbefristet() {
        val request = sampleRequest(kind = ContributionReliefKind.EXEMPTION, exemptionFrom = LocalDate(2026, 1, 1), exemptionUntil = null)
        val description = reliefEffectDescription(request, tierAmountLabel = null)
        assertTrue(description.contains("2026-01-01"))
        assertTrue(description.contains("unbefristet"))
    }

    @Test
    fun reliefEffectDescription_exemption_bounded_showsBothDates() {
        val request =
            sampleRequest(
                kind = ContributionReliefKind.EXEMPTION,
                exemptionFrom = LocalDate(2026, 1, 1),
                exemptionUntil = LocalDate(2026, 12, 31),
            )
        val description = reliefEffectDescription(request, tierAmountLabel = null)
        assertTrue(description.contains("2026-01-01"))
        assertTrue(description.contains("2026-12-31"))
    }

    @Test
    fun reliefEffectDescription_reduction_usesTierAmountLabelWhenProvided() {
        val request = sampleRequest(kind = ContributionReliefKind.REDUCTION, reductionTargetTierName = "Fallback")
        val description = reliefEffectDescription(request, tierAmountLabel = "Ermäßigt (5,00 € / MONTHLY)")
        assertTrue(description.contains("Ermäßigt (5,00 € / MONTHLY)"))
    }

    @Test
    fun reliefEffectDescription_reduction_fallsBackToTierNameWhenLabelMissing() {
        val request = sampleRequest(kind = ContributionReliefKind.REDUCTION, reductionTargetTierName = "Ermäßigt")
        val description = reliefEffectDescription(request, tierAmountLabel = null)
        assertTrue(description.contains("Ermäßigt"))
    }

    // ---- reliefExecutionErrorMessage / parseReliefExecutionErrorCode (F4) --------------------------

    @Test
    fun reliefExecutionErrorMessage_allElevenCodes_produceNonBlankMessagesWithoutTheRawCode() {
        val codes =
            listOf(
                "contribution_not_found",
                "contribution_not_deferrable:OPEN",
                "new_due_date_not_in_future",
                "member_not_found",
                "member_anonymized",
                "membership_ended",
                "target_tier_not_found",
                "target_tier_not_active",
                "member_has_no_tier_of_their_own",
                "target_tier_already_current",
                "target_tier_not_cheaper_than_current",
            )
        codes.forEach { raw ->
            val message = reliefExecutionErrorMessage(raw)
            assertTrue(message.isNotBlank(), "expected a non-blank message for \"$raw\"")
            assertFalse(
                message.contains(raw.substringBefore(':')),
                "message for \"$raw\" must never contain the raw wire code: \"$message\"",
            )
        }
    }

    @Test
    fun reliefExecutionErrorMessage_contributionNotDeferrable_withParsableStatus_mentionsTranslatedStatus() {
        val message = reliefExecutionErrorMessage("contribution_not_deferrable:PAID")
        assertTrue(message.contains(contributionStatusLabel(ContributionStatus.PAID)))
    }

    @Test
    fun reliefExecutionErrorMessage_contributionNotDeferrable_withUnparsableSuffix_omitsStatusButStaysMeaningful() {
        val message = reliefExecutionErrorMessage("contribution_not_deferrable:GARBAGE")
        assertTrue(message.isNotBlank())
        assertFalse(message.contains("GARBAGE"), "must never surface the raw unparsable suffix: \"$message\"")
    }

    @Test
    fun reliefExecutionErrorMessage_unknownCode_returnsGenericFallback() {
        val message = reliefExecutionErrorMessage("some_future_code_this_client_does_not_know")
        assertTrue(message.isNotBlank())
        assertFalse(message.contains("some_future_code_this_client_does_not_know"))
    }

    @Test
    fun reliefExecutionErrorMessage_nullRaw_returnsGenericFallback() {
        assertTrue(reliefExecutionErrorMessage(null).isNotBlank())
    }

    @Test
    fun parseReliefExecutionErrorCode_unknownCode_returnsNull() {
        assertNull(parseReliefExecutionErrorCode("this_is_not_a_real_code"))
    }

    @Test
    fun parseReliefExecutionErrorCode_splitsOnlyAtFirstColon() {
        assertEquals(
            ContributionReliefExecutionErrorCode.CONTRIBUTION_NOT_DEFERRABLE,
            parseReliefExecutionErrorCode("contribution_not_deferrable:OPEN"),
        )
    }
}
