package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.281 -- i18n guard of the raised hand of event guests: the one new msgid is in `messages.pot` and translated (non-empty) in all seven catalogs; the
 * texts the guest button reuses exist, too. Lives in `lapis-server` because `jsTest` has no file system.
 */
class EventGuestHandI18nCatalogTest :
    FunSpec({
        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }
        val newId = "Die Hand kann erst nach dem Verbinden gehoben werden."
        val reused = listOf("Hand heben", "Hand senken", "Die Hand konnte nicht gehoben werden. Bitte versuchen Sie es erneut.")

        fun entries(file: File): Map<String, String> {
            val result = linkedMapOf<String, String>()
            var id: String? = null
            file.readLines().forEach { line ->
                when {
                    line.startsWith("msgid \"") -> id = line.removePrefix("msgid \"").removeSuffix("\"")
                    line.startsWith("msgstr \"") && id != null -> {
                        result[id!!] = line.removePrefix("msgstr \"").removeSuffix("\"")
                        id = null
                    }
                }
            }
            return result
        }

        test("messages.pot carries the new msgid exactly once") {
            File(i18nDir, "messages.pot").readLines().count { it == "msgid \"$newId\"" } shouldBe 1
        }

        listOf("en", "es", "fr", "it", "nl", "pl", "ru").forEach { lang ->
            test("messages-$lang.po translates the new msgid and has the reused ones") {
                val catalog = entries(File(i18nDir, "messages-$lang.po"))
                catalog.getValue(newId).isNotBlank() shouldBe true
                reused.forEach { catalog.getValue(it).isNotBlank() shouldBe true }
            }
        }

        // V1.9.309: i18n guard of the full-screen button in the guest call view ("Vollbild" is reused, no new msgid). It lives here, not in a
        // class of its own: a NEW server test class shifts the distribution of the specs over the test JVMs and exposed an existing,
        // order-dependent failure of TenderPayoutTest (see the V1.9.309 notes).
        test("messages.pot carries the full-screen msgid exactly once") {
            File(i18nDir, "messages.pot").readLines().count { it == "msgid \"Vollbild\"" } shouldBe 1
        }

        listOf("en", "es", "fr", "it", "nl", "pl", "ru").forEach { lang ->
            test("messages-$lang.po translates the full-screen msgid without gendering characters") {
                val text = entries(File(i18nDir, "messages-$lang.po")).getValue("Vollbild")
                text.isNotBlank() shouldBe true
                Regex("[*:_/]innen").containsMatchIn(text) shouldBe false
            }
        }
    })
