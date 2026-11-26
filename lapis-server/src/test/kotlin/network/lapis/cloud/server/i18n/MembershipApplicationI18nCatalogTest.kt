package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.315 -- i18n guard of the admin switch "Mitglied werden" and its closed state. Every message of the wave is used in the client source, is in the template and in all seven
 * catalogs with a non-empty translation that keeps its `%N` placeholders, and no German message uses a gender form.
 */
class MembershipApplicationI18nCatalogTest :
    FunSpec({
        val waveFiles =
            listOf(
                "MembershipApplicationSettings.kt",
                "RegistrationScreen.kt",
                "EmbedIntegrationScreen.kt",
                "OrganizationScreen.kt",
                "AppState.kt",
            )
        val expected =
            listOf(
                "Mitgliedsantrag",
                "Online-Mitgliedsantrag anbieten („Mitglied werden“)",
                "Wenn ausgeschaltet, verschwinden alle Links „Mitglied werden“ (Anmeldeseite, öffentliche Startseite, Einbettungs-Widget auf fremden Webseiten), und der Server nimmt keine Online-Anträge mehr an. Die Registrierung als Freund oder Gast bleibt möglich. Bereits eingegangene Anträge bleiben unverändert.",
                "Ein Online-Mitgliedsantrag ist auf dieser Plattform nicht möglich. Bitte wenden Sie sich direkt an die Organisation.",
                "Der Baustein „Mitglied werden“ ist ausgeblendet, weil der Online-Mitgliedsantrag ausgeschaltet ist.",
                "Der Online-Mitgliedsantrag wird angeboten.",
                "Der Online-Mitgliedsantrag wird nicht mehr angeboten.",
                "Zeitzone: %1 · Mitgliederzahl öffentlich: %2 · Online-Mitgliedsantrag: %3 · Mailsprache: %4",
                "Zur Anmeldung",
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

        test("the client source uses every message of the wave (not vacuous)") {
            expected.filter { it !in used }.shouldBeEmpty()
        }

        test("every message is in the template and in all seven catalogs, translated") {
            val problems =
                expected.flatMap { id ->
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
                    expected
                        .filter { id ->
                            val translated = catalogs.getValue(lang)[id] ?: return@filter false
                            placeholder.findAll(id).map { it.value }.toSet() != placeholder.findAll(translated).map { it.value }.toSet()
                        }.map { "$lang: \"$it\"" }
                }
            broken.shouldBeEmpty()
        }

        test("no German message uses a gender form") {
            val forms = listOf("*innen", ":innen", "_innen", "/innen")
            expected.filter { id -> forms.any { it in id } }.size shouldBe 0
        }
    })
