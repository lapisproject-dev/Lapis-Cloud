package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the pure, DOM-independent functions in `DocumentsScreen.kt` -- same scope posture as
 * [FileDisplayTest] (no rendering harness exists in this module).
 */
class DocumentsScreenTest {
    private fun document(title: String) =
        DocumentDto(
            id = "doc-$title",
            folderId = "folder-1",
            title = title,
            currentVersionId = null,
            createdBy = "acct-1",
            createdByDisplayName = "Anna Muster",
            createdAt = LocalDateTime(2026, 1, 1, 10, 0),
            accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
            isDeleted = false,
        )

    // --- filterDocuments ----------------------------------------------------------------------

    @Test
    fun filterDocuments_emptyQuery_returnsAllUnfiltered() {
        val documents = listOf(document("Satzung"), document("Protokoll"))
        assertEquals(documents, filterDocuments(documents, ""))
    }

    @Test
    fun filterDocuments_whitespaceOnlyQuery_returnsAllUnfiltered() {
        val documents = listOf(document("Satzung"), document("Protokoll"))
        assertEquals(documents, filterDocuments(documents, "   "))
    }

    @Test
    fun filterDocuments_caseInsensitiveMatch() {
        val documents = listOf(document("Satzung"), document("Protokoll"))
        assertEquals(listOf(documents[0]), filterDocuments(documents, "SATZUNG"))
    }

    @Test
    fun filterDocuments_substringInMiddleOfTitle_matches() {
        val documents = listOf(document("Prüfungsordnung 2026"), document("Protokoll"))
        assertEquals(listOf(documents[0]), filterDocuments(documents, "sordnung"))
    }

    @Test
    fun filterDocuments_umlautInTitle_matches() {
        val documents = listOf(document("Prüfungsordnung"), document("Satzung"))
        assertEquals(listOf(documents[0]), filterDocuments(documents, "rüf"))
    }

    @Test
    fun filterDocuments_noMatch_returnsEmptyList() {
        val documents = listOf(document("Satzung"), document("Protokoll"))
        assertTrue(filterDocuments(documents, "Beitragsordnung").isEmpty())
    }

    @Test
    fun filterDocuments_emptyDocumentList_returnsEmptyRegardlessOfQuery() {
        assertTrue(filterDocuments(emptyList(), "Satzung").isEmpty())
        assertTrue(filterDocuments(emptyList(), "").isEmpty())
    }

    // --- shouldShowDocumentSearch --------------------------------------------------------------

    @Test
    fun shouldShowDocumentSearch_belowThreshold_isFalse() {
        assertFalse(shouldShowDocumentSearch(4))
    }

    @Test
    fun shouldShowDocumentSearch_atThreshold_isTrue() {
        assertTrue(shouldShowDocumentSearch(5))
    }
}
