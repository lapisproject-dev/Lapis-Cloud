package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Governance UI wave -- covers only the pure, DOM-independent label/color functions
 * ([committeeTypeLabel]/[committeeTypeColor]/[committeeRoleLabel]/[committeeRoleColor]) factored
 * out of `CommitteesScreen.kt`, same scope posture as [ValidationTest]/[GuestBadgeTest] (no
 * DOM/rendering test harness exists in this module -- see those files' KDoc for why). Two things
 * asserted per enum: every value has a non-blank German label (completeness -- a missing `when`
 * branch would be a compile error, but an accidentally blank string would not), and every color
 * returned is one of Bootstrap 5.3.8's eight real semantic hues (matches [StatusBadge.kt]'s design
 * contract) and specifically never `"warning"` -- the UI/UX-Design-Team review reserves `warning`
 * exclusively for the Motions screen's amendment-ordering alert and `POSTPONED` statuses (see the
 * approved design decisions, D2), so a Committees-screen badge accidentally using it would violate
 * that cross-screen rule.
 */
class CommitteesScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "info", "dark")

    @Test
    fun committeeTypeLabel_isNonBlankForEveryValue() {
        CommitteeType.entries.forEach { type ->
            assertTrue(committeeTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun committeeTypeColor_isARealBootstrapHueAndNeverWarning() {
        CommitteeType.entries.forEach { type ->
            val color = committeeTypeColor(type)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $type, got \"$color\"")
        }
    }

    @Test
    fun committeeRoleLabel_isNonBlankForEveryValue() {
        CommitteeRole.entries.forEach { role ->
            assertTrue(committeeRoleLabel(role).isNotBlank(), "expected a non-blank label for $role")
        }
    }

    @Test
    fun committeeRoleColor_isARealBootstrapHueAndNeverWarning() {
        CommitteeRole.entries.forEach { role ->
            val color = committeeRoleColor(role)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $role, got \"$color\"")
        }
    }

    @Test
    fun committeeTypeLabel_executiveBoardIsVorstand() {
        assertEquals("Vorstand", committeeTypeLabel(CommitteeType.EXECUTIVE_BOARD))
    }

    @Test
    fun committeeRoleLabel_chairIsVorsitz() {
        assertEquals("Vorsitz", committeeRoleLabel(CommitteeRole.CHAIR))
    }
}
