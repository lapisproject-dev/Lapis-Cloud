package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MembershipAgreementDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- covers
 * [buildRegistrationInput]'s new `regionalChapterId` parameter. The pre-existing four fields
 * (trimmed name/email, un-trimmed password, agreement version/hash) are unit-tested implicitly by
 * every DOM test that submits this form; this file adds the one new, pure concern.
 */
class RegistrationScreenTest {
    private val agreement =
        MembershipAgreementDto(
            version = "3",
            text = "Beitrittsvertrag-Text",
            sha256 = "deadbeef",
        )

    @Test
    fun regionalChapterId_blankOrNull_becomesNull() {
        assertNull(
            buildRegistrationInput(
                displayName = "Erika Musterfrau",
                email = "erika@example.org",
                password = "correct horse battery staple",
                agreement = agreement,
                regionalChapterId = null,
            ).regionalChapterId,
        )
        assertNull(
            buildRegistrationInput(
                displayName = "Erika Musterfrau",
                email = "erika@example.org",
                password = "correct horse battery staple",
                agreement = agreement,
                regionalChapterId = "",
            ).regionalChapterId,
        )
        assertNull(
            buildRegistrationInput(
                displayName = "Erika Musterfrau",
                email = "erika@example.org",
                password = "correct horse battery staple",
                agreement = agreement,
                regionalChapterId = "   ",
            ).regionalChapterId,
        )
    }

    @Test
    fun regionalChapterId_aRealId_isPassedThroughUnchanged() {
        val input =
            buildRegistrationInput(
                displayName = "Erika Musterfrau",
                email = "erika@example.org",
                password = "correct horse battery staple",
                agreement = agreement,
                regionalChapterId = "c1",
            )
        assertEquals("c1", input.regionalChapterId)
    }

    @Test
    fun otherFields_remainTrimmedOrUntrimmed_exactlyAsBefore() {
        val input =
            buildRegistrationInput(
                displayName = "  Erika Musterfrau  ",
                email = "  erika@example.org  ",
                password = "  leading and trailing spaces kept  ",
                agreement = agreement,
                regionalChapterId = "c1",
            )
        assertEquals("Erika Musterfrau", input.displayName)
        assertEquals("erika@example.org", input.email)
        assertEquals("  leading and trailing spaces kept  ", input.password)
    }
}
