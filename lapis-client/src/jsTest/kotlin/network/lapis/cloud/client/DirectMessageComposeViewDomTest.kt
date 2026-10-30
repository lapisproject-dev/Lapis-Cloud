package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessageGroupDto
import network.lapis.cloud.shared.domain.DirectMessageGroupSendResultDto
import network.lapis.cloud.shared.domain.DirectMessageRecipientDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.DirectMessageGroupTooLargeException
import network.lapis.cloud.shared.rpc.DirectMessageGroupUnavailableException
import network.lapis.cloud.shared.rpc.DirectMessageRateLimitedException
import network.lapis.cloud.shared.rpc.DirectMessageRecipientUnavailableException
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeComposeRpc(
    var hits: List<DirectMessageRecipientDto> = emptyList(),
    var groups: List<DirectMessageGroupDto> = emptyList(),
) : DirectMessageComposeRpc {
    val searches = mutableListOf<String>()
    val groupLoads = mutableListOf<Unit>()
    val groupSends = mutableListOf<Pair<String, String>>()
    val personSends = mutableListOf<Triple<String, String, List<String>>>()

    override suspend fun searchMessageRecipients(query: String): List<DirectMessageRecipientDto> {
        searches += query
        return hits
    }

    override suspend fun listMessageGroups(): List<DirectMessageGroupDto> {
        groupLoads += Unit
        return groups
    }

    override suspend fun sendGroupMessage(
        committeeId: String,
        body: String,
    ): DirectMessageGroupSendResultDto {
        groupSends += committeeId to body
        return DirectMessageGroupSendResultDto(
            committeeName =
                groups
                    .first {
                        it.committeeId == committeeId
                    }.name,
            sentAt = LocalDateTime(2026, 10, 2, 9, 0),
        )
    }

    override suspend fun sendWithAttachments(
        recipientId: String,
        body: String,
        attachmentIds: List<String>,
    ): DirectMessageDto {
        personSends += Triple(recipientId, body, attachmentIds)
        return DirectMessageDto("m1", "me", "Dana Keller", recipientId, "Ole Voss", body, LocalDateTime(2026, 10, 2, 9, 0), null)
    }
}

