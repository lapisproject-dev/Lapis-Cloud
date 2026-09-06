package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.4.5.2 "DATEV-Format-Export" -- covers [DatevAuthzUi], the pure client-side mirror of
 * `AccountingService.previewDatevExport`'s ACCOUNTING_READ_ROLES and `DatevRoutes
 * .DATEV_FILE_DOWNLOAD_ROLES`. Same "drift guard" posture as [SepaAuthzUiTest]'s own
 * `canReadSepa_boardIsAllowed` -- [boardCanPreviewButNeverDownload] below is the test that exists
 * specifically to catch [DatevAuthzUi.PREVIEW_ROLES] and [DatevAuthzUi.FILE_DOWNLOAD_ROLES] ever
 * being silently merged into one constant.
 */
class DatevAuthzUiTest {
    @Test
    fun canPreview_treasurerBoardAdminAllowed_memberAndNullDenied() {
        assertTrue(DatevAuthzUi.canPreview(AccountRole.TREASURER))
        assertTrue(DatevAuthzUi.canPreview(AccountRole.BOARD))
        assertTrue(DatevAuthzUi.canPreview(AccountRole.ADMIN))
        assertFalse(DatevAuthzUi.canPreview(AccountRole.MEMBER))
        assertFalse(DatevAuthzUi.canPreview(null))
    }

    @Test
    fun canDownload_boardIsDenied_treasurerAndAdminAreAllowed() {
        assertFalse(DatevAuthzUi.canDownload(AccountRole.BOARD))
        assertTrue(DatevAuthzUi.canDownload(AccountRole.TREASURER))
        assertTrue(DatevAuthzUi.canDownload(AccountRole.ADMIN))
        assertFalse(DatevAuthzUi.canDownload(AccountRole.MEMBER))
        assertFalse(DatevAuthzUi.canDownload(null))
    }

    @Test
    fun canDownloadNow_requiresBothTheRoleAndAnExportablePeriod() {
        assertTrue(DatevAuthzUi.canDownloadNow(AccountRole.TREASURER, exportable = true))
        assertFalse(DatevAuthzUi.canDownloadNow(AccountRole.TREASURER, exportable = false))
        assertFalse(DatevAuthzUi.canDownloadNow(AccountRole.BOARD, exportable = true))
    }

    /** Gegenprobe: BOARD darf die Vorschau sehen, aber niemals herunterladen -- beweist, dass
     * [DatevAuthzUi.PREVIEW_ROLES] und [DatevAuthzUi.FILE_DOWNLOAD_ROLES] nicht versehentlich
     * verschmolzen wurden. */
    @Test
    fun boardCanPreviewButNeverDownload() {
        assertTrue(DatevAuthzUi.canPreview(AccountRole.BOARD))
        assertFalse(DatevAuthzUi.canDownload(AccountRole.BOARD))
    }
}
