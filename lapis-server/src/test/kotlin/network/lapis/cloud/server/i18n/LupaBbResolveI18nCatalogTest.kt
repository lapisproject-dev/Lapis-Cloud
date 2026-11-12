package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * "LupaBB-Abgleich: Konflikte direkt loesen" -- i18n guard of the new texts of the comparison: present and translated in the template and all
 * seven catalogs, every placeholder kept, and no gendered form in the German source (project rule: no gendering). Structure as
 * [StorageSpaceI18nCatalogTest]; the whole-client scan (`AllClientMessagesCatalogTest`) covers the call sites, this one pins THESE sentences.
 */
class LupaBbResolveI18nCatalogTest :
    FunSpec({
        val i18nDir: File =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }
        val languages = listOf("en", "es", "fr", "it", "nl", "pl", "ru")

        fun parsePo(file: File): Map<String, String> {
            val entries = mutableMapOf<String, String>()
            var id: StringBuilder? = null
            var str: StringBuilder? = null
            var mode = 0

            fun unescape(s: String) = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")

            fun flush() {
                val i = id
                val s = str
                if (i != null && s != null) entries[unescape(i.toString())] = unescape(s.toString())
            }
            file.forEachLine { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("msgid \"") -> {
                        flush()
                        id = StringBuilder(line.removePrefix("msgid \"").removeSuffix("\""))
                        str = null
                        mode = 1
                    }
                    line.startsWith("msgstr \"") -> {
                        str = StringBuilder(line.removePrefix("msgstr \"").removeSuffix("\""))
                        mode = 2
                    }
                    line.startsWith("\"") && line.endsWith("\"") -> {
                        val part = line.removePrefix("\"").removeSuffix("\"")
                        if (mode == 1) {
                            id?.append(part)
                        } else if (mode == 2) {
                            str?.append(part)
                        }
                    }
                    else -> mode = 0
                }
            }
            flush()
            return entries
        }

        val msgids =
            listOf(
                "Mitglied öffnen",
                "LupaBB-Werte übernehmen",
                "LupaBB-Werte übernehmen?",
                "Die Werte aus LupaBB überschreiben die Werte dieses Mitglieds mit Verwaltungsrolle. Die Rolle bleibt unverändert.",
                "%1 Werte übernehmen",
                "Status: %1 → %2",
                "Die LupaBB-Werte wurden übernommen.",
                "Das Mitglied wurde seit der Vorschau geändert. Bitte laden Sie die Vorschau neu.",
                "Die E-Mail-Adresse aus LupaBB wird nicht übernommen, sie gehört bereits einem anderen Mitglied.",
                "Welche Person ist gemeint?",
                "Name oder E-Mail suchen",
                "Kein Mitglied gefunden.",
                "Überspringen",
                "Für diesen Abgleich überspringen",
                "Für diesen Abgleich überspringen?",
                "%1 für diesen Abgleich überspringen",
                "Mit %1 verknüpfen",
                "Mitglied öffnen: %1",
                "LupaBB-Werte übernehmen: %1",
                "Die E-Mail gehört zum Mitglied %1, das bereits mit LupaBB-Person Nr. %2 verknüpft ist. Diese Zeile ist LupaBB-Person Nr. %3.",
                "Wahrscheinlich dieselbe Person doppelt in LupaBB (gleicher Name, gleiche E-Mail). Bitte in LupaBB prüfen.",
                "Tippen Sie Name oder E-Mail des Mitglieds, das zu dieser LupaBB-Person gehört.",
                "Nicht verknüpfbar: bereits mit LupaBB-Person Nr. %1 verknüpft.",
                "Nicht verknüpfbar: bereits mit einer anderen LupaBB-Person verknüpft.",
                "Dieser Datensatz wird in diesem Abgleich nicht übernommen. Beim nächsten Abgleich erscheint er wieder.",
                "Ihren eigenen Datensatz gleicht ein anderer Administrator ab.",
                "In diesem Abgleich übersprungen.",
                "Von Hand übernommen.",
                "Für ein Administratorkonto ist dafür die Zustimmung eines zweiten Administrators nötig. Bitte in der Mitgliederverwaltung beantragen.",
                "Ein Spender kann keine Leitungsrolle haben. Bitte zuerst die Rolle in der Mitgliederverwaltung ändern.",
                "Dieses Mitglied ist bereits mit einer anderen LupaBB-Person verknüpft.",
                "Gefiltert auf ein Mitglied.",
                "Alle Mitglieder anzeigen",
                "Der Abgleich liest nur aus LupaBB. Er löscht und deaktiviert niemanden, legt keine Anmeldekonten an und " +
                    "versendet keine E-Mails. Die E-Mail-Adresse bestehender Mitglieder bleibt unverändert. Einzige Ausnahme: " +
                    "Wird der Datensatz eines Administrators von Hand abgeglichen, erhält dieser eine Benachrichtigung.",
            )
        val catalogs: Map<String, Map<String, String>> by lazy { languages.associateWith { parsePo(File(i18nDir, "messages-$it.po")) } }
        val template: Map<String, String> by lazy { parsePo(File(i18nDir, "messages.pot")) }

        test("every text is in the template and in all seven catalogs, translated") {
            val missing = msgids.filterNot { template.containsKey(it) }.map { "pot: \"$it\"" }
            val untranslated =
                languages.flatMap { lang ->
                    msgids.filter { catalogs.getValue(lang)[it].isNullOrBlank() }.map { "$lang: \"$it\"" }
                }
            (missing + untranslated).shouldBeEmpty()
        }

        test("every placeholder %1 to %4 of a German text is kept in every translation") {
            val placeholder = Regex("""%\d""")
            val broken =
                languages.flatMap { lang ->
                    msgids
                        .filter { id ->
                            val expected = placeholder.findAll(id).map { it.value }.toSet()
                            !placeholder
                                .findAll(catalogs.getValue(lang)[id].orEmpty())
                                .map { it.value }
                                .toSet()
                                .containsAll(expected)
                        }.map { "$lang: \"$it\"" }
                }
            broken.shouldBeEmpty()
        }

        test("the old sync paragraph without the receipt exception is gone from the template and all catalogs") {
            val old =
                "Der Abgleich liest nur aus LupaBB. Er löscht und deaktiviert niemanden, legt keine Anmeldekonten an und versendet keine " +
                    "E-Mails. Die E-Mail-Adresse bestehender Mitglieder bleibt unverändert."
            (listOf(template) + catalogs.values).count { it.containsKey(old) } shouldBe 0
        }

        test("no gendered form in the German texts: no star, colon, underscore or slash before -innen") {
            val gendered = Regex("""[*:_/]\s?innen|[a-zäöü][*:_/]in\b""", RegexOption.IGNORE_CASE)
            msgids.filter { gendered.containsMatchIn(it) }.shouldBeEmpty()
        }
    })
