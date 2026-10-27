package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachmentRules
import network.lapis.cloud.shared.domain.DirectMessageAttachmentDto
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessagePageDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.DirectMessageAttachmentsUnavailableException
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.files.File
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ME = "member-1"
private const val PARTNER = "member-2"

/** A fake server for the composer: every upload is answered by [answer] (default: created, id `att-N`); a deferred gate can hold it. */
private class FakeAttachmentHttp : DirectMessageAttachmentHttp {
    val uploads = mutableListOf<Pair<String, String>>()
    val deletes = mutableListOf<String>()

    /** What the server holds as pending for the partner; null == the list cannot be read. */
    var pendingOnServer: List<DirectMessageAttachmentDto>? = emptyList()
    var gate: CompletableDeferred<Unit>? = null
    var answer: (String) -> MailingUploadOutcome = { name -> MailingUploadOutcome.Created("att-${name.substringBefore('.')}") }

    override suspend fun upload(
        recipientId: String,
        file: File,
    ): MailingUploadOutcome {
        uploads += recipientId to file.name
        gate?.await()
        return answer(file.name)
    }

    override suspend fun listPending(recipientId: String): List<DirectMessageAttachmentDto>? = pendingOnServer

    override suspend fun delete(attachmentId: String): String? {
        deletes += attachmentId
        return null
    }
}

/** V1.9.233 -- the attachments of the direct-message composer (state, markup, send gating) and under a message (links, hints). */
class DirectMessageAttachmentsDomTest {
    @AfterTest
    fun reset() = UnreadMessages.resetForTest()

