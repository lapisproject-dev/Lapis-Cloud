package network.lapis.cloud.server.accounting.datev

import java.nio.charset.Charset
import java.text.Normalizer

/**
 * Welle V1.4.5.2. Zeichenaufbereitung fuer DATEV-EXTF-Textfelder. Anders als
 * `network.lapis.cloud.server.payment.sepa.SepaCharacterSet` (SEPA-Basiszeichensatz, Umlaute
 * MUESSEN weichen) behaelt diese Klasse alles, was Windows-1252 darstellen kann -- Umlaute und
 * `ß` bleiben also stehen.
 *
 * Emittiert NIEMALS `?`. Der Standard-CharsetEncoder ersetzt nicht abbildbare Zeichen still durch
 * `?`; in einem Buchungstext sieht ein Fragezeichen wie ein Tippfehler aus und niemand bemerkt den
 * Datenverlust. Stattdessen: (1) explizite Map fuer typografische Zeichen, (2) NFD-Akzentabbau,
 * (3) alles danach noch Nicht-Abbildbare (Kyrillisch, CJK) wird verworfen und ueber
 * [SanitizeResult.transliterated] gemeldet, damit die Vorschau die Zahl betroffener Buchungen
 * nennen kann.
 *
 * Gilt AUSSCHLIESSLICH fuer Nutzdaten (Buchungstext, Bezeichnung, Exporteur). Die fixen
 * Spaltenueberschriften enthalten selbst einen En-Dash (`Beleginfo – Art 1`, Byte 0x96) und
 * duerfen niemals durch diese Funktion laufen -- siehe [DatevBuchungsstapelWriter.COLUMN_NAMES].
 */
object DatevCharacterSet {
    /** Byte-fuer-Byte verifiziert gegen die ledermann/datev EXTF-Referenzdatei -- siehe
     * `docs/architecture/datev-export.adoc` "Format-Grundlage". NIE ISO-8859-1: der En-Dash
     * (Byte 0x96) der Ueberschriftenzeile ist in ISO-8859-1 ein C1-Steuerzeichen. */
    val CP1252: Charset = Charset.forName("windows-1252")

    /** [value] ist das aufbereitete Ergebnis, garantiert vollstaendig CP1252-encodierbar.
     * [transliterated] ist `true`, wenn mindestens ein Zeichen von [raw] entweder ueber die
     * explizite Map gefaltet, per NFD-Akzentabbau normalisiert, oder (nicht abbildbar) verworfen
     * wurde -- ein reiner Umlaut-/ß-Text zaehlt NICHT als transliteriert, da CP1252 ihn nativ
     * traegt. */
    data class SanitizeResult(
        val value: String,
        val transliterated: Boolean,
    )

    private val TYPOGRAPHIC_MAP =
        mapOf(
            '€' to "EUR",
            '„' to "\"",
            '“' to "\"",
            '”' to "\"",
            '‚' to "'",
            '‘' to "'",
            '’' to "'",
            '–' to "-",
            '—' to "-",
            '…' to "...",
            '•' to "-",
            ' ' to " ",
        )

    /**
     * Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
     * MAJOR, OWASP CSV/Formula Injection): the classic CSV-formula-injection trigger set -- see the
     * analogous `FORMULA_TRIGGER_CHARS` constant and its KDoc in
     * `network.lapis.cloud.server.bootstrap.MemberCsvImport` for the full character-by-character
     * rationale (`'='`/`'@'` are the obvious spreadsheet-formula sigils, `'+'`/`'-'` because Excel
     * accepts a bare arithmetic expression like `-1+2` or `+cmd|'/c calc'!A1` as a formula with no
     * leading `=` at all). `'\t'`/`'\r'` are deliberately NOT repeated here -- every C0 control
     * character, tab and CR included, is already folded to a space by the ISO-control branch above,
     * so by the time this check runs neither can ever be the first character of the result any more.
     *
     * Unlike `MemberCsvImport`'s report (a purely internal artifact opened once by an operator,
     * where prefixing a leading apostrophe is an acceptable "force this cell to be text" escape), a
     * DATEV Buchungsstapel crosses a trust boundary to an EXTERNAL Steuerberater who both opens it
     * directly in Excel/LibreOffice AND re-imports the very same bytes into DATEV accounting
     * software -- a literal apostrophe prefix would land inside the imported booking text and
     * corrupt the accounting record itself, not merely how a spreadsheet displays it. Because posted
     * journal entries are immutable (GoBD/§257 HGB -- see `AccountingService` class KDoc
     * "accounting records are never anonymized/deleted"), rejecting the export outright would also
     * make an already-posted, un-editable historical entry permanently unexportable over something
     * as mundane as a booking text starting with "-5% Skonto gewährt". [sanitize] instead FOLDS
     * only the leading trigger character to a space -- the exact same "protect the format, report
     * via [SanitizeResult.transliterated]" idiom the ISO-control fold above already established --
     * which defuses every known spreadsheet formula/DDE vector (`=cmd|...`, `=WEBSERVICE(...)`,
     * `+cmd|...`, `-2+3`, `@SUM(...)`) while leaving the remainder of the text intact and legible.
     */
    private val LEADING_FORMULA_TRIGGER_CHARS = charArrayOf('=', '+', '-', '@')

