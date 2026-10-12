package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CountryCodes
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
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

/** V1.9.121 -- the country list ([countrySelect], built on [SearchableSelect]) in a real mounted root, with deterministic names. */
class CountrySelectDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { withFakeCountryNames(block) }

    private fun HTMLElement.combo(): HTMLInputElement =
        assertNotNull(querySelector("input[role=combobox]") as? HTMLInputElement, "no combobox")

    private fun HTMLElement.options(): List<HTMLElement> = allOf("[role=option]")

    private fun HTMLElement.optionNames(): List<String> = options().map { it.textContent.orEmpty().trim() }

    private fun type(
        input: HTMLInputElement,
        text: String,
    ) {
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

    @Test
    fun theList_hasNoPreselection_theEmptyEntryFirst_thenThePinnedCountry_thenAllOthers() =
        test {
            AppState.setSession(null)
            withMountedRoot("country-order") { root, element ->
                val select = root.countrySelect(label = "Land")
                assertEquals("", select.value.orEmpty(), "no preselection, not even the pinned country")
                assertEquals("", element().combo().value)
                element().combo().click()
                val names = element().optionNames()
                assertEquals("— bitte wählen —", names.first())
                assertEquals("Deutschland", names[1], "the pinned country (Germany when the session names none) comes first")
                assertEquals(SELECTABLE_COUNTRY_COUNT + 1, names.size, "the list is not capped: none entry + every country")
                assertEquals(
                    names.drop(2),
                    names.drop(2).sorted().let { rest ->
                        rest
                    },
                    "the rest is alphabetical (fake collator = plain order)",
                )
                assertTrue(
                    element().options()[2].classList.contains("lapis-ssel-option--separated"),
                    "a divider follows the pinned country",
                )
            }
        }

    @Test
    fun thePinnedCountry_isTheOrganizations_fromTheSession() =
        test {
            AppState.setSession(
                SessionInfoDto(
                    memberId = "m",
                    displayName = "M",
                    role = AccountRole.MEMBER,
                    status = MemberStatus.ACTIVE,
                    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                    organizationCountryCode = "PL",
                ),
            )
            try {
                withMountedRoot("country-pinned") { root, element ->
                    root.countrySelect(label = "Land")
                    element().combo().click()
                    assertEquals("Polen", element().optionNames()[1])
                }
            } finally {
                AppState.setSession(null)
            }
        }

    @Test
    fun everyCountryRow_hasADecorativeFlag_andItsAccessibleNameIsTheNameAlone() =
        test {
            AppState.setSession(null)
            withMountedRoot("country-flags") { root, element ->
                root.countrySelect(label = "Land")
                element().combo().click()
                val rows = element().options().drop(1)
                assertEquals(SELECTABLE_COUNTRY_COUNT, rows.size)
                rows.forEach { row ->
                    val flag = assertNotNull(row.querySelector("img.lapis-flag") as? HTMLImageElement, row.textContent)
                    assertEquals("", flag.getAttribute("alt"), "decorative: empty alt")
                    assertEquals("true", flag.getAttribute("aria-hidden"))
                    val code = row.getAttribute("data-value").orEmpty()
                    assertTrue(
                        flag.getAttribute("src").orEmpty().endsWith("/flag-icons-v7/4x3/${code.lowercase()}.svg"),
                        flag.getAttribute("src"),
                    )
                    // the flag is left of the name: the first child
                    assertEquals(flag, row.firstElementChild)
                }
                val de = element().options().first { it.getAttribute("data-value") == "DE" }
                assertEquals("Deutschland", de.textContent.orEmpty().trim(), "no code in the visible/accessible name")
            }
        }

    @Test
    fun search_findsByTheNameTheEnglishNameAndTheCode_ignoringCaseAndAccents() =
        test {
            AppState.setSession(null)
            withMountedRoot("country-search") { root, element ->
                root.countrySelect(label = "Land")
                val input = element().combo()

                fun search(query: String): List<String> {
                    type(input, query)
                    return element().optionNames()
                }
                assertEquals(listOf("Polen"), search("pol").filter { it == "Polen" })
                assertTrue("Deutschland" in search("germany"), "the English name finds the country")
                assertTrue("Deutschland" in search("DE"), "the code finds the country")
                assertEquals("Deutschland", search("de").first(), "word-start hits rank first")
                assertTrue("Österreich" in search("osterreich"), "accents are ignored")
                assertTrue("Österreich" in search("oesterreich"), "the German transcription works too")
                assertTrue("Österreich" in search("ÖSTERR"))
                assertTrue("Schweiz" in search("switzerland"))
                assertTrue(search("qqqq").isEmpty())
            }
        }

    @Test
    fun keyboard_arrowOpens_enterPicks_theNameAndTheFlagStandInTheField_escCloses() =
        test {
            AppState.setSession(null)
            withMountedRoot("country-keys") { root, element ->
                val select = root.countrySelect(label = "Land")
                val input = element().combo()
                assertTrue(press(input, "ArrowDown"), "ArrowDown opens the list")
                assertEquals("true", input.getAttribute("aria-expanded"))
                val active = input.getAttribute("aria-activedescendant").orEmpty()
                assertTrue(active.isNotEmpty(), "aria-activedescendant points at the active option")
                type(input, "poland")
                press(input, "Enter")
                assertEquals("PL", select.value)
                assertEquals("Polen", input.value)
                val lead =
                    assertNotNull(
                        element().querySelector(".lapis-ssel-lead img.lapis-flag") as? HTMLImageElement,
                        "the flag stands in front of the name",
                    )
                assertTrue(lead.getAttribute("src").orEmpty().endsWith("/4x3/pl.svg"))
                assertEquals("false", input.getAttribute("aria-expanded"))
                // Esc closes an open list, a second Esc restores the selection
                press(input, "ArrowDown")
                assertEquals("true", input.getAttribute("aria-expanded"))
                press(input, "Escape")
                assertEquals("false", input.getAttribute("aria-expanded"))
                assertEquals("PL", select.value)
            }
        }

    @Test
    fun clearing_theEmptyEntryResetsTheValue_andTheFlagInTheFieldDisappears() =
        test {
            AppState.setSession(null)
            withMountedRoot("country-clear") { root, element ->
                val select = root.countrySelect(value = "AT", label = "Land")
                assertEquals("Österreich", element().combo().value)
                assertNotNull(element().querySelector(".lapis-ssel-lead img.lapis-flag"))
                chooseInCombobox(element().combo(), "")
                assertEquals("", select.value.orEmpty())
                assertNull(element().querySelector(".lapis-ssel-lead img.lapis-flag"))
            }
        }

    @Test
    fun theNationalityList_endsWithAFlaglessGroup_staatenlosAndUngeklaert() =
        test {
            val built = buildCountryListOptions(CountryListMode.NATIONALITY, lang = "de", pinned = "DE", provider = FakeCountryNameProvider)
            assertEquals(SELECTABLE_COUNTRY_COUNT + 2, built.options.size)
            assertEquals(listOf(CountryCodes.STATELESS, CountryCodes.UNCLEAR), built.options.takeLast(2).map { it.first })
            assertEquals(listOf("staatenlos", "ungeklärt"), built.options.takeLast(2).map { it.second })
            assertTrue("DE" in built.separatorAfter, "a divider after the pinned country")
            assertTrue(built.options[built.options.size - 3].first in built.separatorAfter, "a divider before the special group")
            assertFalse(countryFlagImage(CountryCodes.STATELESS) != null, "no flag for the internal values")
            // the country list has neither special value
            val countries = buildCountryListOptions(CountryListMode.COUNTRY, lang = "de", pinned = "DE", provider = FakeCountryNameProvider)
            assertEquals(SELECTABLE_COUNTRY_COUNT, countries.options.size)
            assertTrue(countries.options.none { it.first in CountryCodes.NATIONALITY_SPECIAL })
        }

    @Test
    fun anUnknownPinnedCode_isIgnored_theListStillBuilds() =
        test {
            val built = buildCountryListOptions(CountryListMode.COUNTRY, lang = "de", pinned = "QQ", provider = FakeCountryNameProvider)
            assertEquals(SELECTABLE_COUNTRY_COUNT, built.options.size)
            assertTrue(built.separatorAfter.isEmpty())
        }
}