/** V1.9.237 "Neue Nachricht" -- the compose view: segment control, name search (no call below 3 characters), chip, group list, texts. */
class DirectMessageComposeViewDomTest {
    @AfterTest
    fun reset() {
        UnreadMessages.resetForTest()
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

    private inline fun withView(
        id: String,
        rpc: FakeComposeRpc,
        crossinline onPerson: (String) -> Unit = {},
        crossinline onGroup: (String) -> Unit = {},
        crossinline onBack: () -> Unit = {},
        crossinline block: suspend (() -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            UnreadMessages.rpc = UnreadCountRpc { 0 }
            mountedForm(id) { root, element ->
                root.vPanel {
                    directMessageComposeView(
                        rpc = rpc,
                        onSentToPerson = { onPerson(it) },
                        onSentToGroup = { onGroup(it) },
                        onBack = { onBack() },
                    )
                }
                block(element)
            }
        }

    private fun HTMLElement.search(text: String) {
        val input = controlOf("Name des Mitglieds") as HTMLInputElement
        input.value = text
        input.dispatchEvent(Event("input"))
    }

    private val ole = DirectMessageRecipientDto("p-ole", "Ole Voss", hasConversation = true)
    private val eva = DirectMessageRecipientDto("p-eva", "Eva Lind", hasConversation = false)

    @Test
    fun theSegmentControl_isARadiogroup_withThePersonSegmentChecked(): Promise<Unit> =
        withView("cv-segment", FakeComposeRpc()) { el ->
            val group = assertNotNull(el().allOf("[role=radiogroup]").firstOrNull())
            val radios = group.allOf("[role=radio]")
            assertEquals(listOf("An eine Person", "An eine Gruppe"), radios.map { it.textContent?.trim() })
            assertEquals(listOf("true", "false"), radios.map { it.getAttribute("aria-checked") })
            assertTrue(el().textContent.orEmpty().contains("Mindestens 3 Zeichen"))
        }

    @Test
    fun theBackButton_namesTheGoal_andReturns(): Promise<Unit> {
        var back = 0
        return withView("cv-back", FakeComposeRpc(), onBack = { back++ }) { el ->
            assertFalse(el().textContent.orEmpty().contains("Zurück"), "never a bare 'Zurück'")
            el().buttonNamed("Zur Liste").click()
            assertEquals(1, back)
        }
    }

    @Test
    fun belowThreeCharacters_noSearchRuns(): Promise<Unit> {
        val rpc = FakeComposeRpc(hits = listOf(ole))
        return withView("cv-short", rpc) { el ->
            el().search("Ol")
            delay((RECIPIENT_SEARCH_DEBOUNCE_MS + 250).toLong())
            assertTrue(rpc.searches.isEmpty(), "two characters make no call")
            el().search("  Ol  ")
            delay((RECIPIENT_SEARCH_DEBOUNCE_MS + 250).toLong())
            assertTrue(rpc.searches.isEmpty(), "the trimmed length counts")
        }
    }

    @Test
    fun threeCharacters_searchOnceAfterTheDebounce_andListTheHits(): Promise<Unit> {
        val rpc = FakeComposeRpc(hits = listOf(ole, eva))
        return withView("cv-hits", rpc) { el ->
            el().search("Ol")
            el().search("Ole")
            el().search("Ole ")
            awaitUntil("hits shown") { el().textContent.orEmpty().contains("Ole Voss") }
            assertEquals(listOf("Ole"), rpc.searches, "typing fast makes ONE call with the final trimmed term")
            val text = el().textContent.orEmpty()
            assertTrue(text.contains("Eva Lind"))
            assertEquals(
                1,
                el().allOf(".text-muted").count { it.textContent?.trim() == "bereits im Gespräch" },
                "only Ole has a conversation",
            )
            assertTrue(el().allOf(".lapis-dm-avatar").all { it.getAttribute("aria-hidden") == "true" }, "initials are decoration")
        }
    }

    @Test
    fun choosingAHit_showsAChipAndTheForm_andSendingReportsThePartner(): Promise<Unit> {
        val rpc = FakeComposeRpc(hits = listOf(ole))
        var sentTo: String? = null
        return withView("cv-send", rpc, onPerson = { sentTo = it }) { el ->
            el().search("Ole")
            awaitUntil("hit") { el().allOf("button").any { it.textContent.orEmpty().contains("Ole Voss") } }
            el().allOf("button").first { it.textContent.orEmpty().contains("Ole Voss") }.click()
            awaitUntil("chip") { el().allOf(".lapis-dm-chip").isNotEmpty() }
            assertNotNull(el().iconButtonNamed("Empfänger entfernen"))
            assertTrue(el().textContent.orEmpty().contains("Datei anhängen"), "attachments are offered once a person is chosen")
            (el().controlOf("Nachricht") as HTMLTextAreaElement).let {
                it.value = "  Hallo Ole  "
                it.dispatchEvent(Event("input"))
            }
            el().buttonNamed("Senden").click()
            awaitUntil("sent") { sentTo != null }
            assertEquals("p-ole", sentTo)
            assertEquals(listOf(Triple("p-ole", "Hallo Ole", emptyList<String>())), rpc.personSends)
        }
    }

    @Test
    fun removingTheChip_bringsTheSearchBack(): Promise<Unit> {
        val rpc = FakeComposeRpc(hits = listOf(ole))
        return withView("cv-remove", rpc) { el ->
            el().search("Ole")
            awaitUntil("hit") { el().allOf("button").any { it.textContent.orEmpty().contains("Ole Voss") } }
            el().allOf("button").first { it.textContent.orEmpty().contains("Ole Voss") }.click()
            awaitUntil("chip") { el().allOf(".lapis-dm-chip").isNotEmpty() }
            el().iconButtonNamed("Empfänger entfernen").click()
            awaitUntil("chip gone") { el().allOf(".lapis-dm-chip").isEmpty() }
            assertTrue(el().allOf("label").any { it.textContent.orEmpty().contains("Name des Mitglieds") })
            assertEquals(0, el().allOf("textarea").size, "no composer without a recipient")
        }
    }

    @Test
    fun theGroupSegment_listsGroupsAsRadios_hintsAtNoAttachments_andSendsText(): Promise<Unit> {
        val rpc =
            FakeComposeRpc(
                groups = listOf(DirectMessageGroupDto("c1", "Vorstand", 3), DirectMessageGroupDto("c2", "Finanzen", 5)),
            )
        var sentGroup: String? = null
        return withView("cv-group", rpc, onGroup = { sentGroup = it }) { el ->
            assertTrue(rpc.groupLoads.isEmpty(), "groups load only when the segment is opened")
            el().buttonNamed("An eine Gruppe").click()
            awaitUntil("groups") { el().textContent.orEmpty().contains("Vorstand") }
            assertEquals("true", el().buttonNamed("An eine Gruppe").getAttribute("aria-checked"))
            assertTrue(el().textContent.orEmpty().contains("Anhänge sind bei Gruppennachrichten nicht möglich."))
            assertTrue(el().textContent.orEmpty().contains("Empfänger: 3"))
            assertFalse(el().textContent.orEmpty().contains("Mitglieder:"), "the count is labelled as recipients")
            val send = el().buttonNamed("An Gruppe senden")
            assertTrue(send.hasAttribute("disabled"), "nothing to send to before a group is chosen")
            val radios = el().allOf("[role=radiogroup] [role=radio]").filter { it.textContent.orEmpty().contains("Empfänger:") }
            assertEquals(2, radios.size)
            assertTrue(
                radios.all { r ->
                    r.allOf("span").any {
                        it.getAttribute("title") ==
                            "Ohne Sie selbst, ohne ausgeschiedene und inaktive Mitglieder"
                    }
                },
                "the count explains itself",
            )
            radios.first { it.textContent.orEmpty().contains("Vorstand") }.click()
            assertEquals(listOf("true", "false"), radios.map { it.getAttribute("aria-checked") })
            val summary = el().allOf("[role=status]").first { it.textContent.orEmpty().contains("Gruppe") }
            assertTrue(summary.textContent.orEmpty().contains("Vorstand") && summary.textContent.orEmpty().contains("3"))
            assertTrue(summary.textContent.orEmpty().contains("(Empfänger: 3)"), summary.textContent.orEmpty())
            assertFalse(summary.textContent.orEmpty().contains("Mitglieder:"))
            assertFalse(el().buttonNamed("An Gruppe senden").hasAttribute("disabled"))
            (el().controlOf("Nachricht") as HTMLTextAreaElement).let {
                it.value = " Sitzung verschoben "
                it.dispatchEvent(Event("input"))
            }
            el().buttonNamed("An Gruppe senden").click()
            awaitUntil("sent") { sentGroup != null }
            assertEquals("Vorstand", sentGroup)
            assertEquals(listOf("c1" to "Sitzung verschoben"), rpc.groupSends)
            assertEquals(0, el().allOf("input[type=file]").size, "no file input for a group")
        }
    }

    @Test
    fun noGroups_showTheEmptyState(): Promise<Unit> =
        withView("cv-nogroup", FakeComposeRpc()) { el ->
            el().buttonNamed("An eine Gruppe").click()
            awaitUntil("empty") { el().textContent.orEmpty().contains("Sie gehören keiner Gruppe an, an die Sie schreiben können.") }
        }

    @Test
    fun theFourTypedRefusals_haveOneFixedGermanTextEach_andNoneNamesAReason() {
        val texts =
            listOf(
                typedRefusalText(DirectMessageRecipientUnavailableException()),
                typedRefusalText(DirectMessageGroupUnavailableException()),
                typedRefusalText(DirectMessageGroupTooLargeException()),
                typedRefusalText(DirectMessageRateLimitedException()),
            )
        assertTrue(texts.none { it == null })
        assertEquals(4, texts.toSet().size)
        assertEquals("Diese Person kann nicht angeschrieben werden.", resolvedAttributeText(texts[0].orEmpty()))
        assertNull(typedRefusalText(IllegalStateException("x")))
    }

    @Test
    fun initials_takeUpToTwoLetters() {
        assertEquals("OV", nameInitials("Ole Voss"))
        assertEquals("M", nameInitials("madonna"))
        assertEquals("AB", nameInitials("  anna   berta   clara "))
        assertEquals("?", nameInitials("   "))
    }
}
