package network.lapis.cloud.server.translation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun explicit(target: String) = LanguageChoice.Target(target = target, explicit = true)

private fun fromHeader(target: String) = LanguageChoice.Target(target = target, explicit = false)

class LanguageNegotiationTest :
    FunSpec({
        fun header(value: String) = LanguageNegotiation.choose(langParams = null, acceptLanguage = listOf(value))

        fun lang(vararg values: String) = LanguageNegotiation.choose(langParams = values.toList(), acceptLanguage = listOf("fr"))

        test("?lang beats Accept-Language, case and region are normalised, unsupported falls back to English") {
            LanguageNegotiation.choose(langParams = listOf("fr"), acceptLanguage = listOf("en")) shouldBe explicit("fr")
            lang("FR") shouldBe explicit("fr")
            lang("fr-CA") shouldBe explicit("fr")
            lang("pt") shouldBe explicit("en")
        }

        test("a broken or repeated ?lang gives English and the header is not read") {
            val hostile =
                listOf("", "<script>", "de\r\nX-Evil:1", "фг", "../", "de;q=1", "*", "a".repeat(36), "fr_CA", " fr")
            for (value in hostile) lang(value) shouldBe explicit("en")
            lang("fr", "de") shouldBe explicit("en")
            LanguageNegotiation.choose(langParams = emptyList(), acceptLanguage = listOf("fr")) shouldBe explicit("en")
        }

        test("q-values order the header and q=0, * and bad weights are dropped") {
            header("en;q=0.5, fr;q=0.9") shouldBe fromHeader("fr")
            header("fr;q=0, it") shouldBe fromHeader("it")
            header("it, nl") shouldBe fromHeader("it")
            header("fr;q=abc, it") shouldBe fromHeader("it")
            header("fr;q=1.5, it;q=0.1") shouldBe fromHeader("it")
            header("fr;q=0.0001, it;q=0.1") shouldBe fromHeader("it")
            header("fr;Q=0.8, it;q=0.2") shouldBe fromHeader("fr")
            header("en-GB;level=1;q=0.4, nl;q=0.8") shouldBe fromHeader("nl")
            header("ja") shouldBe fromHeader("en")
            header("ja, es;q=0.3") shouldBe fromHeader("es")
        }

        test("no usable wish means no preference") {
            LanguageNegotiation.choose(langParams = null, acceptLanguage = emptyList()) shouldBe LanguageChoice.NoPreference
            header("*") shouldBe LanguageChoice.NoPreference
            header(",,;;==") shouldBe LanguageChoice.NoPreference
            header("") shouldBe LanguageChoice.NoPreference
            header("fr;q=0") shouldBe LanguageChoice.NoPreference
        }

        test("input bounds: oversized headers are treated as absent, never throw, stay fast") {
            header("fr," + "x".repeat(1_100)) shouldBe LanguageChoice.NoPreference
            val huge = "fr;q=0.5,".repeat(8_000)
            val started = System.nanoTime()
            LanguageNegotiation.choose(langParams = null, acceptLanguage = listOf(huge)) shouldBe LanguageChoice.NoPreference
            ((System.nanoTime() - started) / 1_000_000 < 2_000) shouldBe true
            header("a-".repeat(30) + "b") shouldBe LanguageChoice.NoPreference
        }

        test("only the first 32 entries count, several header lines are joined") {
            // 40 entries that are not valid tags are skipped, and "fr" is the 41st: beyond the 32-entry cap
            header((1..40).joinToString(",") { "xx$it" } + ",fr") shouldBe LanguageChoice.NoPreference
            // the language resolver itself looks at the first 20 preferences only
            header((1..19).joinToString(",") { "xx" } + ",fr") shouldBe fromHeader("fr")
            header((1..20).joinToString(",") { "xx" } + ",fr") shouldBe fromHeader("en")
            LanguageNegotiation.choose(langParams = null, acceptLanguage = listOf("ja", "it;q=0.9")) shouldBe fromHeader("it")
        }
    })
