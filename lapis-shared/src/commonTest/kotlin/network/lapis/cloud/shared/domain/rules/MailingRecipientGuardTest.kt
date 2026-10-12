package network.lapis.cloud.shared.domain.rules

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailingRecipientGuardTest {
    private fun recipient(
        status: MemberStatus = MemberStatus.ACTIVE,
        anonymised: Boolean = false,
        deceased: Boolean = false,
        hasValidEmail: Boolean = true,
        optedOut: Boolean = false,
    ) = RecipientFacts(
        member =
            MemberFacts(
                status = status,
                roles = emptySet(),
                joinedOn = LocalDate(2020, 1, 1),
                activeCommitteeIds = emptySet(),
                tierId = null,
            ),
        anonymised = anonymised,
        deceased = deceased,
        hasValidEmail = hasValidEmail,
        optedOut = optedOut,
    )

    @Test
    fun anActiveMemberWithAnAddressIsEligible() {
        assertTrue(MailingRecipientGuard.eligible(f = recipient(), mandatory = false))
        assertTrue(MailingRecipientGuard.eligible(f = recipient(), mandatory = true))
    }

    @Test
    fun eachExclusionIsReported() {
        assertEquals(Exclusion.NOT_ACTIVE, MailingRecipientGuard.exclusionOf(f = recipient(status = MemberStatus.GUEST), mandatory = false))
        assertEquals(Exclusion.ANONYMISED, MailingRecipientGuard.exclusionOf(f = recipient(anonymised = true), mandatory = false))
        assertEquals(Exclusion.DECEASED, MailingRecipientGuard.exclusionOf(f = recipient(deceased = true), mandatory = false))
        assertEquals(Exclusion.NO_EMAIL, MailingRecipientGuard.exclusionOf(f = recipient(hasValidEmail = false), mandatory = false))
        assertEquals(Exclusion.OPTED_OUT, MailingRecipientGuard.exclusionOf(f = recipient(optedOut = true), mandatory = false))
    }

    @Test
    fun aMandatoryListIgnoresOnlyTheOptOut() {
        assertTrue(MailingRecipientGuard.eligible(f = recipient(optedOut = true), mandatory = true))
        assertFalse(MailingRecipientGuard.eligible(f = recipient(optedOut = true, anonymised = true), mandatory = true))
        assertFalse(MailingRecipientGuard.eligible(f = recipient(optedOut = true, deceased = true), mandatory = true))
        assertFalse(MailingRecipientGuard.eligible(f = recipient(optedOut = true, hasValidEmail = false), mandatory = true))
        assertFalse(MailingRecipientGuard.eligible(f = recipient(optedOut = true, status = MemberStatus.WITHDRAWN), mandatory = true))
    }
}
