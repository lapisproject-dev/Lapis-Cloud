package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
import io.kvision.i18n.gettext
import io.kvision.i18n.tr

internal const val MAX_RECEIVABLE_LEVEL_NAME_LENGTH = 100
internal const val MIN_RECEIVABLE_DUNNING_DAYS = 1
internal const val MAX_RECEIVABLE_DUNNING_DAYS = 365
internal const val MAX_RECEIVABLE_FEE_AMOUNT = 25.00

/**
 * Welle V1.4.21 -- Spiegel `ReceivableDunningService.validateLevelInput` (loser Spiegel, der Server
 * bleibt die Autorität).
 *
 * **Bewusst eigene Funktion, nie [validateDunningLevelInput] wiederverwendet** -- zwei Unterschiede
 * zum Beitrags-Mahnwesen:
 * - Es gibt hier **keine** § 286 BGB-Sperre für eine Gebühr auf Stufe 1 (Forderungen an Dritte sind
 *   kein Mitgliedsbeitrag, für den die erste Zahlungserinnerung den Verzug erst begründet).
 * - Der Name wird **nicht** abgeschnitten, sondern bei mehr als [MAX_RECEIVABLE_LEVEL_NAME_LENGTH]
 *   Zeichen abgelehnt (1..100).
 *
 * Die Stufennummer prüft der Server nur auf Eindeutigkeit (`ConflictException`); der Client
 * verlangt zusätzlich mindestens 1 -- eine Stufe 0 oder eine negative Stufe ergibt keine Reihenfolge.
 *
 * **Die Gebühr kommt seit Audit-Fund N2 aus [parseAmountInput]**, nicht mehr aus einem eigenen,
 * lockereren `replace(',', '.').toDoubleOrNull()` + `Validation.roundToTwoDecimalPlaces`-Pfad im
 * Bildschirm. Der alte Pfad akzeptierte "1e2" als 100 € und rundete "12,999" **still** auf 13,00 --
 * eine Gebühr, die niemand so eingegeben hat. Damit gilt für [feeAmount] jetzt: entweder `null`
 * (Feld leer, keine Gebühr) oder ein Betrag > 0 mit höchstens zwei Nachkommastellen. Die
 * Untergrenze 0 unten bleibt als treuer Spiegel der Server-Prüfung (`feeAmount must be 0..25`)
 * stehen, ist über den Bildschirm aber nicht mehr erreichbar.
 */
internal fun validateReceivableDunningLevelInput(
    levelNumber: Int?,
    name: String,
    graceDays: Int?,
    responseDays: Int?,
    feeAmount: Decimal?,
): String? {
    if (levelNumber == null || levelNumber < 1) return tr("Die Stufennummer muss mindestens 1 sein.")
    val trimmedName = name.trim()
    if (trimmedName.isEmpty() || trimmedName.length > MAX_RECEIVABLE_LEVEL_NAME_LENGTH) {
        return gettext("Der Name muss zwischen 1 und %1 Zeichen lang sein.", MAX_RECEIVABLE_LEVEL_NAME_LENGTH)
    }
    if (graceDays == null || graceDays !in MIN_RECEIVABLE_DUNNING_DAYS..MAX_RECEIVABLE_DUNNING_DAYS) {
        return gettext("Die Wartefrist muss zwischen %1 und %2 Tagen liegen.", MIN_RECEIVABLE_DUNNING_DAYS, MAX_RECEIVABLE_DUNNING_DAYS)
    }
    if (responseDays == null || responseDays !in MIN_RECEIVABLE_DUNNING_DAYS..MAX_RECEIVABLE_DUNNING_DAYS) {
        return gettext("Die Antwortfrist muss zwischen %1 und %2 Tagen liegen.", MIN_RECEIVABLE_DUNNING_DAYS, MAX_RECEIVABLE_DUNNING_DAYS)
    }
    if (feeAmount != null) {
        val fee = feeAmount.toDouble()
        if (fee < 0.0 || fee > MAX_RECEIVABLE_FEE_AMOUNT) return feeRangeMessage(MAX_RECEIVABLE_FEE_AMOUNT)
    }
    return null
}
