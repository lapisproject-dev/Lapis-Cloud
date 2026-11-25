package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

/**
 * V1.9.324 -- i18n guard of the reference-date wave: every text of `PriceOracleReferenceDateForm.kt` and the reference-date additions to the
 * Price-Oracle screen and the audit labels is in all seven catalogs with a non-empty translation that keeps the same `%n` placeholders. Only the
 * texts of this wave are checked (older untranslated entries are not this wave's concern). Lives in `lapis-server` because `jsTest` has no file
 * system, same reason as [BankStatementI18nCatalogTest].
 */
class PriceOracleReferenceDateI18nCatalogTest :
    FunSpec({
        val clientSrc =
            File("../lapis-client/src/jsMain/kotlin/network/lapis/cloud/client")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin/network/lapis/cloud/client") }
        val i18nDir =
            File("../lapis-client/src/jsMain/resources/modules/i18n")
                .let { if (it.exists()) it else File("lapis-client/src/jsMain/resources/modules/i18n") }
        val languages = listOf("en", "es", "fr", "it", "nl", "pl", "ru")

        /** The literal chains (`"a" + "b"`) directly inside `tr(` / `gettext(` calls of [text]. */
        fun messageIds(text: String): List<String> {
            val call = Regex("""(?<![A-Za-z0-9_])(?:tr|gettext)\(\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)""")
            val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
            return call
                .findAll(text)
                .map { m -> literal.findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] } }
                .map { it.replace("\\\"", "\"").replace("\\$", "$").replace("\\\\", "\\") }
                .filter { it.isNotBlank() }
                .toList()
        }

        /** msgid -> msgstr of one catalog (multi-line entries joined). */
        fun catalog(file: File): Map<String, String> {
            val entries = mutableMapOf<String, String>()
            val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
            var id: StringBuilder? = null
            var str: StringBuilder? = null
            var mode = 0

            fun flush() {
                if (id != null && str != null) entries[id.toString()] = str.toString()
            }
            file.readLines().forEach { line ->
                when {
                    line.startsWith("msgid ") -> {
                        flush()
                        id =
                            StringBuilder(
                                literal
                                    .find(line)
                                    ?.groupValues
                                    ?.get(1)
                                    .orEmpty(),
                            )
                        str = null
                        mode = 1
                    }
                    line.startsWith("msgstr ") -> {
                        str =
                            StringBuilder(
                                literal
                                    .find(line)
                                    ?.groupValues
                                    ?.get(1)
                                    .orEmpty(),
                            )
                        mode = 2
                    }
                    line.startsWith("\"") -> {
                        val part =
                            literal
                                .find(line)
                                ?.groupValues
                                ?.get(1)
                                .orEmpty()
                        if (mode == 1) {
                            id?.append(part)
                        } else if (mode == 2) {
                            str?.append(part)
                        }
                    }
                }
            }
            flush()
            return entries
                .mapKeys {
                    it.key.replace("\\\"", "\"").replace("\\\\", "\\")
                }.mapValues { it.value.replace("\\\"", "\"").replace("\\\\", "\\") }
        }

        val placeholder = Regex("""%\d""")
        val waveIds =
            (
                messageIds(File(clientSrc, "PriceOracleReferenceDateForm.kt").readText()) +
                    listOf(
                        "Umrechnungskurs",
                        "Ohne Kursverlauf muss jeder Stichtagskurs manuell bestätigt werden.",
                        "Jede Spende wird zum aktuellen Kurs umgerechnet, der beim Bestätigen live abgefragt wird.",
                        "Grundlage: %1, Stichtag %2, Kurstag %3",
                        "Stichtagskurs bestätigt",
                        "Spendenumrechnung zum Stichtag",
                        // V1.9.328: the receipt line of a manual date and the audit label of a donation row.
                        "Begründung: %1",
                        "Spende",
                    )
            ).distinct()

        test("the scan finds the texts of the wave") { waveIds.size shouldBeGreaterThan 30 }

        languages.forEach { language ->
            test("catalog $language has every text of the reference-date wave, translated, with the same placeholders") {
                val entries = catalog(File(i18nDir, "messages-$language.po"))
                val problems =
                    waveIds.filter { id ->
                        val translated = entries[id]
                        translated.isNullOrBlank() ||
                            placeholder
                                .findAll(id)
                                .map { it.value }
                                .sorted()
                                .toList() !=
                            placeholder
                                .findAll(translated)
                                .map { it.value }
                                .sorted()
                                .toList()
                    }
                problems.shouldBeEmpty()
            }
        }

        test("the template lists every text of the wave") {
            val template = File(i18nDir, "messages.pot").readText()
            waveIds.filter { "msgid \"${it.replace("\"", "\\\"")}\"" !in template }.shouldBeEmpty()
        }
    })
