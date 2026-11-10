package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.1.4 "LTR_ELIGIBLE/FRIEND-Erweiterung". Bislang existierte kein eigener Test für
 * [MemberStatusSets] (nur indirekt über `MembershipGuardsTest`). Dieser Test ist die billigste
 * denkbare Regressions-Wall: wenn eines der Sets künftig versehentlich verändert wird -- sei es
 * durch die aktuelle Welle oder eine spätere --, geht genau eine Zeile hier rot, statt dass die
 * Drift erst über einen fehlgeschlagenen Server-Test sichtbar wird.
 */
class MemberStatusSetsTest {
    @Test
    fun ltrEligible_isExactlyActiveFriendAndDonor() {
        // V1.9.298 (owner decision C, open question Q1 default): a donor may sign in and gets everything FRIEND has
        assertEquals(setOf(MemberStatus.ACTIVE, MemberStatus.FRIEND, MemberStatus.DONOR), MemberStatusSets.LTR_ELIGIBLE)
    }

    @Test
    fun organizationMember_remainsUnchanged() {
        assertEquals(setOf(MemberStatus.ACTIVE), MemberStatusSets.ORGANIZATION_MEMBER)
    }

    @Test
    fun nonMember_nowIncludesDonor_failClosed() {
        // V1.9.298: every "not a member -> restrict" gate automatically restricts a donor too
        assertEquals(setOf(MemberStatus.GUEST, MemberStatus.FRIEND, MemberStatus.DONOR), MemberStatusSets.NON_MEMBER)
    }

    /** V1.9.298: the former meaning of NON_MEMBER inside the conference/event-online/encounter files. A donor is deliberately not a guest of the room. */
    @Test
    fun conferenceGuest_isExactlyGuestAndFriend() {
        assertEquals(setOf(MemberStatus.GUEST, MemberStatus.FRIEND), MemberStatusSets.CONFERENCE_GUEST)
        assertFalse(MemberStatus.DONOR in MemberStatusSets.CONFERENCE_GUEST)
        assertEquals(setOf(MemberStatus.GUEST, MemberStatus.FRIEND, MemberStatus.DONOR), MemberStatusSets.CONSENT_REQUIRED)
    }

    @Test
    fun keycloakAutoLinkBlocked_isLoginBlockedPlusDonor() {
        assertEquals(MemberStatusSets.LOGIN_BLOCKED + MemberStatus.DONOR, MemberStatusSets.KEYCLOAK_AUTO_LINK_BLOCKED)
        assertTrue(MemberStatus.DONOR in MemberStatusSets.KEYCLOAK_AUTO_LINK_BLOCKED)
    }

    /** V1.9.298: the capabilities a donor gets (everything GUEST/FRIEND may do), and the ones it must never get. */
    @Test
    fun donor_capabilitiesAreExactlyTheFriendGuestUnion() {
        val granted =
            listOf(
                MemberStatusSets.CARPOOL_PASSENGER,
                MemberStatusSets.EVENT_SELF_REGISTRANT,
                MemberStatusSets.TENDER_PARTICIPANT,
                MemberStatusSets.CONFERENCE_ELIGIBLE,
                MemberStatusSets.LTR_ELIGIBLE,
                MemberStatusSets.POLITICIAN_RATER,
            )
        granted.forEach { assertTrue(MemberStatus.DONOR in it) }
        val never =
            listOf(
                MemberStatusSets.ORGANIZATION_MEMBER,
                MemberStatusSets.NOTICE_BOARD,
                MemberStatusSets.ACTIVISM_PARTICIPANT,
                MemberStatusSets.RULE_COMMITTEE,
                MemberStatusSets.CUSTOM_BACKGROUND_UPLOAD_ELIGIBLE,
                MemberStatusSets.MEMBER_PHOTO_ELIGIBLE,
                MemberStatusSets.MEMBER_MAP_ELIGIBLE,
                MemberStatusSets.CONFERENCE_GUEST,
            )
        never.forEach { assertFalse(MemberStatus.DONOR in it) }
        assertFalse(MemberStatus.DONOR in MemberStatusSets.LOGIN_BLOCKED)
    }

    @Test
    fun conferenceEligible_remainsUnchanged() {
        assertEquals(
            setOf(MemberStatus.ACTIVE, MemberStatus.GUEST, MemberStatus.FRIEND, MemberStatus.DONOR),
            MemberStatusSets.CONFERENCE_ELIGIBLE,
        )
    }

