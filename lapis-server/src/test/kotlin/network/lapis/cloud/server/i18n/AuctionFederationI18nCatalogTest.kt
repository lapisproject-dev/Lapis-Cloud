package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.322 (Fiat wave c, step c2) -- i18n guard of the auction-exchange client. Every message of the step is used in the client source, is in the
 * template and in all seven catalogs with a non-empty translation that keeps its `%N` placeholders, and no German message uses a gender form. The two
 * plural pairs are checked for the right number of forms.
 */
class AuctionFederationI18nCatalogTest :
    FunSpec({
        val waveFiles =
            listOf(
                "AuctionFederationUi.kt",
                "AuctionCreateListingForm.kt",
                "AuctionHintScreen.kt",
                "PartnerServersLogic.kt",
                "PartnerServersScreen.kt",
                // V1.9.322 c3: the receiving side
                "PartnerAuctionHintsUi.kt",
            )
        val expected =
            listOf(
                "Diese Auktion als Kurzhinweis an Partnerorganisationen weitergeben",
                "Sofortkauf %1",
                "Preisvorschlag möglich",
                "So sehen Partner Ihre Auktion: %1 · %2 %3 · endet %4",
                "Partner erhalten Titel, Preis, Ende und – falls vorhanden – den Sofortkaufpreis. Der Preis wird höchstens alle %1 Minuten aktualisiert; ein Preissprung lässt erkennen, ob geboten wurde.",
                "Bieten und Kaufen ist nur bei uns möglich. Verkäufer, Bieter, Gebote und Zahlungsangaben werden nie weitergegeben.",
                "Der Titel dieser Auktion kann Partnern nicht gezeigt werden (leer, Link oder Sonderzeichen).",
                "Diese Auktion endet in weniger als %1 Stunden und kann nicht mehr geteilt werden.",
                "Sie teilen bereits %1 Auktionen. Weitere können nicht geteilt werden.",
                "Das Teilen von Auktionen mit Partnerorganisationen ist derzeit nicht möglich.",
                "Diese Auktion kann nicht mit Partnern geteilt werden.",
                "Die Auktion wurde gespeichert, aber nicht für Partner freigegeben. %1",
                "Bitte versuchen Sie es bei der Auktion erneut.",
                "Auktionshinweis",
                "Diese Auktion ist nicht (mehr) verfügbar.",
                "Zu den Auktionen",
                "Die geteilten Auktionen werden bei %1 zurückgezogen. Bei den anderen Partnern ändert sich nichts.",
                "Auktionen auf Partnerservern",
                "Euro-Auktionen mit freigegebenen Partnern teilen",
                "Mitglieder entscheiden für jede Euro-Auktion selbst, ob ein Kurzhinweis an freigegebene Partner geht: Titel, Preis, Ende und Sofortkaufpreis. Verkäufer, Bieter, Gebote und Zahlungsangaben werden nie weitergegeben. Bieten und Kaufen ist nur beim anbietenden Server möglich.",
                "Auktionsaustausch ausschalten?",
                "Auktionen teilen",
                "Auktionen für diesen Partner zurückziehen?",
                // V1.9.322 c3: the receiving side (cards of partner auctions, the pull lines and the rejection causes)
                "Auktionen von Partnerservern",
                "Kurzhinweise von Partnerservern: Titel, Preis und Ende. Bieten und Kaufen ist nur beim anbietenden Server möglich.",
                "Noch keine Auktionen von Partnerservern.",
                "LTR von %1",
                "· Stand: gerade eben",
                "· Stand: vor %1 Min.",
                "· Stand: vor %1 Std.",
                "endet am %1",
                "endet in weniger als 1 Min.",
                "endet in %1 Min.",
                "Auf %1 ansehen",
                "Auf %1 ansehen (öffnet neues Fenster)",
                "Hinweis ausblenden",
                "Hinweis ausblenden?",
                "Der Hinweis von %1 verschwindet bei uns und kommt nicht wieder, auch wenn der Partnerserver ihn erneut meldet. Der Partnerserver erfährt davon nichts.",
                "Hinweis ausgeblendet.",
                "Der Hinweis konnte nicht ausgeblendet werden.",
                "Abruf der Auktionshinweise fehlgeschlagen: %1",
                "Auktionshinweise zuletzt abgerufen: %1 (%2 übernommen, %3 abgelehnt)",
                "Empfangene Auktionshinweise von diesem Partner: %1",
                "Abruf der Auktionshinweise pausiert nach %1 Fehlschlägen",
                "Empfangene Auktionshinweise: %1",
                "Ausgeblendete Auktionshinweise: %1",
                "Unbekanntes Feld: %1",
                "Titel nicht darstellbar: %1",
                "Währung nicht zulässig: %1",
                "Betrag außerhalb des Bereichs: %1",
                "Ende ungültig: %1",
                "Preisstand ungültig: %1",
                "Ältere Fassung wiedereingespielt: %1",
                "Inhalt bei gleicher Fassung verändert: %1",
            )
        val plurals =
            mapOf(
                "Die %1 geteilte Auktion wird bei den Partnern zurückgezogen. Die Zustimmungen der Verkäufer bleiben bestehen." to
                    "Die %1 geteilten Auktionen werden bei den Partnern zurückgezogen. Die Zustimmungen der Verkäufer bleiben bestehen.",
                "Derzeit geteilt: %1 Auktion" to "Derzeit geteilt: %1 Auktionen",
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

        test("the client source uses every message of the step (not vacuous)") {
            expected.filter { it !in used }.shouldBeEmpty()
        }

        test("every message is in the template and in all seven catalogs, translated") {
            val problems =
                expected.flatMap { id ->
                    buildList {
                        if (!template.containsKey(id)) add("pot: \"$id\"")
                        CATALOG_LANGUAGES.forEach { lang ->
                            val translation = catalogs.getValue(lang)[id]
                            if (translation == null) {
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
            (expected + plurals.keys + plurals.values).filter { id -> forms.any { it in id } }.size shouldBe 0
        }

        test("the plural pairs are in the template and in all seven catalogs with the right number of forms") {
            val template = parsePluralCatalog(File(CATALOG_DIR, "messages.pot"))
            plurals.forEach { (singular, plural) ->
                (template[singular]?.pluralMsgid) shouldBe plural
                CATALOG_LANGUAGES.forEach { lang ->
                    val entry = parsePluralCatalog(File(CATALOG_DIR, "messages-$lang.po"))[singular]
                    (entry != null) shouldBe true
                    entry!!.forms.size shouldBe (if (lang == "pl" || lang == "ru") 3 else 2)
                    entry.forms.all { it.isNotBlank() && "%1" in it } shouldBe true
                }
            }
        }
    })
