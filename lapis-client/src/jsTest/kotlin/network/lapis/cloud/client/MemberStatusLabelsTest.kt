package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V0.11.0 -- covers the pure, DOM-independent `memberStatusLabel`/`memberStatusColor` functions in
 * `MemberStatusLabels.kt`, same scope/assertion posture as [ComplianceLabelsTest] (no DOM/rendering
 * test harness exists in this module).
 */
class MemberStatusLabelsTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark", "light")

    @Test
    fun memberStatusLabel_isNonBlankForEveryValue() {
        MemberStatus.entries.forEach { status ->
            assertTrue(memberStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun memberStatusColor_isARealBootstrapHue() {
        MemberStatus.entries.forEach { status ->
            val color = memberStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun memberStatusLabel_friendIsFreund() {
        assertEquals("Freund", memberStatusLabel(MemberStatus.FRIEND))
    }

    @Test
    fun memberStatusLabel_everyValueHasADistinctLabel() {
        val labels = MemberStatus.entries.map { memberStatusLabel(it) }
        assertEquals(labels.size, labels.toSet().size, "expected every MemberStatus to have a distinct label")
    }

    /** FRIEND is a non-membership, unverified account -- it must not read as "success" (ACTIVE's own color), which would visually imply full, trusted membership standing. */
    @Test
    fun memberStatusColor_friendIsNotSuccess() {
        assertTrue(memberStatusColor(MemberStatus.FRIEND) != "success")
    }

    @Test
    fun memberStatusColor_activeIsSuccess() {
        assertEquals("success", memberStatusColor(MemberStatus.ACTIVE))
    }
}