    @Test
    fun politicianRater_friendNotIncluded_donorIs() {
        // V1.9.298 (open question Q2 default): a donor counts as a GAST rater; a donor account is always created by an administrator
        assertEquals(setOf(MemberStatus.ACTIVE, MemberStatus.GUEST, MemberStatus.DONOR), MemberStatusSets.POLITICIAN_RATER)
        assertFalse(MemberStatus.FRIEND in MemberStatusSets.POLITICIAN_RATER)
    }

    /** Welle V1.9.4 -- deliberately narrower than [MemberStatusSets.CONFERENCE_ELIGIBLE], see that set's own KDoc. */
    @Test
    fun customBackgroundUploadEligible_isExactlyOrganizationMember() {
        assertEquals(setOf(MemberStatus.ACTIVE), MemberStatusSets.CUSTOM_BACKGROUND_UPLOAD_ELIGIBLE)
        assertFalse(MemberStatus.FRIEND in MemberStatusSets.CUSTOM_BACKGROUND_UPLOAD_ELIGIBLE)
        assertFalse(MemberStatus.GUEST in MemberStatusSets.CUSTOM_BACKGROUND_UPLOAD_ELIGIBLE)
    }

    /** Welle V1.9.19 -- only a full organization member may upload/publish a profile photo. */
    @Test
    fun memberPhotoEligible_isExactlyOrganizationMember() {
        assertEquals(setOf(MemberStatus.ACTIVE), MemberStatusSets.MEMBER_PHOTO_ELIGIBLE)
        assertFalse(MemberStatus.FRIEND in MemberStatusSets.MEMBER_PHOTO_ELIGIBLE)
        assertFalse(MemberStatus.GUEST in MemberStatusSets.MEMBER_PHOTO_ELIGIBLE)
        assertFalse(MemberStatus.APPLICATION in MemberStatusSets.MEMBER_PHOTO_ELIGIBLE)
    }

