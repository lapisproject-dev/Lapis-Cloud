package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Welle V1.9.18 -- the vocabulary decision "one word for the `MembershipTier`": "Mitgliedschaftsstufe" (see
 * `docs/architecture/i18n-glossary.adoc`, which fixes its rendering per language and is enforced by
 * [I18nGlossaryConsistencyTest]). This test pins the German side: the four screens that talk about a tier no
 * longer carry a message with "Tarif" (the former roster/family wording), and the tier administration screen
 * itself uses the new word in its headline, its sidebar entry and its audit label.
 *
 * Deliberately NOT asserted: "Beitragssatz" in `BankStatementImportScreen` ("Mitgliedsname oder Beitragssatz" -- the
 * search actually only matches the member name; the label is a finding, not a tier reference) and "Beitragsstufe"
 * of the relief request, both listed as out of scope in the glossary.
 */
class MembershipTierTermTest :
    FunSpec({
        val tierScreens =
            listOf(
                "MemberAdministrationScreen.kt",
                "MemberFamiliesScreen.kt",
                "SepaBatchesScreen.kt",
                "MembershipTiersScreen.kt",
                "ContributionsScreen.kt",
            )

        fun messagesOf(fileName: String): List<String> =
            extractMessages(
                fileName = fileName,
                text = File(CLIENT_KOTLIN_DIR, fileName).readText(),
                constants = clientStringConstants(),
            ).map { it.msgid }

        test("no message of the tier screens still says Tarif or Beitragssatz for the membership tier") {
            val offenders =
                tierScreens.flatMap { file ->
                    messagesOf(
                        file,
                    ).filter { it.contains("Tarif") || it.contains("Beitragssatz") || it.contains("Beitragssätze") }.map { "$file: $it" }
                }
            offenders.shouldBeEmpty()
        }

        test("the tier administration uses the glossary word in its page title and the sidebar/audit labels") {
            val msgids = messagesOf("MembershipTiersScreen.kt")
            ("Mitgliedschaftsstufen" in msgids) shouldBe true
            ("Mitgliedschaftsstufe anlegen" in msgids) shouldBe true
            ("Mitgliedschaftsstufen" in messagesOf("Sidebar.kt")) shouldBe true
            ("Mitgliedschaftsstufe" in messagesOf("ComplianceLabels.kt")) shouldBe true
        }

        test("every rendering of the core term contains the accepted stem of its language") {
            val stems =
                mapOf(
                    "en" to "tier",
                    "es" to "nivel",
                    "fr" to "niveau",
                    "it" to "livell",
                    "nl" to "niveau",
                    "pl" to "poziom",
                    "ru" to "уровен",
                )
            val wrong =
                CATALOG_LANGUAGES.filter { lang ->
                    val entry = parseCatalog(File(CATALOG_DIR, "messages-$lang.po"))["Mitgliedschaftsstufe"]
                    entry == null || !entry.lowercase().contains(stems.getValue(lang))
                }
            wrong.shouldBeEmpty()
        }
    })
