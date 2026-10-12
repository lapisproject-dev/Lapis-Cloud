package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.CountryCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * V1.9.121 -- the REAL browser names (`Intl.DisplayNames` / `Intl.Collator`). Only properties are asserted, never a spelling: the exact
 * text differs between browsers and versions (see `CountryNames.kt`), the stored value is always the code.
 */
class BrowserCountryNameProviderTest {
    private val provider = BrowserCountryNameProvider

    @Test
    fun aKnownCountry_hasANameInTheRequestedLanguage_thatIsNotTheCode() {
        listOf("DE", "PL", "FR", "GE").forEach { code ->
            val name = provider.name(code, "de")
            assertTrue(name.isNotBlank(), code)
            assertNotEquals(code, name, "the name of $code is not the code")
        }
        assertNotEquals(provider.name("DE", "de"), provider.name("DE", "ru"), "a different language gives a different name")
    }

    @Test
    fun theEnglishName_isNotTheCode() {
        assertNotEquals("DE", provider.englishName("DE"))
    }

    @Test
    fun anInvalidLanguageTag_fallsBackToTheCode_neverThrows() {
        assertEquals("DE", provider.name("DE", "not a language tag!"))
    }

    @Test
    fun kosovo_neverShowsTheBareCode() {
        assertNotEquals("XK", provider.name("XK", "de"))
    }

    @Test
    fun theSortedList_hasAllEntries_inANonDecreasingOrder() {
        val built = buildCountryListOptions(CountryListMode.COUNTRY, lang = "de", pinned = "DE", provider = provider)
        assertEquals(250, built.options.size)
        assertEquals("DE", built.options.first().first, "the pinned country comes first")
        val rest = built.options.drop(1).map { it.second }
        val comparator = provider.comparator("de")
        assertTrue(rest.zipWithNext().all { (a, b) -> comparator.compare(a, b) <= 0 }, "alphabetical in the UI language")
        assertEquals(CountryCodes.SELECTABLE.toSet(), built.options.map { it.first }.toSet())
    }

    @Test
    fun theInternalValues_comeFromTheCatalogue_notFromIntl() {
        assertEquals("staatenlos", countryDisplayName(CountryCodes.STATELESS, "de"))
        assertEquals("ungeklärt", countryDisplayName(CountryCodes.UNCLEAR, "de"))
    }
}
