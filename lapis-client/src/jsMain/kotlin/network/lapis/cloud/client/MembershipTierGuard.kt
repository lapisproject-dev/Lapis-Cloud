package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.rpc.MembershipTierIntervalLockedException
import network.lapis.cloud.shared.rpc.MembershipTierNameTakenException

/**
 * Welle V1.9.18 "Verwaltung der Mitgliedschaftsstufen" -- the tier-specific counterpart to
 * [regionalChapterGuarded]: two typed exceptions ahead of the generic [guarded] handling. Kilua RPC
 * transmits only the exception TYPE (see [guarded]), so the client can tell "name taken" from "interval
 * locked" from "tier closed" only because each is its own class (see `ServiceExceptions.kt`).
 *
 * - [MembershipTierNameTakenException]: [onNameTaken] lets a caller with a live form show the conflict AT
 *   THE FIELD (the client pre-checks against the loaded list, so reaching this means a concurrent create);
 *   without one a toast is shown.
 * - [MembershipTierIntervalLockedException]: only reachable when the overview the dialog was built from is
 *   stale (a member was assigned in the meantime) -- the toast says what to do instead.
 * - A closed tier ([network.lapis.cloud.shared.rpc.MembershipTierClosedException]) can only be refused on the
 *   tier ASSIGNMENT path (member roster): that is answered in `handleMemberAdminFailure`, not here.
 *
 * Anything else falls through to [guarded]'s own handling (session expiry, forbidden, not found, ...).
 */
suspend fun <T> membershipTierGuarded(
    onNameTaken: (() -> Unit)? = null,
    block: suspend () -> T,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: MembershipTierNameTakenException) {
        if (onNameTaken != null) {
            onNameTaken()
        } else {
            notifyError(tr("Eine Mitgliedschaftsstufe mit diesem Namen existiert bereits."))
        }
        null
    } catch (e: MembershipTierIntervalLockedException) {
        notifyError(
            tr(
                "Das Intervall kann nicht geändert werden, solange aktive Mitglieder zugeordnet sind. " +
                    "Legen Sie stattdessen eine neue Stufe an.",
            ),
        )
        null
    } catch (e: Throwable) {
        guarded<T> { throw e }
    }
