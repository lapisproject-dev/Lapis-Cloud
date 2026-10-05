package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- i18n guard for the address-change client texts: every `tr()`/`gettext()` text of the
 * three new screens (and the typed-exception toasts in `AppState.kt` / `MemberAdminGuard.kt`) is in the template and in all seven
 * catalogs with a non-empty `msgstr`, the `%1` placeholders survive the translation, and none of the texts carries a gender mark.
 * Same extraction as `AllClientMessagesCatalogTest`, restricted to this wave's files; the shared glossary test
 * (`I18nGlossaryConsistencyTest`) additionally keeps "Mitglied", "Vorstand" etc. consistent.
 */
class EmailChangeI18nCatalogTest :
    FunSpec({
        val waveFiles = listOf("MemberEmailCard.kt", "MemberEmailChangeProposal.kt", "EmailChangeDeepLinkScreens.kt")
        // AppState.kt carries many unrelated texts -- only this wave's own toasts are checked there.
        val appStateMarkers =
            listOf("Adressänderung", "E-Mail-Adressen stimmen", "aktuelle E-Mail-Adresse", "Änderung ist nicht mehr offen", "Mailversand")

        val constants by lazy { clientStringConstants() }
        val waveMessages by lazy {
            waveFiles.flatMap { name ->
                val file = File(CLIENT_KOTLIN_DIR, name)
                extractMessages(fileName = name, text = file.readText(), constants = constants)
            } +
                extractMessages(fileName = "AppState.kt", text = File(CLIENT_KOTLIN_DIR, "AppState.kt").readText(), constants = constants)
                    .filter { m -> appStateMarkers.any { m.msgid.contains(it) } }
        }
        val msgids by lazy { waveMessages.map { it.msgid }.distinct() }
        val template by lazy { parseCatalog(File(CATALOG_DIR, "messages.pot")) }
        val catalogs by lazy { CATALOG_LANGUAGES.associateWith { parseCatalog(File(CATALOG_DIR, "messages-$it.po")) } }

        test("the extraction finds this wave's texts (not vacuous)") {
            (msgids.size >= 50) shouldBe true
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
                                    .toList() ->
                                "$lang placeholders differ: $id"
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