    /**
     * Faltet [raw] auf CP1252-darstellbare Zeichen und schneidet auf [maxLength] Zeichen ab (nach
     * der Faltung, nicht vorher -- ein zu langer Rohtext, der sich auf genau [maxLength] Zeichen
     * faltet, darf nicht vorzeitig abgeschnitten werden).
     *
     * Arbeitet Zeichen-fuer-Zeichen, NICHT durch eine pauschale NFD-Normalisierung des gesamten
     * Strings: CP1252 (anders als der reine SEPA-Basiszeichensatz) traegt Umlaute/ß bereits nativ
     * (0xE4/0xF6/0xFC/0xDF etc.) -- ein Zeichen, das der Encoder schon direkt abbilden kann, wird
     * NIE durch NFD zerlegt. Ohne diese Reihenfolge wuerde eine pauschale NFD-Normalisierung "ü"
     * in "u" + Combining-Diaeresis zerlegen und der naechste Schritt wuerde die Diaeresis als
     * Nicht-Leerzeichen-Markierung STREICHEN -- aus "Müller" wuerde "Muller", obwohl CP1252 "ü"
     * direkt kann. NFD-Akzentabbau greift deshalb nur als FALLBACK fuer ein Zeichen, das CP1252
     * NICHT direkt abbilden kann (z. B. "č", "ř").
     */
    fun sanitize(
        raw: String,
        maxLength: Int,
    ): SanitizeResult {
        var anyChanged = false
        val encoder = CP1252.newEncoder()
        val sb = StringBuilder()
        for (ch in raw) {
            val mapped = TYPOGRAPHIC_MAP[ch]
            when {
                mapped != null -> {
                    anyChanged = true
                    sb.append(mapped)
                }
                Character.isISOControl(ch) -> {
                    // Review-Fund (2026-09): CP1252 CAN encode every C0 control character (`\r`,
                    // `\n`, Tab, …), so the plain `encoder.canEncode(ch)` branch below let them
                    // through unchanged. `DatevBuchungsstapelWriter.render` appends its own
                    // "\r\n" after EVERY line, so an embedded "\r"/"\n" in a booking text tore one
                    // data record into two "\r\n"-separated output lines with the wrong field
                    // count -- while the preview kept reporting the original (correct) row count,
                    // reintroducing exactly the header/file divergence the DATEV export's single
                    // source of truth exists to prevent. Folded to a space, never passed through.
                    anyChanged = true
                    sb.append(' ')
                }
                encoder.canEncode(ch) -> sb.append(ch)
                else -> {
                    anyChanged = true
                    val normalized = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                    val stripped =
                        normalized.filterNot { Character.getType(it).toByte() == Character.NON_SPACING_MARK }
                    if (stripped.isNotEmpty() && stripped.all { encoder.canEncode(it) }) {
                        sb.append(stripped)
                    }
                    // else: no CP1252-safe representation at all (e.g. Cyrillic/CJK) -- drop the
                    // character entirely rather than emit a literal '?' (see class KDoc).
                }
            }
        }
        if (sb.isNotEmpty() && sb[0] in LEADING_FORMULA_TRIGGER_CHARS) {
            // See LEADING_FORMULA_TRIGGER_CHARS KDoc -- only position 0 matters, spreadsheet
            // formula-sniffing looks at the cell's FIRST character only, so a single fold (never a
            // loop stripping repeated leading triggers) is both necessary and sufficient here.
            anyChanged = true
            sb.setCharAt(0, ' ')
        }
        return SanitizeResult(value = sb.toString().take(maxLength), transliterated = anyChanged)
    }

    /** `true` iff every character of [value] is directly representable in [CP1252] -- used by
     * [DatevBuchungsstapelWriter] to assert the fixed column-header constants (which must NEVER be
     * run through [sanitize]) are themselves CP1252-safe. */
    fun isCp1252Safe(value: String): Boolean {
        val encoder = CP1252.newEncoder()
        return value.all { encoder.canEncode(it) }
    }
}
