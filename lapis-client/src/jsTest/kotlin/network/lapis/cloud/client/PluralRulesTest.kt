package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * i18n-Restschuld (2026-09-27): pins [pluralFormIndex]'s category boundaries for all eight UI
 * languages against the standard GNU gettext `Plural-Forms` table (the same one now recorded
 * in each `messages-<lang>.po`'s own header -- see that file's `Plural-Forms:` line). The
 * Polish/Russian cases below are the ones a bare `value == 1` binary gets wrong (this class's
 * pre-2026-09-27 behavior, see [I18nCatalogManager]'s class KDoc history); they are the reason
 * this test exists, not the two-form languages.
 */
class PluralRulesTest {
    @Test
    fun germanicAndRomanceTwoFormLanguages_singularOnlyAtExactlyOne() {
        for (language in listOf("en", "es", "it", "nl")) {
            assertEquals(0, pluralFormIndex(language, 1), "$language: 1 must be singular")
            assertEquals(1, pluralFormIndex(language, 0), "$language: 0 must be plural")
            assertEquals(1, pluralFormIndex(language, 2), "$language: 2 must be plural")
            assertEquals(1, pluralFormIndex(language, 11), "$language: 11 must be plural")
            assertEquals(2, pluralFormCount(language))
        }
    }

    @Test
    fun french_zeroAndOneAreBothSingular() {
        assertEquals(0, pluralFormIndex("fr", 0))
        assertEquals(0, pluralFormIndex("fr", 1))
        assertEquals(1, pluralFormIndex("fr", 2))
        assertEquals(1, pluralFormIndex("fr", 11))
        assertEquals(2, pluralFormCount("fr"))
    }

    @Test
    fun polish_oneFewMany() {
        val expectations =
            mapOf(
                0 to 2,
                1 to 0,
                2 to 1,
                3 to 1,
                4 to 1,
                5 to 2,
                10 to 2,
                // the x12-x14 exception: %10 in 2..4 but still "many", not "few"
                12 to 2,
                13 to 2,
                14 to 2,
                15 to 2,
                // Polish "one" is exactly n == 1, unlike Russian's "n%10==1 && n%100!=11" pattern
                // below -- 21 does NOT behave like 1 in Polish (genitive plural, "many"/"few"
                // group depends only on the last two digits, never on whether the number LOOKS
                // like "...1").
                21 to 2,
                22 to 1,
                24 to 1,
                25 to 2,
                100 to 2,
                102 to 1,
                112 to 2,
                122 to 1,
            )
        expectations.forEach { (n, expected) -> assertEquals(expected, pluralFormIndex("pl", n), "pl: $n") }
        assertEquals(3, pluralFormCount("pl"))
    }

    @Test
    fun russian_oneFewMany_elevenToFourteenAreAlwaysMany() {
        val expectations =
            mapOf(
                0 to 2,
                1 to 0,
                2 to 1,
                3 to 1,
                4 to 1,
                5 to 2,
                10 to 2,
                // "eleven" is the case a naive "n%10==1" rule (without the %100!=11 guard) gets wrong.
                11 to 2,
                12 to 2,
                13 to 2,
                14 to 2,
                21 to 0,
                22 to 1,
                25 to 2,
                101 to 0,
                111 to 2,
            )
        expectations.forEach { (n, expected) -> assertEquals(expected, pluralFormIndex("ru", n), "ru: $n") }
        assertEquals(3, pluralFormCount("ru"))
    }

    @Test
    fun unknownLanguageCode_fallsBackToTheSafeTwoFormRule() {
        assertEquals(0, pluralFormIndex("xx", 1))
        assertEquals(1, pluralFormIndex("xx", 5))
    }

    @Test
    fun negativeCount_isTreatedByItsAbsoluteValue_neverCrashesOrGoesOutOfRange() {
        assertEquals(pluralFormIndex("pl", 3), pluralFormIndex("pl", -3))
        assertEquals(pluralFormIndex("ru", 11), pluralFormIndex("ru", -11))
        assertEquals(pluralFormIndex("en", 1), pluralFormIndex("en", -1))
    }
}
