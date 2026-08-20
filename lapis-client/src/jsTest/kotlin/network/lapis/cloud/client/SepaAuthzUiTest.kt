package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaMandateStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.2.2 SEPA-Client-UI wave -- covers [SepaAuthzUi], the pure client-side mirror of
 * `SepaService`/`SepaRoutes`'s three role tiers (Plan §1 verified backend-contract matrix). Same
 * DOM-free unit-test posture as [GovernanceAuthzUiTest]/[ValidationTest].
 */
class SepaAuthzUiTest {
    private val today = LocalDate(2026, 8, 20)
    private val yesterday = LocalDate(2026, 8, 19)
    private val tomorrow = LocalDate(2026, 8, 21)

    // ── canDownloadBatchFile -- this test is the reason SepaAuthzUi.FILE_DOWNLOAD_ROLES is its own
    // constant rather than a reuse of TREASURY_ROLES (Security Round 1, MAJOR-1). ──────────────────

    @Test
    fun canDownloadBatchFile_boardIsAlwaysDenied_forEveryBatchStatus() {
        SepaDebitBatchStatus.entries.forEach { status ->
            assertFalse(
                SepaAuthzUi.canDownloadBatchFile(AccountRole.BOARD, status, generatedDocumentId = "doc-1"),
                "expected BOARD to be denied for status $status",
            )
        }
    }

    @Test
    fun canDownloadBatchFile_memberIsDenied() {
        assertFalse(SepaAuthzUi.canDownloadBatchFile(AccountRole.MEMBER, SepaDebitBatchStatus.GENERATED, "doc-1"))
    }

    @Test
    fun canDownloadBatchFile_nullRoleIsDenied() {
        assertFalse(SepaAuthzUi.canDownloadBatchFile(null, SepaDebitBatchStatus.GENERATED, "doc-1"))
    }

    @Test
    fun canDownloadBatchFile_cancelledIsDeniedForTreasurerAndAdmin() {
        assertFalse(SepaAuthzUi.canDownloadBatchFile(AccountRole.TREASURER, SepaDebitBatchStatus.CANCELLED, "doc-1"))
        assertFalse(SepaAuthzUi.canDownloadBatchFile(AccountRole.ADMIN, SepaDebitBatchStatus.CANCELLED, "doc-1"))
    }

    @Test
    fun canDownloadBatchFile_missingGeneratedDocumentIdIsDenied() {
        assertFalse(SepaAuthzUi.canDownloadBatchFile(AccountRole.TREASURER, SepaDebitBatchStatus.GENERATED, generatedDocumentId = null))
    }

    @Test
    fun canDownloadBatchFile_treasurerWithGeneratedStatusAndDocumentIdIsAllowed() {
        assertTrue(SepaAuthzUi.canDownloadBatchFile(AccountRole.TREASURER, SepaDebitBatchStatus.GENERATED, "doc-1"))
    }

    // ── canGrantOnBehalf / canTreasuryAct / canRecordReturn ─────────────────────────────────────

    @Test
    fun canGrantOnBehalf_boardIsDenied_treasurerAndAdminAreAllowed_memberIsDenied() {
        assertFalse(SepaAuthzUi.canGrantOnBehalf(AccountRole.BOARD))
        assertTrue(SepaAuthzUi.canGrantOnBehalf(AccountRole.TREASURER))
        assertTrue(SepaAuthzUi.canGrantOnBehalf(AccountRole.ADMIN))
        assertFalse(SepaAuthzUi.canGrantOnBehalf(AccountRole.MEMBER))
    }

    @Test
    fun canTreasuryAct_boardIsDenied_treasurerAndAdminAreAllowed() {
        assertFalse(SepaAuthzUi.canTreasuryAct(AccountRole.BOARD))
        assertTrue(SepaAuthzUi.canTreasuryAct(AccountRole.TREASURER))
        assertTrue(SepaAuthzUi.canTreasuryAct(AccountRole.ADMIN))
    }

    @Test
    fun canRecordReturn_boardIsDenied_treasurerAndAdminAreAllowed() {
        assertFalse(SepaAuthzUi.canRecordReturn(AccountRole.BOARD))
        assertTrue(SepaAuthzUi.canRecordReturn(AccountRole.TREASURER))
        assertTrue(SepaAuthzUi.canRecordReturn(AccountRole.ADMIN))
    }

