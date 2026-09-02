package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import network.lapis.cloud.shared.rpc.WebhookUrlMalformedException
import network.lapis.cloud.shared.rpc.WebhookUrlNotHttpsException
import network.lapis.cloud.shared.rpc.WebhookUrlNotPubliclyRoutableException
import network.lapis.cloud.shared.rpc.WebhookUrlTooLongException

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- like [memberAdminGuarded], dispatches on the FOUR
 * distinct [WebhookUrlNotHttpsException]/[WebhookUrlMalformedException]/
 * [WebhookUrlNotPubliclyRoutableException]/[WebhookUrlTooLongException] types (Kilua RPC's
 * polymorphic exception protocol never transmits `message` across the wire, only the subclass
 * discriminator -- see [memberAdminGuarded] KDoc for the full empirically-verified reasoning).
 * Design-Team decision D6, verbatim -- exactly these four German sentences, no IP address, no
 * hostname, no DNS-resolution detail.
 *
 * **Review fix -- `conflictMessage` REQUIRED, exact `DunningGuard.dunningGuarded` grammar**: a bare
 * [ConflictException] here can mean several structurally different server-side causes
 * (`WebhookService.requireEnabled`'s "Webhooks are disabled on this server", a per-method rate
 * limit, `requireSecretBox`'s "Webhooks are not configured on this server", a revoked API key, an
 * already-active/-inactive endpoint, ...) -- Kilua RPC never transmits `message` across the wire
 * (same reasoning as the four URL-rejection types above), so the generic "Ansicht aktualisieren"
 * toast this used to show named an action that never actually fixes any of these causes and never
 * told the operator WHY. Every call site now passes its own honest, enumerated explanation (see the
 * `WEBHOOK_*_CONFLICT_MESSAGE` constants below) instead of one misleading catch-all sentence.
 */
suspend fun <T> webhookGuarded(
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
    } catch (e: WebhookUrlNotHttpsException) {
        notifyError(tr("Die Adresse muss mit https:// beginnen."))
        null
    } catch (e: WebhookUrlMalformedException) {
        notifyError(tr("Die Adresse ist keine gültige URL."))
        null
    } catch (e: WebhookUrlNotPubliclyRoutableException) {
        notifyError(tr("Diese Adresse ist als Ziel nicht zulässig -- sie muss öffentlich im Internet erreichbar sein."))
        null
    } catch (e: WebhookUrlTooLongException) {
        notifyError(tr("Die Adresse ist zu lang (maximal 2048 Zeichen)."))
        null
    } catch (e: ConflictException) {
        notifyError(conflictMessage)
        null
    } catch (e: Throwable) {
        guarded<T> { throw e }
    }

/**
 * Fallback conflict message for [network.lapis.cloud.shared.rpc.IWebhookService.listWebhookEndpoints]
 * -- that method has no `requireEnabled`/rate-limit gate, so a [ConflictException] here is not
 * expected in practice; kept generic on purpose.
 */
internal const val WEBHOOK_LIST_CONFLICT_MESSAGE = "Webhook-Daten konnten nicht geladen werden."

/**
 * Fallback conflict message for [network.lapis.cloud.shared.rpc.IWebhookService.listWebhookDeliveries]
 * -- only gated by its own rate limiter (`deliveryLogRateLimiter`), no `requireEnabled` (a read must
 * keep working regardless of the feature flag, see `WebhookConfig` KDoc).
 */
internal const val WEBHOOK_DELIVERIES_CONFLICT_MESSAGE =
    "Zustellungsprotokoll konnte nicht geladen werden -- vermutlich wurden zu viele Anfragen in kurzer Zeit gestellt."

/**
 * `setWebhookUrl`'s own conflict causes (`WebhookService.kt`, create AND update path share this
 * message): the server-wide flag is off, the configure-rate-limit was exceeded, the API key this
 * webhook would belong to was revoked (review fix, see `WebhookService.requireApiKeyExists`), or the
 * endpoint was removed by someone else between two concurrent update calls.
 */
internal const val WEBHOOK_SET_URL_CONFLICT_MESSAGE =
    "Die Webhook-Adresse konnte nicht gespeichert werden -- mögliche Gründe: Webhooks sind auf diesem Server " +
        "deaktiviert, der zugehörige API-Schlüssel wurde widerrufen, es wurden zu viele Anfragen in kurzer Zeit " +
        "gestellt, oder der Webhook wurde zwischenzeitlich von anderer Stelle entfernt."

/**
 * `rotateWebhookSecret`'s own conflict causes: the server-wide flag is off, the encryption key is
 * not configured (`requireSecretBox`), or the rotate-rate-limit was exceeded.
 */
internal const val WEBHOOK_ROTATE_SECRET_CONFLICT_MESSAGE =
    "Das Signaturgeheimnis konnte nicht neu erzeugt werden -- mögliche Gründe: Webhooks sind auf diesem Server " +
        "deaktiviert oder nicht konfiguriert, oder es wurden zu viele Anfragen in kurzer Zeit gestellt."

/**
 * `reactivateWebhookEndpoint`'s own conflict causes: the server-wide flag is off, the
 * configure-rate-limit was exceeded, or the underlying API key was revoked (review fix).
 */
internal const val WEBHOOK_REACTIVATE_CONFLICT_MESSAGE =
    "Der Webhook konnte nicht wieder aktiviert werden -- mögliche Gründe: Webhooks sind auf diesem Server " +
        "deaktiviert, der zugehörige API-Schlüssel wurde widerrufen, oder es wurden zu viele Anfragen in kurzer " +
        "Zeit gestellt."

/**
 * `sendWebhookTestEvent`'s own conflict causes: the server-wide flag is off, the encryption key is
 * not configured, the endpoint is currently deactivated, or the test-rate-limit was exceeded.
 */
internal const val WEBHOOK_TEST_EVENT_CONFLICT_MESSAGE =
    "Das Test-Event konnte nicht gesendet werden -- mögliche Gründe: Webhooks sind auf diesem Server deaktiviert " +
        "oder nicht konfiguriert, der Webhook ist derzeit deaktiviert, oder es wurden zu viele Anfragen in kurzer " +
        "Zeit gestellt."

/**
 * `removeWebhookUrl`'s own conflict cause -- deliberately NOT `requireEnabled`-gated (see
 * `WebhookService.removeWebhookUrl` KDoc), so only its own rate limiter can produce a
 * [ConflictException] here.
 */
internal const val WEBHOOK_REMOVE_CONFLICT_MESSAGE =
    "Der Webhook konnte nicht entfernt werden -- es wurden zu viele Anfragen in kurzer Zeit gestellt."
