package network.lapis.cloud.server.accounting.datev

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DatevExportBlockerKind
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingSide
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Pure tests of [DatevBuchungsstapelWriter] -- no DB access anywhere in this file, same
 * "pure logic extracted to a sibling file" idiom as `JournalEntryBalanceTest`.
 */
class DatevBuchungsstapelWriterTest :
    FunSpec({
        val generatedAt = LocalDateTime(2026, 2, 3, 10, 25, 0, 0)

        fun request(
            from: LocalDate = LocalDate(2026, 1, 1),
            to: LocalDate = LocalDate(2026, 1, 31),
            beraterNummer: Int? = 1001,
            mandantNummer: Int? = 1,
            entries: List<DatevSourceEntry> = emptyList(),
        ) = DatevExportRequest(
            from = from,
            to = to,
            beraterNummer = beraterNummer,
            mandantNummer = mandantNummer,
            organizationName = "Verein Beispiel e.V.",
            exportedBy = "Max Mustermann",
            generatedAt = generatedAt,
            entries = entries,
        )

        fun posting(
            side: PostingSide,
            amount: String,
            account: String,
        ) = DatevSourcePosting(
            side = side,
            amount = BigDecimal(amount),
            accountNumber = account,
            // Welle V1.4.5.3 additions -- irrelevant to DatevBuchungsstapelWriter itself (it never
            // reads either field), a fixed placeholder is fine here.
            ledgerAccountId = Uuid.random(),
            accountType = LedgerAccountType.ASSET,
        )

        fun entry(
            date: LocalDate = LocalDate(2026, 1, 15),
            description: String = "Testbuchung",
            voucherReference: String? = "RE-2026-0001",
            postings: List<DatevSourcePosting>,
        ) = DatevSourceEntry(
            id = Uuid.random(),
            entryDate = date,
            description = description,
            voucherReference = voucherReference,
            postings = postings,
        )

        /** One trivial, exportable 1:1 request -- shared by tests that only care about the
         * rendered bytes' structural shape (CRLF, BOM, header content), not the booking itself. */
        fun simpleExportableRequest(): DatevExportRequest {
            val simplePostings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940"))
            return request(entries = listOf(entry(postings = simplePostings)))
        }

        test("COLUMN_NAMES has exactly 125 entries") {
            DatevBuchungsstapelWriter.COLUMN_NAMES.size shouldBe 125
        }

        test("field-count invariant: every rendered data line has exactly COLUMN_NAMES.size fields") {
            val e = entry(postings = listOf(posting(PostingSide.DEBIT, "24.95", "1200"), posting(PostingSide.CREDIT, "24.95", "4940")))
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            plan.exportable shouldBe true
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val lines = String(bytes, DatevCharacterSet.CP1252).split("\r\n").filter { it.isNotEmpty() }
            // line 0 = format header (31 fields), line 1 = column-name header, line 2+ = data rows
            lines[1].split(";").size shouldBe 125
            lines[2].split(";").size shouldBe 125
        }

        test("belegdatum is four-digit TTMM, never TTMMJJJJ") {
            val e =
                entry(
                    date = LocalDate(2026, 2, 3),
                    postings = listOf(posting(PostingSide.DEBIT, "10.00", "1200"), posting(PostingSide.CREDIT, "10.00", "4940")),
                )
            val req = request(from = LocalDate(2026, 2, 1), to = LocalDate(2026, 2, 28), entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val text = String(bytes, DatevCharacterSet.CP1252)
            text.contains(";0302;") shouldBe true
            text.contains("03022026") shouldBe false
        }

        test("amount format: comma decimal separator, no thousands separator, always positive, exactly 2 fractional digits") {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "1000000.00", "1200"),
                            posting(PostingSide.CREDIT, "1000000.00", "4940"),
                        ),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val dataLine = String(bytes, DatevCharacterSet.CP1252).split("\r\n")[2]
            val amountField = dataLine.split(";")[0]
            amountField shouldBe "1000000,00"
        }

        test("Sammelbuchung 1:3 -- one debit account, three credit accounts produces three rows sharing the same contra account") {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "300.00", "1200"),
                            posting(PostingSide.CREDIT, "100.00", "4940"),
                            posting(PostingSide.CREDIT, "100.00", "4941"),
                            posting(PostingSide.CREDIT, "100.00", "4942"),
                        ),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            plan.exportable shouldBe true
            plan.rows.size shouldBe 3
            plan.rows.all { it.contraAccount == "1200" } shouldBe true
            plan.rows.all { it.side == PostingSide.CREDIT } shouldBe true
            plan.rows.sumOf { it.amount } shouldBe BigDecimal("300.00")
        }

        test("multiple postings on the SAME account of the same side aggregate into one account, not one row each") {
            val e =
                entry(
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "10.00", "1200"),
                            posting(PostingSide.DEBIT, "5.00", "1200"),
                            posting(PostingSide.CREDIT, "15.00", "4940"),
                        ),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            plan.exportable shouldBe true
            plan.rows.size shouldBe 1
            plan.rows.single().amount shouldBe BigDecimal("15.00")
        }

        test("a genuine many-to-many entry (more than one account on BOTH sides) is blocked, never guessed") {
            val e =
                entry(
                    date = LocalDate(2026, 1, 20),
                    voucherReference = "RE-2026-0099",
                    postings =
                        listOf(
                            posting(PostingSide.DEBIT, "50.00", "1200"),
                            posting(PostingSide.DEBIT, "50.00", "1210"),
                            posting(PostingSide.CREDIT, "50.00", "4940"),
                            posting(PostingSide.CREDIT, "50.00", "4941"),
                        ),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            plan.exportable shouldBe false
            plan.blockers.map { it.kind } shouldContain DatevExportBlockerKind.UNMAPPABLE_MANY_TO_MANY_ENTRY
            val blocker = plan.blockers.single { it.kind == DatevExportBlockerKind.UNMAPPABLE_MANY_TO_MANY_ENTRY }
            blocker.detail.contains("2026-01-20") shouldBe true
            blocker.detail.contains("RE-2026-0099") shouldBe true
        }

        test("a period crossing a calendar-year boundary is blocked -- Belegdatum has no year") {
            val req = request(from = LocalDate(2025, 12, 1), to = LocalDate(2026, 1, 31))
            val plan = DatevBuchungsstapelWriter.plan(req)
            plan.blockers.map { it.kind } shouldContain DatevExportBlockerKind.PERIOD_CROSSES_CALENDAR_YEAR
        }

        test("mixed account-number lengths in the same period are blocked") {
            val e1 = entry(postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")))
            val e2 = entry(postings = listOf(posting(PostingSide.DEBIT, "1.00", "06500"), posting(PostingSide.CREDIT, "1.00", "4940")))
            val req = request(entries = listOf(e1, e2))
            val plan = DatevBuchungsstapelWriter.plan(req)
            plan.blockers.map { it.kind } shouldContain DatevExportBlockerKind.MIXED_ACCOUNT_NUMBER_LENGTHS
        }

        test("leading zero in an account number survives verbatim into the rendered bytes") {
            val e = entry(postings = listOf(posting(PostingSide.DEBIT, "1.00", "06500"), posting(PostingSide.CREDIT, "1.00", "04940")))
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            plan.exportable shouldBe true
            plan.derivedSachkontenlaenge shouldBe 5
            plan.leadingZeroAccountCount shouldBe 2
            val text = String(DatevBuchungsstapelWriter.render(request = req, plan = plan), DatevCharacterSet.CP1252)
            text.contains(";06500;") shouldBe true
            text.contains(";6500;") shouldBe false
        }

        test("an account-number length outside 4-8 digits is blocked") {
            val e = entry(postings = listOf(posting(PostingSide.DEBIT, "1.00", "120"), posting(PostingSide.CREDIT, "1.00", "494")))
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            plan.blockers.map { it.kind } shouldContain DatevExportBlockerKind.ACCOUNT_NUMBER_LENGTH_OUT_OF_RANGE
        }

        test("a Cyrillic booking text is transliterated (dropped), never renders a literal question mark") {
            val e =
                entry(
                    description = "Привет мир",
                    postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            plan.transliteratedEntryCount shouldBe 1
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val dataLine = String(bytes, DatevCharacterSet.CP1252).split("\r\n")[2]
            val bookingText = dataLine.split(";")[13]
            bookingText.contains("?") shouldBe false
        }

        test("a plain umlaut booking text is NOT counted as transliterated -- CP1252 carries it natively") {
            val e =
                entry(
                    description = "Bücher für Mitglieder",
                    postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")),
                )
            val plan = DatevBuchungsstapelWriter.plan(request(entries = listOf(e)))
            plan.transliteratedEntryCount shouldBe 0
        }

        test("the fixed column-header en-dash survives at the Beleginfo position -- never transliterated") {
            val req = simpleExportableRequest()
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val headerLine = String(bytes, DatevCharacterSet.CP1252).split("\r\n")[1]
            headerLine.contains("Beleginfo – Art 1") shouldBe true
        }

        test("CRLF terminates every line including the last, and no lone LF ever appears") {
            val req = simpleExportableRequest()
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val text = String(bytes, DatevCharacterSet.CP1252)
            text.endsWith("\r\n") shouldBe true
            text.count { it == '\n' } shouldBe text.split("\r\n").size - 1
        }

        test("no BOM at the start of the rendered bytes") {
            val req = simpleExportableRequest()
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            bytes.take(3) shouldBe listOf('"'.code.toByte(), 'E'.code.toByte(), 'X'.code.toByte())
        }

        test("an empty period is blocked") {
            val plan = DatevBuchungsstapelWriter.plan(request(entries = emptyList()))
            plan.blockers.map { it.kind } shouldContain DatevExportBlockerKind.EMPTY_PERIOD
        }

        test("missing Berater-/Mandantennummer is blocked") {
            val e = entry(postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")))
            val plan = DatevBuchungsstapelWriter.plan(request(beraterNummer = null, entries = listOf(e)))
            plan.blockers.map { it.kind } shouldContain DatevExportBlockerKind.BERATER_MANDANT_NOT_CONFIGURED
        }

        test("multiple simultaneous blockers are ALL reported, not just the first") {
            val plan = DatevBuchungsstapelWriter.plan(request(beraterNummer = null, mandantNummer = null, entries = emptyList()))
            plan.blockers.map { it.kind } shouldContain DatevExportBlockerKind.BERATER_MANDANT_NOT_CONFIGURED
            plan.blockers.map { it.kind } shouldContain DatevExportBlockerKind.EMPTY_PERIOD
        }

        test("Belegfeld 1 is filtered to the allowed charset and truncated to 36 characters") {
            val e =
                entry(
                    voucherReference = "RE/2026-0042#*x" + "y".repeat(40),
                    postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val dataLine = String(bytes, DatevCharacterSet.CP1252).split("\r\n")[2]
            val belegfeld1 = dataLine.split(";")[10].trim('"')
            belegfeld1.contains("#") shouldBe false
            (belegfeld1.length <= 36) shouldBe true
        }

        test("a null voucher reference renders an empty (but still quoted) Belegfeld 1") {
            val e =
                entry(
                    voucherReference = null,
                    postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val dataLine = String(bytes, DatevCharacterSet.CP1252).split("\r\n")[2]
            dataLine.split(";")[10] shouldBe "\"\""
        }

        test("more rows than MAX_ROWS is blocked") {
            val entries =
                (1..(DatevBuchungsstapelWriter.MAX_ROWS + 1)).map { i ->
                    entry(
                        date = LocalDate(2026, 1, 1),
                        voucherReference = "RE-$i",
                        postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")),
                    )
                }
            val plan = DatevBuchungsstapelWriter.plan(request(entries = entries))
            plan.blockers.map { it.kind } shouldContain DatevExportBlockerKind.TOO_MANY_ROWS
        }

        // Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
        // MINOR, DoS/Heap): `rows` used to grow completely unbounded before the MAX_ROWS check ran.
        // This pins the fix's two halves at once -- the blocker's message still names the TRUE
        // count, while `plan.rows` itself never grows past MAX_ROWS entries.
        test("plan.rows is capped at MAX_ROWS even though the blocker reports the true, uncapped count") {
            val trueCount = DatevBuchungsstapelWriter.MAX_ROWS + 7
            val entries =
                (1..trueCount).map { i ->
                    entry(
                        date = LocalDate(2026, 1, 1),
                        voucherReference = "RE-$i",
                        postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")),
                    )
                }
            val plan = DatevBuchungsstapelWriter.plan(request(entries = entries))
            plan.rows.size shouldBe DatevBuchungsstapelWriter.MAX_ROWS
            val blocker = plan.blockers.single { it.kind == DatevExportBlockerKind.TOO_MANY_ROWS }
            blocker.detail.contains(trueCount.toString()) shouldBe true
        }

        // Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
        // MAJOR, OWASP CSV/Formula Injection): Belegfeld 1 already strips every character outside
        // `[A-Za-z0-9$%&*+-/]`, but a leading '+'/'-'/'*'/'/' survives that allowlist and Excel/
        // LibreOffice evaluate such a cell as an arithmetic formula regardless of quoting.
        test("a leading formula-trigger character in the voucher reference is stripped from Belegfeld 1") {
            val e =
                entry(
                    voucherReference = "-2024-0007",
                    postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val dataLine = String(bytes, DatevCharacterSet.CP1252).split("\r\n")[2]
            val belegfeld1 = dataLine.split(";")[10].trim('"')
            belegfeld1 shouldBe "2024-0007"
        }

        test("multiple stacked leading formula-trigger characters in the voucher reference are all stripped") {
            val e =
                entry(
                    voucherReference = "+-*RE-2024-0007",
                    postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val dataLine = String(bytes, DatevCharacterSet.CP1252).split("\r\n")[2]
            dataLine.split(";")[10].trim('"') shouldBe "RE-2024-0007"
        }

        // Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
        // MINOR): an account number containing a non-digit character (predating
        // AccountingService.requireValidAccountNumberFormat) would render UNQUOTED into the DATEV
        // data row and silently shift every following field -- now blocked at the export boundary.
        test("an account number containing a semicolon is blocked, never rendered unquoted") {
            val e = entry(postings = listOf(posting(PostingSide.DEBIT, "1.00", "12;00"), posting(PostingSide.CREDIT, "1.00", "4940")))
            val plan = DatevBuchungsstapelWriter.plan(request(entries = listOf(e)))
            plan.exportable shouldBe false
            plan.blockers.map { it.kind } shouldContain DatevExportBlockerKind.ACCOUNT_NUMBER_CONTAINS_INVALID_CHARACTERS
        }

        test("a purely numeric account number of valid length is NOT flagged as containing invalid characters") {
            val e = entry(postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")))
            val plan = DatevBuchungsstapelWriter.plan(request(entries = listOf(e)))
            plan.blockers.map { it.kind } shouldNotContain DatevExportBlockerKind.ACCOUNT_NUMBER_CONTAINS_INVALID_CHARACTERS
        }

        // Review-Fund (2026-09, MAJOR): `quote()` used to wrap a value in `"…"` without doubling an
        // embedded `"` -- RFC4180/DATEV's own escaping convention. A raw `"` mid-field closes the
        // field early and shifts everything after it into neighbouring fields, silently corrupting
        // the row without any exception anywhere in this pipeline.
        test("embedded quote in booking text is doubled (never dropped or left raw), field count stays correct") {
            val e =
                entry(
                    description = "Zahlung für „Sommerfest“", // sanitize() folds „…“ to a plain ASCII quote pair
                    postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val dataLine = String(bytes, DatevCharacterSet.CP1252).split("\r\n")[2]
            dataLine.split(";").size shouldBe 125
            val bookingTextField = dataLine.split(";")[13]
            bookingTextField shouldBe "\"Zahlung für \"\"Sommerfest\"\"\""
        }

        test("a booking text with both a quote and a semicolon does not shift the field count") {
            val e =
                entry(
                    description = "A\";9999;8888;\"B",
                    postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val dataLine = String(bytes, DatevCharacterSet.CP1252).split("\r\n")[2]
            // A quote-AWARE parse (the correct escaper's own content, incl. its literal embedded
            // ";", stays inside ONE quoted field) must still see exactly 125 fields -- a naive
            // `split(";")` is the WRONG check here: it would blindly split on the embedded ";"
            // characters even though they sit inside a properly-quoted field and are not
            // delimiters at all. This is exactly what the bug report's "128 instead of 125 fields"
            // symptom described for the UNESCAPED (pre-fix) output.
            val fields = parseDatevCsvRow(dataLine)
            fields.size shouldBe 125
            fields[13] shouldBe "A\";9999;8888;\"B"
        }

        test("an embedded quote in the organization name is doubled in the header line") {
            val e = entry(postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")))
            val req = request(entries = listOf(e)).copy(organizationName = "Verein \"Zukunft\" e.V.")
            val plan = DatevBuchungsstapelWriter.plan(req)
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val headerLine = String(bytes, DatevCharacterSet.CP1252).split("\r\n")[0]
            headerLine.contains("\"Verein \"\"Zukunft\"\" e.V.\"") shouldBe true
            headerLine.split(";").size shouldBe DatevBuchungsstapelWriter.HEADER_FIELD_COUNT
        }

        // Review-Fund (2026-09, MAJOR): CP1252 can encode every C0 control character, so `\r`/`\n`
        // in a booking text used to pass `sanitize` unchanged -- and `render` appends its own
        // "\r\n" after every line, so one journal entry produced TWO "\r\n"-separated output lines
        // with the wrong field count instead of one correct row.
        test("a line break in booking text does not split one data record into two output lines") {
            val e =
                entry(
                    description = "Zeile1\r\nZeile2",
                    postings = listOf(posting(PostingSide.DEBIT, "1.00", "1200"), posting(PostingSide.CREDIT, "1.00", "4940")),
                )
            val req = request(entries = listOf(e))
            val plan = DatevBuchungsstapelWriter.plan(req)
            plan.transliteratedEntryCount shouldBe 1
            val bytes = DatevBuchungsstapelWriter.render(request = req, plan = plan)
            val lines = String(bytes, DatevCharacterSet.CP1252).split("\r\n").filter { it.isNotEmpty() }
            // format header + column-name header + exactly ONE data line for the ONE entry above.
            lines.size shouldBe 3
            lines[2].split(";").size shouldBe 125
            // "\r" and "\n" are TWO separate C0 control characters -- each is folded to its own
            // space, so "Zeile1\r\nZeile2" becomes "Zeile1  Zeile2" (double space), not a single one.
            lines[2].split(";")[13] shouldBe "\"Zeile1  Zeile2\""
        }

        test("render throws when the plan is not exportable") {
            val emptyRequest = request(entries = emptyList())
            val plan = DatevBuchungsstapelWriter.plan(emptyRequest)
            var threw = false
            try {
                DatevBuchungsstapelWriter.render(request = emptyRequest, plan = plan)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            threw shouldBe true
        }
    })

/**
 * Minimal RFC4180-style quote-aware `;`-delimited row parser, used ONLY by
 * [DatevBuchungsstapelWriterTest]'s embedded-quote/embedded-`;` tests -- a plain `String.split(";")`
 * is the WRONG tool there: it cannot tell a delimiter `;` apart from a literal `;` sitting inside a
 * correctly double-quote-escaped field, and would misreport a properly-escaped row as having too
 * many fields. A DATEV data line mixes quoted (`"…"`, embedded `"` doubled) and bare/unquoted
 * fields (amounts, account numbers, ...) -- see [DatevBuchungsstapelWriter.buildDataLine] -- so
 * this parser handles both, not just the quoted shape.
 */
private fun parseDatevCsvRow(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i <= line.length) {
        if (i < line.length && line[i] == '"') {
            i++ // consume opening quote
            while (i < line.length) {
                if (line[i] == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i += 2
                    } else {
                        i++ // consume closing quote
                        break
                    }
                } else {
                    current.append(line[i])
                    i++
                }
            }
        } else {
            while (i < line.length && line[i] != ';') {
                current.append(line[i])
                i++
            }
        }
        fields += current.toString()
        current.clear()
        if (i < line.length) {
            check(line[i] == ';') { "expected ';' after a closed field at index $i in: $line" }
            i++
        } else {
            break
        }
    }
    return fields
}