    private fun session() =
        SessionInfoDto(
            memberId = ME,
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun reply(
        body: String,
        attachments: List<DirectMessageAttachmentDto> = emptyList(),
    ) = DirectMessageDto(
        id = "m-1",
        senderId = ME,
        senderDisplayName = "Dana Keller",
        recipientId = PARTNER,
        recipientDisplayName = "Ole Voss",
        body = body,
        sentAt = LocalDateTime(2026, 10, 2, 10, 0),
        readAt = null,
        attachments = attachments,
    )

    private fun chooseFiles(
        root: HTMLElement,
        vararg names: String,
    ) {
        val input = assertNotNull(root.querySelector("input[type=file]") as? HTMLInputElement, "no file input")
        val transfer = js("new DataTransfer()")
        names.forEach { transfer.items.add(File(arrayOf<dynamic>("%PDF-1.4"), it, js("({type: 'application/pdf'})"))) }
        input.asDynamic().files = transfer.files
        input.dispatchEvent(Event("change"))
    }

    private fun HTMLElement.sendButton() = buttonNamed("Antworten") as HTMLButtonElement

    @Test
    fun theComposer_hasTheAttachButtonWithIconAndText_theHiddenMultiInput_theHints_andTheList(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            mountedForm("dma-markup") { root, element ->
                root.vPanel {
                    directMessageReplyForm(
                        recipientId = PARTNER,
                        onSent = {},
                        attachments = DmComposerAttachments(PARTNER, FakeAttachmentHttp()),
                    )
                }
                val attach = element().buttonNamed("Datei anhängen")
                assertTrue(attach.querySelector("i.fa-paperclip") != null, "the paperclip icon")
                assertEquals("true", attach.querySelector("i.fa-paperclip")!!.getAttribute("aria-hidden"))
                val input = element().querySelector("input[type=file]") as HTMLInputElement
                assertTrue(input.multiple)
                assertEquals(AttachmentRules.acceptAttribute(), input.getAttribute("accept"))
                assertNotNull(element().querySelector("ul.lapis-dm-composer-attachments"))
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Bis zu 5 Dateien, je höchstens 10 MB, zusammen höchstens 20 MB."), text)
                assertTrue(text.contains("Anhänge sieht nur Ihr Gesprächspartner. Lapis prüft Dateien nicht auf Viren."), text)
                // the attach button sits LEFT of "Antworten" in the same group
                val buttons = element().allOf("button").map { it.textContent?.trim() }
                assertTrue(buttons.indexOf("Datei anhängen") < buttons.indexOf("Antworten"), buttons.toString())
            }
        }

    @Test
    fun whileAFileUploads_sendingIsDisabledWithAHint_thenItSendsTheIdsAndEmptiesTheList(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            UnreadMessages.rpc = UnreadCountRpc { 0 }
            val http = FakeAttachmentHttp().also { it.gate = CompletableDeferred() }
            val state = DmComposerAttachments(PARTNER, http)
            val sentIds = mutableListOf<List<String>>()
            var onSent = 0
            mountedForm("dma-flow") { root, element ->
                root.vPanel {
                    directMessageReplyForm(
                        recipientId = PARTNER,
                        onSent = { onSent++ },
                        attachments = state,
                        send = { _, body, ids ->
                            sentIds += ids
                            reply(body)
                        },
                    )
                }
                element().typeInto("Antwort", "Mit Anhang")
                chooseFiles(element(), "Plan.pdf", "Liste.pdf")
                awaitUntil("both rows listed") { element().allOf("ul.lapis-dm-composer-attachments > li").size == 2 }
                assertTrue(element().sendButton().disabled, "no send while a file uploads")
                assertTrue(element().textContent.orEmpty().contains("Bitte warten, bis alle Dateien hochgeladen sind."))
                assertTrue(element().textContent.orEmpty().contains("Wird hochgeladen…"))
                http.gate!!.complete(Unit)
                awaitUntil("send enabled again") { !element().sendButton().disabled }
                assertFalse(element().textContent.orEmpty().contains("Bitte warten"))
                assertEquals(listOf(PARTNER to "Plan.pdf", PARTNER to "Liste.pdf"), http.uploads)

                element().sendButton().click()
                awaitUntil("sent") { onSent == 1 }
                assertEquals(listOf(listOf("att-Plan", "att-Liste")), sentIds)
                assertEquals(0, element().allOf("ul.lapis-dm-composer-attachments > li").size, "the list is empty after a send")
                assertEquals("", (element().controlOf("Antwort") as HTMLTextAreaElement).value)
            }
        }

    @Test
    fun aFailedUpload_isListedWithItsReason_blocksSending_andCanBeRemovedLocally(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val http = FakeAttachmentHttp().also { it.answer = { MailingUploadOutcome.Refused(415, "MACRO_OR_EMBEDDED") } }
            mountedForm("dma-failed") { root, element ->
                root.vPanel {
                    directMessageReplyForm(recipientId = PARTNER, onSent = {}, attachments = DmComposerAttachments(PARTNER, http))
                }
                element().typeInto("Antwort", "Text")
                chooseFiles(element(), "Makro.pdf")
                awaitUntil("failure shown") { element().textContent.orEmpty().contains("Fehlgeschlagen:") }
                assertTrue(element().textContent.orEmpty().contains("enthält Makros oder eingebettete Objekte"))
                assertNotNull(element().querySelector("li [role=alert]"))
                assertTrue(element().sendButton().disabled)
                assertTrue(element().textContent.orEmpty().contains("Bitte entfernen Sie fehlgeschlagene Dateien."))
                element().iconButtonNamed("„Makro.pdf“ entfernen").click()
                awaitUntil("row gone, send enabled") {
                    element().allOf("ul.lapis-dm-composer-attachments > li").isEmpty() && !element().sendButton().disabled
                }
                assertTrue(http.deletes.isEmpty(), "a failed file was never stored: nothing to delete on the server")
            }
        }

    @Test
    fun removingAnUploadedFile_deletesItOnTheServer(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val http = FakeAttachmentHttp()
            mountedForm("dma-remove") { root, element ->
                root.vPanel {
                    directMessageReplyForm(recipientId = PARTNER, onSent = {}, attachments = DmComposerAttachments(PARTNER, http))
                }
                chooseFiles(element(), "Weg.pdf")
                awaitUntil("listed") { element().allOf("ul.lapis-dm-composer-attachments > li").size == 1 }
                awaitUntil("uploaded") { !element().textContent.orEmpty().contains("Wird hochgeladen") }
                element().iconButtonNamed("„Weg.pdf“ entfernen").click()
                awaitUntil("deleted on the server") { http.deletes == listOf("att-Weg") }
                awaitUntil("row gone") { element().allOf("ul.lapis-dm-composer-attachments > li").isEmpty() }
            }
        }

    @Test
    fun aFailedSend_keepsTextAndFiles_andAFileProblemShowsTheFixedSentence(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            // The server still holds the file as pending (the refusal was for another reason): the draft stays after the re-sync.
            val http =
                FakeAttachmentHttp().also {
                    it.pendingOnServer = listOf(DirectMessageAttachmentDto("att-Bleibt", "Bleibt.pdf", "application/pdf", 8))
                }
            var onFailed = 0
            mountedForm("dma-send-fail") { root, element ->
                root.vPanel {
                    directMessageReplyForm(
                        recipientId = PARTNER,
                        onSent = {},
                        onFailed = { onFailed++ },
                        attachments = DmComposerAttachments(PARTNER, http),
                        send = { _, _, _ -> throw DirectMessageAttachmentsUnavailableException("secret-server-text") },
                    )
                }
                element().typeInto("Antwort", "Bleibt stehen")
                chooseFiles(element(), "Bleibt.pdf")
                awaitUntil("uploaded") {
                    !element().sendButton().disabled &&
                        element().allOf("ul.lapis-dm-composer-attachments > li").size == 1
                }
                element().sendButton().click()
                awaitUntil("failed") { onFailed == 1 }
                delay(60)
                assertEquals("Bleibt stehen", (element().controlOf("Antwort") as HTMLTextAreaElement).value)
                assertEquals(1, element().allOf("ul.lapis-dm-composer-attachments > li").size, "the file stays pending")
                assertFalse(element().textContent.orEmpty().contains("secret-server-text"))
            }
        }

    @Test
    fun aFileTheServerHasPending_butThePageLostTrackOf_isShownAgain_andSendsWithItsId(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            UnreadMessages.rpc = UnreadCountRpc { 0 }
            val http = FakeAttachmentHttp()
            http.pendingOnServer = listOf(DirectMessageAttachmentDto("srv-1", "Vertrag.pdf", "application/pdf", 1234))
            // A fresh state == a page reload / navigation: nothing in memory, the server still holds the pending file.
            val state = DmComposerAttachments(PARTNER, http)
            val sentIds = mutableListOf<List<String>>()
            mountedForm("dma-reload") { root, element ->
                root.vPanel {
                    directMessageReplyForm(
                        recipientId = PARTNER,
                        onSent = {},
                        attachments = state,
                        send = { _, body, ids ->
                            sentIds += ids
                            reply(body)
                        },
                    )
                }
                state.syncWithServer()
                awaitUntil("the pending file is listed again") { element().textContent.orEmpty().contains("Vertrag.pdf") }
                assertEquals(1, element().allOf("ul.lapis-dm-composer-attachments > li").size)
                assertFalse(element().sendButton().disabled)
                element().typeInto("Antwort", "Mit altem Anhang")
                element().sendButton().click()
                awaitUntil("sent with the server's id") { sentIds == listOf(listOf("srv-1")) }
                assertTrue(http.uploads.isEmpty(), "nothing was uploaded again")
            }
        }

    @Test
    fun aPendingFileShownAgain_canBeDeletedOnTheServer(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val http = FakeAttachmentHttp()
            http.pendingOnServer = listOf(DirectMessageAttachmentDto("srv-2", "Alt.pdf", "application/pdf", 10))
            val state = DmComposerAttachments(PARTNER, http)
            mountedForm("dma-reload-delete") { root, element ->
                root.vPanel { directMessageReplyForm(recipientId = PARTNER, onSent = {}, attachments = state) }
                state.syncWithServer()
                awaitUntil("listed") { element().textContent.orEmpty().contains("Alt.pdf") }
                element().iconButtonNamed("„Alt.pdf“ entfernen").click()
                awaitUntil("deleted on the server") { http.deletes == listOf("srv-2") }
            }
        }

    @Test
    fun syncWithServer_neverDuplicatesAKnownFile_andIgnoresAnUnreadableList(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val http = FakeAttachmentHttp()
            val state = DmComposerAttachments(PARTNER, http)
            mountedForm("dma-sync-idempotent") { root, element ->
                root.vPanel { directMessageReplyForm(recipientId = PARTNER, onSent = {}, attachments = state) }
                chooseFiles(element(), "Eins.pdf")
                awaitUntil("uploaded") { state.readyIds == listOf("att-Eins") }
                http.pendingOnServer = listOf(DirectMessageAttachmentDto("att-Eins", "Eins.pdf", "application/pdf", 8))
                state.syncWithServer()
                delay(60)
                assertEquals(listOf("att-Eins"), state.readyIds, "no duplicate row")
                http.pendingOnServer = null
                state.syncWithServer()
                delay(60)
                assertEquals(listOf("att-Eins"), state.readyIds, "an unreadable list changes nothing")
            }
        }

    @Test
    fun whileASendRuns_theAttachButtonTheRemoveButtonsAndSendStayDisabled_andOnlyTheSentFilesLeave(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            UnreadMessages.rpc = UnreadCountRpc { 0 }
            val http = FakeAttachmentHttp()
            val state = DmComposerAttachments(PARTNER, http)
            val sendGate = CompletableDeferred<Unit>()
            val sentIds = mutableListOf<List<String>>()
            mountedForm("dma-sending") { root, element ->
                root.vPanel {
                    directMessageReplyForm(
                        recipientId = PARTNER,
                        onSent = {},
                        attachments = state,
                        send = { _, body, ids ->
                            sentIds += ids
                            sendGate.await()
                            reply(body)
                        },
                    )
                }
                element().typeInto("Antwort", "Langsam")
                chooseFiles(element(), "Eins.pdf")
                awaitUntil("uploaded") { state.readyIds == listOf("att-Eins") && !element().sendButton().disabled }
                element().sendButton().click()
                awaitUntil("send in flight") { sentIds.size == 1 }
                assertTrue(state.sending)
                assertTrue(element().sendButton().disabled, "send stays disabled")
                assertTrue((element().buttonNamed("Datei anhängen") as HTMLButtonElement).disabled, "no attaching while sending")
                assertTrue((element().iconButtonNamed("„Eins.pdf“ entfernen") as HTMLButtonElement).disabled, "no removing while sending")
                // A file chosen anyway (hidden input) is ignored while the send runs.
                chooseFiles(element(), "Zwei.pdf")
                delay(60)
                assertTrue(http.uploads.none { it.second == "Zwei.pdf" }, "no upload during a send")
                assertTrue(element().sendButton().disabled, "still disabled after the attempt")
                sendGate.complete(Unit)
                awaitUntil("list emptied, controls back") {
                    element().allOf("ul.lapis-dm-composer-attachments > li").isEmpty() &&
                        !(element().buttonNamed("Datei anhängen") as HTMLButtonElement).disabled
                }
                assertEquals(listOf(listOf("att-Eins")), sentIds)
            }
        }

    @Test
    fun clearAfterSend_removesOnlyTheSentFiles(): Promise<Unit> =
        formTest {
            val http = FakeAttachmentHttp()
            val state = DmComposerAttachments(PARTNER, http)
            mountedForm("dma-clear-sent") { root, element ->
                root.vPanel { directMessageReplyForm(recipientId = PARTNER, onSent = {}, attachments = state) }
                chooseFiles(element(), "Eins.pdf", "Zwei.pdf")
                awaitUntil("both uploaded") { state.readyIds == listOf("att-Eins", "att-Zwei") }
                state.clearAfterSend(listOf("att-Eins"))
                assertEquals(listOf("att-Zwei"), state.readyIds, "a file that was not sent stays listed")
            }
        }

    @Test
    fun aSendRefusedForTheFiles_dropsTheDraftsTheServerNoLongerHas_butAnotherConflictKeepsThem(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            for ((name, error) in listOf<Pair<String, Throwable>>(
                "files" to DirectMessageAttachmentsUnavailableException(),
                "other" to ConflictException("recipient gone"),
            )) {
                val http = FakeAttachmentHttp()
                val state = DmComposerAttachments(PARTNER, http)
                var failed = 0
                mountedForm("dma-refused-$name") { root, element ->
                    root.vPanel {
                        directMessageReplyForm(
                            recipientId = PARTNER,
                            onSent = {},
                            onFailed = { failed++ },
                            attachments = state,
                            send = { _, _, _ -> throw error },
                        )
                    }
                    element().typeInto("Antwort", "Text")
                    chooseFiles(element(), "Alt.pdf")
                    awaitUntil("uploaded") { state.readyIds == listOf("att-Alt") && !element().sendButton().disabled }
                    // the id expired meanwhile: the server's pending list is empty
                    http.pendingOnServer = emptyList()
                    element().sendButton().click()
                    awaitUntil("failed") { failed == 1 }
                    delay(80)
                    if (name == "files") {
                        assertTrue(state.readyIds.isEmpty(), "the expired draft is dropped, so the user can simply attach it again")
                    } else {
                        assertEquals(listOf("att-Alt"), state.readyIds, "a conflict unrelated to the files keeps them")
                    }
                }
            }
        }

    @Test
    fun pendingFiles_surviveARebuiltForm_theStateLivesOutsideTheForm(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val http = FakeAttachmentHttp()
            val state = DmComposerAttachments(PARTNER, http)
            mountedForm("dma-rebuild") { root, element ->
                root.vPanel { directMessageReplyForm(recipientId = PARTNER, onSent = {}, attachments = state) }
                chooseFiles(element(), "Bleibt.pdf")
                awaitUntil("uploaded") { state.readyIds == listOf("att-Bleibt") }
                root.removeAll()
                root.vPanel { directMessageReplyForm(recipientId = PARTNER, onSent = {}, attachments = state) }
                awaitUntil("listed again in the new form") {
                    element().allOf("ul.lapis-dm-composer-attachments > li").size == 1 &&
                        element().textContent.orEmpty().contains("Bleibt.pdf")
                }
                assertEquals(1, http.uploads.size, "no second upload")
            }
        }

    @Test
    fun theLimits_arePrecheckedClientSide_withoutAnyUpload(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val http = FakeAttachmentHttp()
            mountedForm("dma-precheck") { root, element ->
                root.vPanel {
                    directMessageReplyForm(recipientId = PARTNER, onSent = {}, attachments = DmComposerAttachments(PARTNER, http))
                }
                chooseFiles(element(), "virus.exe")
                awaitUntil("refused") { element().textContent.orEmpty().contains("nicht unterstütztes Format") }
                assertTrue(http.uploads.isEmpty())
            }
        }

    // ── under the message ───────────────────────────────────────────────────────────────────────────────

    private class OnePageRpc(
        val messages: List<DirectMessageDto>,
    ) : DirectMessageConversationRpc {
        override suspend fun listConversationPage(
            otherMemberId: String,
            beforeSentAt: LocalDateTime?,
            beforeId: String?,
            limit: Int,
        ) = DirectMessagePageDto(messages = messages, hasMore = false, nextCursor = null)

        override suspend fun markConversationRead(otherMemberId: String) = 0
    }

    @Test
    fun aMessage_listsItsFilesAsPlainDownloadLinks_withTheIdOnly_andTheVirusHintOnlyWhenReceived(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val files =
                listOf(
                    DirectMessageAttachmentDto(
                        id = "att-77",
                        fileName = "Rechnung <b>1</b>.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = 2048,
                    ),
                )
            val mine = reply("Von mir", files).copy(id = "m-mine")
            val theirs =
                reply(
                    "Von Ihnen",
                    files,
                ).copy(id = "m-theirs", senderId = PARTNER, senderDisplayName = "Ole Voss", recipientId = ME)
            mountedForm("dma-bubble") { root, element ->
                root.vPanel { conversationView(PARTNER, 0, ME, OnePageRpc(listOf(theirs, mine))) }
                awaitUntil("both messages") { element().allOf("ul.lapis-dm-attachments").size == 2 }
                val links = element().allOf("ul.lapis-dm-attachments a")
                assertEquals(2, links.size)
                links.forEach { link ->
                    assertEquals("/api/direct-messages/attachments/att-77/download", link.getAttribute("href"))
                    assertFalse(link.getAttribute("href").orEmpty().contains("Rechnung"), "the name never reaches the URL")
                    assertTrue(link.getAttribute("aria-label").orEmpty().startsWith("„Rechnung"))
                    assertNull(link.getAttribute("target"))
                    assertEquals("noopener", link.getAttribute("rel"))
                    assertEquals("Rechnung <b>1</b>.pdf", link.textContent)
                }
                assertNull(element().querySelector("ul.lapis-dm-attachments b"), "the name is text, never markup")
                assertTrue(element().querySelector("ul.lapis-dm-attachments i.fa-file-pdf") != null, "a type icon")
                val hints =
                    element()
                        .textContent
                        .orEmpty()
                        .split("Lapis prüft Anhänge nicht auf Viren.")
                        .size - 1
                assertEquals(1, hints, "the virus hint appears once: only under the RECEIVED message")
            }
        }

    @Test
    fun aMessageWithoutFiles_hasNoAttachmentList(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            mountedForm("dma-none") { root, element ->
                root.vPanel { conversationView(PARTNER, 0, ME, OnePageRpc(listOf(reply("Nur Text")))) }
                awaitUntil("message") { element().textContent.orEmpty().contains("Nur Text") }
                assertNull(element().querySelector("ul.lapis-dm-attachments"))
            }
        }
}
