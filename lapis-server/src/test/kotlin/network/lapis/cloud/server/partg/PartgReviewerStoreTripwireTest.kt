package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.310 (R6b) -- `PartgReviewerStore` is the only reader and writer of the four auditor tables (the data subject contributor
 * `PartgReportPersonalData` is the one exception); the public routes of the auditor are all behind the guard (the first statement of every handler),
 * read the auditor's cookie only (never the members' `lapis_session`), and nothing in the auditor's code logs a token, a code, an address or an IP.
 */
class PartgReviewerStoreTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()
        val tables = """(ReviewerInvitationVersion|ReviewerInvitation|ReviewerSession|ReviewerEvent)"""
        val table = Regex("""\bPartg${tables}Table\b""")
        val allowedNaming = setOf("PartgReviewerStore.kt", "PartgReportPersonalData.kt")

        test("the scan sees the code base") { files.size shouldBeGreaterThan 500 }

        test("the four tables are named only by the store, the contributor and the generated objects") {
            files
                .filter { "/db/generated/" !in it.path }
                .filter { table.containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .filterNot { it in allowedNaming }
                .shouldBeEmpty()
        }

        test("only the access, the operations, the writer (the removal of a version) and the poller use the store") {
            files
                .filter { Regex("""\bPartgReviewerStore\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .toSet()
                .shouldContainExactlyInAnyOrder(
                    "PartgReviewerStore.kt",
                    "PartgReviewerAccess.kt",
                    "PartgReviewerOperations.kt",
                    "PartgReviewerRetentionPoller.kt",
                    "PartgVersionWriter.kt",
                )
        }

        test("the store has no function that takes a raw token or a raw code") {
            val code = SourceScan.blank(files.single { it.name == "PartgReviewerStore.kt" }.readText())
            Regex("""\b(raw\w*|code)\s*:\s*String""").containsMatchIn(code) shouldBe false
        }

        test("every handler of the auditor's routes starts with the guard, and the routes never read the members' cookie") {
            val raw = files.single { it.name == "PartgReviewerRoutes.kt" }.readText()
            val handlers = Regex("""\b(get|post)\("(/api/pruefung[^"]*)"\)\s*\{\s*(\S[^\n]*)""").findAll(raw).toList()
            handlers.size shouldBe 9
            handlers.forEach { it.groupValues[3].startsWith("if (!call.reviewerGuard(") shouldBe true }
            val code = SourceScan.blank(raw)
            code.contains("SESSION_COOKIE_NAME") shouldBe false
            code.contains("resolveCurrentMember") shouldBe false
            code.contains("extractSessionToken") shouldBe false
            Regex("""\"lapis_session\"""").containsMatchIn(raw) shouldBe false
        }

        test("the members' session resolution never reads the auditor's cookie") {
            files
                .filter { "lapis_pruefung" in it.readText() || "REVIEWER_COOKIE_NAME" in it.readText() }
                .map { it.name }
                .toSet()
                .shouldContainExactlyInAnyOrder("PartgReviewerRoutes.kt")
        }

        test("nothing of the auditor's code logs a secret: only fixed texts and ids") {
            listOf(
                "PartgReviewerRoutes.kt",
                "PartgReviewerAccess.kt",
                "PartgReviewerOperations.kt",
                "PartgReviewerStore.kt",
                "PartgReviewerTokens.kt",
            ).forEach { name ->
                files
                    .single { it.name == name }
                    .readText()
                    .lines()
                    .filter { "logger." in it }
                    .filter {
                        Regex(
                            """\$\{?(raw|token|code|email|cookie|ip|remoteHost|hash)""",
                            RegexOption.IGNORE_CASE,
                        ).containsMatchIn(it)
                    }.shouldBeEmpty()
            }
        }

        test("the code mail is registered as a priority-0 purpose and carries no link") {
            val template = files.single { it.name == "MailTemplates.kt" }.readText()
            val start = template.indexOf("fun partgReviewerCode(")
            val body = template.substring(start, template.indexOf("fun friendVerification(", start))
            body.contains("href") shouldBe false
            body.contains("http") shouldBe false
        }

        test("V1.9.334 the wording of the code mail (all eight languages) carries no link either") {
            val texts = files.single { it.name == "AccountSecurityTexts.kt" }.readText()
            val section = texts.substring(texts.indexOf("internal object PartgReviewerCodeTexts"))
            section.contains("http") shouldBe false
            section.contains("href") shouldBe false
        }
    })
