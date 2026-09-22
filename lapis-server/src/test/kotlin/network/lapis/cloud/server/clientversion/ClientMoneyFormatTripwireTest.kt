package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * W6a "localized and exact amounts": four rules that keep the ONE money formatter (`Money.kt`) the only one. Client sources are read as
 * text (`jsTest` under Karma has no file system, so every tripwire lives here); comment lines are exempt, and every rule has a positive
 * and a negative example so a broken regex cannot go silent. Gradle runs server tests with `lapis-server` as the working directory.
 */
private val CLIENT_SOURCES =
    File("../lapis-client/src/jsMain/kotlin")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

/** M1: `Intl`/`toLocaleString`/`toFixed` round to two places by default (12.345 -> 12,35) and depend on the ICU version. */
private val FORBIDDEN_NUMBER_API = Regex("""Intl\.NumberFormat|toLocaleString\(|toFixed\(""")

/** M2: the display minus U+2212 must never reach a value, a `Decimal` or a payload. */
private const val DISPLAY_MINUS = '−'
private val MACHINE_CONTEXT = Regex("""setValue\(|\bvalue\s*=|Decimal|toDouble|toInt|toLong""")

/** M3: a money token concatenated into another string leaks the i18n marker (audit V1.4.30). */
private val TOKEN_CALL = Regex("""\b(?:moneyToken|ltrToken)\(""")
private val TOKEN_CONCATENATION = Regex(""""\s*\+|\+\s*"|\$\{|\$\w|gettext\(""")

