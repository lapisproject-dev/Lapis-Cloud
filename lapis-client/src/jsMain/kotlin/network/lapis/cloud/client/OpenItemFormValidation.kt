package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import io.kvision.i18n.I18n
import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.OpenItemDirection

/**
 * Welle V1.4.21 "Bedienoberfläche Offene Posten" -- DOM-freie Formular-Vorprüfung für
 * `OpenItemsScreen`. **Loser Spiegel** von `OpenItemService.createOpenItem`/`settleOpenItem`, nie
 * die Sicherheitsgrenze -- gleiche Haltung wie [Validation] und `validateDunningLevelInput`: der
 * Server bleibt die Autorität, dieser Code spart nur den Round-Trip für offensichtliche Fehler.
 */
sealed interface AmountInput {
    data class Valid(
        val value: Decimal,
    ) : AmountInput

    /** [reason] ist bereits übersetzt (gettext). */
    data class Invalid(
        val reason: String,
    ) : AmountInput

    data object Empty : AmountInput
}

/** Spiegel `OpenItemService.MAX_AMOUNT_SCALE`. */
internal const val MAX_OPEN_ITEM_AMOUNT_SCALE = 2
internal const val MAX_COUNTERPARTY_NAME_LENGTH = 200
internal const val MAX_OPEN_ITEM_REFERENCE_LENGTH = 100
internal const val MAX_OPEN_ITEM_NOTE_LENGTH = 1000
internal const val MAX_OPEN_ITEM_REASON_LENGTH = 500

private val AMOUNT_SHAPE = Regex("^\\d+([.,]\\d+)?$")
private val GROUP_BLANKS = Regex("[\\s\\u00A0\\u202F'\\u2019]")
private val DIGITS_ONLY = Regex("^\\d+$")

/**
 * Result of [normalizeAmountText]: a dot-decimal digit string (`1234.56`) or `null` when the text is not a number in the rules below.
 */
private fun groupedOk(
    integerPart: String,
    sep: Char,
): Boolean {
    val groups = integerPart.split(sep)
    return groups.size > 1 &&
        groups.first().length in 1..3 &&
        !groups.first().startsWith('0') &&
        groups.drop(1).all { it.length == 3 } &&
        groups.all { DIGITS_ONLY.matches(it) }
}

/**
 * V1.9.200: reads a typed amount into a plain dot-decimal string (`1234.56`), or `null`. The rules, in this order:
 * 1. Blanks, U+00A0, U+202F and apostrophes (`'`, U+2019) are grouping and are removed.
 * 2. Both `.` and `,` present: the LAST one is the decimal sign, the other one may only separate groups of three (`1.000,50`, `1,000.50`).
 * 3. Only one sign present: if it is the decimal sign of the UI [language] (comma for de/es/fr/it/nl/pl/ru, point for en) it is the decimal
 *    sign. If it is the OTHER sign it is grouping when it stands once with exactly three digits behind it, or several times in valid groups
 *    of three (`1.234` is 1234 in German; `1,234` is 1234 in English). With one or two digits behind it, it is the decimal sign
 *    (`0.1` in German, `0,1` in English stay valid).
 * 4. The same sign more than once is grouping only in valid groups of three (`1.000.000`, in every language); otherwise (`1.00.0`) it is invalid.
 * The server always receives the dot-decimal result; nothing here goes through `Double`.
 */
internal fun normalizeAmountText(
    raw: String,
    language: String,
): String? {
    val text = GROUP_BLANKS.replace(raw, "")
    if (text.isEmpty()) return null
    val uiDecimal = moneyLocale(language).decimal.single()
    val hasDot = '.' in text
    val hasComma = ',' in text
    val body: String
    when {
        hasDot && hasComma -> {
            val sign = if (text.lastIndexOf('.') > text.lastIndexOf(',')) '.' else ','
            val other = if (sign == '.') ',' else '.'
            if (text.count { it == sign } != 1) return null
            val integerPart = text.substringBefore(sign)
            if (!groupedOk(integerPart, other)) return null
            body = integerPart.replace(other.toString(), "") + "." + text.substringAfter(sign)
        }
        hasDot || hasComma -> {
            val sign = if (hasDot) '.' else ','
            val count = text.count { it == sign }
            val after = text.substringAfterLast(sign)
            val asGrouping =
                (count > 1 && groupedOk(text, sign)) ||
                    (count == 1 && sign != uiDecimal && after.length == 3 && groupedOk(text, sign))
            when {
                asGrouping -> body = text.replace(sign.toString(), "")
                count == 1 -> body = text.replace(sign, '.')
                else -> return null
            }
        }
        else -> {
            body = text
        }
    }
    return if (AMOUNT_SHAPE.matches(body)) body else null
}

