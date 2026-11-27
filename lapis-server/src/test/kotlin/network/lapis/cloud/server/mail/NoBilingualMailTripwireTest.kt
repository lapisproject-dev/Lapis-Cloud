package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.334 -- quick source scan of [MailTemplates]: the two-language layout is gone. The only multi-block mails are the membership decisions
 * (recipient language plus de/en as a second block, V1.9.118 -- deliberately kept); everything else is one language.
 */
class NoBilingualMailTripwireTest :
    FunSpec({
        val source = File("src/main/kotlin/network/lapis/cloud/server/mail/MailTemplates.kt").readText()
        val code = source.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }

        test("the bilingual() layout function is gone") {
            source.contains("fun bilingual(") shouldBe false
            source.contains("bilingual(") shouldBe false
        }

        test("renderSections() is only used by the membership decision mails (recipient language plus de/en, V1.9.118)") {
            val users = code.count { it.contains("renderSections(") && !it.contains("fun renderSections(") }
            users shouldBe 1
            val decision =
                source.substring(
                    source.indexOf("private fun membershipDecision("),
                    source.indexOf("private fun renderSections("),
                )
            decision.contains("renderSections(") shouldBe true
        }

        test("no string literal of MailTemplates.kt holds German or English prose (all wording lives in the *Texts.kt files)") {
            val literal = Regex("\"([^\"\\\\]|\\\\.)*\"")
            val prose = Regex("\\b(Sie|Ihr|Ihre|Ihrem|Ihren|und|wurde|please|your|was)\\b")
            val offenders =
                code.filter { it.contains("\"") }.filter { line ->
                    literal.findAll(line).any { m -> m.value.length > 3 && prose.containsMatchIn(m.value) }
                }
            offenders.shouldBeEmpty()
        }

        test("no subject is built from two languages (no ' / ' literal)") {
            code.filter { it.contains("\" / \"") || it.contains(" / ${'$'}") }.shouldBeEmpty()
        }
    })
