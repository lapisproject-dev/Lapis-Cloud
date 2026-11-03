package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

/**
 * V1.9.269 (R3) -- the texts of the new party-report tabs and hints: every msgid of the R3 client files is in the template and in all seven
 * catalogs, has a non-empty translation, keeps its `%N` placeholders, and no text uses a gender mark (project rule: no gendering in German UI texts).
 * Structure like [BroadcastViewDefaultI18nCatalogTest]; the generic [AllClientMessagesCatalogTest] covers the whole client, this one names the wave.
 */
class PartyReportR3I18nCatalogTest :
    FunSpec({
        val waveFiles = listOf("PartyReportAnnexTab.kt", "PartyReportNotesTab.kt", "PartyReportFindings.kt", "PartyReportLogic.kt")
        val constants by lazy { clientStringConstants() }
        val messages by lazy {
            clientKotlinFiles()
                .filter { it.name in waveFiles }
                .flatMap { extractMessages(fileName = it.name, text = it.readText(), constants = constants) }
        }
        val template by lazy { parseCatalog(File(CATALOG_DIR, "messages.pot")) }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parseCatalog(File(CATALOG_DIR, "messages-$it.po")) } }
        val genderMark = Regex("""\w[*:_/]in(nen)?\b|\w\*innen|\w:innen|\w_innen|\w/innen""")

        test("the scan finds the texts of the wave (not vacuous)") {
            messages.map { it.msgid }.distinct().size shouldBeGreaterThan 40
        }

        test("every text of the wave is in the template and in all seven catalogs with a translation") {
            val problems =
                messages.map { it.msgid }.distinct().flatMap { id ->
                    buildList {
                        if (!template.containsKey(id)) add("pot: \"$id\"")
                        CATALOG_LANGUAGES.forEach { lang ->
                            val translated = catalogs.getValue(lang)[id]
                            if (translated == null) {
                                add("$lang missing: \"$id\"")
                            } else if (translated.isBlank()) {
                                add("$lang empty: \"$id\"")
                            }
                        }
                    }
                }
            problems.shouldBeEmpty()
        }

        test("every translation keeps the %N placeholders of its msgid") {
            val placeholder = Regex("""%\d""")
            val broken =
                CATALOG_LANGUAGES.flatMap { lang ->
                    messages
                        .map { it.msgid }
                        .distinct()
                        .filter { id ->
                            val translated = catalogs.getValue(lang)[id] ?: return@filter false
                            placeholder.findAll(id).map { it.value }.toSet() != placeholder.findAll(translated).map { it.value }.toSet()
                        }.map { "$lang: \"$it\"" }
                }
            broken.shouldBeEmpty()
        }

        test("no text of the wave uses a gender mark, in German or in any translation") {
            val found =
                messages
                    .map { it.msgid }
                    .distinct()
                    .filter { genderMark.containsMatchIn(it) }
                    .map { "de: $it" } +
                    CATALOG_LANGUAGES.flatMap { lang ->
                        messages
                            .map { it.msgid }
                            .distinct()
                            .mapNotNull {
                                catalogs.getValue(
                                    lang,
                                )[it]
                            }.filter { genderMark.containsMatchIn(it) }
                    }
            found.shouldBeEmpty()
        }
    })
