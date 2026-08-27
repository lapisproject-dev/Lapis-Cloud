package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.LastAdminException
import network.lapis.cloud.shared.rpc.MemberEmailInUseException
import network.lapis.cloud.shared.rpc.MemberEmailTooLongException
import network.lapis.cloud.shared.rpc.MemberHasNoAccountException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

/**
 * V1.2.12 Mitgliederverwaltung -- like [SepaGuard.sepaGuarded], but for a DIFFERENT reason than
 * SEPA's own "one write action, one fixed conflict message" shape. `AppState.guarded`'s own KDoc
 * documents, empirically verified, that Kilua RPC's polymorphic exception protocol never
 * transmits an `AbstractServiceException` subclass's own `message` across the wire -- only the
 * subclass discriminator itself. `updateMemberCoreData`/`updateMemberStatus`/`updateMemberRole`
 * each need to distinguish SEVERAL structurally different conflict causes (email already used,
 * no login account to change a role on, last remaining admin) -- a single fixed message per call
 * site (SEPA's shape) is not enough here, and parsing `e.message` client-side is not POSSIBLE
 * (always empty on the JS side, see the KDoc above). The only wire-visible signal is the
 * exception's TYPE. `network.lapis.cloud.shared.rpc.MemberEmailInUseException`/
 * [MemberHasNoAccountException]/[LastAdminException] exist specifically so this function can
 * dispatch on TYPE, exactly the way [WeakPasswordException]/[InvalidPasswordException] already do
 * in `AppState.guarded` for password validation. A plain [ConflictException] (blank name,
 * transition not allowed, reason too short, anonymized member) falls through to [guarded]'s own
 * generic conflict toast -- there is nothing more specific to say about those causes anyway.
 */
suspend fun <T> memberAdminGuarded(block: suspend () -> T): T? =
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
    } catch (e: MemberEmailInUseException) {
        notifyError(tr("Diese E-Mail-Adresse wird bereits von einem anderen Mitglied verwendet."))
        null
    } catch (e: MemberEmailTooLongException) {
        // Review Runde 3 -- NIT fix: without this dedicated catch, this fell through to the
        // generic ConflictException toast below ("bitte Ansicht aktualisieren"), which is actively
        // wrong advice for a length problem (see MemberEmailTooLongException's own KDoc).
        notifyError(gettext("Diese E-Mail-Adresse ist zu lang (höchstens %1 Zeichen).", Validation.EMAIL_MAX_LENGTH))
        null
    } catch (e: MemberHasNoAccountException) {
        notifyError(tr("Dieses Mitglied hat kein Login-Konto -- es gibt keine Rolle zu ändern."))
        null
    } catch (e: LastAdminException) {
        notifyError(tr("Der letzte verbleibende Administrator kann nicht entfernt werden."))
        null
    } catch (e: ConflictException) {
        notifyError(tr("Die Aktion steht im Konflikt mit dem aktuellen Zustand -- bitte Ansicht aktualisieren."))
        null
    } catch (e: BadRequestException) {
        notifyError(tr("Ungültige Anfrage."))
        null
    } catch (e: Throwable) {
        guarded<T> { throw e }
    }