    /**
     * V1.9.298 supersedes the V1.2.11 block below for DONOR: a donor may sign in now (owner decision). DECEASED stays blocked.
     */
    @Test
    fun loginBlocked_v1_9_298_donorIsNoLongerBlocked() {
        assertEquals(setOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED), MemberStatusSets.LOGIN_BLOCKED)
        assertFalse(MemberStatus.DONOR in MemberStatusSets.LOGIN_BLOCKED)
    }

    /**
     * V1.2.11 (PdV-CSV-Import): widened from two to four elements -- DECEASED (terminal) and DONOR
     * (no account row is ever created for one, see `MemberCsvImport` KDoc) join WITHDRAWN/REJECTED.
     * This is a deliberate, documented change to the previous "remains unchanged" wall, not a drift.
     */
    @Test
    fun loginBlocked_includesDeceased() {
        assertTrue(MemberStatus.DECEASED in MemberStatusSets.LOGIN_BLOCKED)
    }

    /**
     * The wave's own most important test: neither newly-added status confers ANY of the five
     * capability-set memberships elsewhere in this object, only the deny-listed [MemberStatusSets
     * .LOGIN_BLOCKED] above. Every gate in the server is an allowlist test against one of these sets
     * (see [MemberStatusSets] class KDoc), so this single assertion pins that DONOR/DECEASED are
     * structurally rightless everywhere except the explicit login block.
     */
    @Test
    fun deceased_isInNoCapabilitySetExceptLoginBlocked() {
        // V1.9.298: the DONOR half of this wall moved to donor_capabilitiesAreExactlyTheFriendGuestUnion
        MemberStatus.DECEASED.let { status ->
            assertFalse(status in MemberStatusSets.ORGANIZATION_MEMBER)
            assertFalse(status in MemberStatusSets.NON_MEMBER)
            assertFalse(status in MemberStatusSets.CONFERENCE_ELIGIBLE)
            assertFalse(status in MemberStatusSets.LTR_ELIGIBLE)
            assertFalse(status in MemberStatusSets.POLITICIAN_RATER)
            assertTrue(status in MemberStatusSets.LOGIN_BLOCKED)
        }
    }

    @Test
    fun guest_isNotLtrEligible() {
        assertFalse(MemberStatus.GUEST in MemberStatusSets.LTR_ELIGIBLE)
    }

    @Test
    fun application_isNotLtrEligible() {
        assertFalse(MemberStatus.APPLICATION in MemberStatusSets.LTR_ELIGIBLE)
    }

    @Test
    fun withdrawnAndRejected_areNotLtrEligible() {
        assertFalse(MemberStatus.WITHDRAWN in MemberStatusSets.LTR_ELIGIBLE)
        assertFalse(MemberStatus.REJECTED in MemberStatusSets.LTR_ELIGIBLE)
    }

    @Test
    fun activeAndFriend_areLtrEligible() {
        assertTrue(MemberStatus.ACTIVE in MemberStatusSets.LTR_ELIGIBLE)
        assertTrue(MemberStatus.FRIEND in MemberStatusSets.LTR_ELIGIBLE)
    }

    /**
     * Security finding fix (feature/v1.2.11-member-csv-import, LOW/latent): DECEASED is a third
     * terminal status, alongside WITHDRAWN/REJECTED, and must join this set so
     * `SepaBatchPoller.runPhaseB`'s defense-in-depth mandate revocation covers it too -- see
     * [MemberStatusSets.MEMBERSHIP_ENDED]'s own KDoc.
     */
    @Test
    fun membershipEnded_isExactlyWithdrawnRejectedDeceased() {
        assertEquals(
            setOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED),
            MemberStatusSets.MEMBERSHIP_ENDED,
        )
    }

    @Test
    fun membershipEnded_excludesDonor() {
        assertFalse(MemberStatus.DONOR in MemberStatusSets.MEMBERSHIP_ENDED)
    }

    @Test
    fun anniversaryEligible_isExactlyActiveAndDonor() {
        assertEquals(setOf(MemberStatus.ACTIVE, MemberStatus.DONOR), MemberStatusSets.ANNIVERSARY_ELIGIBLE)
    }

    @Test
    fun anniversaryEligible_excludesFriendGuestAndTerminalStatuses() {
        setOf(
            MemberStatus.FRIEND,
            MemberStatus.GUEST,
            MemberStatus.APPLICATION,
            MemberStatus.WITHDRAWN,
            MemberStatus.REJECTED,
            MemberStatus.DECEASED,
        ).forEach {
            assertFalse(it in MemberStatusSets.ANNIVERSARY_ELIGIBLE)
        }
    }

    // ── Welle V1.9.102 "Gremien, Welle B": which status changes end committee seats ──

    @Test
    fun committeeSeatEnding_isExactlyWithdrawnRejectedDeceasedDonor() {
        assertEquals(
            setOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED, MemberStatus.DONOR),
            MemberStatusSets.COMMITTEE_SEAT_ENDING,
        )
    }

    @Test
    fun committeeSeatKeeping_isExactlyActiveApplicationFriendGuest() {
        assertEquals(
            setOf(MemberStatus.ACTIVE, MemberStatus.APPLICATION, MemberStatus.FRIEND, MemberStatus.GUEST),
            MemberStatusSets.COMMITTEE_SEAT_KEEPING,
        )
    }

    /** Every status is classified exactly once: a new MemberStatus literal fails here until somebody decides what it does to seats. */
    @Test
    fun committeeSeatSets_partitionAllStatuses() {
        val ending = MemberStatusSets.COMMITTEE_SEAT_ENDING
        val keeping = MemberStatusSets.COMMITTEE_SEAT_KEEPING
        assertTrue((ending intersect keeping).isEmpty())
        assertEquals(MemberStatus.entries.toSet(), ending union keeping)
        MemberStatus.entries.forEach { status ->
            // an exhaustive when: adding a literal without extending it does not compile
            val endsSeats =
                when (status) {
                    MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED, MemberStatus.DONOR -> true
                    MemberStatus.ACTIVE, MemberStatus.APPLICATION, MemberStatus.FRIEND, MemberStatus.GUEST -> false
                }
            assertEquals(endsSeats, status in ending, "$status")
            assertEquals(!endsSeats, status in keeping, "$status")
        }
    }

    /** DONOR ends seats but is NOT a membership end: that set also revokes SEPA mandates, and a donor keeps a running mandate. */
    @Test
    fun donor_endsSeats_butIsNotInMembershipEnded() {
        assertTrue(MemberStatus.DONOR in MemberStatusSets.COMMITTEE_SEAT_ENDING)
        assertFalse(MemberStatus.DONOR in MemberStatusSets.MEMBERSHIP_ENDED)
        assertTrue(MemberStatusSets.MEMBERSHIP_ENDED.all { it in MemberStatusSets.COMMITTEE_SEAT_ENDING })
    }
}
