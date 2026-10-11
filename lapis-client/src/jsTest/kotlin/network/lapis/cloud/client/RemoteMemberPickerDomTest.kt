package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.MemberPickDto
import network.lapis.cloud.shared.domain.MemberPickPageDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.101 [RemoteMemberPicker] in a real mounted root: the minimum query length, the debounce, the paging button, the keyboard
 * contract and ARIA, discarding of stale answers, untrusted names, `initial`, and that the picker only ever holds an offered id.
 * The search function is a recording lambda here; the RPC wiring ([committeeCandidateSearch]) is covered by the screen tests.
 */
class RemoteMemberPickerDomTest {
    private class Recorder(
        private val pageOf: (query: String, page: Int) -> MemberPickPageDto,
        private val delayFor: (query: String) -> Long = { 0L },
    ) {
        val calls = mutableListOf<Pair<String, Int>>()

        val search: suspend (String, Int) -> MemberPickPageDto? = { query, page ->
            calls += query to page
            val wait = delayFor(query)
            if (wait > 0) delay(wait)
            pageOf(query, page)
        }
    }

    private fun pick(n: Int) = MemberPickDto(id = "m$n", displayName = "Person ${n.toString().padStart(2, '0')}")

    private fun HTMLElement.combo(): HTMLInputElement =
        assertNotNull(querySelector("input[role=combobox]") as? HTMLInputElement, "no combobox")

    private fun HTMLElement.optionLabels(): List<String> = allOf("[role=option]").map { it.textContent.orEmpty().trim() }

    private fun HTMLElement.moreButton(): HTMLElement? = querySelector(".lapis-ssel-more") as? HTMLElement

    private fun HTMLElement?.isShown(): Boolean = this != null && this.offsetParent != null

    private fun type(
        input: HTMLInputElement,
        text: String,
    ) {
        input.focus()
        input.value = text
        input.dispatchEvent(Event("input"))
    }

