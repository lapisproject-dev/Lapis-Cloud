package network.lapis.cloud.server.accounting.datev

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DatevExportBlockerDto
import network.lapis.cloud.shared.domain.DatevExportBlockerKind
import network.lapis.cloud.shared.domain.PostingSide
import java.math.BigDecimal
import java.math.RoundingMode

/** One posting line as seen by [DatevBuchungsstapelWriter], entirely DB-free -- see
 * `network.lapis.cloud.server.accounting.datev.loadDatevSourceEntries` for how these are read. */
internal data class DatevSourcePosting(
    val side: PostingSide,
    val amount: BigDecimal,
    val accountNumber: String,
)

/** One journal entry as seen by [DatevBuchungsstapelWriter] -- deliberately NOT the full
 * `JournalEntryDto` shape (no id/status/donor fields): only what a DATEV row can ever carry. */
internal data class DatevSourceEntry(
    val entryDate: LocalDate,
    val description: String,
    val voucherReference: String?,
    val postings: List<DatevSourcePosting>,
)

/** Everything [DatevBuchungsstapelWriter.plan]/[DatevBuchungsstapelWriter.render] need -- built once
 * by `buildDatevExportRequest` and shared verbatim between the preview RPC and the file route, so
 * neither can ever see a different world than the other. */
internal data class DatevExportRequest(
    val from: LocalDate,
    val to: LocalDate,
    val beraterNummer: Int?,
    val mandantNummer: Int?,
    val organizationName: String,
    val exportedBy: String,
    val generatedAt: LocalDateTime,
    val entries: List<DatevSourceEntry>,
)

/** One rendered DATEV Buchungsstapel data row -- the seven fields this wave actually populates
 * (see [DatevBuchungsstapelWriter] class KDoc "Feldbelegung"). */
internal data class DatevBookingRow(
    val amount: BigDecimal,
    val side: PostingSide,
    val account: String,
    val contraAccount: String,
    val entryDate: LocalDate,
    val voucherField1: String,
    val bookingText: String,
)

/** Outcome of [DatevBuchungsstapelWriter.plan] -- the single truth both the preview RPC
 * (`AccountingService.previewDatevExport`) and the file route consume. */
internal data class DatevExportPlan(
    val blockers: List<DatevExportBlockerDto>,
    val rows: List<DatevBookingRow>,
    val derivedSachkontenlaenge: Int?,
    val debitTotal: BigDecimal,
    val creditTotal: BigDecimal,
    val transliteratedEntryCount: Int,
    val leadingZeroAccountCount: Int,
) {
    val exportable: Boolean get() = blockers.isEmpty()
}

