package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.rpc.OpenItemAmountExceedsOpenAmountException
import network.lapis.cloud.shared.rpc.OpenItemNotBookedException
import network.lapis.cloud.shared.rpc.PaymentAccountNotPaymentCapableException
import network.lapis.cloud.shared.rpc.PaymentBankAccountNotConfiguredException

/**
 * Welle V1.4.22 "Zahlungskonto im Offene-Posten-Pfad" -- exactly [memberAdminGuarded]'s shape, for
 * exactly [memberAdminGuarded]'s reason: `AppState.guarded`'s own KDoc documents (empirically
 * verified) that Kilua RPC never transmits an `AbstractServiceException`'s own `message` across the
 * wire, only the subclass discriminator. The settlement path has FOUR structurally different,
 * actionable causes, and before this wave they all arrived as a bare `ConflictException`/
 * `BadRequestException` and were shown as one generic toast:
 *
 * > Die Aktion steht im Konflikt mit dem aktuellen Zustand -- bitte Ansicht aktualisieren.
 *
 * Found live on staging: the real cause was "no default bank account configured for the
 * organization", where refreshing the view helps not at all. The four server-side types
 * ([PaymentBankAccountNotConfiguredException]/[OpenItemNotBookedException]/
 * [OpenItemAmountExceedsOpenAmountException]/[PaymentAccountNotPaymentCapableException]) exist so
 * this function can dispatch on TYPE. `OpenItemRpcWireTest` pins that they really do cross the wire.
 *
 * **`AppState.handleGuardedFailure` is deliberately NOT touched** -- its generic conflict toast is
 * right for the ~200 other call sites and stays the fallback here too: every other exception (and a
 * plain `ConflictException`, e.g. "item is CANCELLED, cannot be settled") is re-thrown into
 * [guarded], so session expiry, the `ClientVersionWatcher` hook and every existing toast keep
 * working unchanged. Used by the settlement dialog (`openItemSettlementDialog`) and by
 * `retrySettlementPosting`; every other open-item write keeps plain [guarded], because none of them
 * can throw one of these types.
 *
 * [ClientVersionWatcher.notifyRpcFailure] is called on the typed branches too (audit follow-up,
 * MINOR-b): the V1.4.20 intent is "every failed RPC lifts an active update snooze", and a stale
 * client hitting a newer server contract is exactly when that hint matters -- the branch that
 * handled the failure must not change that. The `guarded` fallback below does it for everything else,
 * so each failure still reports exactly once.
 */
suspend fun <T> openItemGuarded(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PaymentBankAccountNotConfiguredException) {
        openItemFailure(
            tr(
                "Für die Organisation ist kein Standard-Bankkonto hinterlegt. Bitte ein Zahlungskonto wählen " +
                    "oder im Kontenplan eines zuordnen.",
            ),
        )
    } catch (e: PaymentAccountNotPaymentCapableException) {
        openItemFailure(
            tr(
                "Das gewählte Konto ist kein Zahlungskonto (Bank oder Kasse) oder nicht mehr aktiv. " +
                    "Bitte ein anderes Konto wählen und die Ansicht aktualisieren.",
            ),
        )
    } catch (e: OpenItemNotBookedException) {
        openItemFailure(tr("Dieser Posten ist noch nicht gebucht — bitte zuerst nachbuchen, dann ausgleichen."))
    } catch (e: OpenItemAmountExceedsOpenAmountException) {
        openItemFailure(tr("Der Betrag übersteigt den offenen Betrag dieses Postens — bitte die Ansicht aktualisieren."))
    } catch (e: Throwable) {
        guarded<T> { throw e }
    }

/** Toast + the V1.4.20 snooze-lift hook, then `null` -- the shape every typed branch above shares. */
private fun <T> openItemFailure(message: String): T? {
    notifyError(message)
    ClientVersionWatcher.notifyRpcFailure()
    return null
}
