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
 */
suspend fun <T> webhookGuarded(block: suspend () -> T): T? =
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
        notifyError(tr("Die Aktion steht im Konflikt mit dem aktuellen Zustand -- bitte Ansicht aktualisieren."))
        null
    } catch (e: Throwable) {
        guarded<T> { throw e }
    }
