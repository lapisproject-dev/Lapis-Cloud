package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * Welle V1.9.55 -- on money paths a caught `ExposedSQLException` is only allowed to mean "unique violation": a lock or
 * statement timeout that is swallowed as "duplicate" would, for a PSP webhook, 200-acknowledge a delivery whose twin may
 * still roll back (the payment is then lost for good). Every catch/is-check in the scanned files must therefore reach
 * `isUniqueViolation()` / SQLSTATE 23505 within its block, or be allowlisted with a reason.
 */
class MoneyPathSqlCatchTripwireTest :
    FunSpec({
        val moneyPrefixes =
            listOf(
                "network/lapis/cloud/server/payment/",
                "network/lapis/cloud/server/accounting/",
                "network/lapis/cloud/server/rpc/AccountingService.kt",
                "network/lapis/cloud/server/rpc/DunningService.kt",
                "network/lapis/cloud/server/rpc/ContributionService.kt",
                "network/lapis/cloud/server/rpc/PaymentGatewayService.kt",
            )

        // "<relative path>:<n-th occurrence>" -> reason
        val allowlist = emptyMap<String, String>()

        fun blockAfter(
            text: String,
            from: Int,
        ): String {
            val open = text.indexOf('{', from)
            if (open == -1) return ""
            var depth = 0
            var j = open
            while (j < text.length) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            return text.substring(open, minOf(j + 1, text.length))
        }

        test("every ExposedSQLException catch on a money path is narrowed to unique violations") {
            val root = SourceScan.mainRoot()
            val offenders = mutableListOf<String>()
            var catches = 0
            SourceScan.mainFiles().forEach { file ->
                val rel = file.relativeTo(root).path.replace('\\', '/')
                if (moneyPrefixes.none { rel.startsWith(it) }) return@forEach
                val blanked = SourceScan.blank(file.readText())
                val regex = Regex("""catch\s*\(\s*\w+\s*:\s*(?:org\.jetbrains\.exposed\.v1\.exceptions\.)?ExposedSQLException\s*\)""")
                regex.findAll(blanked).forEachIndexed { idx, m ->
                    catches++
                    val block = blockAfter(blanked, m.range.last)
                    // asNameTakenOrSelf narrows by the unique-index NAME (ContributionService) and rethrows everything else.
                    val narrowed =
                        "isUniqueViolation" in block || "23505" in block || "UNIQUE_VIOLATION" in block || "asNameTakenOrSelf" in block
                    if (!narrowed &&
                        "$rel:$idx" !in allowlist
                    ) {
                        offenders += "$rel:${SourceScan.lineOf(text = blanked, index = m.range.first)}"
                    }
                }
                // `x is ExposedSQLException` checks (e.g. on a runCatching result): the enclosing if-branch must narrow too.
                Regex("""\bis\s+ExposedSQLException\b""").findAll(blanked).forEach { m ->
                    catches++
                    val lineEnd = blanked.indexOf('\n', m.range.last).let { if (it == -1) blanked.length else it }
                    val line = blanked.substring(blanked.lastIndexOf('\n', m.range.first) + 1, lineEnd)
                    val narrowed = "sqlState" in line || "isUniqueViolation" in line || "UNIQUE_VIOLATION" in line
                    if (!narrowed) {
                        offenders +=
                            "$rel:${SourceScan.lineOf(text = blanked, index = m.range.first)} (is-check, use isUniqueViolation())"
                    }
                }
            }
            catches shouldBeGreaterThan 0
            offenders shouldBe emptyList()
        }
    })
