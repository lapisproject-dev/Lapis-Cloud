package network.lapis.cloud.client.encounter

import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceMode
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.62 -- the DOM-free decisions of the room view: labels, who may be moderated, the refresh planner. */
class EncounterViewStateTest {
    private fun space(myRole: EncounterSpaceRole?) =
        EncounterSpaceDto(
            id = "s1",
            title = "Sonntag",
            description = "",
            theme = EncounterTheme.CHURCH,
            mode = EncounterSpaceMode.SERVICE,
            guestPolicy = EncounterGuestPolicy.MEMBERS_ONLY,
            closedNotice = null,
            open = true,
            openedAt = null,
            presentCount = 0,
            maxParticipants = 100,
            pulpitDisplayNames = emptyList(),
            myRole = myRole,
            canModerate = myRole != null,
            archived = false,
        )

    private fun person(
        id: String,
        role: EncounterPresenceRole,
        isGuest: Boolean = false,
    ) = EncounterPresentDto(memberId = id, displayName = "Name $id", role = role, isGuest = isGuest)

    private fun viewer(
        role: EncounterPresenceRole,
        privileged: Boolean = false,
    ) = EncounterViewerRights(
        presenceRole = role,
        canPublish = role != EncounterPresenceRole.CONGREGATION,
        canPublishData = true,
        selfIdentity = "me",
        isPrivileged = privileged,
    )

    @Test
    fun theEntryButton_namesTheOfficeTheOfficeHolderEntersWith() {
        assertEquals("Eintreten", encounterEnterLabel(space(null)))
        assertEquals("Eintreten und Kanzel übernehmen", encounterEnterLabel(space(EncounterSpaceRole.PULPIT)))
        assertEquals("Eintreten als Ordner", encounterEnterLabel(space(EncounterSpaceRole.STEWARD)))
    }

    @Test
    fun theCongregation_hasNoModerationMenu_atAll() {
        val congregation = viewer(EncounterPresenceRole.CONGREGATION)
        EncounterPresenceRole.entries.forEach { role ->
            assertFalse(encounterCanActOn(congregation, person("x", role)), role.name)
        }
    }

    @Test
    fun noOne_canModerateThemselves() {
        EncounterPresenceRole.entries.forEach { role ->
            assertFalse(encounterCanActOn(viewer(role, privileged = true), person("me", EncounterPresenceRole.CONGREGATION)), role.name)
        }
    }

    @Test
    fun anOfficeHolder_actsAgainstTheCongregationOnly_notAgainstOtherOffices() {
        listOf(EncounterPresenceRole.STEWARD, EncounterPresenceRole.PULPIT).forEach { own ->
            val holder = viewer(own)
            assertTrue(encounterCanActOn(holder, person("x", EncounterPresenceRole.CONGREGATION)), "$own -> congregation")
            assertFalse(encounterCanActOn(holder, person("x", EncounterPresenceRole.STEWARD)), "$own -> steward")
            assertFalse(encounterCanActOn(holder, person("x", EncounterPresenceRole.PULPIT)), "$own -> pulpit")
        }
    }

    @Test
    fun boardAndAdmin_withoutAnOffice_mayModerateEveryoneElse() {
        val board = viewer(EncounterPresenceRole.CONGREGATION, privileged = true)
        assertTrue(board.canModerate)
        EncounterPresenceRole.entries.forEach { role -> assertTrue(encounterCanActOn(board, person("x", role)), role.name) }
    }

    @Test
    fun theGuestMarker_andTheRole_areShownOnlyWhereTheyAreMeant() {
        assertEquals(
            "",
            encounterPersonFacts(
                person("a", EncounterPresenceRole.CONGREGATION),
                showGuestMarker = true,
                profile = EncounterProfile.CHURCH_SERVICE,
            ),
        )
        assertEquals(
            "Gast",
            encounterPersonFacts(
                person("a", EncounterPresenceRole.CONGREGATION, isGuest = true),
                showGuestMarker = true,
                profile = EncounterProfile.CHURCH_SERVICE,
            ),
        )
        assertEquals(
            "",
            encounterPersonFacts(
                person("a", EncounterPresenceRole.CONGREGATION, isGuest = true),
                showGuestMarker = false,
                profile = EncounterProfile.CHURCH_SERVICE,
            ),
        )
        assertEquals(
            "Kanzel",
            encounterPersonFacts(
                person("a", EncounterPresenceRole.PULPIT),
                showGuestMarker = false,
                profile = EncounterProfile.CHURCH_SERVICE,
            ),
        )
        assertEquals(
            "Ordner im Gottesdienst · Gast",
            encounterPersonFacts(
                person("a", EncounterPresenceRole.STEWARD, isGuest = true),
                showGuestMarker = true,
                profile = EncounterProfile.CHURCH_SERVICE,
            ),
        )
    }

    @Test
    fun theStewardLabel_isNeverTheBareWordOrdner() {
        // The bare msgid "Ordner" already means "folder" (documents) in every catalog.
        EncounterPresenceRole.entries.forEach { role ->
            assertTrue(
                encounterPresenceRoleLabel(role, EncounterProfile.CHURCH_SERVICE) != "Ordner",
                role.name,
            )
        }
    }

    // ── the refresh planner ──────────────────────────────────────────────────────

    @Test
    fun aRosterStorm_isMergedIntoOneScheduledRefresh_andRunsAreFiveSecondsApart() {
        var clock = 100_000.0
        val planner = EncounterRefreshPlanner({ clock })
        assertEquals(0.0, planner.request(), "the first request may run at once")
        assertNull(planner.request(), "a second request while one is scheduled is merged")
        assertNull(planner.request())
        planner.started()
        clock += 1_000
        assertEquals(4_000.0, planner.request(), "the next run waits until five seconds after the last start")
        planner.started()
        clock += 20_000
        assertEquals(0.0, planner.request())
    }
}