/**
 * Welle V1.4.5.2 "DATEV-Format-Export". Pure, DB-free DATEV-EXTF-Buchungsstapel generator -- same
 * "pure logic extracted to a sibling file, unit-testable without a database" idiom as
 * [network.lapis.cloud.server.rpc.JournalEntryBalance]/`GeneralLedgerCalculator`.
 *
 * [plan] is THE authority on whether `[from, to]` can be exported at all and what it would contain
 * -- both `AccountingService.previewDatevExport` and `network.lapis.cloud.server.routes
 * .registerDatevRoutes`'s file route call it (via the shared `buildDatevExportRequest`) and never
 * duplicate its rules. [render] only ever runs on an already-[DatevExportPlan.exportable] plan --
 * see that function's own KDoc.
 *
 * ## Format-Grundlage (byte-verifiziert gegen die ledermann/datev EXTF-Referenzdatei)
 *
 * Windows-1252 (NICHT ISO-8859-1 -- die Ueberschriftenzeile selbst traegt einen En-Dash, Byte
 * 0x96, der in ISO-8859-1 ein C1-Steuerzeichen ist), CRLF nach JEDER Zeile inklusive der letzten,
 * kein BOM. `Belegdatum` (Feld 10 der Datenzeile) ist TTMM -- vierstellig, OHNE Jahr; ein
 * ueberjaehriger Zeitraum ist deshalb strukturell nicht exportierbar (siehe
 * [DatevExportBlockerKind.PERIOD_CROSSES_CALENDAR_YEAR]). Kopfzeilen-Datumsfelder (Feld 15/16)
 * sind dagegen JJJJMMTT (voll, mit Jahr) -- ein anderes Format als das Belegdatum, nicht
 * verwechseln.
 *
 * ## Feldbelegung (Datenzeile, 125 Felder gesamt, siehe [COLUMN_NAMES])
 *
 * Nur SIEBEN Felder werden tatsaechlich befuellt -- alle uebrigen (inklusive Feld 9 BU-Schluessel,
 * Feld 37 KOST1 und Feld 114 Festschreibung, die extra genannt werden, weil sie leicht mit
 * "sollte doch was tragen" verwechselt werden) bleiben leer:
 *
 * | Nr  | Feld            | Wert                                              | Quoting |
 * |-----|-----------------|---------------------------------------------------|---------|
 * | 1   | Umsatz          | Betrag, IMMER positiv, Komma statt Punkt           | nackt   |
 * | 2   | Soll/Haben-Kz   | `S` bei DEBIT, `H` bei CREDIT                      | `"…"`   |
 * | 7   | Konto           | Kontonummer verbatim, fuehrende Nullen erhalten    | nackt   |
 * | 8   | Gegenkonto      | dito                                               | nackt   |
 * | 9   | BU-Schluessel   | LEER -- Lapis fuehrt keine USt-Schluessel          | --      |
 * | 10  | Belegdatum      | TTMM                                               | nackt   |
 * | 11  | Belegfeld 1     | Belegreferenz, gefiltert auf `[A-Za-z0-9$%&*+-/]`, ≤36 | `"…"` |
 * | 14  | Buchungstext    | Buchungstext, CP1252-aufbereitet, ≤60              | `"…"`   |
 * | 37  | KOST1           | LEER -- kein Kostenstellen-Mapping beim Steuerberater | --   |
 * | 114 | Festschreibung  | LEER (Datenzeile -- NICHT mit Kopfzeilenfeld 21 verwechseln) | -- |
 *
 * ## Zeilenbildung (Sammelbuchungen)
 *
 * Ein DATEV-Datensatz kennt genau EIN `Konto` und EIN `Gegenkonto` je Zeile. Eine Buchung mit
 * genau einem Konto je Seite (1:1) wird zu einer Zeile; eine Sammelbuchung mit genau einem Konto
 * auf einer Seite und mehreren auf der anderen (n:1 bzw. 1:n) wird zu n Zeilen, je eine pro Konto
 * der "vielen" Seite, alle mit dem Einzelkonto der Gegenseite als Gegenkonto. Mehrere Buchungen
 * auf DASSELBE Konto derselben Seite zaehlen als EIN Konto (aggregiert nach Kontonummer, nicht
 * nach Buchungszeilen-Anzahl). Eine echte n:m-Buchung (mehr als ein Konto auf BEIDEN Seiten) kann
 * das Format nicht abbilden -- [DatevExportBlockerKind.UNMAPPABLE_MANY_TO_MANY_ENTRY].
 */
internal object DatevBuchungsstapelWriter {
    /** DoS-Deckel (Review-Vorgabe) -- ein unbegrenzter Jahresexport darf den Heap nicht sprengen. */
    const val MAX_ROWS = 50_000

    /** Feste 31 Kopfzeilenfelder. */
    const val HEADER_FIELD_COUNT = 31

    /** Erlaubte Zeichen in Belegfeld 1 (DATEV-Beleg-Referenzfeld) -- alles andere wird entfernt. */
    private val BELEGFELD1_ALLOWED = Regex("[^A-Za-z0-9$%&*+\\-/]")
    private const val BELEGFELD1_MAX_LENGTH = 36

