package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.CountryCodes
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.121 -- the nationality chips: remove buttons with their own names, replacement rules announced, the cap of ten, Backspace safety. */
class NationalityMultiSelectDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { withFakeCountryNames(block) }

    private fun HTMLElement.chips(): List<HTMLElement> = allOf(".lapis-nationality-chips .lapis-chip")

    private fun HTMLElement.chipNames(): List<String> = chips().map { it.textContent.orEmpty().trim() }

    private fun HTMLElement.combo(): HTMLInputElement = assertNotNull(querySelector("input[role=combobox]") as? HTMLInputElement)

    private fun HTMLElement.live(): String = querySelector(".lapis-nationality-live")?.textContent.orEmpty().trim()

    private suspend fun HTMLElement.awaitLive(expected: String) = awaitUntil("live region says: $expected") { live() == expected }

    private fun HTMLElement.pick(code: String) = chooseInCombobox(combo(), code)

    @Test
    fun chips_showFlagAndName_sorted_andEveryRemoveButtonNamesItsCountry() =
        test {
            withMountedRoot("nat-chips") { root, element ->
                val widget = root.nationalityMultiSelect(label = "Staatsangehörigkeit", initial = listOf("PL", "DE"))
                assertEquals(listOf("DE", "PL"), widget.codes)
                assertEquals(listOf("Deutschland", "Polen"), element().chipNames())
                element().chips().forEach { chip ->
                    val flag = assertNotNull(chip.querySelector("img.lapis-flag"))
                    assertEquals("", flag.getAttribute("alt"))
                    assertEquals("true", flag.getAttribute("aria-hidden"))
                }
                val labels = element().allOf(".lapis-chip-remove").map { it.getAttribute("aria-label") }
                assertEquals(listOf("Staatsangehörigkeit „Deutschland“ entfernen", "Staatsangehörigkeit „Polen“ entfernen"), labels)
                // the group is named by its label, and the label points at the field
                val group = assertNotNull(element().querySelector("[role=group]"))
                assertEquals(
                    "Staatsangehörigkeit",
                    element().querySelector("#${group.getAttribute("aria-labelledby")}")?.textContent?.trim(),
                )
            }
        }

    @Test
    fun removeButton_removesThatChip_andAnnouncesIt() =
        test {
            withMountedRoot("nat-remove") { root, element ->
                val widget = root.nationalityMultiSelect(label = "Staatsangehörigkeit", initial = listOf("DE", "PL"))
                val seen = mutableListOf<List<String>>()
                widget.subscribe { seen += it }
                (element().allOf(".lapis-chip-remove")[0] as HTMLButtonElement).click()
                assertEquals(listOf("PL"), widget.codes)
                assertEquals(listOf("Polen"), element().chipNames())
                element().awaitLive("Deutschland entfernt.")
                assertEquals(listOf(listOf("DE", "PL"), listOf("PL")), seen)
            }
        }

    @Test
    fun addingCountries_extendsTheList_andTheChosenOnesLeaveTheOfferedOptions() =
        test {
            withMountedRoot("nat-add") { root, element ->
                val widget = root.nationalityMultiSelect(label = "Staatsangehörigkeit", initial = listOf("DE"))
                element().pick("PL")
                assertEquals(listOf("DE", "PL"), widget.codes)
                element().awaitLive("Polen hinzugefügt.")
                element().combo().click()
                val offered = element().allOf("[role=option]").map { it.getAttribute("data-value").orEmpty() }
                assertFalse("DE" in offered || "PL" in offered, "already chosen countries are not offered again")
                assertTrue("AT" in offered)
            }
        }

    @Test
    fun backspaceInTheSearchField_neverRemovesAChip() =
        test {
            withMountedRoot("nat-backspace") { root, element ->
                val widget = root.nationalityMultiSelect(label = "Staatsangehörigkeit", initial = listOf("DE", "PL"))
                val input = element().combo()
                input.focus()
                repeat(
                    3,
                ) { input.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Backspace", bubbles = true, cancelable = true))) }
                assertEquals(listOf("DE", "PL"), widget.codes)
                assertEquals(2, element().chips().size)
            }
        }

    @Test
    fun stateless_replacesEverything_andACountryReplacesTheSpecialValue_bothAnnounced() =
        test {
            withMountedRoot("nat-special") { root, element ->
                val widget = root.nationalityMultiSelect(label = "Staatsangehörigkeit", initial = listOf("DE", "PL"))
                element().pick(CountryCodes.STATELESS)
                assertEquals(listOf("XS"), widget.codes)
                element().awaitLive("Ersetzt: Deutschland, Polen")
                assertEquals(listOf("staatenlos"), element().chipNames())
                assertEquals(0, element().allOf(".lapis-nationality-chips img.lapis-flag").size, "no flag for an internal value")
                element().pick("FR")
                assertEquals(listOf("FR"), widget.codes)
                element().awaitLive("Ersetzt: staatenlos")
                element().pick(CountryCodes.UNCLEAR)
                assertEquals(listOf("ZZ"), widget.codes)
                element().awaitLive("Ersetzt: Frankreich")
            }
        }

    @Test
    fun atTenEntries_theFieldIsDisabledWithAVisibleReason_andARemovalReenablesIt() =
        test {
            withMountedRoot("nat-limit") { root, element ->
                val ten = CountryCodes.SELECTABLE.take(10)
                val widget = root.nationalityMultiSelect(label = "Staatsangehörigkeit", initial = ten)
                assertEquals(10, widget.codes.size)
                assertTrue(element().combo().disabled)
                assertTrue(element().textContent.orEmpty().contains("Höchstens 10 Staatsangehörigkeiten."))
                (element().allOf(".lapis-chip-remove")[0] as HTMLButtonElement).click()
                assertFalse(element().combo().disabled)
                assertFalse(element().textContent.orEmpty().contains("Höchstens 10"))
                // more than ten in the initial list is cut, never accepted
                val capped = root.nationalityMultiSelect(label = "Weitere", initial = CountryCodes.SELECTABLE.take(12))
                assertEquals(10, capped.codes.size)
            }
        }

    @Test
    fun reset_goesBackToTheGivenState() =
        test {
            withMountedRoot("nat-reset") { root, element ->
                val widget = root.nationalityMultiSelect(label = "Staatsangehörigkeit", initial = listOf("DE"))
                element().pick("PL")
                widget.reset(listOf("DE"))
                assertEquals(listOf("DE"), widget.codes)
                assertEquals(listOf("Deutschland"), element().chipNames())
                element().awaitLive("")
            }
        }

    @Test
    fun invalidCodesInTheInitialList_areDropped() =
        test {
            withMountedRoot("nat-invalid") { root, _ ->
                val widget = root.nationalityMultiSelect(label = "Staatsangehörigkeit", initial = listOf("DE", "QQ", "de", "DE"))
                assertEquals(listOf("DE"), widget.codes)
            }
        }
}
