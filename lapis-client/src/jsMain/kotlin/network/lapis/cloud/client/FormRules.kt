package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Obergrenze eines Buchungsbetrags (eine Billion). Die Spalte `posting.amount` ist `DECIMAL(15,2)` und läuft ab 10^13 über; die Grenze
 * liegt bewusst darunter und ist als `Double` exakt darstellbar. Siehe [FormRules.postingAmount].
 */
internal const val MAX_POSTING_AMOUNT = 1_000_000_000_000.00

/**
 * Obergrenze der Rücklastschriftgebühr (eine Milliarde). Die Spalte `sepa_return.return_fee` ist `DECIMAL(12,2)` und läuft ab 10^10 über --
 * NICHT die Journal-Grenze [MAX_POSTING_AMOUNT], die zu einer 100-mal breiteren Spalte gehört. Dieselbe Größenordnung wie
 * [MAX_OPEN_ITEM_AMOUNT] (gleiche Spaltenbreite); der Server zieht dieselbe Grenze in `SepaService.recordReturn`. Siehe [FormRules.returnFee].
 */
internal const val MAX_RETURN_FEE_AMOUNT = 1_000_000_000.00

/** Spaltenbreite `journal_entry.description` (`varchar(500)`); der Server prüft dieselbe Grenze in `AccountingService`. */
internal const val MAX_JOURNAL_DESCRIPTION_LENGTH = 500

/** Spaltenbreite `journal_entry.voucher_reference` (`varchar(100)`); der Server prüft dieselbe Grenze in `AccountingService`. */
internal const val MAX_JOURNAL_VOUCHER_LENGTH = 100

/**
 * Welle V1.4.28 (W4a): die wiederverwendbaren Feldregeln der Formular-Grammatik ([LapisForm]). Rein und DOM-frei
 * (direkt testbar). Jede Regel spiegelt ausschließlich eine BESTEHENDE Grenze aus [Validation] bzw. dem Server -- der Server
 * bleibt die Autorität; die Regeln machen die Grenze nur früher und feldbezogen sichtbar. Meldungstexte sind
 * `gettext(...)`-Ergebnisse mit `%N`-Platzhaltern (nie eine Konstante im msgid) und damit bereits aufgelöst.
 *
 * Leere Werte behandelt der Baustein ([LapisField]) selbst (`required`); eine Regel sieht nur nicht-leere Werte.
 */
object FormRules {
    /** E-Mail-Adresse: höchstens [Validation.EMAIL_MAX_LENGTH] Zeichen und "sieht aus wie eine Adresse". */
    fun email(value: String): FieldCheck =
        when {
            value.trim().length > Validation.EMAIL_MAX_LENGTH ->
                FieldCheck.Invalid(gettext("Die E-Mail-Adresse ist zu lang (höchstens %1 Zeichen).", Validation.EMAIL_MAX_LENGTH))
            !Validation.looksLikeEmail(value) -> FieldCheck.Invalid(gettext("Bitte eine gültige E-Mail-Adresse eingeben."))
            else -> FieldCheck.Ok
        }

    /** Ganze Zahl im geschlossenen Bereich [[min], [max]] -- Vorabankündigungs-, Mahn-Fristen und Stufennummern. */
    fun intInRange(
        value: String,
        min: Int,
        max: Int,
    ): FieldCheck {
        val number = value.trim().toIntOrNull()
        return if (number == null || number < min || number > max) {
            FieldCheck.Invalid(gettext("Bitte eine ganze Zahl zwischen %1 und %2 eingeben.", min, max))
        } else {
            FieldCheck.Ok
        }
    }

    /**
     * Optionale Gebühr in EUR: leer ist gültig, `0` ebenfalls, sonst ein Betrag mit höchstens zwei Nachkommastellen bis
     * [max] (Mahngebühr: 0,00 bis 25,00). Die Meldungen sind BEREITS aufgelöst (nie ein `tr()`-Marker aus [parseAmountInput])
     * und nennen die Grenze dieses Feldes, nicht die Milliarden-Grenze des Buchungsbetrags.
     */
    fun optionalFee(
        value: String,
        max: Double,
    ): FieldCheck =
        when (val parsed = parseAmountInput(value, allowZero = true, enforceMaxAmount = false)) {
            is AmountInput.Empty -> FieldCheck.Ok
            is AmountInput.Invalid -> FieldCheck.Invalid(resolvedAttributeText(parsed.reason))
            is AmountInput.Valid ->
                if (parsed.value.toDouble() > max) {
                    FieldCheck.Invalid(feeRangeMessage(max))
                } else {
                    FieldCheck.Ok
                }
        }

