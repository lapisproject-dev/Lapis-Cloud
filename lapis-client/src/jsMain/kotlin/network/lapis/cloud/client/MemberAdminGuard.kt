package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.LastAdminException
import network.lapis.cloud.shared.rpc.MemberAlreadyHasAccountException
import network.lapis.cloud.shared.rpc.MemberEmailInUseException
import network.lapis.cloud.shared.rpc.MemberEmailTooLongException
import network.lapis.cloud.shared.rpc.MemberHasNoAccountException
import network.lapis.cloud.shared.rpc.MembershipTierClosedException
import network.lapis.cloud.shared.rpc.NoSecondAdminException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PeerApprovalRequiredException
import network.lapis.cloud.shared.rpc.PeerProtectionDeniedException
import network.lapis.cloud.shared.rpc.PrivilegedActionStateException
import network.lapis.cloud.shared.rpc.RegionalChapterInUseException
import network.lapis.cloud.shared.rpc.RegionalChapterLimitReachedException
import network.lapis.cloud.shared.rpc.RegionalChapterNameTakenException
import network.lapis.cloud.shared.rpc.RegionalChapterOfficerIneligibleException
import network.lapis.cloud.shared.rpc.RegionalChapterRequiredException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

/**
 * V1.2.12 Mitgliederverwaltung -- like [SepaGuard.sepaGuarded], but for a DIFFERENT reason than
 * SEPA's own "one write action, one fixed conflict message" shape. `AppState.guarded`'s own KDoc
 * documents, empirically verified, that Kilua RPC's polymorphic exception protocol never
 * transmits an `AbstractServiceException` subclass's own `message` across the wire -- only the
 * subclass discriminator itself. `updateMemberCoreData`/`updateMemberStatus`/`updateMemberRole`/
 * `grantMemberAccount` (Welle V1.2.13, fourth caller) each need to distinguish SEVERAL
 * structurally different conflict causes (email already used, no login account to change a role
 * on, last remaining admin, a member that already has a login account) -- a single fixed message
 * per call site (SEPA's shape) is not enough here, and parsing `e.message` client-side is not
 * POSSIBLE (always empty on the JS side, see the KDoc above). The only wire-visible signal is the
 * exception's TYPE. `network.lapis.cloud.shared.rpc.MemberEmailInUseException`/
 * [MemberHasNoAccountException]/[LastAdminException]/[MemberAlreadyHasAccountException] exist
 * specifically so this function can dispatch on TYPE, exactly the way [WeakPasswordException]/
 * [InvalidPasswordException] already do in `AppState.guarded` for password validation. A plain
 * [ConflictException] (blank name, transition not allowed, reason too short, anonymized member,
 * deceased target of a granted account) falls through to [guarded]'s own generic conflict toast --
 * there is nothing more specific to say about those causes anyway.
 *
 * Welle V1.9.14 also gained the [RegionalChapterRequiredException] branch: `updateMemberStatus`
 * throws it when the activation flag (`RegionalChapterEnforcementConfig`) is on and the target has
 * no chapter assigned yet -- purely additive, same generic toast [regionalChapterGuarded] uses for
 * this exception on its own call sites.
 */
suspend fun <T> memberAdminGuarded(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RegionalChapterRequiredException) {
        notifyError(tr("Bitte zuerst einen Landesverband zuordnen."))
        null
    } catch (e: Throwable) {
        handleMemberAdminFailure(e)
    }

/**
 * Welle V1.9.14 "Gliederungsverwaltung (Landesverbände), Oberfläche" -- the chapter-specific
 * counterpart to [memberAdminGuarded]: adds five [RegionalChapterInUseException]/
 * [RegionalChapterLimitReachedException]/[RegionalChapterNameTakenException]/
 * [RegionalChapterOfficerIneligibleException]/[RegionalChapterRequiredException] branches ahead of
 * every OTHER branch (plan §2.6 "diese Zweige kommen vor die Zweige von memberAdminGuarded"), then
 * delegates the rest to the SAME shared handler [memberAdminGuarded] itself uses
 * ([handleMemberAdminFailure]) so the two guards never drift. Review fix (NIT, KDoc correction):
 * all five extend [network.lapis.cloud.shared.rpc.AbstractServiceException] DIRECTLY (see
 * `ServiceExceptions.kt`) -- unlike [MemberEmailInUseException]/[MemberHasNoAccountException]/etc.
 * in [memberAdminGuarded]'s own KDoc, they are not narrower siblings of [ConflictException]/
 * [BadRequestException] that would otherwise "fall through" to those; branch order still matters
 * (a `when`/`catch` chain always tries the more specific type first), it just is not resolving an
 * inheritance relationship here.
 *
 * [onNameTaken] lets a caller with a live form (create/rename) show the conflict AT THE FIELD
 * instead of a toast -- when `null` (the default), a toast is shown. [onChapterRejected] is the
 * same idea for [RegionalChapterRequiredException] ONLY. Review fix (NIT, KDoc correction): this
 * function is never handed a [BadRequestException] to inspect for [onChapterRejected] -- Kilua
 * RPC's polymorphic exception protocol never transmits enough to tell a "chapter no longer exists"
 * `BadRequestException` apart from any other one (see [memberAdminGuarded] KDoc). A caller with its
 * own chapter-select field that wants the SAME field-error treatment for that case
 * (`RegistrationScreen.kt`/`renderDirectMemberCreation` in `MemberAdministrationScreen.kt`) instead
 * catches `BadRequestException` ITSELF, ahead of this guard, and only falls through to
 * `regionalChapterGuarded { throw e }` when its own chapter field was not the one submitted (blank/
 * absent) -- see either call site's own KDoc/comment for the exact condition.
 */
