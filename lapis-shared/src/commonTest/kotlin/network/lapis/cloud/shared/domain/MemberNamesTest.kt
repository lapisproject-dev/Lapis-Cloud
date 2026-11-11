package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class MemberNamesTest {
    @Test
    fun noTitleLeavesTheNameUntouched() {
        assertEquals("Max Muster", MemberNames.withTitle(title = null, displayName = "Max Muster"))
        assertEquals("Max Muster", MemberNames.withTitle(title = "", displayName = "Max Muster"))
        assertEquals("Max Muster", MemberNames.withTitle(title = "   ", displayName = "Max Muster"))
    }

    @Test
    fun titleIsPrepended() {
        assertEquals("Dr. Max Muster", MemberNames.withTitle(title = "Dr.", displayName = "Max Muster"))
        assertEquals("Prof. Dr. Max Muster", MemberNames.withTitle(title = " Prof. Dr. ", displayName = "Max Muster"))
    }

    @Test
    fun aNameThatAlreadyStartsWithTheTitleIsNotDoubled() {
        assertEquals("Dr. Max Muster", MemberNames.withTitle(title = "Dr.", displayName = "Dr. Max Muster"))
        assertEquals("dr. max muster", MemberNames.withTitle(title = "Dr.", displayName = "dr. max muster"))
        assertEquals("Dr.", MemberNames.withTitle(title = "Dr.", displayName = "Dr."))
        assertEquals("Ärztin Eva", MemberNames.withTitle(title = "ärztin", displayName = "Ärztin Eva"))
    }

    @Test
    fun aPrefixWithoutSpaceIsNotTheTitle() {
        assertEquals("Dr. Dr.Max", MemberNames.withTitle(title = "Dr.", displayName = "Dr.Max"))
    }

    @Test
    fun titlesAreNotSplit() {
        assertEquals("Prof. Dr. Dr. X", MemberNames.withTitle(title = "Prof. Dr.", displayName = "Dr. X"))
    }
}
