package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MailLanguageTest :
    FunSpec({
        listOf(
            Triple("en", "pl", "en"),
            Triple("xx", "pl", "pl"),
            Triple(null, "pl", "pl"),
            Triple(null, "xx", "de"),
            Triple(null, null, "de"),
            Triple(" ", "fr", "fr"),
            Triple("EN", "pl", "en"),
            Triple("e\u0000n", "pl", "pl"),
            Triple("e".repeat(500), "ru", "ru"),
        ).forEach { (recipient, instance, expected) ->
            test("recipient=$recipient instance=$instance -> $expected") {
                MailLanguage.resolve(recipient = recipient, instance = instance) shouldBe expected
            }
        }

        test("isSupported uses the normalisation of normalize") {
            MembershipDecisionTexts.isSupported("PL") shouldBe true
            MembershipDecisionTexts.isSupported(" pl ") shouldBe true
            MembershipDecisionTexts.isSupported("xx") shouldBe false
            MembershipDecisionTexts.isSupported("") shouldBe false
            MembershipDecisionTexts.isSupported(null) shouldBe false
            MembershipDecisionTexts.normalize("PL") shouldBe "pl"
            MembershipDecisionTexts.normalize("xx") shouldBe "de"
        }

        test("the server supports exactly the eight languages the client offers") {
            MembershipDecisionTexts.SUPPORTED shouldBe setOf("de", "en", "es", "fr", "it", "nl", "pl", "ru")
        }
    })

class MailLanguageInstanceTest :
    FunSpec({
        test("instance() reads the source and normalises it") {
            MailLanguage.instance { "pl" } shouldBe "pl"
            MailLanguage.instance { " FR " } shouldBe "fr"
        }

        test("instance() never throws: a failing or unknown source means German") {
            MailLanguage.instance { error("boom") } shouldBe "de"
            MailLanguage.instance { "xx" } shouldBe "de"
            MailLanguage.instance { "" } shouldBe "de"
        }

        test("the recipient language wins over the instance language, an unknown one falls through") {
            MailLanguage.resolve(recipient = "pl", instance = "fr") shouldBe "pl"
            MailLanguage.resolve(recipient = "xx", instance = "fr") shouldBe "fr"
            MailLanguage.resolve(recipient = null, instance = null) shouldBe "de"
        }
    })
