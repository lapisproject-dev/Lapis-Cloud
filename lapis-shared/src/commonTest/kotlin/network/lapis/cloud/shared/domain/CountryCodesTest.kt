package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CountryCodesTest {
    @Test
    fun isoListHasTheOfficial249Codes() {
        assertEquals(249, CountryCodes.ISO_ALPHA2.size)
        assertEquals(CountryCodes.ISO_ALPHA2.size, CountryCodes.ISO_ALPHA2.toSet().size)
        assertEquals(CountryCodes.ISO_ALPHA2.sorted(), CountryCodes.ISO_ALPHA2)
        assertTrue(CountryCodes.ISO_ALPHA2.all { it.length == 2 && it.all { c -> c in 'A'..'Z' } })
    }

    @Test
    fun selectableIsIsoPlusKosovo() {
        assertEquals(250, CountryCodes.SELECTABLE.size)
        assertTrue("XK" in CountryCodes.SELECTABLE)
        assertTrue("DE" in CountryCodes.SELECTABLE)
        assertFalse("XK" in CountryCodes.ISO_ALPHA2)
        assertEquals(CountryCodes.SELECTABLE.sorted(), CountryCodes.SELECTABLE)
    }

    @Test
    fun specialCodesAreNotCountriesButAreNationalityValues() {
        assertFalse(CountryCodes.isCountry("XS"))
        assertFalse(CountryCodes.isCountry("ZZ"))
        assertTrue(CountryCodes.isNationalityValue("XS"))
        assertTrue(CountryCodes.isNationalityValue("ZZ"))
        assertTrue(CountryCodes.isNationalityValue("PL"))
        assertFalse(CountryCodes.isNationalityValue("QQ"))
        assertFalse(CountryCodes.isCountry(null))
        assertFalse(CountryCodes.isCountry("de"))
    }

    @Test
    fun normalizeCodeTrimsUppercasesAndRejectsWrongShape() {
        assertEquals("DE", CountryCodes.normalizeCode(" de "))
        assertNull(CountryCodes.normalizeCode("DEU"))
        assertNull(CountryCodes.normalizeCode("D"))
        assertNull(CountryCodes.normalizeCode("1A"))
        assertNull(CountryCodes.normalizeCode(null))
    }

    @Test
    fun nationalitiesAreDeduplicatedAndSorted() {
        val result = CountryCodes.normalizeNationalities(listOf("pl", "DE", "PL"))
        assertEquals(CountryCodes.NationalitySelection.Valid(listOf("DE", "PL")), result)
    }

    @Test
    fun emptyNationalitySelectionIsValid() {
        assertEquals(CountryCodes.NationalitySelection.Valid(emptyList()), CountryCodes.normalizeNationalities(emptyList()))
    }

    @Test
    fun unknownNationalityCodeIsRejected() {
        assertEquals(
            CountryCodes.NationalitySelection.Invalid(CountryViolation.UNKNOWN_CODE),
            CountryCodes.normalizeNationalities(listOf("DE", "QQ")),
        )
    }

    @Test
    fun moreThanTenNationalitiesAreRejectedButTenAreAccepted() {
        val ten = CountryCodes.SELECTABLE.take(10)
        assertTrue(CountryCodes.normalizeNationalities(ten) is CountryCodes.NationalitySelection.Valid)
        assertEquals(
            CountryCodes.NationalitySelection.Invalid(CountryViolation.TOO_MANY),
            CountryCodes.normalizeNationalities(CountryCodes.SELECTABLE.take(11)),
        )
    }

    @Test
    fun duplicatesDoNotCountTowardsTheLimit() {
        val list = CountryCodes.SELECTABLE.take(10) + CountryCodes.SELECTABLE.take(5)
        assertTrue(CountryCodes.normalizeNationalities(list) is CountryCodes.NationalitySelection.Valid)
    }

    @Test
    fun specialValuesMustStandAlone() {
        assertEquals(
            CountryCodes.NationalitySelection.Invalid(CountryViolation.SPECIAL_NOT_EXCLUSIVE),
            CountryCodes.normalizeNationalities(listOf("XS", "DE")),
        )
        assertEquals(
            CountryCodes.NationalitySelection.Invalid(CountryViolation.SPECIAL_NOT_EXCLUSIVE),
            CountryCodes.normalizeNationalities(listOf("ZZ", "XS")),
        )
        assertEquals(CountryCodes.NationalitySelection.Valid(listOf("XS")), CountryCodes.normalizeNationalities(listOf("XS")))
        assertEquals(CountryCodes.NationalitySelection.Valid(listOf("ZZ")), CountryCodes.normalizeNationalities(listOf("zz", "ZZ")))
    }

    @Test
    fun countryFieldRejectsSpecialAndUnknownCodes() {
        assertNull(CountryCodes.validateCountry("PL"))
        assertNull(CountryCodes.validateCountry("xk"))
        assertEquals(CountryViolation.SPECIAL_IN_COUNTRY_FIELD, CountryCodes.validateCountry("XS"))
        assertEquals(CountryViolation.SPECIAL_IN_COUNTRY_FIELD, CountryCodes.validateCountry("ZZ"))
        assertEquals(CountryViolation.UNKNOWN_CODE, CountryCodes.validateCountry("QQ"))
        assertEquals(CountryViolation.UNKNOWN_CODE, CountryCodes.validateCountry(null))
    }
}
