package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
import network.lapis.cloud.shared.domain.NettingCandidateDto

/**
 * Welle V1.4.21 -- Verrechnungs-Dialog. Atkinson-Ruling: "Verrechnen" ist genau dann aktiv, wenn
 * die **angezeigte** Vorschau zu genau diesem Tripel (Kreditor-Posten, Debitor-Posten, Betrag)
 * gehört. Kein Zeitstempel, kein "ist geladen"-Flag -- ein geänderter Betrag oder ein anderes Paar
 * macht die Freigabe sofort ungültig, bis eine neue Vorschau eingetroffen ist.
 */
data class NettingPreviewToken(
    val payableItemId: String,
    val receivableItemId: String,
    val amount: Decimal,
)

fun nettingPreviewToken(
    candidate: NettingCandidateDto,
    amount: Decimal,
): NettingPreviewToken = NettingPreviewToken(candidate.payable.id, candidate.receivable.id, amount)

/**
 * Betragsvergleich über [Decimal.toDouble]: [Decimal] ist auf JS ein Wrapper ohne verlässliches
 * strukturelles `equals` -- die Werte stammen aus [parseAmountInput] (<= 2 Nachkommastellen),
 * ein Double-Vergleich ist dafür exakt genug.
 */
fun canExecuteNetting(
    current: NettingPreviewToken?,
    lastPreviewed: NettingPreviewToken?,
): Boolean =
    current != null &&
        lastPreviewed != null &&
        current.payableItemId == lastPreviewed.payableItemId &&
        current.receivableItemId == lastPreviewed.receivableItemId &&
        current.amount.toDouble() == lastPreviewed.amount.toDouble()

/** Entprellung der Vorschau beim Tippen -- kein RPC pro Tastendruck. */
internal const val NETTING_PREVIEW_DEBOUNCE_MS = 400