    private fun press(
        input: HTMLInputElement,
        key: String,
    ): Boolean {
        val event = KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true))
        input.dispatchEvent(event)
        return event.defaultPrevented
    }

    private fun Root.picker(
        recorder: Recorder,
        initial: MemberPickDto? = null,
    ): RemoteMemberPicker {
        val control = RemoteMemberPicker(search = recorder.search, initial = initial, label = "Mitglied", debounceMs = 0)
        add(control)
        return control
    }

    @Test
    fun belowTwoCharacters_noSearchIsSent_andTheHintSaysSo(): Promise<Unit> =
        formTest {
            val recorder = Recorder({ _, _ -> MemberPickPageDto(listOf(pick(1)), hasMore = false) })
            mountedForm("rmp-short") { root, element ->
                root.picker(recorder)
                type(element().combo(), "a")
                delay(80)
                assertTrue(recorder.calls.isEmpty(), "one character must not reach the server")
                assertTrue(element().textContent.orEmpty().contains("Mindestens 2 Zeichen eingeben."), "the hint is shown")
                assertEquals(emptyList(), element().optionLabels())
            }
        }

    @Test
    fun typing_sendsOneSearchAfterTheDebounce_withTheLastQuery(): Promise<Unit> =
        formTest {
            val recorder = Recorder({ _, _ -> MemberPickPageDto(listOf(pick(1), pick(2)), hasMore = false) })
            mountedForm("rmp-debounce") { root, element ->
                val control = RemoteMemberPicker(search = recorder.search, label = "Mitglied", debounceMs = 120)
                root.add(control)
                type(element().combo(), "an")
                delay(30)
                type(element().combo(), "ann")
                delay(30)
                type(element().combo(), "anna")
                assertTrue(recorder.calls.isEmpty(), "nothing is sent while the person is still typing")
                awaitUntil("the debounced search") { recorder.calls.isNotEmpty() }
                delay(200)
                assertEquals(listOf("anna" to 0), recorder.calls, "exactly one request, with the last text, page 0")
                awaitUntil("the options") { element().optionLabels().size == 2 }
            }
        }

    @Test
    fun theMoreButton_loadsTheNextPage_appends_andDisappearsWithoutHasMore(): Promise<Unit> =
        formTest {
            val recorder =
                Recorder({ _, page ->
                    when (page) {
                        0 -> MemberPickPageDto((1..3).map { pick(it) }, hasMore = true)
                        else -> MemberPickPageDto((4..5).map { pick(it) }, hasMore = false)
                    }
                })
            mountedForm("rmp-more") { root, element ->
                root.picker(recorder)
                type(element().combo(), "pers")
                awaitUntil("page 0") { element().optionLabels().size == 3 }
                val more = assertNotNull(element().moreButton(), "hasMore => a button")
                assertTrue(more.isShown())
                assertTrue(more.textContent.orEmpty().contains("Weitere anzeigen"))
                more.click()
                awaitUntil("page 1 appended") { element().optionLabels().size == 5 }
                assertEquals(listOf("pers" to 0, "pers" to 1), recorder.calls)
                assertEquals((1..5).map { "Person ${it.toString().padStart(2, '0')}" }, element().optionLabels())
                assertFalse(element().moreButton().isShown(), "no more pages: no button")
                assertTrue(element().textContent.orEmpty().contains("5 Treffer angezeigt"), "the live region counts what is shown")
            }
        }

    @Test
    fun atPageNine_theButtonIsGone_andTheHintAsksToRefine(): Promise<Unit> =
        formTest {
            val recorder = Recorder({ _, page -> MemberPickPageDto(listOf(pick(page + 1)), hasMore = true) })
            mountedForm("rmp-cap") { root, element ->
                root.picker(recorder)
                type(element().combo(), "pers")
                awaitUntil("page 0") { element().optionLabels().size == 1 }
                for (expected in 2..10) {
                    element().moreButton()!!.click()
                    awaitUntil("page ${expected - 1}") { element().optionLabels().size == expected }
                }
                assertEquals((0..9).map { "pers" to it }, recorder.calls, "pages 0..9 and never a page 10")
                assertFalse(element().moreButton().isShown(), "the 10th page was the last")
                assertTrue(element().textContent.orEmpty().contains("Bitte Suche verfeinern."))
            }
        }

    @Test
    fun keyboard_arrowDownEnterPicks_escapeCloses_andAriaFollows(): Promise<Unit> =
        formTest {
            val recorder = Recorder({ _, _ -> MemberPickPageDto((1..3).map { pick(it) }, hasMore = false) })
            mountedForm("rmp-keys") { root, element ->
                val control = root.picker(recorder)
                val input = element().combo()
                type(input, "pers")
                awaitUntil("options") { element().optionLabels().size == 3 }
                assertEquals("true", input.getAttribute("aria-expanded"))
                assertEquals("list", input.getAttribute("aria-autocomplete"))
                assertFalse(input.getAttribute("aria-controls").isNullOrBlank())
                assertEquals("option", element().allOf("[role=option]").first().getAttribute("role"))
                assertEquals("listbox", element().querySelector("[role=listbox]")?.getAttribute("role"))
                val firstId = element().allOf("[role=option]").first().id
                assertEquals(firstId, input.getAttribute("aria-activedescendant"), "the first match is active")
                assertTrue(press(input, "ArrowDown"))
                assertEquals(element().allOf("[role=option]")[1].id, input.getAttribute("aria-activedescendant"))
                assertTrue(press(input, "Enter"))
                assertEquals("m2", control.value, "Enter picks the active entry")
                assertEquals("Person 02", control.selectedPick?.displayName)
                assertEquals("Person 02", input.value)
                assertEquals("false", input.getAttribute("aria-expanded"))

                type(input, "pers")
                awaitUntil("options again") { element().optionLabels().size == 3 }
                assertTrue(press(input, "Escape"), "Esc closes the list")
                assertEquals("false", input.getAttribute("aria-expanded"))
                assertEquals("m2", control.value, "Esc does not change the choice")
            }
        }

    @Test
    fun anAnswerThatArrivesAfterANewerQuery_isDiscarded(): Promise<Unit> =
        formTest {
            val recorder =
                Recorder(
                    pageOf = { query, _ ->
                        MemberPickPageDto(listOf(MemberPickDto("id-$query", "Treffer $query")), hasMore = false)
                    },
                    delayFor = { query -> if (query == "ab") 250L else 0L },
                )
            mountedForm("rmp-stale") { root, element ->
                root.picker(recorder)
                type(element().combo(), "ab")
                awaitUntil("the slow request left") { recorder.calls.size == 1 }
                type(element().combo(), "abc")
                awaitUntil("the fast answer") { element().optionLabels() == listOf("Treffer abc") }
                delay(400)
                assertEquals(listOf("Treffer abc"), element().optionLabels(), "the late answer of the older query must not replace it")
            }
        }

    @Test
    fun anAnswerOfNull_showsNothing_andDoesNotBreak(): Promise<Unit> =
        formTest {
            val search: suspend (String, Int) -> MemberPickPageDto? = { _, _ -> null }
            mountedForm("rmp-null") { root, element ->
                root.add(RemoteMemberPicker(search = search, label = "Mitglied", debounceMs = 0))
                type(element().combo(), "pe")
                delay(120)
                assertEquals(emptyList(), element().optionLabels(), "a failed search (rate limit, no right) offers nothing")
                assertFalse(element().moreButton().isShown())
            }
        }

    @Test
    fun maliciousNames_areShownAsPlainText_neverAsMarkupOrAMarker(): Promise<Unit> =
        formTest {
            val evil = "${KV_I18N_MARKER}Kurs<b>fett</b>"
            val recorder = Recorder({ _, _ -> MemberPickPageDto(listOf(MemberPickDto("evil", evil)), hasMore = false) })
            mountedForm("rmp-evil") { root, element ->
                root.picker(recorder)
                type(element().combo(), "ku")
                awaitUntil("the option") { element().optionLabels().size == 1 }
                assertFalse(element().innerHTML.contains(KV_I18N_MARKER), "a forged marker must be neutralized")
                assertEquals(null, element().querySelector("[role=option] b"), "no markup from a name")
            }
        }

    @Test
    fun initial_showsTheName_andOnlyAnOfferedIdIsAValidValue(): Promise<Unit> =
        formTest {
            val recorder = Recorder({ _, _ -> MemberPickPageDto(emptyList(), hasMore = false) })
            mountedForm("rmp-initial") { root, element ->
                val control = root.picker(recorder, initial = MemberPickDto("m9", "Vorbelegt Vera"))
                assertEquals("m9", control.value)
                assertEquals("Vorbelegt Vera", element().combo().value)
                control.value = "never-offered"
                assertEquals("", control.value, "an id nobody offered clears the field instead of being kept")
                control.offer(MemberPickDto("m3", "Angeboten Anton"))
                control.value = "m3"
                assertEquals("m3", control.value)
                assertEquals("Angeboten Anton", element().combo().value)
                assertTrue(recorder.calls.isEmpty(), "pre-filling never searches")
            }
        }

    @Test
    fun leavingTheField_restoresTheChoice_andDropsTheQuery(): Promise<Unit> =
        formTest {
            val recorder = Recorder({ _, _ -> MemberPickPageDto(listOf(pick(1)), hasMore = false) })
            mountedForm("rmp-blur") { root, element ->
                val control = root.picker(recorder, initial = MemberPickDto("m9", "Vorbelegt Vera"))
                val input = element().combo()
                type(input, "xyz")
                awaitUntil("options") { element().optionLabels().size == 1 }
                input.dispatchEvent(Event("blur"))
                assertEquals("Vorbelegt Vera", input.value)
                assertEquals("m9", control.value)
            }
        }

    @Test
    fun setUnavailable_disablesThePicker_andShowsTheNoteAsPlaceholder(): Promise<Unit> =
        formTest {
            val recorder = Recorder({ _, _ -> MemberPickPageDto(emptyList(), hasMore = false) })
            mountedForm("rmp-off") { root, element ->
                val control = root.picker(recorder)
                control.setUnavailable("Bitte zuerst ein Gremium wählen.")
                val input = element().combo()
                assertTrue(input.disabled)
                assertEquals("Bitte zuerst ein Gremium wählen.", input.placeholder)
                control.setUnavailable(null)
                assertFalse(input.disabled)
            }
        }
}
