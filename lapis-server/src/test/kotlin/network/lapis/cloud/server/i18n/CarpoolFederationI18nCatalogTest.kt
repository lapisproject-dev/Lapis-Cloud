package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.311 -- i18n guard of the carpool-exchange client. Every message of the wave is used in the client source, is in the template and in all seven
 * catalogs with a non-empty translation that keeps its `%N` placeholders, and no German message uses a gender form.
 */
class CarpoolFederationI18nCatalogTest :
    FunSpec({
        val waveFiles =
            listOf("PartnerServersScreen.kt", "PartnerServersLogic.kt", "CarpoolScreen.kt", "CarpoolFederationUi.kt", "ComplianceLabels.kt")
        val expected =
            listOf(
                "Mitfahrzentrale-Austausch",
                "Fahrtangebote mit freigegebenen Partnern austauschen",
                "Mitglieder entscheiden für jede Fahrt selbst, ob ein Kurzhinweis an freigegebene Partner geht: das Datum und die ersten zwei Ziffern der Postleitzahlen von Start und Ziel. Namen, Orte, Uhrzeiten und Notizen werden nie weitergegeben.",
                "Nur direkte Partner",
                "Auch Partner unserer Partner",
                "Eine Stufe weiter",
                "Reichweite",
                "Freigegebene Partner können die Kurzhinweise abrufen. Lapis-Server rufen sie erst ab einer späteren Version selbst ab.",
                "Fahrten teilen",
                "Diese Fahrt als Kurzhinweis an Partnerorganisationen weitergeben",
                "So sehen Partner Ihre Fahrt: %1 · PLZ-Gebiet %2 → PLZ-Gebiet %3",
                "Start oder Ziel hat keine Postleitzahl – diese Fahrt kann nicht geteilt werden.",
                "Für Partner freigegeben",
                "Die Fahrt wurde gespeichert, aber nicht für Partner freigegeben. Bitte versuchen Sie es erneut.",
                "Mitfahrzentrale: Weitergabe an Partnerorganisationen",
                // V1.9.312 (wave 2)
                "Kein Signaturschlüssel des Partners bekannt (der Partner muss Version 1.9.312 oder neuer nutzen)",
                "Kurzhinweise: noch nie abgerufen",
                "Kurzhinweise abgerufen: %1 (zuletzt %2, %3 Einträge)",
                "Schlüssel für Kurzhinweis-IDs fehlt (LAPIS_SECRET_ENCRYPTION_KEY)",
                "Abgewiesene Abrufe seit dem Start: %1",
                "Gedrosselte Abrufe seit dem Start: %1",
                "Mitfahr-Kurzhinweise abgerufen",
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