suspend fun <T> regionalChapterGuarded(
    onNameTaken: (() -> Unit)? = null,
    onChapterRejected: (() -> Unit)? = null,
    block: suspend () -> T,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RegionalChapterNameTakenException) {
        if (onNameTaken != null) {
            onNameTaken()
        } else {
            notifyError(tr("Ein Landesverband mit diesem Namen existiert bereits."))
        }
        null
    } catch (e: RegionalChapterInUseException) {
        notifyError(tr("Landesverband wird noch verwendet -- bitte Ansicht aktualisieren."))
        null
    } catch (e: RegionalChapterLimitReachedException) {
        notifyError(tr("Die Höchstzahl ist erreicht."))
        null
    } catch (e: RegionalChapterOfficerIneligibleException) {
        notifyError(tr("Dieses Mitglied ist nicht als Landesvorstand geeignet (Status muss aktiv sein)."))
        null
    } catch (e: RegionalChapterRequiredException) {
        if (onChapterRejected != null) {
            onChapterRejected()
        } else {
            notifyError(tr("Bitte zuerst einen Landesverband zuordnen."))
        }
        null
    } catch (e: Throwable) {
        handleMemberAdminFailure(e)
    }

/**
 * Shared branch list [memberAdminGuarded] and [regionalChapterGuarded] both fall through to, kept
 * as ONE private function (plan §2.6 "eine gemeinsame private Funktion ... die beide Guards
 * benutzen") so the two guards' generic error handling can never silently drift apart. Branch
 * ORDER matters and is preserved from the pre-V1.9.14 [memberAdminGuarded]: the specific
 * `@RpcServiceException` subclasses first, the generic [Throwable] fallback last.
 */
private suspend fun <T> handleMemberAdminFailure(e: Throwable): T? =
    when (e) {
        is UnauthenticatedException -> guarded<T> { throw e }
        is ForbiddenException -> {
            notifyError(tr("Keine Berechtigung für diese Aktion."))
            null
        }
        is NotFoundException -> {
            notifyError(tr("Nicht gefunden."))
            null
        }
        is MemberEmailInUseException -> {
            notifyError(tr("Diese E-Mail-Adresse wird bereits von einem anderen Mitglied verwendet."))
            null
        }
        is MemberEmailTooLongException -> {
            // Review Runde 3 -- NIT fix: without this dedicated catch, this fell through to the
            // generic ConflictException toast below ("bitte Ansicht aktualisieren"), which is actively
            // wrong advice for a length problem (see MemberEmailTooLongException's own KDoc).
            notifyError(gettext("Diese E-Mail-Adresse ist zu lang (höchstens %1 Zeichen).", Validation.EMAIL_MAX_LENGTH))
            null
        }
        is MemberHasNoAccountException -> {
            // Welle V1.4.9 "Admin-Passwort-Reset" widened this message from the updateMemberRole-
            // specific "keine Rolle zu ändern" wording to a neutral sentence -- setTemporaryPasswordForMember
            // / sendPasswordResetMailToMember throw the SAME exception type and show the SAME toast.
            notifyError(tr("Dieses Mitglied hat kein Login-Konto."))
            null
        }
        is MemberAlreadyHasAccountException -> {
            notifyError(tr("Dieses Mitglied hat bereits ein Login-Konto -- bitte Ansicht aktualisieren."))
            null
        }
        is MembershipTierClosedException -> {
            // Welle V1.9.18: `updateMemberMembershipTier` (roster dialog) refuses a CLOSED tier -- see
            // MembershipTierAssignment.apply. Only the type reaches the client, so the sentence is fixed here.
            notifyError(tr("Diese Mitgliedschaftsstufe ist geschlossen und kann nicht mehr zugewiesen werden."))
            null
        }
        is LastAdminException -> {
            notifyError(tr("Der letzte verbleibende Administrator kann nicht entfernt werden."))
            null
        }
        // Welle V1.9.57 "Admin-Peer-Schutz" -- typed, fixed toasts (Kilua RPC never transmits the server's own message). The precise
        // reason stands as text in the dialogs (`getPeerActionDecisions`); a stale dialog lands here.
        is PeerProtectionDeniedException -> {
            notifyError(tr("Geschützt: Diese Aktion ist gegen ein Administratorkonto nicht möglich."))
            null
        }
        is PeerApprovalRequiredException -> {
            notifyError(
                tr(
                    "Für ein Administratorkonto ist die Zustimmung eines zweiten Administrators erforderlich -- " +
                        "bitte Ansicht aktualisieren und die Freigabe beantragen.",
                ),
            )
            null
        }
        is NoSecondAdminException -> {
            notifyError(tr("Nicht möglich: Es gibt keinen weiteren Administrator für die Freigabe."))
            null
        }
        is PrivilegedActionStateException -> {
            notifyError(tr("Dieser Antrag ist nicht mehr offen -- bitte Ansicht aktualisieren."))
            null
        }
        is ConflictException -> {
            notifyError(tr("Die Aktion steht im Konflikt mit dem aktuellen Zustand -- bitte Ansicht aktualisieren."))
            null
        }
        is BadRequestException -> {
            notifyError(tr("Ungültige Anfrage."))
            null
        }
        else -> guarded<T> { throw e }
    }
