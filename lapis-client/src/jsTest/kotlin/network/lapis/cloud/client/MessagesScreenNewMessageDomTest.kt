package network.lapis.cloud.client

import io.kvision.html.div
import io.kvision.panel.vPanel
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DirectMessageBlockDto
import network.lapis.cloud.shared.domain.DirectMessageCursorDto
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessagePageDto
import network.lapis.cloud.shared.domain.DirectMessagePartnerDto
import network.lapis.cloud.shared.domain.DirectMessageRecipientDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class StubList(
    var partners: List<DirectMessagePartnerDto> = emptyList(),
) : DirectMessagePartnerListRpc {
    override suspend fun listConversationPartners(limit: Int) = partners
}

private class StubThread : DirectMessageConversationRpc {
    override suspend fun listConversationPage(
        otherMemberId: String,
        beforeSentAt: LocalDateTime?,
        beforeId: String?,
        limit: Int,
    ): DirectMessagePageDto {
        val m =
            DirectMessageDto(
                "msg-1",
                otherMemberId,
                "Ole Voss",
                "member-1",
                "Dana Keller",
                "Gesendet an $otherMemberId",
                LocalDateTime(2026, 10, 2, 9, 0),
                LocalDateTime(2026, 10, 2, 9, 1),
            )
        return DirectMessagePageDto(listOf(m), false, DirectMessageCursorDto(m.sentAt, m.id))
    }

    override suspend fun markConversationRead(otherMemberId: String) = 0
}

private class StubCompose(
    private val list: StubList,
) : DirectMessageComposeRpc {
    override suspend fun searchMessageRecipients(query: String) = listOf(DirectMessageRecipientDto("p-ole", "Ole Voss", false))

    override suspend fun listMessageGroups() = emptyList<network.lapis.cloud.shared.domain.DirectMessageGroupDto>()

    override suspend fun sendGroupMessage(
        committeeId: String,
        body: String,
    ) = error("no group send expected")

    override suspend fun sendWithAttachments(
        recipientId: String,
        body: String,
        attachmentIds: List<String>,
    ): DirectMessageDto {
        // the message now exists, so the conversation shows up in the list
        list.partners = listOf(DirectMessagePartnerDto(recipientId, "Ole Voss", LocalDateTime(2026, 10, 2, 9, 0), 0))
        return DirectMessageDto("new", "member-1", "Dana Keller", recipientId, "Ole Voss", body, LocalDateTime(2026, 10, 2, 9, 0), null)
    }
}

private class StubDiscoverability(
    var visible: Boolean = true,
    var failSet: Boolean = false,
    var blocks: List<DirectMessageBlockDto> = emptyList(),
) : DirectMessageDiscoverabilityRpc {
    val sets = mutableListOf<Boolean>()
    val unblocked = mutableListOf<String>()

    override suspend fun getVisibility() = visible

    override suspend fun setVisibility(visible: Boolean) {
        sets += visible
        if (failSet) throw ForbiddenException("x")
    }

    override suspend fun listBlocks() = blocks

    override suspend fun unblock(blockId: String) {
        unblocked += blockId
        blocks = blocks.filterNot { it.blockId == blockId }
    }
}

/** V1.9.237 -- the Nachrichten page around "Neue Nachricht": the header action, `?view=new`, arriving in the conversation, blocking, discoverability. */
class MessagesScreenNewMessageDomTest {
    @BeforeTest
    fun pinZone() {
        OrganizationTime.zoneId = "UTC"
    }

    @AfterTest
    fun reset() {
        UnreadMessages.resetForTest()
        OrganizationTime.zoneId = DEFAULT_ORGANIZATION_ZONE_ID
    }