    /**
     * Buchungs-/Zahlbetrag dieser Welle (V1.4.30): höchstens zwei Nachkommastellen (`JournalEntryBalance.MAX_AMOUNT_SCALE = 2`
     * LEHNT mehr ab -- die Oberfläche rundete "10,005" bisher still auf 10,01 und buchte etwas anderes, als dort stand) und höchstens
     * [MAX_POSTING_AMOUNT]. **Benannte Verschärfung**, siehe CHANGELOG.
     *
     * Die Obergrenze zieht auch der SERVER (`JournalEntryBalance.MAX_POSTING_AMOUNT`, dieselbe Größenordnung; der Server bleibt die
     * Autorität): Die Spalte ist `DECIMAL(15,2)` (ab 10^13 Überlauf), und "99999999999999999999" kommt als JSON-Double (`1.0E20`,
     * Skala -19) an, das Skala und `> 0` passiert -- ohne Serverprüfung endete das im DB-Überlauf mit HTTP 500 (dieselbe Fehlerklasse
     * wie der Review-Fund N1 bei den offenen Posten). Die Grenze hier ist bewusst großzügig, aber weit unter dem Spaltenlimit -- und NICHT
     * `MAX_OPEN_ITEM_AMOUNT`, die gehört dem Teilbuch der offenen Posten. Kein Vorzeichen: die Richtung ist und bleibt allein das
     * Soll/Haben-Feld.
     */
    fun postingAmount(value: String): FieldCheck =
        when (val parsed = parseAmountInput(value, allowZero = false, enforceMaxAmount = false)) {
            is AmountInput.Empty -> FieldCheck.Ok // Leere behandelt `required`
            is AmountInput.Invalid -> FieldCheck.Invalid(resolvedAttributeText(parsed.reason))
            is AmountInput.Valid ->
                if (parsed.value.toDouble() > MAX_POSTING_AMOUNT) {
                    FieldCheck.Invalid(gettext("Der Betrag ist zu groß (höchstens %1).", formatMoney(MAX_POSTING_AMOUNT.toDecimal())))
                } else {
                    FieldCheck.Ok
                }
        }

    /**
     * Freitext mit fester Spaltenbreite: höchstens [max] Zeichen nach `trim()` (das ist, was gesendet wird). Spiegelt die Servergrenze
     * (`AccountingService.requireValidTextLengths`); der Server bleibt Autorität. Der Meldungstext existiert bereits im Katalog.
     */
    fun maxLength(
        value: String,
        max: Int,
    ): FieldCheck = if (value.trim().length > max) FieldCheck.Invalid(gettext("Höchstens %1 Zeichen.", max)) else FieldCheck.Ok

    /**
     * Rücklastschriftgebühr (optional, positiv, höchstens zwei Nachkommastellen) bis [MAX_RETURN_FEE_AMOUNT]. Die Grenze gehört zur Spalte
     * `sepa_return.return_fee DECIMAL(12,2)` (Überlauf ab 10^10), nicht zum Journal. Die Meldung nennt die Grenze DIESES Feldes.
     */
    fun returnFee(value: String): FieldCheck =
        when (val parsed = parseAmountInput(value, allowZero = false, enforceMaxAmount = false)) {
            is AmountInput.Empty -> FieldCheck.Ok
            is AmountInput.Invalid -> FieldCheck.Invalid(resolvedAttributeText(parsed.reason))
            is AmountInput.Valid ->
                if (parsed.value.toDouble() > MAX_RETURN_FEE_AMOUNT) {
                    FieldCheck.Invalid(gettext("Der Betrag ist zu groß (höchstens %1).", formatMoney(MAX_RETURN_FEE_AMOUNT.toDecimal())))
                } else {
                    FieldCheck.Ok
                }
        }

