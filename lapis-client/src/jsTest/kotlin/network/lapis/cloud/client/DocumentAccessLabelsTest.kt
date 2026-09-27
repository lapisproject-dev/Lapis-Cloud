package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.DocumentAccessLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/** Welle V1.9.1 -- pure, DOM-independent coverage of [DocumentAccessLabels.kt]'s label/color tables. */
class DocumentAccessLabelsTest {
    @Test
    fun label_allThreeLevels_areDistinctAndNonBlank() {
        val labels = DocumentAccessLevel.entries.map { documentAccessLevelLabel(it) }
        assertEquals(3, labels.toSet().size, "each level must have its own, distinct label")
        labels.forEach { assertEquals(true, it.isNotBlank()) }
    }

    @Test
    fun color_publicIsNeutral_boardAndAdminEscalate() {
        assertEquals("secondary", documentAccessLevelColor(DocumentAccessLevel.PUBLIC_MEMBERS))
        assertEquals("warning", documentAccessLevelColor(DocumentAccessLevel.BOARD_ONLY))
        assertEquals("danger", documentAccessLevelColor(DocumentAccessLevel.ADMIN_ONLY))
    }
}
