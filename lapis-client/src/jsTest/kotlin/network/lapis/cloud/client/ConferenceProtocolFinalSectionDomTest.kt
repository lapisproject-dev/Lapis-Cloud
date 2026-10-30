package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MeetingProtocolPreviewDto
import network.lapis.cloud.shared.domain.MeetingProtocolVersionsDto
import network.lapis.cloud.shared.domain.MyProtocolTaskDto
import network.lapis.cloud.shared.domain.ProtocolConfirmOutcome
import network.lapis.cloud.shared.domain.ProtocolConfirmResultDto
import network.lapis.cloud.shared.domain.ProtocolConfirmationDto
import network.lapis.cloud.shared.domain.ProtocolConfirmationMode
import network.lapis.cloud.shared.domain.ProtocolConfirmationRole
import network.lapis.cloud.shared.domain.ProtocolFinaliseOutcome
import network.lapis.cloud.shared.domain.ProtocolFinaliseResultDto
import network.lapis.cloud.shared.domain.ProtocolVersionDto
import network.lapis.cloud.shared.domain.ProtocolVersionStatus
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.240 -- the finalising section and the task list mounted for real: what each role sees, what a click sends. */
class ConferenceProtocolFinalSectionDomTest {
    private val hash = "a1b2c3d4e5f6".padEnd(64, '0')

    private fun version(
        no: Int,
        status: ProtocolVersionStatus,
        missing: Set<ProtocolConfirmationRole> = emptySet(),
        pdf: Boolean = false,
    ) = ProtocolVersionDto(
        versionId = "v$no",
        versionNo = no,
        status = status,
        contentSha256 = hash,
        correctionNote = if (no > 1) "Korrektur wegen eines Fehlers" else null,
        finalisedAt = LocalDateTime(2030, 5, 4, 18, 0),
        confirmationMode = ProtocolConfirmationMode.APP_CONFIRMATION,
        minutesRequired = true,
        confirmations =
            if (status == ProtocolVersionStatus.CONFIRMED) {
                listOf(
                    ProtocolConfirmationDto(ProtocolConfirmationRole.LEAD, "Chris Chair", LocalDateTime(2030, 5, 4, 18, 0)),
                    ProtocolConfirmationDto(ProtocolConfirmationRole.MINUTES, "Mia Minutes", LocalDateTime(2030, 5, 4, 18, 5)),
                )
            } else {
                emptyList()
            },
        missingRoles = missing,
        pdfAvailable = pdf,
    )

    private fun versions(
        roles: Set<ProtocolConfirmationRole>,
        list: List<ProtocolVersionDto>,
        canConfirm: Boolean = false,
        canCorrect: Boolean = false,
    ) = MeetingProtocolVersionsDto("m1", roles, canConfirm, canCorrect, list)

    private class Calls {
        var versions: MeetingProtocolVersionsDto? = null
        val finalised = mutableListOf<String>()
        val confirmed = mutableListOf<String>()
        val corrections = mutableListOf<String>()
        var finaliseOutcome = ProtocolFinaliseOutcome.FINALISED
        var blocked: ProtocolFinaliseOutcome? = null
        var jumps = 0
        var withJump = true
    }

    private suspend fun <T> withSection(
        calls: Calls,
        block: suspend (HTMLElement) -> T,
    ): T {
        val rpc =
            MeetingProtocolRpc(
                preview = { _ ->
                    MeetingProtocolPreviewDto(
                        "m1",
                        1,
                        "f".repeat(64),
                        1,
                        calls.blocked == null,
                        calls.blocked,
                        setOf(ProtocolConfirmationRole.LEAD),
                        true,
                    )
                },
                versions = { _ -> calls.versions!! },
                finalise = { _, h ->
                    calls.finalised += h
                    ProtocolFinaliseResultDto(calls.finaliseOutcome, null, null)
                },
                confirm = { _, v ->
                    calls.confirmed += v
                    ProtocolConfirmResultDto(ProtocolConfirmOutcome.FULLY_CONFIRMED, null)
                },
                correct = { _, note -> calls.corrections += note },
            )
        return mountedForm("protocol-final") { root: Root, element ->
            renderProtocolFinalSection(
                parent = root,
                meetingId = "m1",
                rpc = rpc,
                onJumpToDraft = if (calls.withJump) ({ calls.jumps++ }) else null,
            )
            block(element())
        }
    }

    private suspend fun awaitDialogText(fragment: String): String {
        awaitUntil("the dialog shows '$fragment'") {
            kotlinx.browser.document
                .querySelector(".modal.show")
                ?.textContent
                ?.contains(fragment) ==
                true
        }
        return lastOpenModal().textContent.orEmpty()
    }

    private fun confirmDialogButton(label: String): HTMLElement =
        assertNotNull(
            lastOpenModal().allOf("button").firstOrNull { it.textContent.orEmpty().trim() == label },
            "no dialog button '$label'",
        )

    private fun HTMLElement.text(): String = textContent.orEmpty().replace(Regex("\\s+"), " ").trim()

