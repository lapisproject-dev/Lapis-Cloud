package network.lapis.cloud.server.payment.bankstatement

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.BankStatementRejectionCode
import java.math.BigDecimal

private data class Mt940Tag(
    val id: String,
    val value: String,
)

/** A `:60F:`/`:60M:`/`:62F:`/`:62M:` balance field, fully parsed -- signed [amount] (debit negated) plus the [currency] code the SAME field itself carries (previously discarded, see [Mt940Parser.parseBalance] KDoc). */
private data class ParsedBalance(
    val amount: BigDecimal,
    val currency: String,
)

private val TAG_START_REGEX = Regex("""^:(\d{2}[A-Z]?):""")
private val BALANCE_REGEX = Regex("""^([DC])(\d{6})([A-Z]{3})([\d,]+)$""")

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import". A minimal, character-driven MT940 (SWIFT camt-predecessor
 * bank-statement) parser covering exactly the tags this domain needs: `:20:` (message reference,
 * also the block delimiter -- multiple `:20:` blocks per file are allowed, each independently
 * saldengeprüft), `:25:` (account/IBAN), `:60F:`/`:60M:` (opening balance), `:61:` (one statement
 * line), `:86:` (that line's detail subfields), `:62F:`/`:62M:` (closing balance). `:28C:` is
 * tokenized but not otherwise interpreted.
 *
 * **No nested-quantifier regex** -- every per-line/per-tag pattern below is a small, fixed-width,
 * non-backtracking match; the genuinely variable-length parts (`:61:`'s amount/reference fields,
 * `:86:`'s subfield text) are scanned character-by-character, never via a greedy/nested regex
 * group, so this parser cannot be driven into catastrophic backtracking (ReDoS) by adversarial
 * input.
 *
 * **Saldenprüfung, hart, per block**: `Σ(:61: amounts) + opening balance == closing balance`
 * (`BigDecimal.compareTo`, not `equals`, so a scale difference alone never fails a genuinely
 * balanced statement) -- a mismatch throws [BankStatementParseException] and rejects the WHOLE
 * import, never just the offending block.
 */
internal object Mt940Parser {
    fun parse(text: String): ParsedStatement {
        val tags = tokenizeTags(text)
        if (tags.none { it.id == "20" }) {
            throw BankStatementParseException(message = "Keine :20: -Kennung gefunden -- keine gueltige MT940-Datei")
        }

        val blocks = splitIntoBlocks(tags)
        val allLines = mutableListOf<ParsedLine>()
        var accountIban: String? = null
        // Review fix (MINOR): captured from the FIRST :20: block only, so it can finally flow into
        // the returned ParsedStatement below instead of being hard-coded null -- see that field's own
        // comment for why only the first block's balance is meaningful here.
        var firstBlockOpening: ParsedBalance? = null
        var firstBlockClosing: ParsedBalance? = null

        for (block in blocks) {
            val opening =
                block.firstOrNull { it.id == "60F" || it.id == "60M" }
                    ?: throw BankStatementParseException(message = "MT940-Block ohne :60F:/:60M: (Anfangssaldo)")
            val closing =
                block.firstOrNull { it.id == "62F" || it.id == "62M" }
                    ?: throw BankStatementParseException(message = "MT940-Block ohne :62F:/:62M: (Endsaldo) -- Datei unvollstaendig")
            val openingBalance = parseBalance(opening.value)
            val closingBalance = parseBalance(closing.value)
            if (firstBlockOpening == null) {
                firstBlockOpening = openingBalance
                firstBlockClosing = closingBalance
            }

            block.firstOrNull { it.id == "25" }?.let { accountIban = accountIban ?: it.value.trim() }

            val blockLines = mutableListOf<ParsedLine>()
            var pendingLine: MutableParsedLineBuilder? = null

            fun flushPending() {
                pendingLine?.let { blockLines += it.build() }
                pendingLine = null
            }

            for (tag in block) {
                when (tag.id) {
                    "61" -> {
                        flushPending()
                        // Review fix (MINOR): MT940's :61: carries no currency field of its own -- the
                        // block's own :60F:/:60M: balance is the only place a currency code appears,
                        // so that (rather than a hard-coded "EUR") is what every line in this block
                        // gets. Still a scope-cut simplification (a statement genuinely switching
                        // currency mid-block is not a real-world case for a single bank account), but
                        // no longer silently wrong for a non-EUR account.
                        pendingLine = MutableParsedLineBuilder(parseStatementLine(value = tag.value, currency = openingBalance.currency))
                    }
                    "86" -> {
                        val builder =
                            pendingLine ?: throw BankStatementParseException(message = ":86: ohne vorangehende :61: -- Datei fehlerhaft")
                        applySubfields(builder = builder, value = tag.value)
                    }
                }
            }
            flushPending()

            val sum = blockLines.fold(BigDecimal.ZERO) { acc, line -> acc + line.amount }
            if (openingBalance.amount.add(sum).compareTo(closingBalance.amount) != 0) {
                throw BankStatementParseException(
                    message =
                        "Saldenpruefung fehlgeschlagen: Anfangssaldo ${openingBalance.amount} + Summe $sum " +
                            "!= Endsaldo ${closingBalance.amount}",
                    code = BankStatementRejectionCode.MT940_BALANCE_MISMATCH,
                )
            }
            allLines += blockLines
        }

        return ParsedStatement(
            accountIban = accountIban,
            statementFrom = allLines.minOfOrNull { it.bookingDate },
            statementTo = allLines.maxOfOrNull { it.bookingDate },
            // Review fix (MINOR): previously hard-coded null ("never persisted") on every MT940
            // import, with a stale KDoc comment claiming a firstBlockBalances() helper elsewhere
            // supplied it for display -- no caller anywhere in this codebase actually called that
            // helper, so the balance never reached bank_statement_import.opening_balance/
            // closing_balance at all. Wired up directly here instead (the removed helper duplicated
            // this exact re-parse) -- first :20: block only, see the field's own class-KDoc scope note
            // "per-block, not modelled on the multi-block ParsedStatement".
            openingBalance = firstBlockOpening?.amount,
            closingBalance = firstBlockClosing?.amount,
            lines = allLines,
        )
    }

    private fun tokenizeTags(text: String): List<Mt940Tag> {
        val tags = mutableListOf<Mt940Tag>()
        var currentId: String? = null
        val currentValue = StringBuilder()

        fun flush() {
            currentId?.let { tags += Mt940Tag(id = it, value = currentValue.toString()) }
            currentValue.clear()
        }
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            val match = TAG_START_REGEX.find(line)
            if (match != null) {
                flush()
                currentId = match.groupValues[1]
                currentValue.append(line.substring(match.range.last + 1))
            } else if (currentId != null) {
                if (currentValue.isNotEmpty()) currentValue.append('\n')
                currentValue.append(line)
            }
        }
        flush()
        return tags
    }

    /** Splits the tag stream at every `:20:` occurrence -- each resulting block is one independently saldengeprüfte statement message. */
    private fun splitIntoBlocks(tags: List<Mt940Tag>): List<List<Mt940Tag>> {
        val blocks = mutableListOf<MutableList<Mt940Tag>>()
        for (tag in tags) {
            if (tag.id == "20") blocks.add(mutableListOf())
            if (blocks.isEmpty()) continue
            blocks.last().add(tag)
        }
        return blocks
    }

    /** Review fix (MINOR): now also extracts the currency code [BALANCE_REGEX] already captures (group 3) instead of discarding it -- see [ParsedBalance] KDoc. */
    private fun parseBalance(value: String): ParsedBalance {
        val match =
            BALANCE_REGEX.find(value.trim())
                ?: throw BankStatementParseException(message = "Saldo nicht lesbar: '$value'")
        val (mark, _, currencyCode, amountRaw) = match.destructured
        val amount = parseGermanAmount(amountRaw) ?: throw BankStatementParseException(message = "Saldo-Betrag nicht lesbar: '$value'")
        val signedAmount = if (mark == "D") amount.abs().negate() else amount.abs()
        return ParsedBalance(amount = signedAmount, currency = currencyCode.trim().uppercase().ifEmpty { "EUR" })
    }

    /** A [:61:]'s primary fields, WITHOUT its `:86:` detail (added afterwards via [applySubfields]). */
    private class MutableParsedLineBuilder(
        initial: ParsedLine,
    ) {
        var line: ParsedLine = initial

        fun build(): ParsedLine = line
    }

    private fun applySubfields(
        builder: MutableParsedLineBuilder,
        value: String,
    ) {
        val subfields = parseStructuredSubfields(value)
        val purposeParts = (20..29).mapNotNull { subfields[it] }.filter { it.isNotBlank() }
        val purpose = purposeParts.joinToString(separator = "").ifBlank { null }
        val counterpartyName = listOfNotNull(subfields[32], subfields[33]).joinToString(separator = " ").trim().ifBlank { null }
        val counterpartyIban = subfields[31]?.trim()?.ifBlank { null }
        val bookingText = subfields[0]?.trim()?.ifBlank { null }

        builder.line =
            builder.line.copy(
                purpose = purpose ?: builder.line.purpose,
                counterpartyName = counterpartyName ?: builder.line.counterpartyName,
                counterpartyIban = counterpartyIban ?: builder.line.counterpartyIban,
                bookingText = bookingText ?: builder.line.bookingText,
            )
    }

    /** Parses `?NNtext?NNtext...` into a `subfield number -> text` map -- character-driven, no regex. */
    private fun parseStructuredSubfields(value: String): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        var i = 0
        while (i < value.length) {
            if (value[i] != '?' || i + 2 >= value.length || !value[i + 1].isDigit() || !value[i + 2].isDigit()) {
                i++
                continue
            }
            val number = value.substring(i + 1, i + 3).toInt()
            var j = i + 3
            while (j < value.length && !(value[j] == '?' && j + 2 < value.length && value[j + 1].isDigit() && value[j + 2].isDigit())) {
                j++
            }
            result[number] = value.substring(i + 3, j)
            i = j
        }
        return result
    }

    /**
     * Parses one `:61:` value's primary fields (everything except the `:86:` detail):
     * `<Valuta YYMMDD>[<Buchungsdatum MMDD>](C|D|RC|RD)[fundsCode]<amount,with,comma><txnTypeCode><ownerRef>[//<bankRef>]`.
     *
     * **SWIFT field order, load-bearing (Review fix, MEDIUM)**: the first six digits are the
     * VALUTA/Wertstellungsdatum (full `YYMMDD`), the optional following four digits are the
     * Buchungsdatum, `MMDD` only (same year as the Valuta unless a rare year-boundary statement
     * straddles New Year's, not handled here -- no test fixture in this codebase exercises that
     * edge, same scope-cut precedent `parseYyMmDd` already accepts for the whole parser). The two
     * were previously assigned CROSS-WISE (`bookingDate` from the Valuta bytes, `valueDate` from the
     * Buchungsdatum bytes) -- swapped for a statement whose Buchungsdatum and Valuta genuinely
     * differ (a booking posted with a later/earlier value date, common around bank holidays and
     * weekends), which corrupted `statementFrom`/`statementTo`, the displayed booking date, AND the
     * fingerprint (see `BankStatementFingerprint` -- `bookingDate` is one of its hashed fields, so a
     * swapped date also silently changes which re-imports are recognized as duplicates).
     */
    private fun parseStatementLine(
        value: String,
        currency: String,
    ): ParsedLine {
        var i = 0

        fun fail(reason: String): Nothing = throw BankStatementParseException(message = ":61: nicht lesbar ($reason): '$value'")
        if (value.length < 6 || !value.substring(0, 6).all { it.isDigit() }) fail("Wertstellungsdatum fehlt")
        val valutaRaw = value.substring(0, 6)
        i = 6

        var bookingMmddRaw: String? = null
        if (i + 4 <= value.length && value.substring(i, i + 4).all { it.isDigit() }) {
            bookingMmddRaw = value.substring(i, i + 4)
            i += 4
        }

        val mark =
            when {
                value.startsWith("RC", i) -> "RC"
                value.startsWith("RD", i) -> "RD"
                value.startsWith("C", i) -> "C"
                value.startsWith("D", i) -> "D"
                else -> fail("Soll/Haben-Kennzeichen fehlt")
            }
        i += mark.length

        // Optional single-letter funds code -- distinguished from the amount by not being a digit.
        if (i < value.length && value[i].isLetter()) i++

        val amountStart = i
        while (i < value.length && (value[i].isDigit() || value[i] == ',')) i++
        if (i == amountStart) fail("Betrag fehlt")
        val amount = parseGermanAmount(value.substring(amountStart, i)) ?: fail("Betrag nicht lesbar")

        // Transaction type code: 1 letter + up to 3 alphanumerics.
        if (i < value.length && value[i].isLetter()) {
            i++
            var extra = 0
            while (i < value.length && extra < 3 && value[i].isLetterOrDigit()) {
                i++
                extra++
            }
        }

        val rest = value.substring(i)
        val slashIdx = rest.indexOf("//")
        // Review fix (MINOR, Runde-2-Fund #4 -- half-fixed originally): capped like the CSV path's
        // own endToEndReference (see BankCsvParser.MAX_END_TO_END_REFERENCE_LENGTH's own comment for
        // why 140, the tighter of bank_statement_line.end_to_end_reference VARCHAR(140) and
        // payment_transaction.provider_payment_id VARCHAR(255)). `rest` is NOT bounded to one
        // physical line here -- tokenizeTags folds every continuation line up to the next `:NN:` tag
        // into the SAME tag value with '\n' separators, so a `:61:` without a `//` bank reference can
        // carry its entire continuation block. Uncapped, that reached postOneLine's AutoPostCandidate
        // and then the provider_payment_id INSERT uncapped, failing with SQLSTATE 22001 ("value too
        // long") -- NOT 23505, so it escaped the duplicate-file filter and bubbled up as an
        // uncaught HTTP 500 in the middle of an already-partially-committed import.
        val endToEndReference =
            (if (slashIdx >= 0) rest.substring(0, slashIdx) else rest)
                .trim()
                .ifEmpty { null }
                ?.take(MAX_END_TO_END_REFERENCE_LENGTH)

        // C -> credit (+), D -> debit (-); a leading "R" (RC/RD) REVERSES that base sign -- see
        // class KDoc "R" handling.
        val baseSign = if (mark.endsWith("C")) 1 else -1
        val finalSign = if (mark.startsWith("R")) -baseSign else baseSign
        val signedAmount = amount.abs().let { if (finalSign < 0) it.negate() else it }

        val valuta = parseYyMmDd(valutaRaw) ?: fail("Wertstellungsdatum ungueltig")
        val bookingDate =
            bookingMmddRaw?.let { mmdd ->
                val month = mmdd.substring(0, 2).toInt()
                val day = mmdd.substring(2, 4).toInt()
                try {
                    LocalDate(valuta.year, month, day)
                } catch (e: IllegalArgumentException) {
                    null
                }
            } ?: valuta

        return ParsedLine(
            bookingDate = bookingDate,
            valueDate = valuta,
            amount = signedAmount,
            currency = currency,
            counterpartyName = null,
            counterpartyIban = null,
            purpose = null,
            endToEndReference = endToEndReference,
            bookingText = null,
        )
    }

    private fun parseYyMmDd(raw: String): LocalDate? =
        try {
            val yy = raw.substring(0, 2).toInt()
            val mm = raw.substring(2, 4).toInt()
            val dd = raw.substring(4, 6).toInt()
            LocalDate(2000 + yy, mm, dd)
        } catch (e: IllegalArgumentException) {
            null
        }

    /** Same value as [BankCsvParser]'s own constant of the same name -- see that constant's KDoc for why 140. */
    private const val MAX_END_TO_END_REFERENCE_LENGTH = 140
}
