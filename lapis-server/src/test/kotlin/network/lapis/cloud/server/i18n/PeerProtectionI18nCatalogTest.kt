package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- i18n guard for the client texts of the four-eyes protection: every `tr()`/`gettext()` text of the
 * new files (and this wave's own sentences in the files it touched) is in the template and in all seven catalogs with a non-empty
 * `msgstr`, the `%N` placeholders survive the translation, and none of the texts carries a gender mark. Same extraction as
 * `AllClientMessagesCatalogTest`, restricted to this wave; the glossary test keeps "Antrag", "Vorstand" etc. consistent.
 */
class PeerProtectionI18nCatalogTest :
    FunSpec({
        val waveFiles = listOf("PrivilegedActionsCard.kt", "PeerProtectionNotice.kt", "PrivilegedActionVetoScreen.kt")
        // Files with many unrelated texts -- only this wave's own sentences are checked there.
        val markedFiles =
            mapOf(
                "MemberAdminGuard.kt" to
                    listOf("Geschützt", "zweiten Administrators", "weiteren Administrator", "Antrag ist nicht mehr offen"),
                "MemberPasswordResetDialog.kt" to
                    listOf("Freigabe beantragen", "Administratorkonto", "Schutzprüfung", "Ausstehende Freigaben", "Begründung"),
                "MemberAddressCard.kt" to listOf("Geschützt", "Administratoren sind für den Vorstand"),
                "KeycloakLinkScreen.kt" to emptyList(),
            )

        val constants by lazy { clientStringConstants() }
        val waveMessages by lazy {
            waveFiles.flatMap { name ->
                extractMessages(fileName = name, text = File(CLIENT_KOTLIN_DIR, name).readText(), constants = constants)
            } +
                markedFiles.flatMap { (name, markers) ->
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
