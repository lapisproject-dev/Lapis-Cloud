package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Welle V1.9.34 -- the quorum badge is never green, and the plain-language sentence is always there. */
class MeetingQuorumExplanationDomTest {
    @Test
    fun quorumBadgeColor_isCalmWhenReached_andWarningWhenNot_neverSuccess() {
        assertEquals("secondary", quorumBadgeColor(true))
        assertEquals("warning", quorumBadgeColor(false))
        assertNotEquals("success", quorumBadgeColor(true))
        assertNotEquals("danger", quorumBadgeColor(false))
    }

    @Test
    fun theExplanation_isRendered_smallAndMuted_andClaimsNoLegalAssessment(): Promise<Unit> =
        formTest {
            mountedForm("quorum-explanation") { root, element ->
                root.vPanel { quorumExplanation() }
                val line = element().allOf("div").last { it.textContent.orEmpty().startsWith("Quorum heißt:") }
                assertTrue(line.className.contains("text-muted"))
                assertTrue(line.className.contains("small"))
                val text = line.textContent.orEmpty()
                assertTrue(text.contains("keine rechtliche Bewertung"))
                assertTrue(text.contains("Beschlussergebnis"))
                assertTrue(element().allOf("button").isEmpty(), "no button next to the sentence")
            }
        }
}
