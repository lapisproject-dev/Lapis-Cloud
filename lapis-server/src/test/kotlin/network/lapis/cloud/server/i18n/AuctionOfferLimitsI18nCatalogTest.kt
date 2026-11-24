package network.lapis.cloud.server.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.323 (Fiat wave b2) -- i18n guard of the price-offer limits: every message of the wave is used in the client source, is in the template and in
 * all seven catalogs with a non-empty translation that keeps its `%N` placeholders, and no German message uses a gender form. The notice texts are
 * NOT in the catalogs on purpose (they are versioned server constants, see `AuctionOfferNotice`): the two former msgids must be gone everywhere.
 */
class AuctionOfferLimitsI18nCatalogTest :
    FunSpec({
        val waveFiles = listOf("AuctionOfferUi.kt", "AppState.kt")
        val expected =
            listOf(
                "Hinweisfassung %1",
                "Der Hinweistext konnte nicht geladen werden. Bitte schließen Sie das Fenster und versuchen Sie es erneut.",
                "Das ist die dritte Ablehnung. Der Käufer kann danach %1 Tage keinen Preisvorschlag für diese Auktion machen.",
                "Sie können danach einen neuen Preisvorschlag machen.",
                "Der Verkäufer hat drei Ihrer Preisvorschläge abgelehnt. Bis %1 können Sie für diese Auktion keinen neuen machen.",
                "Offene Preisvorschläge: %1 von %2",
                "Gesperrt bis %1",
                "Ablehnungen: %1 von %2",
                "Sie haben für diese Auktion bereits einen offenen Preisvorschlag.",
                "Für diese Auktion liegen schon %1 offene Preisvorschläge vor. Bitte versuchen Sie es später noch einmal.",
                "Der Hinweistext wurde aktualisiert. Bitte lesen Sie ihn erneut und senden Sie den Vorschlag noch einmal.",
                "Dieser Preisvorschlag wurde bereits angenommen.",
                "Dieser Preisvorschlag wurde bereits abgelehnt.",
                "Der Verkäufer hat bereits einen Gegenvorschlag gemacht.",
                "Dieser Preisvorschlag ist bereits abgelaufen.",
                "Dieser Preisvorschlag wurde bereits zurückgezogen.",
            )
        val removed =
            listOf(
                "Ein Preisvorschlag bindet Sie erst, wenn der Verkäufer ihn annimmt. Er gilt höchstens bis zum Auktionsende. " +
                    "Ihre Gebote und der Sofortkauf bleiben davon unberührt.",
                "Ein Gegenvorschlag bindet Sie erst, wenn der Käufer ihn annimmt. Es ist nur ein Gegenvorschlag möglich.",
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

        test("the notice texts live on the server: their former msgids are gone from every catalog and from the client source") {
            removed.forEach { id ->
                template.containsKey(id) shouldBe false
                CATALOG_LANGUAGES.forEach { lang -> catalogs.getValue(lang).containsKey(id) shouldBe false }
                (id in used) shouldBe false
            }
        }

        test("no German message uses a gender form") {
            val forms = listOf("*innen", ":innen", "_innen", "/innen")
            expected.filter { id -> forms.any { it in id } }.size shouldBe 0
        }
    })