    /** Gegenprobe zu [canDownloadBatchFile_boardIsAlwaysDenied_forEveryBatchStatus]: beweist, dass
     * [SepaAuthzUi.READ_ROLES] und [SepaAuthzUi.FILE_DOWNLOAD_ROLES] nicht versehentlich
     * verschmolzen wurden -- BOARD darf lesen, aber nicht herunterladen. */
    @Test
    fun canReadSepa_boardIsAllowed() {
        assertTrue(SepaAuthzUi.canReadSepa(AccountRole.BOARD))
        assertTrue(SepaAuthzUi.canReadSepa(AccountRole.TREASURER))
        assertTrue(SepaAuthzUi.canReadSepa(AccountRole.ADMIN))
        assertFalse(SepaAuthzUi.canReadSepa(AccountRole.MEMBER))
        assertFalse(SepaAuthzUi.canReadSepa(null))
    }

    // ── nextBatchAction ──────────────────────────────────────────────────────────────────────────

    @Test
    fun nextBatchAction_boardIsAlwaysNull() {
        SepaDebitBatchStatus.entries.forEach { status ->
            listOf(null, today, yesterday, tomorrow).forEach { threshold ->
                listOf(true, false).forEach { hasSettleable ->
                    assertEquals(
                        null,
                        SepaAuthzUi.nextBatchAction(AccountRole.BOARD, status, today, threshold, hasSettleable),
                        "expected null for BOARD, status=$status, threshold=$threshold, settleable=$hasSettleable",
                    )
                }
            }
        }
    }

    @Test
    fun nextBatchAction_draftIsAlwaysNotify() {
        assertEquals(
            SepaBatchAction.NOTIFY,
            SepaAuthzUi.nextBatchAction(AccountRole.TREASURER, SepaDebitBatchStatus.DRAFT, today, null, hasSettleableItems = false),
        )
    }

    // Review Round 2 (2026-08-20, MAJOR): renamed from `..ThresholdOnOrBeforeTodayIsGenerateFile` --
    // the comparison no longer involves `today` at all, only the batch's own (fixed, once NOTIFIED)
    // `requestedCollectionDate` against `fileGenerationAllowedFrom`. See `SepaAuthzUi.nextBatchAction`
    // KDoc for the full rationale.
    @Test
    fun nextBatchAction_notifiedWithCollectionDateOnOrAfterAllowedFromIsGenerateFile() {
        assertEquals(
            SepaBatchAction.GENERATE_FILE,
            SepaAuthzUi.nextBatchAction(AccountRole.ADMIN, SepaDebitBatchStatus.NOTIFIED, today, today, hasSettleableItems = false),
        )
        assertEquals(
            SepaBatchAction.GENERATE_FILE,
            SepaAuthzUi.nextBatchAction(AccountRole.ADMIN, SepaDebitBatchStatus.NOTIFIED, today, yesterday, hasSettleableItems = false),
        )
    }

    /**
     * Regression for the OLD (wrong) `fileGenerationAllowedFrom <= today` check: a batch whose
     * `requestedCollectionDate` is far in the future but already satisfies the notice period used to
     * stay `GENERATE_FILE_TOO_EARLY` for weeks, purely because `today` had not yet caught up --
     * mirrors the review finding's "Fehlerszenario A" (sepaPrenotificationDays=14, notified today,
     * requestedCollectionDate 09-30, allowedFrom 09-03).
     */
    @Test
    fun nextBatchAction_notifiedIsGenerateFileAsSoonAsCollectionDateSatisfiesTheThreshold_regardlessOfToday() {
        assertEquals(
            SepaBatchAction.GENERATE_FILE,
            SepaAuthzUi.nextBatchAction(
                AccountRole.ADMIN,
                SepaDebitBatchStatus.NOTIFIED,
                LocalDate(2026, 9, 30),
                LocalDate(2026, 9, 3),
                hasSettleableItems = false,
            ),
        )
    }

    @Test
    fun nextBatchAction_notifiedWithCollectionDateBeforeAllowedFromOrNullIsGenerateFileTooEarly() {
        assertEquals(
            SepaBatchAction.GENERATE_FILE_TOO_EARLY,
            SepaAuthzUi.nextBatchAction(
                AccountRole.TREASURER,
                SepaDebitBatchStatus.NOTIFIED,
                yesterday,
                tomorrow,
                hasSettleableItems = false,
            ),
        )
        assertEquals(
            SepaBatchAction.GENERATE_FILE_TOO_EARLY,
            SepaAuthzUi.nextBatchAction(AccountRole.TREASURER, SepaDebitBatchStatus.NOTIFIED, today, null, hasSettleableItems = false),
        )
    }