    /**
     * Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
     * MAJOR, OWASP CSV/Formula Injection -- "im selben Zug mitzuziehen" for Belegfeld 1): unlike
     * Buchungstext (Feld 14, see [DatevCharacterSet]'s `LEADING_FORMULA_TRIGGER_CHARS`), Belegfeld 1
     * already has no `=`/`(`/`!`/`'`/`|` in its output at all -- [BELEGFELD1_ALLOWED] strips every
     * character outside `[A-Za-z0-9$%&*+-/]`, so no DDE/command-execution payload can ever survive
     * here. A leading `+`/`-`/`*`/`/` still SURVIVES that allowlist, though, and Excel/LibreOffice
     * evaluate a cell starting with any of those four as an arithmetic formula regardless of quoting
     * -- e.g. a voucher reference `"-2024-0007"` would silently display as `-1983` instead of the
     * literal reference text, a stealth value-corruption rather than code execution. Every LEADING
     * occurrence is stripped (looped, not folded once) because removing a character shifts the next
     * one into position 0, which can itself be a trigger (`"+-2024-0007"` -> `"-2024-0007"` still
     * starts with `-`) -- unlike the single-space fold in [DatevCharacterSet.sanitize], Belegfeld 1
     * is a short reference code with no prose meaning to preserve, so dropping the leading
     * character(s) entirely (rather than replacing them) is the simpler, equally safe choice.
     */
    private val BELEGFELD1_LEADING_TRIGGER_CHARS = charArrayOf('+', '-', '*', '/')

    private fun stripLeadingFormulaTriggers(value: String): String {
        var i = 0
        while (i < value.length && value[i] in BELEGFELD1_LEADING_TRIGGER_CHARS) i++
        return value.substring(i)
    }

    private const val BOOKING_TEXT_MAX_LENGTH = 60
    private const val EXPORTED_BY_MAX_LENGTH = 25
    private const val ORGANIZATION_NAME_MAX_LENGTH = 30
    private val VALID_ACCOUNT_LENGTH_RANGE = 4..8
    private const val MAX_LISTED_ENTRIES_IN_DETAIL = 20

    /**
     * Die 125 Spaltennamen der DATEV-EXTF-Buchungsstapel-Ueberschriftenzeile, IN DIESER
     * REIHENFOLGE -- Ueberschriftenzeile UND Feldanzahl jeder Datenzeile werden beide aus dieser
     * einen Liste abgeleitet (siehe [render]), damit Kopf und Daten nicht driften koennen. Enthaelt
     * bewusst den En-Dash (nicht Bindestrich) in "Beleginfo – Art n"/"Zusatzinformation – Art n" --
     * exakt das Zeichen, das [DatevCharacterSet.sanitize] NIE zu sehen bekommen darf (siehe object
     * KDoc "Gilt AUSSCHLIESSLICH fuer Nutzdaten").
     */
    val COLUMN_NAMES: List<String> =
        buildList {
            add("Umsatz (ohne Soll/Haben-Kz)")
            add("Soll/Haben-Kennzeichen")
            add("WKZ Umsatz")
            add("Kurs")
            add("Basisumsatz")
            add("WKZ Basisumsatz")
            add("Konto")
            add("Gegenkonto (ohne BU-Schlüssel)")
            add("BU-Schlüssel")
            add("Belegdatum")
            add("Belegfeld 1")
            add("Belegfeld 2")
            add("Skonto")
            add("Buchungstext")
            add("Postensperre")
            add("Diverse Adressnummer")
            add("Geschäftspartnerbank")
            add("Sachverhalt")
            add("Zinssperre")
            add("Beleglink")
            for (n in 1..8) {
                add("Beleginfo – Art $n")
                add("Beleginfo – Inhalt $n")
            }
            add("KOST1 – Kostenstelle")
            add("KOST2 – Kostenstelle")
            add("Kost Menge")
            add("EU-Land u. USt-IdNr.")
            add("EU-Steuersatz")
            add("Abw. Versteuerungsart")
            add("Sachverhalt L+L")
            add("Funktionsergänzung L+L")
            add("BU 49 Hauptfunktionstyp")
            add("BU 49 Hauptfunktionsnummer")
            add("BU 49 Funktionsergänzung")
            for (n in 1..20) {
                add("Zusatzinformation – Art $n")
                add("Zusatzinformation – Inhalt $n")
            }
            add("Stück")
            add("Gewicht")
            add("Zahlweise")
            add("Forderungsart")
            add("Veranlagungsjahr")
            add("Zugeordnete Fälligkeit")
            add("Skontotyp")
            add("Auftragsnummer")
            add("Buchungstyp")
            add("USt-Schlüssel (Anzahlungen)")
            add("EU-Mitgliedstaat (Anzahlungen)")
            add("Sachverhalt L+L (Anzahlungen)")
            add("EU-Steuersatz (Anzahlungen)")
            add("Erlöskonto (Anzahlungen)")
            add("Herkunft-Kz")
            add("Leerfeld")
            add("KOST-Datum")
            add("SEPA-Mandatsreferenz")
            add("Skontosperre")
            add("Gesellschaftername")
            add("Beteiligtennummer")
            add("Identifikationsnummer")
            add("Zeichnernummer")
            add("Postensperre bis")
            add("Bezeichnung")
            add("Kennzeichen")
            add("Festschreibung")
            add("Leistungsdatum")
            add("Datum Zuord.")
            add("Fälligkeit")
            add("Generalumkehr")
            add("Steuersatz")
            add("Land")
            add("Abrechnungsreferent")
            add("BVV-Position")
            add("EU-Mitgliedstaat u. UStID (Ursprung)")
            add("EU-Steuersatz (Ursprung)")
            add("Abw. Skontokonto")
        }