    /**
     * Neues Passwort: Längen- und Selbst-E-Mail-Regel aus [Validation.passwordHint]. **Nie für ein Login-Passwort**: ein
     * altes, damals gültiges Passwort darf die Oberfläche nicht für falsch erklären.
     */
    fun newPassword(
        value: String,
        email: String,
    ): FieldCheck = Validation.passwordHint(value, email)?.let { FieldCheck.Invalid(it) } ?: FieldCheck.Ok

    /** Passwortgleichheit -- die Meldung existiert bereits im Katalog. */
    fun passwordsMatch(
        password: String,
        confirmation: String,
    ): FieldCheck =
        if (Validation.passwordsMatch(password, confirmation)) {
            FieldCheck.Ok
        } else {
            FieldCheck.Invalid(gettext("Die Passwörter stimmen nicht überein."))
        }

    /**
     * Begründung/Notiz mit Protokollwirkung: [min]..[max] Zeichen nach `trim()`. Spiegelt die Servergrenze; der Server bleibt
     * Autorität. Der Meldungstext existiert bereits im Katalog (aus `MemberPasswordResetDialog`, V1.4.28).
     */
    fun reasonText(
        value: String,
        min: Int = REASON_MIN_LENGTH,
        max: Int = REASON_MAX_LENGTH,
    ): FieldCheck =
        if (value.trim().length in min..max) {
            FieldCheck.Ok
        } else {
            FieldCheck.Invalid(gettext("Bitte eine Begründung mit %1 bis %2 Zeichen angeben.", min, max))
        }

    /** Zeitpunkt (`2026-08-15T18:00`): ein echter Kalenderzeitpunkt. Das Format nennt der Hinweis des Feldes, nie die Meldung. */
    fun localDateTime(value: String): FieldCheck =
        if (runCatching { LocalDateTime.parse(value.trim()) }.isSuccess) {
            FieldCheck.Ok
        } else {
            FieldCheck.Invalid(gettext("Bitte einen gültigen Termin angeben."))
        }

    /** Datum (`2026-03-14`): ein echtes Kalenderdatum. Das Format nennt der Hinweis des Feldes, nie die Meldung. */
    fun isoDate(value: String): FieldCheck =
        if (runCatching { LocalDate.parse(value.trim()) }.isSuccess) {
            FieldCheck.Ok
        } else {
            FieldCheck.Invalid(gettext("Bitte ein gültiges Datum angeben."))
        }

    /** Irgendeine ganze Zahl (Position einer Tagesordnung): spiegelt "lässt sich als `Int` lesen". */
    fun wholeNumber(value: String): FieldCheck =
        if (value.trim().toIntOrNull() == null) FieldCheck.Invalid(gettext("Bitte eine ganze Zahl eingeben.")) else FieldCheck.Ok

    /** Ganze Zahl ab [min] (Stimmzahlen, Positionen). */
    fun intAtLeast(
        value: String,
        min: Int,
    ): FieldCheck {
        val number = value.trim().toIntOrNull()
        return if (number == null || number < min) {
            FieldCheck.Invalid(gettext("Bitte eine ganze Zahl von %1 oder größer eingeben.", min))
        } else {
            FieldCheck.Ok
        }
    }

    /** Grenzen der Begründung -- spiegeln die Servergrenze (3..1000). */
    const val REASON_MIN_LENGTH: Int = 3
    const val REASON_MAX_LENGTH: Int = 1000
}

/** "Die Gebühr muss zwischen 0,00 € und [max] liegen." -- die Grenzen als Platzhalter, nie im `msgid`. */
internal fun feeRangeMessage(max: Double): String = gettext("Die Gebühr muss zwischen %1 und %2 liegen.", feeBound(0.0), feeBound(max))

/** "25,00 €" -- zwei Nachkommastellen, Dezimalkomma wie im Rest der Oberfläche ([formatMoney] hängt nur ein " €" an). */
internal fun feeBound(amount: Double): String = "${amount.asDynamic().toFixed(2).unsafeCast<String>().replace('.', ',')} €"
