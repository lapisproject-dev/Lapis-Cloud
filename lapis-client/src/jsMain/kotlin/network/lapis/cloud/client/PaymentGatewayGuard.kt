package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

// Welle V1.2.8 "PSP-Checkout (Stripe)" (GitHub Issue #6) -- exact mirror of SepaGuard.kt's own two
// functions/rationale. Kilua RPC never transmits an exception's own message (verified empirically,
// see AppState.guarded KDoc), and FIVE structurally different server-side causes -- gate disabled,
// stale disclaimer acknowledgment, PSP secrets not configured, configured provider != STRIPE, and
// (for createDonationCheckout) a PROHIBITED §25 PartG verdict -- all surface identically as a bare
// ConflictException. Every payment-gateway-specific call site in this client goes through one of
// these two functions instead of the generic AppState.guarded, so a member/treasurer sees an
// honest, call-site-specific German explanation instead of one generic "im Konflikt" toast for five
// different reasons.

/** Stille Probe -- exaktes Muster [sepaProbe]s: für `getPaymentGatewayAvailability`, das häufigste Lese-Aufkommen dieser Welle (jede Beitragsseite jedes Mitglieds). */
suspend fun <T> pspProbe(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

/** Wie `AppState.guarded()`, aber [ConflictException] bekommt [conflictMessage] statt des generischen "im Konflikt"-Texts. */
suspend fun <T> pspGuarded(
    conflictMessage: String,
    block: suspend () -> T,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnauthenticatedException) {
        guarded<T> { throw e }
    } catch (e: ForbiddenException) {
        notifyError(tr("Keine Berechtigung für diese Aktion."))
        null
    } catch (e: NotFoundException) {
        notifyError(tr("Nicht gefunden."))
        null
    } catch (e: ConflictException) {
        notifyError(conflictMessage)
        null
    } catch (e: BadRequestException) {
        notifyError(tr("Ungültige Anfrage."))
        null
    } catch (e: Throwable) {
        guarded<T> { throw e }
    }