/** M4: a second `" €"` / `" LTR"` suffix literal is a second formatter. */
private val SUFFIX_LITERAL = Regex("""(?:%\d|\$\w+|\})[ \u00A0](?:€|LTR)"""")

/** M5: a `Decimal` amount rendered by a bare `.toString()` skips the formatter (field list = every `Decimal` property declared in lapis-shared, checked by grep at W6a). */
private const val DECIMAL_FIELDS =
    "accumulatedResult|allocated|amountDue|amountEur|amountIn|amountLtr|amountOut|anchorPrice|" +
        "anchorUnitsPerLtr|annualCap|annualTotal|auctionMaxValueLtr|authorFreeBalanceLtr|balance|" +
        "basketTotalLtr|bookedEquity|buyNowPriceLtr|closingBalance|closingOverdue|" +
        "closingTimelyUseObligation|combinedTrustWeight|contributionAmount|contributionsOutstanding|" +
        "contributionsPaid|contributionsWaived|creditTotal|currentPriceLtr|currentWeightLtr|" +
        "debitTotal|donationAmount|donationsTotal|exceedingAmountSnapshot|feeAmount|finalPriceLtr|" +
        "freeAmountSnapshot|freeBalanceLtr|fundsAllocatedToReserves|fundsReceived|fundsUsed|" +
        "grossAmount|grossTotal|groupConflictViableThreshold|groupConflictWarnThreshold|" +
        "guestTrustWeight|honoraryCap|initialWeightLtr|instructorCap|lastDebitedAmount|" +
        "listingFeeLtr|ltrMinted|maxBidLtr|maxCheckoutAmountEur|maxNettableAmount|medianPrice|" +
        "memberTrustWeight|mileageRatePerKm|netTotal|openAmount|openingBalance|overdueAmount|" +
        "ownCurrentWeightLtr|paidAmount|payableOpenAmountAfter|payableOpenTotal|perDiemRate|" +
        "periodResult|postedTotalInThisOrganization|priorPostedTotalThisYear|priorTotalSnapshot|" +
        "priorYearVatBalance|rateSnapshot|receivableOpenAmountAfter|receivableOpenTotal|" +
        "remainingInThisOrganization|result|returnFee|runningBalance|secondPriceLtr|settledLtr|" +
        "stakeLtr|startingBidLtr|taxableGrossTotal|timelyUseObligationRemaining|totalAmount|" +
        "totalAssets|totalCurrentWeightLtr|totalDue|totalEquityAndLiabilities|totalExpense|" +
        "totalFeesCharged|totalFundsAllocatedToReserves|totalFundsReceived|totalFundsUsed|" +
        "totalGross|totalIncome|totalInputVat|totalLiabilities|totalOpen|totalOutputVat|totalPaid|" +
        "unassignedExpense|unassignedIncome|unassignedResult|unclassifiedGrossTotal|vatAmount|" +
        "vatBearingGrossTotal|vatTotal|yourMaxBidLtr|amount|kilometers"
private val RAW_DECIMAL_TOSTRING = Regex("""\b(?:$DECIMAL_FIELDS)\.toString\(\)""")

/**
 * M6: a `Decimal` field passed raw as a gettext argument -- `substitute` turns it into the same bare `toString()`. Evaluated per
 * whole call (parenthesis balance across lines, so a reformatted multi-line call stays covered), on the top-level arguments.
 */
private val RAW_DECIMAL_ARGUMENT = Regex("""^[\w.?]*\b(?:$DECIMAL_FIELDS)$""")

/** The top-level arguments (after the msgid) of every `gettext(` call in [text], across line breaks. */
internal fun gettextArguments(text: String): List<List<String>> {
    val calls = mutableListOf<List<String>>()
    var from = text.indexOf("gettext(")
    while (from >= 0) {
        var i = from + "gettext(".length
        var depth = 1
        var inString = false
        val current = StringBuilder()
        val args = mutableListOf<String>()
        while (i < text.length && depth > 0) {
            val c = text[i]
            when {
                inString -> {
                    current.append(c)
                    if (c == '\\' && i + 1 < text.length) {
                        current.append(text[++i])
                    } else if (c == '"') {
                        inString = false
                    }
                }
                c == '"' -> {
                    inString = true
                    current.append(c)
                }
                c == '(' || c == '{' || c == '[' -> {
                    depth++
                    current.append(c)
                }
                c == ')' || c == '}' || c == ']' -> {
                    depth--
                    if (depth > 0) current.append(c)
                }
                c == ',' && depth == 1 -> {
                    args += current.toString().trim()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        current
            .toString()
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { args += it }
        calls += args.drop(1)
        from = text.indexOf("gettext(", from + 1)
    }
    return calls
}

private fun rawDecimalGettextCalls(file: File): List<String> =
    gettextArguments(codeLines(file).joinToString("\n")).flatMap { args ->
        // a bare local `amount` is too generic (StatuteQaScreen's is an Int wait time); a qualified `payment.amount` is a field
        args.filter { it != "amount" && RAW_DECIMAL_ARGUMENT.matches(it) }
    }

/** Sites in `MotionsScreen.kt` that still bake " LTR" into a string (measured at W6a, closed in W6b). Only ever lowered. */
private const val MOTIONS_SCREEN_LEDGER = 0

/** Raw `Decimal` gettext arguments still in `MotionsScreen.kt` (measured at W6a by the whole-call detector, LTR-auction stakes; closed in W6b). Only ever lowered. */
private const val MOTIONS_SCREEN_RAW_ARG_LEDGER = 0

private fun codeLines(file: File): List<String> = file.readLines().filterNot { isCommentLine(it) }

class ClientMoneyFormatTripwireTest :
    FunSpec({
        val files = CLIENT_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val others = files.filter { it.name != "Money.kt" }

        test("the scan sees the client sources") {
            (files.size > 100) shouldBe true
            files.any { it.name == "Money.kt" } shouldBe true
        }

        test("M1: no Intl.NumberFormat / toLocaleString / toFixed in the client, and the detector sees one") {
            files.flatMap { f ->
                codeLines(f).filter { FORBIDDEN_NUMBER_API.containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" }
            } shouldBe
                emptyList()
            FORBIDDEN_NUMBER_API.containsMatchIn("""x.asDynamic().toFixed(2)""") shouldBe true
            FORBIDDEN_NUMBER_API.containsMatchIn("""Intl.NumberFormat("de")""") shouldBe true
            FORBIDDEN_NUMBER_API.containsMatchIn("""formatMoney(x)""") shouldBe false
        }

        test("M2: the display minus U+2212 stays out of values -- outside Money.kt only inside a tr/gettext msgid") {
            val findings =
                others.flatMap { f ->
                    codeLines(f)
                        .filter { it.contains(DISPLAY_MINUS) }
                        .filter { MACHINE_CONTEXT.containsMatchIn(it) || !(it.contains("tr(") || it.contains("gettext(")) }
                        .map { "${f.name}: ${it.trim()}" }
                }
            findings shouldBe emptyList()
            MACHINE_CONTEXT.containsMatchIn("""field.setValue("$DISPLAY_MINUS" + x)""") shouldBe true
            MACHINE_CONTEXT.containsMatchIn("""val d = "${DISPLAY_MINUS}1".toDouble()""") shouldBe true
        }

        test("M3: a money token is never concatenated into a string nor passed to gettext, and the detector sees both") {
            val findings =
                files.flatMap { f ->
                    codeLines(f)
                        .filter { TOKEN_CALL.containsMatchIn(it) && TOKEN_CONCATENATION.containsMatchIn(it) }
                        .map { "${f.name}: ${it.trim()}" }
                }
            findings shouldBe emptyList()
            TOKEN_CONCATENATION.containsMatchIn("""val s = "Offen: " + moneyToken(x)""") shouldBe true
            TOKEN_CONCATENATION.containsMatchIn("""gettext("Offen: %1", moneyToken(x))""") shouldBe true
            TOKEN_CONCATENATION.containsMatchIn("""val s = "${'$'}{moneyToken(x)} extra"""") shouldBe true
            TOKEN_CONCATENATION.containsMatchIn("""trFormat(tr("Offen: %1"), moneyToken(x))""") shouldBe false
            TOKEN_CONCATENATION.containsMatchIn("""span(moneyToken(amount)) {""") shouldBe false
        }

        test("M4: no second euro / LTR suffix literal outside Money.kt, and the detector sees one") {
            val findings = others.flatMap { f -> codeLines(f).filter { SUFFIX_LITERAL.containsMatchIn(it) }.map { f.name to it.trim() } }
            // Ledger of the former known gap (W6a): the LTR-auction vote stakes of MotionsScreen carried a baked-in " LTR" inside their
            // msgids (`"%1 LTR"`, `"Ihr Gebot: %1, %2 LTR"`) and one string template. Fixing them meant changing those msgids in all
            // eight catalogs; W6b did exactly that, so the ledger is now 0. The number may only go DOWN, never back up.
            findings.filter { it.first != "MotionsScreen.kt" } shouldBe emptyList()
            (findings.count { it.first == "MotionsScreen.kt" } <= MOTIONS_SCREEN_LEDGER) shouldBe true
            SUFFIX_LITERAL.containsMatchIn("""val s = "${'$'}amount €"""") shouldBe true
            SUFFIX_LITERAL.containsMatchIn("""val s = "${'$'}amount LTR"""") shouldBe true
            SUFFIX_LITERAL.containsMatchIn("""tr("Betrag in EUR")""") shouldBe false
        }

        test("M5: no table cell / label renders a Decimal amount by a bare toString(), and the detector sees one") {
            // Form prefills are machine-readable by design (Money.kt KDoc) and are set through setValue/value = .../a named `amount = ` argument, never through a cell.
            val findings =
                others.flatMap { f ->
                    codeLines(f)
                        .filter { RAW_DECIMAL_TOSTRING.containsMatchIn(it) }
                        .filterNot { it.contains("setValue(") || it.contains("value = ") || it.trim().startsWith("amount = ") }
                        .map { "${f.name}: ${it.trim()}" }
                }
            findings shouldBe emptyList()
            RAW_DECIMAL_TOSTRING.containsMatchIn("""{ contribution: ContributionDto -> contribution.amountDue.toString() }""") shouldBe true
            RAW_DECIMAL_TOSTRING.containsMatchIn("""{ formatMoney(contribution.amountDue) }""") shouldBe false
        }

        test("M6: no gettext message receives a Decimal field raw (also across lines), and the detector sees one") {
            others.filter { it.name != "MotionsScreen.kt" }.flatMap { f -> rawDecimalGettextCalls(f).map { "${f.name}: $it" } } shouldBe
                emptyList()
            // MotionsScreen.kt: ledgered like M4 -- measured at W6a, closed in W6b, may only go DOWN.
            val motions = others.filter { it.name == "MotionsScreen.kt" }.sumOf { rawDecimalGettextCalls(it).size }
            (motions <= MOTIONS_SCREEN_RAW_ARG_LEDGER) shouldBe true
            RAW_DECIMAL_ARGUMENT.matches("summary.totalOpen") shouldBe true
            RAW_DECIMAL_ARGUMENT.matches("line.kilometers") shouldBe true
            RAW_DECIMAL_ARGUMENT.matches("formatMoney(summary.totalOpen)") shouldBe false
            gettextArguments("""gettext("Offen: %1", summary.totalOpen)""") shouldBe listOf(listOf("summary.totalOpen"))
            // the multi-line form of the ContributionsScreen header that a per-line regex missed
            gettextArguments("gettext(\n \"Offen: %1 | (%2)\" +\n \"x\",\n  summary.totalOpen,\n  formatMoney(a, b),\n)") shouldBe
                listOf(listOf("summary.totalOpen", "formatMoney(a, b)"))
            gettextArguments("gettext(\n \"Offen: %1\",\n formatMoney(summary.totalOpen),\n)")
                .flatten()
                .none { RAW_DECIMAL_ARGUMENT.matches(it) } shouldBe true
        }
    })
