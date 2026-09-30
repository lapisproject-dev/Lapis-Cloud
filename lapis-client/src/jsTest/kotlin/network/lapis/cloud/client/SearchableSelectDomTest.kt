package network.lapis.cloud.client

import io.kvision.modal.Modal
import io.kvision.panel.Root
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import org.w3c.dom.EventInit
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [SearchableSelect] in a real mounted root (Karma/Chrome): filtering, keyboard contract, ARIA, form wiring, untrusted text. */
class SearchableSelectDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private val members =
        listOf("m1" to "Anna Berg", "m2" to "Hans Müller", "m3" to "Johanna Klein", "m4" to "Karl Roth")

    private fun HTMLElement.combo(): HTMLInputElement =
        assertNotNull(querySelector("input[role=combobox]") as? HTMLInputElement, "no combobox")

    private fun HTMLElement.options(): List<HTMLElement> = allOf("[role=option]")

    private fun HTMLElement.optionLabels(): List<String> = options().map { it.textContent.orEmpty().trim() }

    private fun HTMLElement.dropdownVisible(): Boolean = querySelector(".lapis-ssel-dropdown") != null

    private fun type(
        input: HTMLInputElement,
        text: String,
    ) {
        input.value = text
        input.dispatchEvent(Event("input"))
    }

    /** Dispatches a keydown and answers whether the page default was prevented. */
    private fun press(
        input: HTMLInputElement,
        key: String,
        alt: Boolean = false,
    ): Boolean {
        val event = KeyboardEvent("keydown", KeyboardEventInit(key = key, altKey = alt, bubbles = true, cancelable = true))
        input.dispatchEvent(event)
        return event.defaultPrevented
    }

    private fun blur(input: HTMLInputElement) {
        input.dispatchEvent(Event("blur"))
    }

    @Test
    fun typing_filtersTheList_andLeavesValueUntouched() =
        test {
            withMountedRoot("ssel-filter") { root, element ->
                val select = root.searchableSelect(options = members, label = "Mitglied")
                val input = element().combo()
                assertFalse(element().dropdownVisible(), "the list must not open by itself")
                type(input, "muller")
                assertEquals(listOf("Hans Müller"), element().optionLabels())
                assertNull(select.value, "typing alone must never select")
                type(input, "anna")
                assertEquals(listOf("Anna Berg", "Johanna Klein"), element().optionLabels())
            }
        }

    @Test
    fun noMatch_showsTheEmptyText_andNoOption() =
        test {
            withMountedRoot("ssel-nomatch") { root, element ->
                root.searchableSelect(options = members, label = "Mitglied")
                type(element().combo(), "zzz")
                assertTrue(element().options().isEmpty())
                assertTrue(
                    element()
                        .querySelector(".lapis-ssel-hint")
                        ?.textContent
                        .orEmpty()
                        .contains("zzz"),
                )
            }
        }

    @Test
    fun moreThanFiftyMatches_renderFifty_andSayHowManyMatch() =
        test {
            withMountedRoot("ssel-limit") { root, element ->
                root.searchableSelect(options = (1..120).map { "id$it" to "Mitglied $it" }, label = "Mitglied")
                type(element().combo(), "mitglied")
                assertEquals(50, element().options().size)
                val hint = element().querySelector(".lapis-ssel-hint")?.textContent.orEmpty()
                assertTrue(hint.contains("50") && hint.contains("120"), hint)
            }
        }

    @Test
    fun arrows_doNotWrap_enterPicks_subscribeFires() =
        test {
            withMountedRoot("ssel-keys") { root, element ->
                val select = root.searchableSelect(options = members, label = "Mitglied")
                val seen = mutableListOf<String?>()
                select.subscribe { seen += it }
                assertEquals(listOf<String?>(null), seen, "subscribe reports the current value at once, like KVision's own")
                val input = element().combo()
                assertTrue(press(input, "ArrowDown"), "ArrowDown opens the list")
                assertTrue(element().dropdownVisible())
                assertEquals("true", input.getAttribute("aria-expanded"))
                press(input, "ArrowDown")
                press(input, "ArrowDown")
                press(input, "ArrowDown")
                press(input, "ArrowDown")
                press(input, "ArrowDown") // past the last entry: no wrap-around
                assertEquals("m4", activeValue(element()))
                press(input, "ArrowUp")
                assertEquals("m3", activeValue(element()))
                press(input, "Enter")
                assertEquals("m3", select.value)
                assertEquals("Johanna Klein", input.value)
                assertEquals(listOf<String?>(null, "m3"), seen)
                assertFalse(element().dropdownVisible())
                assertEquals("false", input.getAttribute("aria-expanded"))
            }
        }

    private fun activeValue(element: HTMLElement): String? = element.querySelector(".lapis-ssel-option--active")?.getAttribute("data-value")

    @Test
    fun firstMatch_isActive_afterTyping_andEnterPicksIt() =
        test {
            withMountedRoot("ssel-first") { root, element ->
                val select = root.searchableSelect(options = members, label = "Mitglied")
                val input = element().combo()
                type(input, "roth")
                assertEquals("m4", activeValue(element()))
                assertEquals(element().options().single().id, input.getAttribute("aria-activedescendant"))
                press(input, "Enter")
                assertEquals("m4", select.value)
            }
        }

    @Test
    fun enter_withoutAnyMatch_selectsNothing() =
        test {
            withMountedRoot("ssel-enter-empty") { root, element ->
                val select = root.searchableSelect(options = members, value = "m1", label = "Mitglied")
                val input = element().combo()
                type(input, "zzz")
                press(input, "Enter")
                assertEquals("m1", select.value, "Enter without a match must not change the selection")
            }
        }

    @Test
    fun escape_closesFirst_thenRestoresTheSelection() =
        test {
            withMountedRoot("ssel-escape") { root, element ->
                val select = root.searchableSelect(options = members, value = "m2", label = "Mitglied")
                val input = element().combo()
                assertEquals("Hans Müller", input.value)
                type(input, "kar")
                assertTrue(element().dropdownVisible())
                assertTrue(press(input, "Escape"))
                assertFalse(element().dropdownVisible())
                assertEquals("kar", input.value, "the first Esc only closes")
                assertTrue(press(input, "Escape"))
                assertEquals("Hans Müller", input.value, "the second Esc restores the selection")
                assertEquals("m2", select.value)
                assertFalse(press(input, "Escape"), "with nothing left to undo Esc must reach the surrounding modal")
            }
        }

    @Test
    fun tab_picksOnlyAnEntryTheUserArrowedTo() =
        test {
            withMountedRoot("ssel-tab") { root, element ->
                val select = root.searchableSelect(options = members, label = "Mitglied")
                val input = element().combo()
                type(input, "anna")
                press(input, "Tab")
                assertNull(select.value, "Tab without arrowing must not take the first match")
                assertEquals("", input.value)
                type(input, "anna")
                press(input, "ArrowDown")
                press(input, "Tab")
                assertEquals("m3", select.value)
            }
        }

    @Test
    fun blur_restoresThePreviousSelection() =
        test {
            withMountedRoot("ssel-blur") { root, element ->
                val select = root.searchableSelect(options = members, value = "m1", label = "Mitglied")
                val input = element().combo()
                type(input, "hans")
                blur(input)
                assertEquals("m1", select.value)
                assertEquals("Anna Berg", input.value)
                assertFalse(element().dropdownVisible())
            }
        }

    @Test
    fun clickingAnOption_picks_andNeverBlursTheField() =
        test {
            withMountedRoot("ssel-click") { root, element ->
                val select = root.searchableSelect(options = members, label = "Mitglied")
                val input = element().combo()
                input.click()
                assertEquals(4, element().options().size)
                val mouseDown = Event("mousedown", EventInit(bubbles = true, cancelable = true))
                element().options()[1].dispatchEvent(mouseDown)
                assertTrue(mouseDown.defaultPrevented, "mousedown on an entry must be swallowed so the input keeps the focus")
                element().options()[1].click()
                assertEquals("m2", select.value)
            }
        }

    @Test
    fun ariaContract_isSetOnTheInputAndTheList() =
        test {
            withMountedRoot("ssel-aria") { root, element ->
                root.searchableSelect(options = members, label = "Mitglied")
                val input = element().combo()
                assertEquals("combobox", input.getAttribute("role"))
                assertEquals("list", input.getAttribute("aria-autocomplete"))
                assertEquals("false", input.getAttribute("aria-expanded"))
                val listId = assertNotNull(input.getAttribute("aria-controls"))
                input.click()
                val listbox = assertNotNull(document.getElementById(listId), "aria-controls must point at the listbox")
                assertEquals("listbox", listbox.getAttribute("role"))
                assertTrue(element().options().all { it.getAttribute("role") == "option" && it.id.isNotEmpty() })
                assertTrue(element().options().all { it.getAttribute("aria-selected") != null })
                val status = assertNotNull(element().querySelector("[role=status]"), "live region")
                assertEquals("polite", status.getAttribute("aria-live"))
                assertTrue(status.textContent.orEmpty().contains("4"), "the live region announces the match count")
            }
        }

    @Test
    fun selectedOption_isMarked_withAriaSelected() =
        test {
            withMountedRoot("ssel-selected") { root, element ->
                root.searchableSelect(options = members, value = "m3", label = "Mitglied")
                element().combo().click()
                val marked = element().options().filter { it.getAttribute("aria-selected") == "true" }
                assertEquals(listOf("m3"), marked.map { it.getAttribute("data-value") })
                assertEquals("m3", activeValue(element()), "the chosen entry is the active one when the list opens")
            }
        }

    @Test
    fun optionsArrivingLater_keepAHeldValue_andShowItsName() =
        test {
            withMountedRoot("ssel-async") { root, element ->
                val select = root.searchableSelect(options = emptyList(), value = "m2", label = "Mitglied")
                assertEquals("m2", select.value)
                assertEquals("", element().combo().value)
                select.options = members
                assertEquals("m2", select.value, "a held value must survive the options arriving")
                assertEquals("Hans Müller", element().combo().value)
            }
        }

    @Test
    fun noneEntry_isPinnedOnTop_andOnlyForAnEmptyQuery() =
        test {
            withMountedRoot("ssel-none") { root, element ->
                val select = root.searchableSelect(options = members, value = "m1", label = "Mitglied", noneOption = "-- keine --")
                val input = element().combo()
                input.click()
                assertEquals(listOf("-- keine --", "Anna Berg", "Hans Müller", "Johanna Klein", "Karl Roth"), element().optionLabels())
                type(input, "anna")
                assertEquals(listOf("Anna Berg", "Johanna Klein"), element().optionLabels())
                type(input, "")
                element().options().first().click()
                assertEquals("", select.value)
                assertEquals("-- keine --", input.getAttribute("placeholder"), "the none entry shows as the placeholder")
            }
        }

    @Test
    fun disabled_doesNotOpen() =
        test {
            withMountedRoot("ssel-disabled") { root, element ->
                val select = root.searchableSelect(options = members, label = "Mitglied")
                select.disabled = true
                val input = element().combo()
                input.click()
                press(input, "ArrowDown")
                assertFalse(element().dropdownVisible())
            }
        }

    @Test
    fun untrustedNames_areShownAsPlainText_neverAsMarkupOrAMarker() =
        test {
            withMountedRoot("ssel-untrusted") { root, element ->
                val evil = "${KV_I18N_MARKER}Kurs<b>fett</b>"
                val select =
                    root.searchableSelect(options = listOf("x" to evil, "y" to "<img src=x onerror=alert(1)>"), label = "Mitglied")
                val input = element().combo()
                input.click()
                val labels = element().optionLabels()
                assertEquals(listOf("Kurs<b>fett</b>", "<img src=x onerror=alert(1)>"), labels)
                assertNull(element().querySelector(".lapis-ssel-list b"))
                assertNull(element().querySelector(".lapis-ssel-list img"))
                assertFalse(element().innerHTML.contains(KV_I18N_MARKER))
                element().options().first().click()
                assertEquals("x", select.value)
                assertEquals("Kurs<b>fett</b>", input.value)
            }
        }

    @Test
    fun openingNearTheBottomOfTheViewport_opensUpwards() =
        test {
            withMountedRoot("ssel-up") { root, element ->
                root.searchableSelect(options = members, label = "Mitglied")
                element().style.position = "fixed"
                element().style.bottom = "0"
                element().style.left = "0"
                element().combo().click()
                assertNotNull(element().querySelector(".lapis-ssel-layer--up"), "not enough room below: the list opens upwards")
            }
        }

    @Test
    fun plentyOfRoom_opensDownwards() =
        test {
            withMountedRoot("ssel-down") { root, element ->
                root.searchableSelect(options = members, label = "Mitglied")
                element().style.position = "fixed"
                element().style.top = "0"
                element().style.left = "0"
                element().combo().click()
                assertNull(element().querySelector(".lapis-ssel-layer--up"))
                assertTrue(window.innerHeight > 300)
            }
        }

    // ---- form grammar wiring -----------------------------------------------------------------------------------------

    private fun requiredForm(root: Root): Pair<LapisForm, LapisField> {
        val form = root.lapisForm()
        val field =
            form.searchableSelectField(
                label = "Mitglied",
                options = members,
                required = true,
                requiredMessage = "Bitte ein Mitglied auswählen.",
            )
        form.finish()
        return form to field
    }

    @Test
    fun requiredField_reportsOnBlur_onlyAfterTheUserTouchedIt() =
        test {
            withMountedRoot("ssel-form-required") { root, element ->
                val (_, field) = requiredForm(root)
                val input = element().combo()
                assertEquals("true", input.getAttribute("aria-required"))
                blur(input)
                assertTrue(element().shownErrors().isEmpty(), "an untouched field stays quiet")
                type(input, "zzz")
                blur(input)
                assertEquals(listOf("Bitte ein Mitglied auswählen."), element().shownErrors())
                assertEquals("true", input.getAttribute("aria-invalid"))
                assertEquals("", field.value)
            }
        }

    @Test
    fun aChoice_clearsTheError_andMarksTheFieldDirty() =
        test {
            withMountedRoot("ssel-form-choose") { root, element ->
                val (form, field) = requiredForm(root)
                assertFalse(form.validateAndReport())
                assertEquals(1, element().shownErrors().size)
                chooseInCombobox(element().combo(), "m2")
                assertEquals("m2", field.value)
                assertTrue(field.dirty)
                assertTrue(element().shownErrors().isEmpty(), "choosing a valid entry clears the shown error")
                assertTrue(form.validateAndReport())
            }
        }

    @Test
    fun clickInTheList_isNotABlur_soNoErrorAppearsMidChoice() =
        test {
            withMountedRoot("ssel-form-midchoice") { root, element ->
                requiredForm(root)
                val input = element().combo()
                input.click()
                element().options()[0].dispatchEvent(Event("mousedown"))
                assertTrue(element().shownErrors().isEmpty())
            }
        }

    @Test
    fun setValue_andReset_workThroughTheField() =
        test {
            withMountedRoot("ssel-form-setvalue") { root, element ->
                val (_, field) = requiredForm(root)
                field.setValue("m4")
                assertEquals("m4", field.value)
                assertEquals("Karl Roth", element().combo().value)
                field.reset()
                assertEquals("", field.value)
                assertEquals("", element().combo().value)
                val seen = mutableListOf<String>()
                field.subscribe { seen += it }
                field.setValue("m1")
                assertEquals(listOf("", "m1"), seen)
            }
        }

    @Test
    fun hintAndErrorSlot_stayInsideTheWrapper_belowTheInput() =
        test {
            withMountedRoot("ssel-form-slots") { root, element ->
                val form = root.lapisForm()
                form.searchableSelectField(label = "Mitglied", options = members, hint = "Pflichtangabe", required = true)
                form.finish()
                val wrapper = assertNotNull(element().querySelector(".lapis-ssel") as? HTMLElement)
                assertNotNull(wrapper.querySelector(".form-text"))
                assertNotNull(wrapper.querySelector(".invalid-feedback.lapis-field-error"))
                val describedBy =
                    element()
                        .combo()
                        .getAttribute("aria-describedby")
                        .orEmpty()
                        .split(" ")
                assertEquals(2, describedBy.size)
                assertTrue(describedBy.all { document.getElementById(it) != null })
            }
        }

    @Test
    fun aChoice_firesARealBubblingChangeEvent_forCrossFieldWatchers() =
        test {
            withMountedRoot("ssel-form-change") { root, element ->
                val select = root.searchableSelect(options = members, label = "Mitglied")
                var changes = 0
                element().combo().addEventListener("change", { changes++ })
                chooseInCombobox(element().combo(), "m1")
                assertEquals("m1", select.value)
                assertEquals(1, changes, "a choice from the list is not typing: the change event must be raised explicitly")
            }
        }

    // ---- inside a Bootstrap modal ---------------------------------------------------------------------------------------

    @Test
    fun inAModal_theListIsNotClippedByAnAncestor_andStacksAboveTheModal() =
        test {
            withMountedRoot("ssel-modal") { _, _ ->
                disableModalTransitions()
                val modal = Modal(caption = "Auswahl")
                val select = modal.searchableSelect(options = members, label = "Mitglied")
                modal.show()
                try {
                    awaitUntil("the modal is shown", timeoutMs = 1500) { document.querySelector(".modal.show") != null }
                    val modalElement = lastOpenModal()
                    val input = modalElement.combo()
                    input.click()
                    val dropdown = assertNotNull(modalElement.querySelector(".lapis-ssel-dropdown") as? HTMLElement)
                    assertTrue(dropdown.getBoundingClientRect().height > 0, "the list has a box")
                    var ancestor: HTMLElement? = dropdown.parentElement as? HTMLElement
                    while (ancestor != null && ancestor !== modalElement) {
                        val overflow = window.getComputedStyle(ancestor).overflowY
                        assertTrue(
                            overflow == "visible",
                            "ancestor <${ancestor.tagName} class=${ancestor.className}> would clip the list: $overflow",
                        )
                        ancestor = ancestor.parentElement as? HTMLElement
                    }
                    val z = window.getComputedStyle(dropdown).zIndex.toIntOrNull()
                    // theme.css is not loaded in Karma; the stylesheet rule itself is pinned by the tripwire test, so accept both.
                    assertTrue(z == null || z >= 1056, "z-index $z must stack above the modal (1055)")
                    press(input, "ArrowDown")
                    press(input, "Enter")
                    assertEquals("m2", select.value)
                } finally {
                    closeOpenModals()
                }
            }
        }
}