    private fun session() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            organizationTimeZone = "UTC",
        )

    private inline fun withScreen(
        id: String,
        hash: String,
        list: StubList,
        discover: StubDiscoverability = StubDiscoverability(),
        noinline blockSender: suspend (String) -> Unit = {},
        crossinline block: suspend (() -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            UnreadMessages.rpc = UnreadCountRpc { 0 }
            mountedForm(id) { root, element ->
                root.vPanel {
                    renderMessagesScreen(
                        this,
                        hash,
                        MessagesScreenDependencies(
                            listRpc = list,
                            conversationRpc = StubThread(),
                            composeRpc = StubCompose(list),
                            discoverabilityRpc = discover,
                            blockSender = blockSender,
                        ),
                    )
                }
                block(element)
            }
        }

    @Test
    fun theHeaderOffersNeueNachricht_andItReplacesTheListArea_thenBackReturns(): Promise<Unit> =
        withScreen(
            "ms-toggle",
            "#/messages",
            StubList(listOf(DirectMessagePartnerDto("p1", "Eva Lind", LocalDateTime(2026, 10, 2, 9, 0), 0))),
        ) { el ->
            awaitUntil("list") { el().textContent.orEmpty().contains("Eva Lind") }
            el().buttonNamed("Neue Nachricht").click()
            awaitUntil("compose") { el().textContent.orEmpty().contains("An eine Person") }
            assertFalse(el().textContent.orEmpty().contains("Eva Lind"), "the list area is replaced")
            el().buttonNamed("Zur Liste").click()
            awaitUntil("list again") { el().textContent.orEmpty().contains("Eva Lind") }
        }

    @Test
    fun viewNewInTheLink_opensTheComposeViewDirectly(): Promise<Unit> =
        withScreen("ms-deeplink", "#/messages?view=new", StubList()) { el ->
            awaitUntil("compose") { el().textContent.orEmpty().contains("An eine Person") }
            assertFalse(el().textContent.orEmpty().contains("Noch keine Nachrichten."))
        }

    @Test
    fun afterSendingToAPerson_theListOpensWithThatConversation_andNoIdIsInTheAddress(): Promise<Unit> {
        val list = StubList()
        return withScreen("ms-arrive", "#/messages?view=new", list) { el ->
            awaitUntil("compose") { el().textContent.orEmpty().contains("An eine Person") }
            (el().controlOf("Name des Mitglieds") as HTMLInputElement).let {
                it.value = "Ole"
                it.dispatchEvent(Event("input"))
            }
            awaitUntil("hit") { el().allOf("button").any { b -> b.textContent.orEmpty().contains("Ole Voss") } }
            el().allOf("button").first { b -> b.textContent.orEmpty().contains("Ole Voss") }.click()
            awaitUntil("form") { el().allOf("textarea").isNotEmpty() }
            (el().controlOf("Nachricht") as HTMLTextAreaElement).let {
                it.value = "Hallo"
                it.dispatchEvent(Event("input"))
            }
            el().buttonNamed("Senden").click()
            awaitUntil("conversation open") { el().textContent.orEmpty().contains("Gesendet an p-ole") }
            assertFalse(
                kotlinx.browser.window.location.href
                    .contains("p-ole"),
                "no id in the address",
            )
            assertFalse(
                kotlinx.browser.window.location.href
                    .contains("view=new"),
                "the list is back",
            )
        }
    }

    @Test
    fun blocking_asksFirst_inTheOrderCancelThenBlock_andOnlyTheConfirmationCallsTheServer(): Promise<Unit> {
        val blocked = mutableListOf<String>()
        val list = StubList(listOf(DirectMessagePartnerDto("p1", "Eva Lind", LocalDateTime(2026, 10, 2, 9, 0), 0)))
        return withScreen("ms-block", "#/messages", list, blockSender = { blocked += it }) { el ->
            awaitUntil("row") { el().textContent.orEmpty().contains("Eva Lind") }
            el().buttonNamed("Eva Lind").click()
            awaitUntil("open") { el().textContent.orEmpty().contains("Gesendet an p1") }
            el().buttonNamed("Blockieren").click()
            assertTrue(blocked.isEmpty(), "a click on the text button only asks")
            val text = el().textContent.orEmpty()
            assertTrue(text.contains("Diese Person kann Ihnen danach nicht mehr schreiben."))
            val order = el().allOf("button").map { it.textContent?.trim() }.filter { it == "Abbrechen" || it == "Blockieren" }
            assertEquals(listOf("Abbrechen", "Blockieren"), order, "Abbrechen left, Blockieren right (R27)")
            el().buttonNamed("Abbrechen").click()
            assertTrue(blocked.isEmpty())
            el().buttonNamed("Blockieren").click()
            el().allOf("button").last { it.textContent?.trim() == "Blockieren" }.click()
            awaitUntil("blocked") { blocked == listOf("p1") }
        }
    }

    @Test
    fun theDiscoverabilityCheckbox_savesAtOnce_andRollsBackWhenTheServerRefuses(): Promise<Unit> {
        val discover = StubDiscoverability(visible = true, failSet = true)
        return withScreen("ms-visible", "#/messages", StubList(), discover) { el ->
            awaitUntil("section") { el().allOf("input[type=checkbox]").isNotEmpty() }
            assertTrue(el().textContent.orEmpty().contains("In der Empfängersuche für neue Nachrichten erscheinen"))

            fun box() = el().allOf("input[type=checkbox]").single() as HTMLInputElement
            awaitUntil("initially ticked") { box().checked }
            box().click()
            awaitUntil("saved once") { discover.sets == listOf(false) }
            awaitUntil("rolled back") { box().checked }
        }
    }

    @Test
    fun theBlockedList_namesOnlyKnownPeople_andLiftsABlock(): Promise<Unit> {
        val discover =
            StubDiscoverability(
                blocks =
                    listOf(
                        DirectMessageBlockDto("b1", "Ole Voss", LocalDateTime(2026, 10, 2, 9, 0)),
                        DirectMessageBlockDto("b2", "", LocalDateTime(2026, 10, 1, 9, 0)),
                    ),
            )
        return withScreen("ms-blocks", "#/messages", StubList(), discover) { el ->
            awaitUntil("blocks") {
                el().textContent.orEmpty().contains("Blockierte Personen") &&
                    el().textContent.orEmpty().contains("Ole Voss")
            }
            assertTrue(el().textContent.orEmpty().contains("Mitglied"), "an unknown person is a neutral label")
            val lifts = el().allOf("button").filter { it.textContent?.trim() == "Aufheben" }
            assertEquals(2, lifts.size)
            lifts.first().click()
            awaitUntil("lifted") { discover.unblocked == listOf("b1") }
        }
    }
}
