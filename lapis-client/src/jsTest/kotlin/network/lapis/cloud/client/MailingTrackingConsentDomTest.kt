package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IDirectMessageService
import network.lapis.cloud.shared.rpc.IMailingService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Welle V1.9.15 Teil B/C -- the two opt-in switches under a subscribed list. */
class MailingTrackingConsentDomTest {
    private val stamp = LocalDateTime(2026, 9, 30, 10, 0)

    private fun list(
        id: String = "l1",
        subscribed: Boolean = true,
        open: LocalDateTime? = null,
        click: LocalDateTime? = null,
    ) = MailingListDto(
        id = id,
        name = "Liste $id",
        description = null,
        createdBy = "m0",
        subscriberCount = 3,
        isSubscribedByCurrentMember = subscribed,
        currentMemberOpenTrackingConsentedAt = open,
        currentMemberClickTrackingConsentedAt = click,
    )

    private fun HTMLElement.checkboxes(): List<HTMLInputElement> = allOf("input[type=checkbox]").map { it as HTMLInputElement }

    @Test
    fun bothSwitchesStartOff_whenNoConsentExists(): Promise<Unit> =
        formTest {
            mountedForm("mtc-default") { root, element ->
                val changes = mutableListOf<Pair<Boolean, Boolean>>()
                renderTrackingConsentSwitches(root, list()) { open, click -> changes += open to click }
                val boxes = element().checkboxes()
                assertEquals(2, boxes.size)
                assertEquals(listOf(false, false), boxes.map { it.checked })
                assertTrue(changes.isEmpty(), "rendering itself must not report a change")
            }
        }

    @Test
    fun aStoredTimestamp_rendersTheSwitchChecked(): Promise<Unit> =
        formTest {
            mountedForm("mtc-checked") { root, element ->
                renderTrackingConsentSwitches(root, list(open = stamp, click = null)) { _, _ -> }
                assertEquals(listOf(true, false), element().checkboxes().map { it.checked })
            }
        }

    @Test
    fun switches_areBootstrapSwitches_withLabelsAndHints(): Promise<Unit> =
        formTest {
            mountedForm("mtc-look") { root, element ->
                renderTrackingConsentSwitches(root, list()) { _, _ -> }
                assertEquals(2, element().allOf(".form-switch").size, "form-switch on both checkbox widgets")
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Öffnungen zählen"))
                assertTrue(text.contains("Klicks zählen"))
                assertTrue(text.contains("jederzeit widerrufbar"))
                assertFalse(text.contains("###"), "no tr() marker on screen")
            }
        }

    @Test
    fun togglingASwitch_reportsBothValues(): Promise<Unit> =
        formTest {
            mountedForm("mtc-toggle") { root, element ->
                val changes = mutableListOf<Pair<Boolean, Boolean>>()
                renderTrackingConsentSwitches(root, list(open = stamp)) { open, click -> changes += open to click }
                element().checkboxes()[1].click()
                assertEquals(listOf(true to true), changes)
                element().checkboxes()[0].click()
                assertEquals(listOf(true to true, false to true), changes)
            }
        }

    private fun session() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Testperson",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2026, 12, 1, 12, 0),
        )

    @Test
    fun screen_rendersSwitchesOnlyForSubscribedLists_andSavesThroughTheRpc(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val listsRoute = routeOf { rpcService<IMailingService>().listMailingLists() }
            val inboxRoute = routeOf { rpcService<IDirectMessageService>().listInbox() }
            val consentRoute = routeOf { rpcService<IMailingService>().setTrackingConsent("x", false, false) }
            var saved = false
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listsRoute ->
                            request.answerWith(
                                jsonOf(
                                    ListSerializer(MailingListDto.serializer()),
                                    listOf(
                                        list(id = "sub", subscribed = true, open = if (saved) stamp else null),
                                        list(id = "nosub", subscribed = false),
                                    ),
                                ),
                            )
                        request.rpcRoute == inboxRoute ->
                            request.answerWith(
                                jsonOf(ListSerializer(DirectMessageDto.serializer()), emptyList()),
                            )
                        request.rpcRoute == consentRoute -> {
                            saved = true
                            request.answerWith("null")
                        }
                        else -> request.answerWith("null")
                    }
                },
            ) { calls ->
                mountedForm("mtc-screen") { root, element ->
                    renderCommunicationScreen(root)
                    awaitUntil("lists rendered") { element().textContent.orEmpty().contains("Liste sub") }
                    assertEquals(2, element().checkboxes().size, "switches only under the subscribed list (two switches, one list)")
                    element().checkboxes()[0].click()
                    awaitUntil("the consent RPC was sent") { calls.count { it.isRpc && it.rpcRoute == consentRoute } == 1 }
                    val request = calls.single { it.isRpc && it.rpcRoute == consentRoute }
                    assertEquals("sub", JSON.parse<String>(request.json.params[0] as String))
                    assertEquals(true, JSON.parse<Boolean>(request.json.params[1] as String), "open")
                    assertEquals(false, JSON.parse<Boolean>(request.json.params[2] as String), "click")
                    awaitUntil("the list re-rendered from the server state") { calls.count { it.isRpc && it.rpcRoute == listsRoute } >= 2 }
                    awaitUntil("switch reflects the stored consent") { element().checkboxes().firstOrNull()?.checked == true }
                    assertNotNull(element().textContent)
                }
            }
        }
}