    init {
        check(COLUMN_NAMES.size == 125) { "COLUMN_NAMES must have exactly 125 entries, has ${COLUMN_NAMES.size}" }
    }

    /**
     * Die eine Wahrheit: Vorschau zaehlt nur, was hier herauskommt; [render] serialisiert exakt
     * dieses Ergebnis. Sammelt ALLE Blocker (bricht nie beim ersten ab), damit ein Schatzmeister
     * alles auf einmal sieht.
     */
    fun plan(request: DatevExportRequest): DatevExportPlan {
        val blockers = mutableListOf<DatevExportBlockerDto>()

        if (request.from.year != request.to.year) {
            blockers +=
                DatevExportBlockerDto(
                    kind = DatevExportBlockerKind.PERIOD_CROSSES_CALENDAR_YEAR,
                    detail =
                        "Zeitraum ${request.from} bis ${request.to} überspannt mehr als ein Kalenderjahr -- " +
                            "das DATEV-Belegdatum (TTMM) trägt kein Jahr.",
                )
        }
        if (request.beraterNummer == null || request.mandantNummer == null) {
            blockers +=
                DatevExportBlockerDto(
                    kind = DatevExportBlockerKind.BERATER_MANDANT_NOT_CONFIGURED,
                    detail = "Berater- und/oder Mandantennummer sind nicht konfiguriert -- siehe Kontenzuordnung.",
                )
        }
        if (request.entries.isEmpty()) {
            blockers +=
                DatevExportBlockerDto(
                    kind = DatevExportBlockerKind.EMPTY_PERIOD,
                    detail = "Keine gebuchten (POSTED) Buchungen im Zeitraum ${request.from} bis ${request.to}.",
                )
        }

        val rows = mutableListOf<DatevBookingRow>()
        val unmappableEntries = mutableListOf<DatevSourceEntry>()
        var transliteratedEntryCount = 0
        var debitTotal = BigDecimal.ZERO
        var creditTotal = BigDecimal.ZERO

        /**
         * Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
         * MINOR, DoS/Heap): [rows] used to grow completely unbounded for the DURATION of this whole
         * loop -- the [MAX_ROWS] check below only ever ran AFTER every row for every entry had
         * already been built and appended, so a single Sammelbuchung entry that groups into far more
         * than [MAX_ROWS] distinct accounts on one side (the entry-count cap in
         * `buildDatevExportRequest` bounds the number of ENTRIES, never the number of ROWS one
         * single entry can expand into) could still materialize an unbounded [DatevBookingRow] list
         * before the export was ever refused. [rowCount] tracks the TRUE total (so the blocker's
         * message and [DatevExportPlan.rows]'s reported size before capping stay accurate) while
         * [rows] itself is capped at [MAX_ROWS] entries -- once a period is going to be blocked
         * anyway, there is no reason to keep allocating [DatevBookingRow] objects nobody will ever
         * render.
         */
        var rowCount = 0

        fun addRow(row: DatevBookingRow) {
            rowCount++
            if (rowCount <= MAX_ROWS) rows += row
        }

        for (entry in request.entries) {
            val bookingText = DatevCharacterSet.sanitize(raw = entry.description, maxLength = BOOKING_TEXT_MAX_LENGTH)
            if (bookingText.transliterated) transliteratedEntryCount++
            val voucherField1 =
                entry.voucherReference
                    ?.let { BELEGFELD1_ALLOWED.replace(it, "") }
                    ?.let { stripLeadingFormulaTriggers(it) }
                    ?.take(BELEGFELD1_MAX_LENGTH)
                    .orEmpty()

            val debitByAccount = entry.postings.filter { it.side == PostingSide.DEBIT }.groupAmountsByAccount()
            val creditByAccount = entry.postings.filter { it.side == PostingSide.CREDIT }.groupAmountsByAccount()
            entry.postings.forEach { p ->
                if (p.side == PostingSide.DEBIT) debitTotal += p.amount else creditTotal += p.amount
            }

            when {
                debitByAccount.size == 1 && creditByAccount.size == 1 -> {
                    val (debitAccount, amount) = debitByAccount.entries.single()
                    val (creditAccount, _) = creditByAccount.entries.single()
                    addRow(
                        DatevBookingRow(
                            amount = amount,
                            side = PostingSide.DEBIT,
                            account = debitAccount,
                            contraAccount = creditAccount,
                            entryDate = entry.entryDate,
                            voucherField1 = voucherField1,
                            bookingText = bookingText.value,
                        ),
                    )
                }
                debitByAccount.size > 1 && creditByAccount.size == 1 -> {
                    val (creditAccount, _) = creditByAccount.entries.single()
                    debitByAccount.forEach { (account, amount) ->
                        addRow(
                            DatevBookingRow(
                                amount = amount,
                                side = PostingSide.DEBIT,
                                account = account,
                                contraAccount = creditAccount,
                                entryDate = entry.entryDate,
                                voucherField1 = voucherField1,
                                bookingText = bookingText.value,
                            ),
                        )
                    }
                }
                debitByAccount.size == 1 && creditByAccount.size > 1 -> {
                    val (debitAccount, _) = debitByAccount.entries.single()
                    creditByAccount.forEach { (account, amount) ->
                        addRow(
                            DatevBookingRow(
                                amount = amount,
                                side = PostingSide.CREDIT,
                                account = account,
                                contraAccount = debitAccount,
                                entryDate = entry.entryDate,
                                voucherField1 = voucherField1,
                                bookingText = bookingText.value,
                            ),
                        )
                    }
                }
                else -> unmappableEntries += entry
            }
        }

        if (unmappableEntries.isNotEmpty()) {
            val listed =
                unmappableEntries.take(MAX_LISTED_ENTRIES_IN_DETAIL).joinToString("; ") { entry ->
                    "Belegdatum ${entry.entryDate}" + (entry.voucherReference?.let { ", Beleg $it" } ?: "")
                }
            val more = unmappableEntries.size - MAX_LISTED_ENTRIES_IN_DETAIL
            val detail = if (more > 0) "$listed; … und $more weitere" else listed
            blockers += DatevExportBlockerDto(kind = DatevExportBlockerKind.UNMAPPABLE_MANY_TO_MANY_ENTRY, detail = detail)
        }

        val accountsUsed = rows.flatMap { listOf(it.account, it.contraAccount) }.distinct()

        /**
         * Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
         * MINOR): every check below this point only ever looked at an account number's LENGTH
         * ([VALID_ACCOUNT_LENGTH_RANGE]/[DatevExportBlockerKind.MIXED_ACCOUNT_NUMBER_LENGTHS]/
         * [DatevExportBlockerKind.ACCOUNT_NUMBER_LENGTH_OUT_OF_RANGE]) -- never its CHARACTER
         * content. `AccountingService.requireValidAccountNumberFormat` enforces digits-only for
         * every NEWLY created [network.lapis.cloud.server.db.generated.LedgerAccountTable] row, but
         * that check did not exist before this branch and the column itself carries no DB-level
         * CHECK constraint (`VARCHAR(10) NOT NULL`, see `V1__baseline.sql`) -- an account number
         * created before that guard existed could therefore still contain `;`, `"`, CR or LF. `Konto`
         * /`Gegenkonto` (Feld 7/8) are rendered UNQUOTED ("nackt", see class KDoc "Feldbelegung"), so
         * such a character would silently shift every subsequent field in the row -- exactly the
         * stealth field-count corruption `requireValidAccountNumberFormat`'s own KDoc describes.
         * Checked here, at the export boundary, rather than only at account-creation time, because
         * "reject at the source" cannot retroactively fix data that predates the source-side guard.
         */
        val invalidAccounts = accountsUsed.filterNot { it.all(Char::isDigit) }
        if (invalidAccounts.isNotEmpty()) {
            blockers +=
                DatevExportBlockerDto(
                    kind = DatevExportBlockerKind.ACCOUNT_NUMBER_CONTAINS_INVALID_CHARACTERS,
                    detail =
                        "Kontonummern mit unzulässigen Zeichen (nur Ziffern erlaubt): " +
                            invalidAccounts.take(MAX_LISTED_ENTRIES_IN_DETAIL).joinToString(", "),
                )
        }

        var derivedSachkontenlaenge: Int? = null
        if (accountsUsed.isNotEmpty()) {
            val lengths = accountsUsed.map { it.length }.distinct()
            if (lengths.size > 1) {
                blockers +=
                    DatevExportBlockerDto(
                        kind = DatevExportBlockerKind.MIXED_ACCOUNT_NUMBER_LENGTHS,
                        detail =
                            "Unterschiedliche Kontonummer-Längen im Zeitraum: " +
                                lengths.sorted().joinToString(", ") { len ->
                                    val example = accountsUsed.first { it.length == len }
                                    "$len (z. B. $example)"
                                },
                    )
            } else {
                val onlyLength = lengths.single()
                if (onlyLength !in VALID_ACCOUNT_LENGTH_RANGE) {
                    blockers +=
                        DatevExportBlockerDto(
                            kind = DatevExportBlockerKind.ACCOUNT_NUMBER_LENGTH_OUT_OF_RANGE,
                            detail = "Kontonummern-Länge $onlyLength liegt außerhalb des gültigen Bereichs $VALID_ACCOUNT_LENGTH_RANGE.",
                        )
                } else {
                    derivedSachkontenlaenge = onlyLength
                }
            }
        }

        if (rowCount > MAX_ROWS) {
            blockers +=
                DatevExportBlockerDto(
                    kind = DatevExportBlockerKind.TOO_MANY_ROWS,
                    detail = "Der Zeitraum ergäbe $rowCount Zeilen, das Limit liegt bei $MAX_ROWS.",
                )
        }

        val leadingZeroAccountCount = accountsUsed.count { it.startsWith("0") }

        return DatevExportPlan(
            blockers = blockers,
            rows = rows,
            derivedSachkontenlaenge = derivedSachkontenlaenge,
            debitTotal = debitTotal,
            creditTotal = creditTotal,
            transliteratedEntryCount = transliteratedEntryCount,
            leadingZeroAccountCount = leadingZeroAccountCount,
        )
    }

