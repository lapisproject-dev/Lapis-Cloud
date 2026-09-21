package network.lapis.cloud.client

import dev.kilua.rpc.types.toDouble
import io.kvision.i18n.gettext

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
}

/** "Die Gebühr muss zwischen 0,00 € und [max] liegen." -- die Grenzen als Platzhalter, nie im `msgid`. */
internal fun feeRangeMessage(max: Double): String = gettext("Die Gebühr muss zwischen %1 und %2 liegen.", feeBound(0.0), feeBound(max))

/** "25,00 €" -- zwei Nachkommastellen, Dezimalkomma wie im Rest der Oberfläche ([formatMoney] hängt nur ein " €" an). */
internal fun feeBound(amount: Double): String = "${amount.asDynamic().toFixed(2).unsafeCast<String>().replace('.', ',')} €"
