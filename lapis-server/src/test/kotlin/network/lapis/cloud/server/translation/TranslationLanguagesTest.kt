package network.lapis.cloud.server.translation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.mail.MembershipDecisionTexts
import network.lapis.cloud.server.routes.PublicLanguage

class TranslationLanguagesTest :
    FunSpec({
        test("single tags") {
            TranslationLanguages.resolveTarget(listOf("fr")) shouldBe "fr"
            TranslationLanguages.resolveTarget(listOf("en-US")) shouldBe "en"
            TranslationLanguages.resolveTarget(listOf("pt-BR", "es-MX")) shouldBe "es"
        }

        test("the first supported tag wins, unsupported ones are skipped") {
            TranslationLanguages.resolveTarget(listOf("ja", "pl")) shouldBe "pl"
        }

        test("nothing supported: English") {
            TranslationLanguages.resolveTarget(listOf("ja")) shouldBe "en"
            TranslationLanguages.resolveTarget(emptyList()) shouldBe "en"
            TranslationLanguages.resolveTarget(listOf("*")) shouldBe "en"
        }

        test("case and whitespace are tolerated") {
            TranslationLanguages.resolveTarget(listOf("  FR-ca ")) shouldBe "fr"
            TranslationLanguages.resolveTarget(listOf("NL")) shouldBe "nl"
        }

        test("an over-long tag is ignored and only the first twenty entries count") {
            TranslationLanguages.resolveTarget(listOf("de-" + "x".repeat(40))) shouldBe "en"
            val many = List(20) { "ja" } + "fr"
            TranslationLanguages.resolveTarget(many) shouldBe "en"
            val ok = List(19) { "ja" } + "fr"
            TranslationLanguages.resolveTarget(ok) shouldBe "fr"
        }

        test("source normalisation") {
            TranslationLanguages.normalizeSource(null) shouldBe "de"
            TranslationLanguages.normalizeSource("") shouldBe "de"
            TranslationLanguages.normalizeSource("xx") shouldBe "de"
            TranslationLanguages.normalizeSource(" EN ") shouldBe "en"
        }

        test("the set equals the mail languages and the public site languages, in the site order") {
            TranslationLanguages.SUPPORTED.toSet() shouldBe MembershipDecisionTexts.SUPPORTED
            TranslationLanguages.SUPPORTED shouldBe PublicLanguage.entries.map { it.code }
        }
    })