/**
 * Spiegel von `OpenItemService.MAX_AMOUNT` (loser Spiegel, der Server bleibt die Autorität): jeder
 * Betrag dieses Teilbuchs landet in einer `numeric(12,2)`-Spalte, ab 10^10 läuft sie über. Ohne
 * diese Obergrenze war "99999999999999999999,99" hier gültig, ging als JSON-Double (`1.0E20`) über
 * die Leitung und endete server-seitig in einem DB-Überlauf mit HTTP 500 statt einer sauberen
 * Ablehnung (Review-Fund N1).
 *
 * **Die Fehlermeldung nennt diesen Wert über einen Platzhalter, nie als Literal im `msgid`**
 * (Audit-Fund zweiter Durchgang): vorher stand die Obergrenze zweimal in der Datei -- hier als
 * Konstante und ausgeschrieben im Meldungstext -- und eine Änderung der Konstante hätte acht
 * Katalogtexte still verfälscht.
 */
internal const val MAX_OPEN_ITEM_AMOUNT = 1_000_000_000.00

/**
 * Betrags-Parser (seit V1.9.200 mit Tausendertrennzeichen, Regeln siehe [normalizeAmountText]; die Sprache der Oberfläche
 * entscheidet über "1.234" gegenüber "1,234"), strikt größer 0, höchstens zwei Nachkommastellen
 * (sonst lehnt der Server erst nach dem Round-Trip mit einem kryptischen Fehler ab) und höchstens
 * [MAX_OPEN_ITEM_AMOUNT].
 *
 * [allowZero] und [enforceMaxAmount] gibt es für FELDER MIT EIGENER GRENZE (Mahngebühr 0,00 bis 25,00 EUR): dort ist `0` ein
 * gültiger Betrag, und die Obergrenze des Buchungsbetrags (eine Milliarde) darf nicht als Fehlermeldung erscheinen, wenn die
 * echte Grenze 25 ist -- der Aufrufer prüft seine eigene Obergrenze selbst. Die Voreinstellung ist unverändert.
 */
fun parseAmountInput(
    raw: String?,
    allowZero: Boolean = false,
    enforceMaxAmount: Boolean = true,
    language: String = I18n.language,
    maxFractionDigits: Int = MAX_OPEN_ITEM_AMOUNT_SCALE,
): AmountInput {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return AmountInput.Empty
    val normalized =
        normalizeAmountText(trimmed, language)
            ?: return AmountInput.Invalid(
                gettext("Bitte einen Betrag wie %1 eingeben.", formatPlainAmountIn(language, 1234.56.toDecimal())),
            )
    val fractionDigits = normalized.substringAfter('.', "").length
    if (fractionDigits > maxFractionDigits) {
        return AmountInput.Invalid(gettext("Höchstens %1 Nachkommastellen erlaubt.", maxFractionDigits))
    }
    val value = normalized.toDoubleOrNull()
    if (value == null || !value.isFinite() || value < 0.0 || (value == 0.0 && !allowZero)) {
        return AmountInput.Invalid(gettext("Der Betrag muss größer als 0 sein."))
    }
    if (enforceMaxAmount && value > MAX_OPEN_ITEM_AMOUNT) {
        return AmountInput.Invalid(gettext("Der Betrag ist zu groß (höchstens %1).", formatMoney(MAX_OPEN_ITEM_AMOUNT.toDecimal())))
    }
    return AmountInput.Valid(value.toDecimal())
}

/**
 * Feldregel für einen Betrag der offenen Posten (Anlegen, Ausgleich, Verrechnung): [parseAmountInput] MIT der Teilbuch-Grenze
 * ([MAX_OPEN_ITEM_AMOUNT]); die Meldung ist bereits aufgelöst (nie ein `tr()`-Marker). Leere behandelt `required`.
 */
internal fun openItemAmountCheck(value: String): FieldCheck =
    when (val parsed = parseAmountInput(value)) {
        is AmountInput.Empty -> FieldCheck.Ok
        is AmountInput.Invalid -> FieldCheck.Invalid(resolvedAttributeText(parsed.reason))
        is AmountInput.Valid -> FieldCheck.Ok
    }

/**
 * Lose Vorprüfung des Anlegen-Formulars; `null` = gültig, sonst der (bereits übersetzte) erste
 * Fehler. [direction] ist bewusst ein Pflichtfeld ohne Vorbelegung in der "Alle"-Sicht
 * (Jobs-Ruling 1, Stolperfalle S11) -- `null` heißt "noch nicht gewählt".
 */