    private fun HTMLElement.buttons(): List<String> = allOf("button").map { it.text() }

    private fun HTMLElement.button(label: String): HTMLElement =
        assertNotNull(allOf("button").firstOrNull { it.text() == label || it.getAttribute("aria-label") == label }, "no button '$label'")

    @Test
    fun theLead_withoutAVersion_isOfferedToFinalise_andAParticipantIsOfferedNothing(): Promise<Unit> =
        formTest {
            val lead = Calls().also { it.versions = versions(setOf(ProtocolConfirmationRole.LEAD), emptyList()) }
            withSection(lead) { el ->
                awaitUntil("the section") { el.text().contains("Noch keine Fassung fertiggestellt.") }
                assertTrue(el.buttons().any { it.contains("Protokoll fertigstellen") })
            }
            val reader = Calls().also { it.versions = versions(emptySet(), emptyList()) }
            withSection(reader) { el ->
                awaitUntil("the section") { el.text().contains("Noch keine Fassung fertiggestellt.") }
                assertTrue(el.allOf("button").isEmpty(), "a participant has no button")
            }
        }

    @Test
    fun aWaitingVersion_showsWhoIsMissing_andOnlyThatRoleSeesConfirm(): Promise<Unit> =
        formTest {
            val awaiting = version(1, ProtocolVersionStatus.AWAITING_CONFIRMATION, missing = setOf(ProtocolConfirmationRole.MINUTES))
            val minutes =
                Calls().also {
                    it.versions =
                        versions(setOf(ProtocolConfirmationRole.MINUTES), listOf(awaiting), canConfirm = true, canCorrect = true)
                }
            withSection(minutes) { el ->
                awaitUntil("waiting") { el.text().contains("Bestätigung von Protokollführung ausstehend") }
                el.button("Bestätigen").click()
                awaitUntil("confirm sent") { minutes.confirmed == listOf("v1") }
                assertFalse(el.buttons().any { it.contains("Protokoll fertigstellen") }, "a waiting version cannot be finalised again")
            }
            val lead = Calls().also { it.versions = versions(setOf(ProtocolConfirmationRole.LEAD), listOf(awaiting), canCorrect = true) }
            withSection(lead) { el ->
                awaitUntil("waiting") { el.text().contains("Bestätigung von Protokollführung ausstehend") }
                assertFalse(el.buttons().any { it == "Bestätigen" }, "the lead already confirmed, there is nothing to confirm")
            }
        }

    @Test
    fun aConfirmedVersion_showsNoTextArea_theShortHash_theCopyButton_andThePdfLink(): Promise<Unit> =
        formTest {
            val confirmed = version(1, ProtocolVersionStatus.CONFIRMED, pdf = true)
            val calls =
                Calls().also { it.versions = versions(setOf(ProtocolConfirmationRole.LEAD), listOf(confirmed), canCorrect = true) }
            withSection(calls) { el ->
                awaitUntil("confirmed") { el.text().contains("a1b2 c3d4 e5f6") }
                assertTrue(el.allOf("textarea").isEmpty(), "no field in the resting view")
                assertTrue(el.buttons().any { it.contains("Prüfsumme vollständig kopieren") })
                val link = assertNotNull(el.allOf("a").firstOrNull { it.getAttribute("href") == "/api/meeting-protocol/v1/pdf" })
                assertTrue(link.text().contains("PDF herunterladen"))
                assertTrue(el.text().contains("Chris Chair"))
            }
        }

    @Test
    fun aConfirmedVersionWithoutAPdf_saysItIsBeingCreated_andOffersARetry(): Promise<Unit> =
        formTest {
            val confirmed = version(1, ProtocolVersionStatus.CONFIRMED, pdf = false)
            val calls =
                Calls().also { it.versions = versions(setOf(ProtocolConfirmationRole.LEAD), listOf(confirmed), canCorrect = true) }
            withSection(calls) { el ->
                awaitUntil("confirmed") { el.text().contains("PDF wird erstellt") }
                el.button("Erneut versuchen").click()
                awaitUntil("retry sent") { calls.confirmed == listOf("v1") }
            }
        }

    private suspend fun openBlockedDialog(
        calls: Calls,
        fragment: String,
        block: suspend (HTMLElement) -> Unit,
    ) {
        calls.versions = versions(setOf(ProtocolConfirmationRole.LEAD), emptyList())
        withSection(calls) { el ->
            awaitUntil("the section") { el.buttons().any { it.contains("Protokoll fertigstellen") } }
            el.button("Protokoll fertigstellen …").click()
            awaitDialogText(fragment)
            block(el)
        }
    }

