package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.334 -- completeness of the wording of every system mail (the `*Texts` objects with [MailText] fields): eight languages, none empty,
 * none identical to German unless explicitly allowed, the same `{token}` set in every language, no string templates in the source.
 */
private val textObjects: List<Any> =
    listOf(
        MailCommonTexts,
        PasswordResetTexts,
        FriendVerificationTexts,
        PartgReviewerCodeTexts,
        AdminPasswordResetTexts,
        KeycloakLinkTexts,
        KeycloakProvisioningTexts,
        EmailChangeTexts,
        PeerNotificationTexts,
        WebhookDeactivatedTexts,
        FinTsReauthTexts,
        EncounterEntryTexts,
        CarpoolNoticeTexts,
        ArticleReviewTexts,
    )

private fun fieldsOf(holder: Any): List<Pair<String, MailText>> =
    holder.javaClass.declaredFields
        .filter { it.type == MailText::class.java }
        .map { field ->
            field.isAccessible = true
            "${holder.javaClass.simpleName}.${field.name}" to (field.get(holder) as MailText)
        }

/**
 * Fields that may be spelled like German in another language, with the reason. Kept as small as possible.
 * (Language -> field). Cognates and identical spellings only.
 */
private val sameAsGerman: Map<String, Set<String>> =
    mapOf(
        // "Webhook"/"Webhook deaktiviert"-like headings are international words in some languages.
        "WebhookDeactivatedTexts.heading" to setOf("de"),
    )

class MailTextsCompletenessTest :
    FunSpec({
        val all = textObjects.flatMap(::fieldsOf)

        test("every text object contributes fields (reflection works)") {
            (all.size >= 100) shouldBe true
        }

        test("every MailText has exactly the eight supported languages and none is blank") {
            all.forEach { (name, text) ->
                text.byCode().keys shouldBe MembershipDecisionTexts.SUPPORTED
                text
                    .byCode()
                    .filterValues { it.isBlank() }
                    .keys
                    .also { it.shouldBeEmpty() }
                check(name.isNotEmpty())
            }
        }

        test("every language differs from German, except an explicit and reasoned allowlist") {
            val offenders =
                all.flatMap { (name, text) ->
                    text
                        .byCode()
                        .filter { (code, value) ->
                            code != "de" && value == text.de && code !in (sameAsGerman[name] ?: emptySet())
                        }.keys
                        .map { "$name/$it" }
                }
            offenders.shouldBeEmpty()
        }

        test("the token set of every language equals the German one") {
            val offenders =
                all.flatMap { (name, text) ->
                    val expected = mailTokens(text.de)
                    text
                        .byCode()
                        .filter { (_, value) -> mailTokens(value) != expected }
                        .keys
                        .map { "$name/$it" }
                }
            offenders.shouldBeEmpty()
        }

        test("no German text carries a gender marker (no star, colon, underscore or slash form)") {
            val marker = Regex("(?i)[a-zäöü](\\*|:|_|/)innen|Innen\\b")
            all.filter { (_, text) -> marker.containsMatchIn(text.de) }.map { it.first }.shouldBeEmpty()
        }

        test("the *Texts.kt sources contain no string templates (placeholders go through fillMail only)") {
            val dir = File("src/main/kotlin/network/lapis/cloud/server/mail")
            val offenders =
                dir
                    .listFiles { f ->
                        f.name.endsWith("Texts.kt") &&
                            f.name != "MembershipDecisionTexts.kt" &&
                            f.name != "TenderPayoutTexts.kt"
                    }!!
                    .filter { file ->
                        file.readLines().any { line ->
                            line.trimStart().startsWith("//").not() &&
                                line.contains("\${") &&
                                line.contains("'$'").not()
                        }
                    }.map { it.name }
            offenders.shouldBeEmpty()
        }

        test("fillMail expands in ONE pass and leaves an unknown token literal") {
            fillMail(template = "a {x} b {y} {z}", values = mapOf("x" to "{y}", "y" to "Y")) shouldBe "a {y} b Y {z}"
            fillMail(template = "{brand}", values = mapOf("brand" to "{brand} \$1 \\")) shouldBe "{brand} \$1 \\"
        }
    })
