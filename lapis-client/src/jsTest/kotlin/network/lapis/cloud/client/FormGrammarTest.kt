package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Welle V1.4.28 (W4a): die reinen Feldregeln der Formular-Grammatik ([FormRules]) an den Grenzen der UNVERÄNDERTEN
 * Regeln (die Welle macht sie nur früher und feldbezogen sichtbar, der Server bleibt Autorität). Ohne DOM.
 * `TestI18nSetup` liefert einen Katalog-Manager, der `%N` wirklich ersetzt -- die Meldungstexte sind hier also die
 * deutschen Quelltexte mit eingesetzten Grenzen.
 */
class FormGrammarTest {
    private fun message(check: FieldCheck): String = (check as FieldCheck.Invalid).message

    private fun assertOk(check: FieldCheck) = assertEquals(FieldCheck.Ok, check)

    @Test
    fun newPassword_boundariesOfTheLengthRule() {
        assertEquals("Mindestens 12 Zeichen.", message(FormRules.newPassword("a".repeat(11), email = "")))
        assertOk(FormRules.newPassword("a".repeat(12), email = ""))
        assertOk(FormRules.newPassword("a".repeat(128), email = ""))
        assertEquals("Höchstens 128 Zeichen.", message(FormRules.newPassword("a".repeat(129), email = "")))
    }

    @Test
    fun newPassword_mayNotBeTheEmailAddress() {
        val email = "amara@example.org-long-enough"
        assertEquals("Darf nicht die E-Mail-Adresse sein.", message(FormRules.newPassword(email.uppercase(), email = email)))
    }

    @Test
    fun email_boundariesOfTheLengthRule() {
        val atLimit = "a".repeat(Validation.EMAIL_MAX_LENGTH - 5) + "@b.co"
        assertEquals(Validation.EMAIL_MAX_LENGTH, atLimit.length)
        assertOk(FormRules.email(atLimit))
        assertEquals(
            "Die E-Mail-Adresse ist zu lang (höchstens 320 Zeichen).",
            message(FormRules.email("a$atLimit")),
        )
    }

    @Test
    fun email_rejectsAddressesThatDoNotLookLikeOne() {
        val expected = "Bitte eine gültige E-Mail-Adresse eingeben."
        listOf("plain", "@b.co", "a@", "a@b").forEach { assertEquals(expected, message(FormRules.email(it)), it) }
        assertOk(FormRules.email("  amara.admin@example.org  "))
    }

    @Test
    fun intInRange_prenotificationDaysBoundaries() {
        val expected = "Bitte eine ganze Zahl zwischen 1 und 30 eingeben."
        assertEquals(expected, message(FormRules.intInRange("0", min = 1, max = 30)))
        assertOk(FormRules.intInRange("1", min = 1, max = 30))
        assertOk(FormRules.intInRange("30", min = 1, max = 30))
        assertEquals(expected, message(FormRules.intInRange("31", min = 1, max = 30)))
    }

    @Test
    fun intInRange_dunningLevelAndDaysBoundaries() {
        assertEquals("Bitte eine ganze Zahl zwischen 1 und 1000 eingeben.", message(FormRules.intInRange("0", min = 1, max = 1000)))
        assertOk(FormRules.intInRange("1000", min = 1, max = 1000))
        assertEquals("Bitte eine ganze Zahl zwischen 1 und 1000 eingeben.", message(FormRules.intInRange("1001", min = 1, max = 1000)))
        assertEquals("Bitte eine ganze Zahl zwischen 1 und 365 eingeben.", message(FormRules.intInRange("366", min = 1, max = 365)))
        assertOk(FormRules.intInRange(" 365 ", min = 1, max = 365))
    }

    @Test
    fun intInRange_rejectsNonIntegers() {
        listOf("", "abc", "1.5", "1e2", "12abc").forEach {
            assertTrue(FormRules.intInRange(it, min = 1, max = 30) is FieldCheck.Invalid, it)
        }
    }

    @Test
    fun passwordsMatch_usesTheExistingCatalogMessage() {
        assertOk(FormRules.passwordsMatch("geheim-geheim", "geheim-geheim"))
        assertEquals("Die Passwörter stimmen nicht überein.", message(FormRules.passwordsMatch("geheim-geheim", "geheim-geheiM")))
    }
}