@Suppress("LongParameterList")
fun validateOpenItemForm(
    direction: OpenItemDirection?,
    counterpartyName: String,
    itemDate: LocalDate?,
    dueDate: LocalDate?,
    amount: AmountInput,
    contraAccountId: String?,
    reference: String?,
    note: String?,
): String? {
    val name = counterpartyName.trim()
    return when {
        direction == null -> gettext("Bitte eine Richtung (Kreditor oder Debitor) wählen.")
        name.isEmpty() -> gettext("Bitte eine Gegenpartei angeben.")
        name.length > MAX_COUNTERPARTY_NAME_LENGTH ->
            gettext("Die Gegenpartei darf höchstens %1 Zeichen lang sein.", MAX_COUNTERPARTY_NAME_LENGTH)
        itemDate == null -> gettext("Bitte ein Belegdatum angeben.")
        dueDate == null -> gettext("Bitte ein Fälligkeitsdatum angeben.")
        dueDate < itemDate -> gettext("Das Fälligkeitsdatum darf nicht vor dem Belegdatum liegen.")
        amount is AmountInput.Empty -> gettext("Bitte einen Betrag angeben.")
        amount is AmountInput.Invalid -> amount.reason
        contraAccountId.isNullOrBlank() -> gettext("Bitte ein Gegenkonto wählen.")
        (reference?.trim()?.length ?: 0) > MAX_OPEN_ITEM_REFERENCE_LENGTH ->
            gettext("Die Belegnummer darf höchstens %1 Zeichen lang sein.", MAX_OPEN_ITEM_REFERENCE_LENGTH)
        (note?.trim()?.length ?: 0) > MAX_OPEN_ITEM_NOTE_LENGTH ->
            gettext("Die Notiz darf höchstens %1 Zeichen lang sein.", MAX_OPEN_ITEM_NOTE_LENGTH)
        else -> null
    }
}

/**
 * Welcher [LedgerAccountType] als Gegenkonto in Frage kommt -- Spiegel
 * `OpenItemPostingBridge.contraExpectedType`: Kreditor -> EXPENSE, Debitor -> INCOME.
 *
 * **Reine Fehlervermeidung, kein Spiegel einer Server-Ablehnung** (Korrektur K2 des Plans):
 * `createOpenItem` prüft den Kontotyp NICHT. Erst die Buchungsbrücke tut es -- und ein falscher Typ
 * führt dort zu `creationPostingError = "contra_account_wrong_type"`, der Posten wird TROTZDEM
 * angelegt, nur eben nicht gebucht. Der Typfilter im Select verhindert diesen Zustand, ersetzt aber
 * nicht die Anzeige des Buchungsfehlers ([openItemPostingErrorMessage]).
 */
fun expectedContraAccountType(direction: OpenItemDirection): LedgerAccountType =
    when (direction) {
        OpenItemDirection.PAYABLE -> LedgerAccountType.EXPENSE
        OpenItemDirection.RECEIVABLE -> LedgerAccountType.INCOME
    }

/**
 * Klartext zu jedem `creationPostingError`-/`postingError`-Code der Buchungsbrücke
 * (`OpenItemPostingBridge`). Unbekannter Code -> `null`: der Aufrufer zeigt dann den Rohtext,
 * verschluckt einen neuen Server-Code also nie.
 */
fun openItemPostingErrorMessage(code: String): String? =
    when (code) {
        "payables_account_not_configured" ->
            gettext("Kein Verbindlichkeitenkonto zugeordnet. Ein Administrator muss es im Kontenplan hinterlegen.")
        "receivables_account_not_configured" ->
            gettext("Kein Forderungskonto zugeordnet. Ein Administrator muss es im Kontenplan hinterlegen.")
        "payment_bank_account_not_configured" ->
            gettext("Kein Bankkonto zugeordnet. Bitte ein Bankkonto wählen oder im Kontenplan hinterlegen.")
        "ledger_account_inactive" -> gettext("Ein beteiligtes Konto ist inaktiv oder nicht mehr vorhanden.")
        "contra_account_wrong_type" ->
            gettext("Das Gegenkonto hat den falschen Kontotyp (Kreditor: Aufwandskonto, Debitor: Ertragskonto).")
        "receivables_account_not_asset_type" -> gettext("Das zugeordnete Forderungskonto ist kein Aktivkonto.")
        "payables_account_not_liability_type" -> gettext("Das zugeordnete Verbindlichkeitenkonto ist kein Passivkonto.")
        "cash_voucher_required" -> gettext("Für Buchungen auf ein Kassenkonto ist ein Beleg erforderlich.")
        "cash_register_balance_insufficient" -> gettext("Der Kassenbestand würde negativ.")
        else -> null
    }

/**
 * V1.9.200: the one reader for an LTR amount typed into a field (stake, boost, bid, transfer, pledge): [parseAmountInput] in the language of the
 * UI, strictly above 0, at most two decimal places (the server's scale), no ledger ceiling. `.Empty` and `.Invalid` carry the reason.
 */
internal fun parseLtrAmountInput(text: String): AmountInput = parseAmountInput(text, allowZero = false, enforceMaxAmount = false)

/** The message for a rejected amount: the parser's own reason where it has one, [emptyMessage] (the field's "please enter ...") when blank. */
internal fun amountErrorText(
    parsed: AmountInput,
    emptyMessage: String,
): String = (parsed as? AmountInput.Invalid)?.reason ?: emptyMessage
