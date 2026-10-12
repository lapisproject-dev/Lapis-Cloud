package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.111 "Dynamische Mailinglisten" -- i18n guard for the texts of the rule builder, the automatic-list rows and the explanation
 * sentences: every `tr()`/`gettext()` text of the four new files (and this wave's own texts in the files it touched) is in the template
 * and in all seven catalogs with a non-empty `msgstr`, the `%N` placeholders survive the translation, and none of the texts carries a gender
 * mark (decision 2026-09-22). The live count is a plural text (`ngettext`), which the generic extraction does not see -- it is pinned by
 * [PluralMessagesCatalogTest]'s ledger.
 */
class DynamicMailingListI18nCatalogTest :
    FunSpec({
        val waveFiles = listOf("MailingRulePresets.kt", "MailingRuleBuilder.kt", "MailingListCreateForm.kt", "DynamicMailingListRows.kt")

        // The files of earlier waves carry many unrelated texts -- only this wave's own are checked there.
        val sharedFileMarkers =
            mapOf(
                "MailingListRows.kt" to listOf("automatischen Mailingliste", "An %1 Empfänger senden", "Pflichtmitteilung"),
                "CommunicationScreen.kt" to listOf("(automatisch)"),
                "MailingSendFormat.kt" to listOf("Empfänger werden ermittelt"),
                "ComplianceLabels.kt" to listOf("Mailing-Versand"),
            )

        val constants by lazy { clientStringConstants() }
        val waveMessages by lazy {
            waveFiles.flatMap { name ->
                extractMessages(fileName = name, text = File(CLIENT_KOTLIN_DIR, name).readText(), constants = constants)
            } +
                sharedFileMarkers.flatMap { (name, markers) ->
                    extractMessages(fileName = name, text = File(CLIENT_KOTLIN_DIR, name).readText(), constants = constants)
                        .filter { m -> markers.any { m.msgid.contains(it) } }
                }
        }
        val msgids by lazy { waveMessages.map { it.msgid }.distinct() }
        val template by lazy { parseCatalog(File(CATALOG_DIR, "messages.pot")) }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parseCatalog(File(CATALOG_DIR, "messages-$it.po")) } }

        test("the extraction finds this wave's texts (not vacuous)") {
            (msgids.size >= 40) shouldBe true
            waveMessages.filter { it.msgid.isBlank() }.shouldBeEmpty()
        }

        test("every text is in the template") {
            msgids.filterNot { template.containsKey(it) }.shouldBeEmpty()
        }

        test("every text is in all seven catalogs, translated, and keeps its placeholders") {
            val findings =
                CATALOG_LANGUAGES.flatMap { lang ->
                    val catalog = catalogs.getValue(lang)
                    msgids.mapNotNull { id ->
                        val str = catalog[id]
                        when {
                            str == null -> "$lang missing: $id"
                            str.isBlank() -> "$lang untranslated: $id"
                            Regex("%\\d")
                                .findAll(id)
                                .map { it.value }
                                .sorted()
                                .toList() !=
                                Regex("%\\d")
                                    .findAll(str)
                                    .map { it.value }
                                    .sorted()
                                    .toList() -> "$lang placeholders differ: $id"
                            else -> null
                        }
                    }
                }
            findings.shouldBeEmpty()
        }

        test("no gender marks in the source texts or the translations (decision 2026-09-22)") {
            val genderMark = Regex("""\w(\*|:|_|/)innen\b|\w(\*|:|_)in\b""")
            val findings =
                msgids.filter { genderMark.containsMatchIn(it) } +
                    CATALOG_LANGUAGES.flatMap { lang ->
                        msgids.filter { genderMark.containsMatchIn(catalogs.getValue(lang)[it].orEmpty()) }
                    }
            findings.shouldBeEmpty()
        }
    })
