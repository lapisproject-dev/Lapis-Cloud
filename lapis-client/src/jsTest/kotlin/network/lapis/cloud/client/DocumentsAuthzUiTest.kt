package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle "Treasurer Document Upload" -- review finding fix. Drift guard for [DocumentsAuthzUi],
 * the pure client-side mirror of `network.lapis.cloud.server.security.ESCALATED_ROLES`, the role
 * set `DocumentService.createFolder`/`createDocument`/`deleteDocument` and the document upload
 * route all check server-side. Same posture as [DatevAuthzUiTest].
 */
class DocumentsAuthzUiTest {
    @Test
    fun canManage_boardTreasurerAdminAllowed_memberAndNullDenied() {
        assertTrue(DocumentsAuthzUi.canManage(AccountRole.BOARD))
        assertTrue(DocumentsAuthzUi.canManage(AccountRole.TREASURER))
        assertTrue(DocumentsAuthzUi.canManage(AccountRole.ADMIN))
        assertFalse(DocumentsAuthzUi.canManage(AccountRole.MEMBER))
        assertFalse(DocumentsAuthzUi.canManage(null))
    }

    /**
     * Runde-4 review finding fix: before [DocumentsAuthzUi.allowedCreateLevels] existed, the
     * dropdown offered `DocumentAccessLevel.entries` unconditionally, so a TREASURER or BOARD
     * member could pick `ADMIN_ONLY` and create a document only an ADMIN could ever see, fill or
     * remove again. Pins that only ADMIN may pick `ADMIN_ONLY`, mirroring the server's
     * `canAccessDocumentAtLevel` role branch exactly.
     */
    @Test
    fun allowedCreateLevels_boardAndTreasurer_excludeAdminOnly() {
        assertEquals(
            listOf(DocumentAccessLevel.PUBLIC_MEMBERS, DocumentAccessLevel.BOARD_ONLY),
            DocumentsAuthzUi.allowedCreateLevels(AccountRole.BOARD),
        )
        assertEquals(
            listOf(DocumentAccessLevel.PUBLIC_MEMBERS, DocumentAccessLevel.BOARD_ONLY),
            DocumentsAuthzUi.allowedCreateLevels(AccountRole.TREASURER),
        )
    }

    @Test
    fun allowedCreateLevels_admin_includesAllLevels() {
        assertEquals(DocumentAccessLevel.entries.toList(), DocumentsAuthzUi.allowedCreateLevels(AccountRole.ADMIN))
    }

    @Test
    fun allowedCreateLevels_memberAndNull_onlyPublicMembers() {
        assertEquals(listOf(DocumentAccessLevel.PUBLIC_MEMBERS), DocumentsAuthzUi.allowedCreateLevels(AccountRole.MEMBER))
        assertEquals(listOf(DocumentAccessLevel.PUBLIC_MEMBERS), DocumentsAuthzUi.allowedCreateLevels(null))
    }
}