    @Test
    fun anEmptyProtokoll_explainsItself_hasNoConfirmButton_andJumpsToTheDraft(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.blocked = ProtocolFinaliseOutcome.EMPTY }
            openBlockedDialog(calls, "Es ist noch nichts eingetragen") { _ ->
                val labels = lastOpenModal().allOf("button").map { it.text() }
                assertFalse(labels.any { it.contains("Fertigstellen und bestätigen") }, "no confirm button: $labels")
                assertTrue(labels.any { it.contains("Schließen") }, "$labels")
                assertTrue(labels.any { it.contains("Zum Entwurf") }, "$labels")
                assertTrue(calls.finalised.isEmpty())
                confirmDialogButton("Zum Entwurf").click()
                awaitUntil("the jump") { calls.jumps == 1 }
            }
        }

    @Test
    fun aNotOpenedSitzung_showsOnlyClose(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.blocked = ProtocolFinaliseOutcome.NOT_OPENED }
            openBlockedDialog(calls, "Die Sitzung wurde noch nicht eröffnet.") { _ ->
                val labels = lastOpenModal().allOf("button").map { it.text() }.filter { it.isNotBlank() }
                assertFalse(labels.any { it.contains("Zum Entwurf") }, "$labels")
                assertFalse(labels.any { it.contains("Fertigstellen und bestätigen") }, "$labels")
                assertTrue(labels.any { it.contains("Schließen") }, "$labels")
            }
        }

    @Test
    fun anEmptyProtokoll_withoutADraftOnThePage_offersNoJump(): Promise<Unit> =
        formTest {
            val calls =
                Calls().also {
                    it.blocked = ProtocolFinaliseOutcome.EMPTY
                    it.withJump = false
                }
            openBlockedDialog(calls, "Es ist noch nichts eingetragen") { _ ->
                assertFalse(lastOpenModal().allOf("button").any { it.text().contains("Zum Entwurf") })
            }
        }

    @Test
    fun finalising_asksFirst_andSendsTheHashTheCallerSaw(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.versions = versions(setOf(ProtocolConfirmationRole.LEAD), emptyList()) }
            withSection(calls) { el ->
                awaitUntil("the section") { el.buttons().any { it.contains("Protokoll fertigstellen") } }
                el.button("Protokoll fertigstellen …").click()
                val dialog = awaitDialogText("Damit bestätigen Sie das Protokoll als Sitzungsleitung")
                assertTrue(dialog.contains("1 Tagesordnungspunkte ohne Verlauf"))
                confirmDialogButton("Fertigstellen und bestätigen").click()
                awaitUntil("finalise sent") { calls.finalised == listOf("f".repeat(64)) }
            }
        }

    @Test
    fun aCorrection_needsTwentyCharacters_andShowsTheEarlierVersions(): Promise<Unit> =
        formTest {
            val v2 = version(2, ProtocolVersionStatus.CONFIRMED, pdf = true)
            val v1 = version(1, ProtocolVersionStatus.CONFIRMED, pdf = true)
            val calls = Calls().also { it.versions = versions(setOf(ProtocolConfirmationRole.LEAD), listOf(v2, v1), canCorrect = true) }
            withSection(calls) { el ->
                awaitUntil("history") { el.buttons().contains("Frühere Fassungen anzeigen") }
                el.button("Frühere Fassungen anzeigen").click()
                awaitUntil("table") { el.allOf("table").isNotEmpty() }
                el.button("Korrektur anlegen …").click()
                awaitUntil("form") { el.allOf("textarea").isNotEmpty() }
                val create = el.button("Korrektur anlegen")
                assertTrue(create.hasAttribute("disabled"), "disabled below the minimum")
            }
        }

    @Test
    fun myTasks_areShownOnlyWhenThereAreAny_overdueIsMarked_andTickingSendsTheKey(): Promise<Unit> =
        formTest {
            val task =
                MyProtocolTaskDto(
                    taskKey = "k1",
                    meetingId = "m1",
                    meetingDate = LocalDate(2030, 5, 4),
                    committeeName = "Vorstand",
                    versionNo = 1,
                    description = "Bericht schreiben",
                    dueDate = LocalDate(2030, 5, 20),
                    done = false,
                    doneAt = null,
                    overdue = true,
                )
            val ticked = mutableListOf<Pair<String, Boolean>>()
            val rpc =
                MyProtocolTasksRpc(
                    list = { listOf(task) },
                    setDone = { key, done ->
                        ticked += key to done
                        task.copy(done = done, overdue = false)
                    },
                )
            mountedForm("my-protocol-tasks") { root: Root, element ->
                renderMyProtocolTasks(parent = root, rpc = rpc)
                val el = element()
                awaitUntil("tasks") { el.text().contains("Bericht schreiben") }
                assertTrue(el.text().contains("überfällig"))
                assertTrue(el.text().contains("Meine Aufgaben aus Protokollen"))
                el.button("Erledigt").click()
                awaitUntil("ticked") { ticked == listOf("k1" to true) }
                awaitUntil("done") { el.text().contains("Wieder öffnen") }
                assertEquals(1, ticked.size)
            }
            val empty = MyProtocolTasksRpc(list = { emptyList() })
            mountedForm("my-protocol-tasks") { root: Root, element ->
                renderMyProtocolTasks(parent = root, rpc = empty)
                kotlinx.coroutines.delay(50)
                assertFalse(element().text().contains("Meine Aufgaben"), "a person without tasks sees nothing")
            }
        }
}
