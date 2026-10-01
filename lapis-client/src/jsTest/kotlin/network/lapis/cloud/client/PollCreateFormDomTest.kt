package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PollDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.31: "Umfrage erstellen", mounted for real -- validation, option rows, the deadline, the single-shot submit, the fixed error texts. */
class PollCreateFormDomTest {
    private suspend fun <T> withForm(
        world: PollWorld,
        id: String,
        done: MutableList<PollDto?> = mutableListOf(),
        block: suspend (HTMLElement, List<RecordedRequest>, PollRoutes) -> T,
    ): T {
        AppState.setSession(pollSession())
        val routes = pollRoutes()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root, element ->
                renderPollCreateForm(root) { done += it }
                block(element(), calls, routes)
            }
        }
    }

    private fun HTMLElement.fillValid(question: String = "Sommerfest im Juli?") {
        typeInto("Frage", question)
        typeInto("Option 1", "Ja")
        typeInto("Option 2", "Nein")
    }

    private fun HTMLElement.reason(): String = flatText()

    @Test
    fun anEmptyForm_disablesTheStartButton_andNamesTheFirstReason_andTellsItIsNonBinding(): Promise<Unit> =
        formTest {
            withForm(PollWorld(), "poll-form-empty") { el, _, _ ->
                assertTrue(el.isButtonDisabled("Umfrage starten"))
                assertTrue(el.reason().contains("Bitte geben Sie eine Frage ein."))
                assertTrue(
                    el.flatText().contains(
                        "Unverbindliche Umfrage – ein Stimmungsbild, kein Beschluss. Antworten sind anonym; das Ergebnis ist erst nach dem Ende sichtbar.",
                    ),
                )
                assertTrue(el.allOf(".alert-info").isNotEmpty())
                assertEquals("h168", (el.controlOf("Frist") as HTMLSelectElement).value, "the default deadline is 7 days")
                assertEquals(
                    2,
                    el.allOf("label").count {
                        it.textContent
                            .orEmpty()
                            .trim()
                            .startsWith("Option ")
                    },
                )
            }
        }

    @Test
    fun aValidDraft_enablesTheButton_andTheCounterCounts(): Promise<Unit> =
        formTest {
            withForm(PollWorld(), "poll-form-valid") { el, _, _ ->
                el.typeInto("Frage", "Sommerfest")
                assertTrue(el.flatText().contains("10/500"))
                assertTrue(el.isButtonDisabled("Umfrage starten"), "options are still empty")
                assertTrue(el.reason().contains("Option 1 ist leer."))
                el.typeInto("Option 1", "Ja")
                el.typeInto("Option 2", "Nein")
                assertFalse(el.isButtonDisabled("Umfrage starten"))
            }
        }

    @Test
    fun options_canBeAddedUpToTen_andRemovedFromTheThirdOn(): Promise<Unit> =
        formTest {
            withForm(PollWorld(), "poll-form-options") { el, _, _ ->
                assertEquals(
                    0,
                    el.allOf("button").count {
                        it.getAttribute("aria-label").orEmpty().startsWith("Option ") &&
                            it.getAttribute("aria-label").orEmpty().endsWith("entfernen")
                    },
                    "no remove button for the first two",
                )
                repeat(8) { el.buttonNamed("Option hinzufügen").click() }
                assertEquals(
                    10,
                    el.allOf("label").count {
                        it.textContent
                            .orEmpty()
                            .trim()
                            .startsWith("Option ")
                    },
                )
                assertTrue(el.isButtonDisabled("Option hinzufügen"), "ten is the maximum")
                val removeThird = el.allOf("button").first { it.getAttribute("aria-label") == "Option 3 entfernen" }
                removeThird.click()
                assertEquals(
                    9,
                    el.allOf("label").count {
                        it.textContent
                            .orEmpty()
                            .trim()
                            .startsWith("Option ")
                    },
                )
                assertFalse(el.isButtonDisabled("Option hinzufügen"))
                assertTrue(el.allOf("button").any { it.getAttribute("aria-label") == "Option 9 entfernen" }, "the rest is renumbered")
                assertFalse(el.allOf("button").any { it.getAttribute("aria-label") == "Option 10 entfernen" })
            }
        }

    @Test
    fun aDuplicateOption_isNamedInlineAtTheSecondOccurrence_andBlocksTheButton(): Promise<Unit> =
        formTest {
            withForm(PollWorld(), "poll-form-duplicate") { el, _, _ ->
                el.typeInto("Frage", "Frage?")
                el.typeInto("Option 1", "Ja")
                el.typeInto("Option 2", "  JA ")
                assertTrue(el.shownErrors().contains("Diese Option gibt es schon."), "inline: ${el.shownErrors()}")
                assertTrue(el.isButtonDisabled("Umfrage starten"))
                assertTrue(el.reason().contains("Option 2 gibt es schon."))
            }
        }

    @Test
    fun theDeadline_noneShowsTheHint_customShowsADateField(): Promise<Unit> =
        formTest {
            withForm(PollWorld(), "poll-form-deadline") { el, _, _ ->
                el.chooseIn("Frist", "none")
                assertTrue(
                    el.allOf("div").any {
                        it.textContent?.trim() == "Sie müssen die Umfrage dann selbst schließen." &&
                            it.offsetParent != null
                    },
                )
                el.chooseIn("Frist", "custom")
                val custom = el.controlOf("Datum und Uhrzeit der Frist") as HTMLInputElement
                assertEquals("datetime-local", custom.type)
                assertTrue(custom.offsetParent != null, "the date field is visible")
                el.fillValid()
                assertTrue(el.isButtonDisabled("Umfrage starten"), "a custom deadline needs a date")
                assertTrue(el.reason().contains("Bitte geben Sie für die Frist ein gültiges Datum mit Uhrzeit an."))
                custom.value = "2020-01-01T10:00"
                custom.dispatchEvent(Event("input"))
                assertTrue(el.reason().contains("Die Frist muss mindestens 15 Minuten in der Zukunft liegen."))
            }
        }

    @Test
    fun theStart_sendsTheDraft_withASevenDayDeadline_andHandsTheCreatedPollOver(): Promise<Unit> =
        formTest {
            val created = pollDto(id = "p-new")
            val done = mutableListOf<PollDto?>()
            withForm(PollWorld(created = created), "poll-form-send", done) { el, calls, routes ->
                el.fillValid("Sommerfest im Juli?")
                el.typeInto("Beschreibung", "Mehr dazu am Abend.")
                el.buttonNamed("Umfrage starten").click()
                awaitUntil("created", 3000) { done.size == 1 }
                assertEquals("p-new", done.single()?.id)
                val input = calls.singleCall(routes.create).rpcParam(0)
                assertEquals("Sommerfest im Juli?", input.question as String)
                assertEquals("Mehr dazu am Abend.", input.description as String)
                assertEquals(listOf("Ja", "Nein"), (input.options as Array<String>).toList())
                val closesAt = LocalDateTime.parse(input.closesAt as String)
                assertNotNull(closesAt)
            }
        }

    @Test
    fun noDeadline_sendsNone_andAnEmptyDescriptionIsNull(): Promise<Unit> =
        formTest {
            val done = mutableListOf<PollDto?>()
            withForm(PollWorld(created = pollDto(id = "p-new")), "poll-form-none", done) { el, calls, routes ->
                el.fillValid()
                el.chooseIn("Frist", "none")
                el.buttonNamed("Umfrage starten").click()
                awaitUntil("created", 3000) { done.size == 1 }
                val input = calls.singleCall(routes.create).rpcParam(0)
                assertNull(input.closesAt)
                assertNull(input.description)
            }
        }

    @Test
    fun aDoubleClickOnTheStartButton_createsExactlyOnePoll(): Promise<Unit> =
        formTest {
            val done = mutableListOf<PollDto?>()
            withForm(PollWorld(created = pollDto(id = "p-new"), createDelayMs = 250), "poll-form-double", done) { el, calls, routes ->
                el.fillValid()
                val start = el.buttonNamed("Umfrage starten")
                start.click()
                start.click()
                start.click()
                awaitUntil("created", 3000) { done.size == 1 }
                assertEquals(1, calls.toRoute(routes.create).size, "three clicks, one poll")
            }
        }

    @Test
    fun aConflict_showsTheFixedText_neverTheServers(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = PollWorld()
            world.failures[routes.create] = CONFLICT_EXCEPTION
            val shown = mutableListOf<String>()
            val original = pollErrorSink
            pollErrorSink = { shown += it }
            try {
                withForm(world, "poll-form-conflict") { el, calls, _ ->
                    el.fillValid()
                    el.buttonNamed("Umfrage starten").click()
                    awaitUntil("error shown", 3000) { shown.isNotEmpty() }
                    assertEquals(
                        listOf(
                            "Zurzeit können Sie keine weitere Umfrage starten – zu viele sind offen oder wurden kürzlich gestartet. " +
                                "Bitte versuchen Sie es später erneut.",
                        ),
                        shown,
                    )
                    assertFalse(shown.single().contains("simulated"))
                    awaitUntil("button usable again", 2000) { !el.isButtonDisabled("Umfrage starten") }
                    assertEquals(1, calls.toRoute(routes.create).size)
                }
            } finally {
                pollErrorSink = original
            }
        }

    @Test
    fun aBadRequest_pointsAtTheDetails_andCancelHandsOverNothing(): Promise<Unit> =
        formTest {
            val routes = pollRoutes()
            val world = PollWorld()
            world.failures[routes.create] = "network.lapis.cloud.shared.rpc.BadRequestException"
            val shown = mutableListOf<String>()
            val original = pollErrorSink
            pollErrorSink = { shown += it }
            val done = mutableListOf<PollDto?>()
            try {
                withForm(world, "poll-form-bad", done) { el, _, _ ->
                    el.fillValid()
                    el.buttonNamed("Umfrage starten").click()
                    awaitUntil("error shown", 3000) { shown.isNotEmpty() }
                    assertEquals(listOf("Bitte prüfen Sie die Angaben, insbesondere die Frist."), shown)
                    el.buttonNamed("Abbrechen").click()
                    assertEquals(listOf<PollDto?>(null), done)
                }
            } finally {
                pollErrorSink = original
            }
        }
}