    /**
     * Serialisiert [plan] nach Windows-1252-Bytes. Wirft [IllegalArgumentException], wenn
     * `plan.exportable == false` -- die Route/der Service pruefen VORHER und antworten mit dem
     * Blocker statt einer Teil-Datei.
     */
    fun render(
        request: DatevExportRequest,
        plan: DatevExportPlan,
    ): ByteArray {
        require(plan.exportable) { "DatevExportPlan is not exportable -- ${plan.blockers}" }
        val beraterNummer = requireNotNull(request.beraterNummer) { "beraterNummer must be set when plan is exportable" }
        val mandantNummer = requireNotNull(request.mandantNummer) { "mandantNummer must be set when plan is exportable" }
        val sachkontenlaenge =
            requireNotNull(plan.derivedSachkontenlaenge) { "derivedSachkontenlaenge must be set when plan is exportable" }

        val sb = StringBuilder()
        sb.append(
            buildHeaderLine(
                request = request,
                beraterNummer = beraterNummer,
                mandantNummer = mandantNummer,
                sachkontenlaenge = sachkontenlaenge,
            ),
        )
        sb.append("\r\n")
        sb.append(COLUMN_NAMES.joinToString(";") { quote(it) })
        sb.append("\r\n")
        plan.rows.forEach { row ->
            sb.append(buildDataLine(row))
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(DatevCharacterSet.CP1252)
    }

    private fun buildHeaderLine(
        request: DatevExportRequest,
        beraterNummer: Int,
        mandantNummer: Int,
        sachkontenlaenge: Int,
    ): String {
        val generatedAt = request.generatedAt
        val timestamp =
            "%04d%02d%02d%02d%02d%02d%03d".format(
                generatedAt.year,
                generatedAt.monthNumber,
                generatedAt.dayOfMonth,
                generatedAt.hour,
                generatedAt.minute,
                generatedAt.second,
                generatedAt.nanosecond / 1_000_000,
            )
        val exportedBy = DatevCharacterSet.sanitize(raw = request.exportedBy, maxLength = EXPORTED_BY_MAX_LENGTH).value
        val organizationName =
            DatevCharacterSet.sanitize(raw = request.organizationName, maxLength = ORGANIZATION_NAME_MAX_LENGTH).value
        val fields =
            listOf(
                quote("EXTF"),
                "700",
                "21",
                quote("Buchungsstapel"),
                "13",
                timestamp,
                "",
                quote("LC"),
                quote(exportedBy),
                "",
                beraterNummer.toString(),
                mandantNummer.toString(),
                "${request.from.year}0101",
                sachkontenlaenge.toString(),
                headerDate(request.from),
                headerDate(request.to),
                quote(organizationName),
                "",
                "1",
                "",
                "0",
                quote("EUR"),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
            )
        check(fields.size == HEADER_FIELD_COUNT) { "Header must have exactly $HEADER_FIELD_COUNT fields, has ${fields.size}" }
        return fields.joinToString(";")
    }

    private fun buildDataLine(row: DatevBookingRow): String {
        val fields = MutableList(COLUMN_NAMES.size) { "" }
        fields[0] = formatAmount(row.amount) // 1 Umsatz
        fields[1] = quote(if (row.side == PostingSide.DEBIT) "S" else "H") // 2 Soll/Haben-Kennzeichen
        fields[6] = row.account // 7 Konto
        fields[7] = row.contraAccount // 8 Gegenkonto
        fields[9] = formatBelegdatum(row.entryDate) // 10 Belegdatum (TTMM)
        fields[10] = quote(row.voucherField1) // 11 Belegfeld 1
        fields[13] = quote(row.bookingText) // 14 Buchungstext
        return fields.joinToString(";")
    }

    /** JJJJMMTT (voll, mit Jahr) -- NUR fuer Kopfzeilen-Datumsfelder, NICHT das Belegdatum (TTMM,
     * siehe [formatBelegdatum]). Die Aufgabenstellung nennt beide fälschlich "TTMMJJJJ". */
    private fun headerDate(date: LocalDate): String = "%04d%02d%02d".format(date.year, date.monthNumber, date.dayOfMonth)

    /** TTMM -- vierstellig, OHNE Jahr. Das eigentliche Belegdatum-Format der Datenzeile. */
    private fun formatBelegdatum(date: LocalDate): String = "%02d%02d".format(date.dayOfMonth, date.monthNumber)

    /** Immer positiv, Komma statt Punkt, kein Tausendertrenner. `DECIMAL(15,2)` ist die maximale
     * Praezision dieses Betrags in der DB -- `RoundingMode.UNNECESSARY` wirft, statt still zu
     * runden, falls jemals ein feiner skalierter Wert hier ankaeme. */
    private fun formatAmount(amount: BigDecimal): String =
        amount
            .abs()
            .setScale(2, RoundingMode.UNNECESSARY)
            .toPlainString()
            .replace('.', ',')

    /** RFC4180/DATEV-Konvention: ein eingebettetes `"` wird verdoppelt (`""`), NICHT entfernt oder
     * unescaped durchgereicht -- ein rohes `"` mitten im Feld wuerde das Feld vorzeitig schliessen
     * und den Rest der Zeile in Nachbarfelder verschieben (siehe [DatevBuchungsstapelWriterTest]
     * "embedded quote in booking text is doubled, not dropped or left raw"). Betrifft Freitext, das
     * NICHT durch [DatevCharacterSet.sanitize] laeuft (Kontonummern etc. tragen nie `"`), aber auch
     * sanitiertes Freitext -- `sanitize` faltet typografische Anfuehrungszeichen (`„ “ ”`) AKTIV auf
     * ein plaines `"`, siehe dessen KDoc, das hier verdoppelt werden muss. */
    private fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun List<DatevSourcePosting>.groupAmountsByAccount(): Map<String, BigDecimal> =
        groupBy { it.accountNumber }.mapValues { (_, postings) -> postings.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount } }
}
