package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

/**
 * V1.9.316 -- i18n guard of the LTR recognition for Sitzungen (settings section, card on the Sitzung, ledger labels, audit labels). Every message of the wave
 * files is in the template and in all seven catalogs with a non-empty translation that keeps its `%N` placeholders, no German message uses a gender
 * form, and the wording is always "Anerkennung" -- never a payment word (it is an issuance of LTR, not a wage).
 */
class MeetingRewardI18nCatalogTest :
    FunSpec({
        val waveFiles =
            listOf(
                "MeetingRewardClientSupport.kt",
                "MeetingAttendanceRecognitionCard.kt",
                "MeetingAttendanceRecognitionSettings.kt",
            )
        val used by lazy {
            val constants = clientStringConstants()
            waveFiles
                .flatMap { name ->
                    extractMessages(fileName = name, text = File(CLIENT_KOTLIN_DIR, name).readText(), constants = constants)
                }.map { it.msgid }
                .toSet()
        }
        val template by lazy { parseCatalog(File(CATALOG_DIR, "messages.pot")) }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parseCatalog(File(CATALOG_DIR, "messages-$it.po")) } }

        test("the wave files carry their messages (not vacuous)") {
            used.size shouldBeGreaterThan 50
        }

        test("every message is in the template and in all seven catalogs, translated") {
            val problems =
                used.flatMap { id ->
                    buildList {
                        if (!template.containsKey(id)) add("pot: \"$id\"")
                        CATALOG_LANGUAGES.forEach { lang ->
                            val translation = catalogs.getValue(lang)[id]
                            if (translation ==
                                null
                            ) {
                                add("$lang: missing \"$id\"")
                            } else if (translation.isBlank()) {
                                add("$lang: blank \"$id\"")
                            }
                        }
                    }
                }
            problems.shouldBeEmpty()
        }

        test("every translation keeps the %N placeholders of its message") {
            val placeholder = Regex("""%\d""")
            val broken =
                CATALOG_LANGUAGES.flatMap { lang ->
                    used
                        .filter { id ->
                            val translated = catalogs.getValue(lang)[id] ?: return@filter false
                            placeholder.findAll(id).map { it.value }.toSet() != placeholder.findAll(translated).map { it.value }.toSet()
                        }.map { "$lang: \"$it\"" }
                }
            broken.shouldBeEmpty()
        }

        test("no German message uses a gender form or a payment word") {
            val forms = listOf("*innen", ":innen", "_innen", "/innen", "Vergütung", "Honorar", "Lohn", "Gehalt")
            used.filter { id -> forms.any { it in id } }.shouldBeEmpty()
        }
    })