    /**
     * Regression for the OLD (wrong) `fileGenerationAllowedFrom <= today` check: a batch whose
     * `requestedCollectionDate` never satisfies the notice period used to FLIP to `GENERATE_FILE`
     * once `today` itself passed `allowedFrom`, even though the server rejects it permanently --
     * mirrors the review finding's "Fehlerszenario B" (notified 2026-08-20, requiredNoticeDays=14,
     * requestedCollectionDate 2026-08-28 -> allowedFrom 2026-09-03).
     */
    @Test
    fun nextBatchAction_notifiedStaysTooEarlyForeverWhenCollectionDateNeverSatisfiesTheThreshold() {
        assertEquals(
            SepaBatchAction.GENERATE_FILE_TOO_EARLY,
            SepaAuthzUi.nextBatchAction(
                AccountRole.TREASURER,
                SepaDebitBatchStatus.NOTIFIED,
                LocalDate(2026, 8, 28),
                LocalDate(2026, 9, 3),
                hasSettleableItems = false,
            ),
        )
    }

    @Test
    fun nextBatchAction_generatedIsAlwaysMarkSubmitted() {
        assertEquals(
            SepaBatchAction.MARK_SUBMITTED,
            SepaAuthzUi.nextBatchAction(AccountRole.TREASURER, SepaDebitBatchStatus.GENERATED, today, null, hasSettleableItems = false),
        )
    }

    @Test
    fun nextBatchAction_submittedWithSettleableItemsIsSettle() {
        assertEquals(
            SepaBatchAction.SETTLE,
            SepaAuthzUi.nextBatchAction(AccountRole.ADMIN, SepaDebitBatchStatus.SUBMITTED, today, null, hasSettleableItems = true),
        )
    }

    @Test
    fun nextBatchAction_submittedWithoutSettleableItemsIsNull() {
        assertEquals(
            null,
            SepaAuthzUi.nextBatchAction(AccountRole.ADMIN, SepaDebitBatchStatus.SUBMITTED, today, null, hasSettleableItems = false),
        )
    }

    @Test
    fun nextBatchAction_settledAndCancelledAreAlwaysNull() {
        assertEquals(
            null,
            SepaAuthzUi.nextBatchAction(AccountRole.ADMIN, SepaDebitBatchStatus.SETTLED, today, today, hasSettleableItems = true),
        )
        assertEquals(
            null,
            SepaAuthzUi.nextBatchAction(AccountRole.ADMIN, SepaDebitBatchStatus.CANCELLED, today, today, hasSettleableItems = true),
        )
    }

    // ── canRevokeMandateOf ───────────────────────────────────────────────────────────────────────

    @Test
    fun canRevokeMandateOf_ownActiveMandateAsMemberIsAllowed() {
        assertTrue(SepaAuthzUi.canRevokeMandateOf(AccountRole.MEMBER, ownMandate = true, status = SepaMandateStatus.ACTIVE))
    }

    @Test
    fun canRevokeMandateOf_foreignActiveMandateAsMemberIsDenied() {
        assertFalse(SepaAuthzUi.canRevokeMandateOf(AccountRole.MEMBER, ownMandate = false, status = SepaMandateStatus.ACTIVE))
    }

    @Test
    fun canRevokeMandateOf_foreignActiveMandateAsTreasurerIsAllowed() {
        assertTrue(SepaAuthzUi.canRevokeMandateOf(AccountRole.TREASURER, ownMandate = false, status = SepaMandateStatus.ACTIVE))
    }

    @Test
    fun canRevokeMandateOf_nonActiveStatusIsAlwaysDenied() {
        listOf(SepaMandateStatus.REVOKED, SepaMandateStatus.EXPIRED).forEach { status ->
            assertFalse(SepaAuthzUi.canRevokeMandateOf(AccountRole.MEMBER, ownMandate = true, status = status))
            assertFalse(SepaAuthzUi.canRevokeMandateOf(AccountRole.ADMIN, ownMandate = false, status = status))
        }
    }
}
