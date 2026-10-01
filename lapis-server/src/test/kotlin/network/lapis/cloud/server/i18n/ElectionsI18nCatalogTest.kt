package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.22 "Wahlen" -- i18n-Wächter der Wahlen-Oberfläche. [AllClientMessagesCatalogTest] prüft den ganzen Client; dieser Test fasst
 * die Dateien dieser Welle zusammen, damit ein fehlender oder leerer Eintrag der Wahlen-Texte mit dem Namen der Welle auffällt, und
 * prüft zusätzlich zwei Eigenschaften, die nur diese Welle braucht: kein Gendern (Konvention 2026-09-22) und das Glossar-Wort
 * "Wahlausschuss" als "election committee"-Familie, nie als "board".
 */
class ElectionsI18nCatalogTest :
    FunSpec({
        val waveFiles =
            listOf(
                "ElectionsScreen.kt",
                "ElectionBoardUi.kt",
                "ElectionResultUi.kt",
                "ElectionBooth.kt",
                "ElectionOpenForm.kt",
                "ElectionLabels.kt",
                "ElectionPhase.kt",
                "ElectionMajorityExplain.kt",
                "ElectionAuthzUi.kt",
            )
        val constants by lazy { clientStringConstants() }
        val messages by lazy {
            waveFiles.flatMap { name ->
                extractMessages(fileName = name, text = File(CLIENT_KOTLIN_DIR, name).readText(), constants = constants)
            }
        }
        val msgids by lazy { messages.map { it.msgid }.distinct() }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parseCatalog(File(CATALOG_DIR, "messages-$it.po")) } }

        test("the wave files exist and the extraction is not vacuous") {
            waveFiles.filterNot { File(CLIENT_KOTLIN_DIR, it).isFile }.shouldBeEmpty()
            (msgids.size > 100) shouldBe true
            messages.filter { it.msgid.isBlank() }.shouldBeEmpty()
        }

        test("every text of the elections screens is in all seven catalogs and translated") {
            val problems =
                CATALOG_LANGUAGES.flatMap { lang ->
                    val catalog = catalogs.getValue(lang)
                    msgids.mapNotNull { id ->
                        when {
                            !catalog.containsKey(id) -> "$lang: missing \"$id\""
                            catalog.getValue(id).isBlank() -> "$lang: untranslated \"$id\""
                            else -> null
                        }
                    }
                }
            problems.shouldBeEmpty()
        }

        test("the template carries every text of the elections screens") {
            val pot = parseCatalog(File(CATALOG_DIR, "messages.pot"))
            msgids.filterNot { pot.containsKey(it) }.shouldBeEmpty()
        }

        test("no msgid and no translation of the elections screens uses a gender mark") {
            val genderMark = Regex("""\w(\*|:|_|/)innen\b|\w(\*|:|_)in\b|\w/-in\b|\(in\)|\(innen\)""")
            val offenders =
                (
                    listOf("de" to msgids.associateWith { it }) +
                        CATALOG_LANGUAGES.map { it to catalogs.getValue(it).filterKeys { id -> id in msgids } }
                ).flatMap { (lang, entries) ->
                    entries.filter { (_, text) -> genderMark.containsMatchIn(text) }.map { "$lang: \"${it.value}\"" }
                }
            offenders.shouldBeEmpty()
        }

        test("the election committee is never rendered as a board") {
            val committeeMsgids = msgids.filter { Regex("(?<![\\p{L}])Wahlausschuss(?![\\p{L}])").containsMatchIn(it) }
            (committeeMsgids.size >= 5) shouldBe true
            val offenders =
                committeeMsgids.filter { id ->
                    catalogs
                        .getValue("en")
                        .getValue(id)
                        .lowercase()
                        .let { "election board" in it }
                }
            offenders.shouldBeEmpty()
        }
    })
