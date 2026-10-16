package network.lapis.cloud.server.i18n

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.145 -- i18n guard of the member finder client. The wave-wide scan (`AllClientMessagesCatalogTest`) already covers every message of the client; this
 * test names the files of THIS wave and checks the finder-specific rules on top: every message is in the template and in all seven catalogs with a
 * non-empty translation that keeps its `%N` placeholders, no German message uses a gender form (`*innen`, `:innen`, `_innen`, `/innen`), and the sentences the
 * privacy design depends on (what a switch-off deletes, what a withdrawal costs) are present in the catalogs.
 */
class MemberFinderI18nCatalogTest :
    FunSpec({
        val waveFiles =
            listOf(
                "MemberFinderLabels.kt",
                "MemberFinderNav.kt",
                "MemberFinderScreen.kt",
                "MemberFinderSettings.kt",
                "MemberFinderBoardCard.kt",
            )
        val messages by lazy {
            val constants = clientStringConstants()
            waveFiles.flatMap { name ->
                extractMessages(fileName = name, text = File(CLIENT_KOTLIN_DIR, name).readText(), constants = constants)
            }
        }
        val msgids by lazy { messages.map { it.msgid }.distinct() }
        val template by lazy { parseCatalog(File(CATALOG_DIR, "messages.pot")) }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parseCatalog(File(CATALOG_DIR, "messages-$it.po")) } }

        test("the scan sees the files of the wave (not vacuous)") {
            waveFiles.forEach { File(CLIENT_KOTLIN_DIR, it).exists() shouldBe true }
            msgids.size shouldBeGreaterThan 100
        }

        test("every message of the wave is in the template and in all seven catalogs, translated") {
            val problems =
                msgids.flatMap { id ->
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
                    msgids
                        .filter { id ->
                            val translated = catalogs.getValue(lang)[id] ?: return@filter false
                            placeholder.findAll(id).map { it.value }.toSet() != placeholder.findAll(translated).map { it.value }.toSet()
                        }.map { "$lang: \"$it\"" }
                }
            broken.shouldBeEmpty()
        }

        test("no German message of the wave uses a gender form") {
            val forms = listOf("*innen", ":innen", "_innen", "/innen")
            val gendered = msgids.filter { id -> forms.any { it in id } }
            gendered.shouldBeEmpty()
        }

        test("the sentences about the consequences are in every catalog") {
            val deletes =
                "Wenn Sie den Mitglieder-Finder abschalten, werden alle Anfragen und Blockierungen gelöscht und alle Einwilligungen beendet."
            val cooldown = "Eine erneute Teilnahme ist erst 24 Stunden nach Ihrem Widerruf möglich."
            listOf(deletes, cooldown).forEach { id ->
                withClue("\"$id\" is a message of the wave") { (id in msgids) shouldBe true }
                CATALOG_LANGUAGES.forEach { lang ->
                    withClue("$lang: $id") {
                        catalogs.getValue(lang)[id].orEmpty().isNotBlank() shouldBe
                            true
                    }
                }
            }
        }
    })
