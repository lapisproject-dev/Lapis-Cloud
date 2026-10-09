package network.lapis.cloud.server.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.mail.budget.MailLane
import java.io.File

private val mainRoot: File = File("src/main/kotlin").let { if (it.exists()) it else File("lapis-server/src/main/kotlin") }

private fun mainSources(): List<File> = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

/**
 * Welle V1.9.81 -- the registry of mail purposes ([MailPurpose]) may not rot.
 *  - every `purpose = "..."` string literal handed to the mail dispatcher in main code is registered (an unregistered purpose silently gets
 *    priority 1 / lane SYSTEM and a log WARN -- harmless but wrong for a security mail);
 *  - the set of priority-0 purposes is pinned exactly (a password reset must never quietly lose its priority, a marketing mail must never gain it);
 *  - only the one intended purpose draws from the BULK lane.
 */
class MailPurposeRegistryTripwireTest :
    FunSpec({
        // purpose = "literal"  -- the named-argument form used at every dispatcher.enqueue(...) call site
        val literal = Regex("""\bpurpose\s*=\s*"([a-z0-9-]+)"""")
        // the dynamic ones: purpose = "article-review-${...}" is covered by a registered prefix
        val templated = Regex("""\bpurpose\s*=\s*"([a-z0-9-]+)\$\{""")

        test("every purpose literal in main code is registered") {
            val unregistered = mutableListOf<String>()
            mainSources().forEach { file ->
                val text = file.readText()
                literal.findAll(text).forEach { m ->
                    val purpose = m.groupValues[1]
                    // Other uses of a parameter called `purpose` (a PSP payment purpose, a bank-statement line, ...) are not mail purposes:
                    // only a literal that is passed to the mail dispatcher / outbox -- i.e. close behind an `enqueue(` / `OutboundMail(`
                    // call, or anywhere in a `*Mailer.kt` -- has to be registered.
                    val before = text.substring((m.range.first - 600).coerceAtLeast(0), m.range.first)
                    val isMail = file.name.endsWith("Mailer.kt") || before.contains("enqueue(") || before.contains("OutboundMail(")
                    if (isMail && !MailPurpose.isKnown(purpose)) unregistered += "${file.name}: $purpose"
                }
                templated.findAll(text).forEach { m ->
                    if (MailPurpose.KNOWN_PREFIXES.none { it == m.groupValues[1] }) {
                        unregistered +=
                            "${file.name}: ${m.groupValues[1]}\${...}"
                    }
                }
            }
            unregistered.shouldBeEmpty()
        }

        test("the purposes that really exist in main code are all found by the scan (guards the scan itself)") {
            val found = mainSources().flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
            ("event-ticket" in found) shouldBe true // sent from PspWebhookCommon (routes/): the scan covers every main directory
            listOf("password-reset", "friend-email-verification", "email-change-confirm", "event-cancelled", "event-registration").forEach {
                (it in found) shouldBe true
            }
        }

        test("the set of priority-0 purposes is exactly the security-relevant one") {
            MailPurpose.PRIORITY_ZERO_PURPOSES shouldBe
                setOf(
                    "password-reset",
                    "email-change-confirm",
                    "email-change-warning",
                    "email-change-self-info",
                    "email-change-applied-info",
                    "friend-email-verification",
                    "admin-password-reset-notice",
                    "keycloak-member-provisioned",
                )
            MailPurpose.KNOWN_PURPOSES.forEach { purpose ->
                val policy = MailPurpose.policyFor(purpose)
                (policy.priority == 0) shouldBe (purpose in MailPurpose.PRIORITY_ZERO_PURPOSES)
                // priority 0 is always short-lived, priority 1 never expires
                (policy.outboxTtl != null) shouldBe (policy.priority == 0)
            }
        }

        test("only event-cancelled draws from the BULK lane; article-review-* and unknown purposes are priority 1, lane SYSTEM") {
            MailPurpose.KNOWN_PURPOSES.filter { MailPurpose.policyFor(it).lane == MailLane.BULK } shouldBe listOf("event-cancelled")
            MailPurpose.policyFor("article-review-approved").let {
                it.priority shouldBe 1
                it.lane shouldBe MailLane.SYSTEM
            }
            MailPurpose.policyFor("never-heard-of-it").let {
                it.priority shouldBe 1
                it.lane shouldBe MailLane.SYSTEM
                it.outboxTtl shouldBe null
            }
        }

        test("enqueueAll (the unthrottled-by-timing direct write) is never called from the unauthenticated auth / registration paths") {
            val forbidden = listOf("enqueueAll", "persistAll")
            val offenders =
                mainSources()
                    .filter { f ->
                        f.name in
                            setOf(
                                "AuthRoutes.kt",
                                "RegistrationService.kt",
                                "PasswordResetMailer.kt",
                                "SmtpPasswordResetMailer.kt",
                            ) ||
                            f.name.startsWith("Smtp") &&
                            f.name.endsWith("Mailer.kt")
                    }.filter { f -> forbidden.any { f.readText().contains(it) } }
                    .map { it.name }
            offenders.shouldBeEmpty()
            // and the only production caller of enqueueAll today is the event cancellation
            val callers =
                mainSources()
                    .filter { f -> f.name != "MailDispatcher.kt" && f.readText().contains(".enqueueAll(") }
                    .map { it.name }
            callers shouldBe listOf("EventService.kt")
        }
    })
