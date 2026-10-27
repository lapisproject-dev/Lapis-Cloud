package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.LinkedEventRef
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.229 -- the Sitzung page block: an officer who may publish gets the button and no "the board publishes" sentence. */
class MeetingEventLinkUiDomTest {
    private val at = LocalDateTime(2099, 11, 1, 18, 0)

    private fun meeting(linked: LinkedEventRef?) =
        MeetingDto(
            id = "m-1",
            committeeId = "c-1",
            committeeName = "Vorstand",
            title = "Sitzung",
            scheduledAt = at,
            location = "Vereinsheim",
            format = MeetingFormat.IN_PERSON,
            status = MeetingStatus.PLANNED,
            calledById = null,
            calledByDisplayName = null,
            calledAt = null,
            chairMemberId = null,
            chairDisplayName = null,
            minuteTakerMemberId = null,
            minuteTakerDisplayName = null,
            protocolDocumentId = null,
            createdAt = at,
            linkedEvent = linked,
        )

    private fun ref(canPublish: Boolean) =
        LinkedEventRef(
            eventId = "e-1",
            title = "Vorstand: Sitzung am 01.11.2099",
            status = EventStatus.DRAFT,
            startsAt = at,
            endsAt = at,
            locationText = "Vereinsheim",
            onlineKind = EventOnlineKind.NONE,
            canOpen = true,
            difference = null,
            canPublish = canPublish,
        )

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun session() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    @Test
    fun aDraftTheCallerMayPublish_showsThePublishButton_andNoBoardOnlySentence(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            mountedForm("meeting-link-can-publish") { root, element ->
                renderMeetingEventLink(root, meeting(ref(canPublish = true)), canManage = true, onChanged = {})
                val text = element().text()
                assertTrue(text.contains("Entwurf, noch nicht veröffentlicht."))
                assertFalse(text.contains("Veröffentlichen kann der Vorstand"))
                assertEquals(1, element().allOf("button").count { it.text().trim() == "Veröffentlichen" })
            }
        }

    @Test
    fun aDraftTheCallerMayNotPublish_showsNoPublishButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            mountedForm("meeting-link-cannot-publish") { root, element ->
                renderMeetingEventLink(root, meeting(ref(canPublish = false)), canManage = true, onChanged = {})
                assertEquals(0, element().allOf("button").count { it.text().trim() == "Veröffentlichen" })
            }
        }
}
