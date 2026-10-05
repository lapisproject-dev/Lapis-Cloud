package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * Welle V1.9.55 -- tripwires for the two transaction rules (docs/architecture/database-timeouts-and-retries.adoc):
 *
 * 1. External effects (provider HTTP calls, mail, letters, accounting pushes, blocking `runBlocking`) never run inside a
 *    `transaction { }` block: Exposed re-runs the block on any SQLException (3 attempts), a lock timeout or an
 *    idle-in-transaction kill would replay the effect, and the transaction would hold a connection and row locks across
 *    a network round trip.
 * 2. No session-wide `SET lock_timeout|statement_timeout|idle_in_transaction_session_timeout` in main code (only
 *    `SET LOCAL`), because a session-wide SET poisons the pooled connection for every later borrower.
 */
class ExternalEffectsOutsideTransactionTripwireTest :
    FunSpec({
        val forbidden =
            listOf(
                "runBlocking" to Regex("""\brunBlocking\b"""),
                "provider createCheckout" to Regex("""\.createCheckout\("""),
                "provider captureOrder" to Regex("""\.captureOrder\("""),
                "mail transport send" to Regex("""\b[tT]ransport\.send\("""),
                "mailer send" to Regex("""\b\w*[mM]ailer\w*\.send\("""),
                "mail dispatcher enqueue" to Regex("""\b\w*[mM]ailDispatcher\w*\.enqueue\("""),
                "postal dispatchLetter" to Regex("""\.dispatchLetter\("""),
                "accounting pushVoucher" to Regex("""\.pushVoucher\("""),
                "federation/webhook delivery" to Regex("""\b(WebhookDeliverer|FederationHttpClient)\w*\.(post|deliver|send)\w*\("""),
            )

        // path (relative to src/main/kotlin) + token name -> reason. Start empty: every entry needs a justification.
        val allowlist = emptyMap<Pair<String, String>, String>()

        test("no external effect inside a transaction block") {
            val root = SourceScan.mainRoot()
            var blocks = 0
            val offenders = mutableListOf<String>()
            SourceScan.mainFiles().forEach { file ->
                val blanked = SourceScan.blank(file.readText())
                val ranges = SourceScan.transactionBlocks(blanked)
                blocks += ranges.size
                val rel = file.relativeTo(root).path
                ranges.forEach { r ->
                    val body = blanked.substring(r.first, r.last + 1)
                    forbidden.forEach { (name, regex) ->
                        regex.findAll(body).forEach { m ->
                            if ((rel to name) !in allowlist) {
                                offenders += "$rel:${SourceScan.lineOf(text = blanked, index = r.first + m.range.first)} $name"
                            }
                        }
                    }
                }
            }
            // Self-check: a broken matcher that finds no blocks must not pass silently.
            blocks shouldBeGreaterThan 1000
            offenders shouldBe emptyList()
        }

        test("no session-wide SET of a timeout variable in main code, only SET LOCAL") {
            val regex =
                Regex(
                    """\bSET\s+(?!LOCAL\b)(?:SESSION\s+)?(lock_timeout|statement_timeout|idle_in_transaction_session_timeout)\b""",
                    RegexOption.IGNORE_CASE,
                )
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { it.name != "DbSessionTimeouts.kt" }
                    .flatMap { file ->
                        // Raw text on purpose: the SQL lives inside string literals.
                        val text = file.readText()
                        regex.findAll(text).map { "${file.name}:${SourceScan.lineOf(text = text, index = it.range.first)}" }.toList()
                    }
            offenders shouldBe emptyList()
        }
    })
