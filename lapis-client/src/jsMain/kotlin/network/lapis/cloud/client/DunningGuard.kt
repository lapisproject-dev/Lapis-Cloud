package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

// Client-UI wave for GitHub Issue #5 -- exact `SepaGuard.kt` grammar. Kilua RPC never transmits an
// exception's own message (`AppState.guarded` KDoc, empirically verified), and several structurally
// different server-side causes at each Dunning write call site all surface identically as a bare
// [ConflictException]. Every Dunning-specific call site in this client goes through one of these two
// functions instead of the generic `AppState.guarded`, so a treasurer sees an honest,
// dunning-specific explanation instead of the generic "im Konflikt" toast for several different
// underlying reasons.

/**
 * Stille Probe -- exakt das `sepaProbe`-Muster: lässt [CancellationException] durch, schluckt
 * jeden anderen [Throwable] zu `null`, ohne Toast und ohne Konsolenausgabe. Für die ADMIN-only
 * Warnbänder in [renderDunningCasesScreen]/[renderDunningSettingsScreen] (`getDunningSettings`/
 * `getDunningComplianceDisclaimer` sind beide ADMIN-only, plan finding B2): ein
 * [ConflictException]/[ForbiddenException] hier bedeutet nur "diese Rolle darf das nicht sehen"
 * oder "kein Gate-Zustand abrufbar" -- kein Fehler, den ein TREASURER/BOARD bei jedem Seitenaufruf
 * gemeldet bekommen muss.
 */
suspend fun <T> dunningProbe(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

/**
 * Wie `AppState.guarded()`, aber [ConflictException] bekommt [conflictMessage] statt des
 * generischen "im Konflikt"-Texts -- für jeden Dunning-SCHREIB-Aufruf (issueDunningNotice/
 * skipDunningLevel/resetDunning/cancelDunningNotice/createDunningLevel/updateDunningLevel/
 * deactivateDunningLevel/enableDunning/disableDunning), NIE für eine Leseoperation (die nutzt
 * [dunningProbe] oder, wenn die Route selbst schon rollen-gegattert ist, das gewöhnliche
 * `guarded { }`). Jede andere Ausnahmeart (Unauthenticated/Forbidden/NotFound/BadRequest/...)
 * verhält sich exakt wie `AppState.guarded()`.
 */
suspend fun <T> dunningGuarded(
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

/** Shared gate-reason clause reused by every specific conflict message below -- mirrors
 * `requireDunningUsable`'s causes: the feature is not activated, the disclaimer has not been
 * (re-)acknowledged, or no active dunning level is configured. */
private const val DUNNING_GATE_CONFLICT_HINT =
    "das Mahnwesen ist nicht aktiviert, der aktuelle Rechtshinweis wurde nicht erneut bestätigt, oder es ist keine " +
        "aktive Mahnstufe konfiguriert"

/** Fallback write-conflict message for `enableDunning`/`disableDunning` -- their own conflict
 * causes (a mismatched disclaimer version/hash) are narrow enough that the shared gate hint plus a
 * short lead-in already covers them accurately. */
internal const val DUNNING_WRITE_CONFLICT_MESSAGE =
    "Mahnwesen-Aktion war nicht erfolgreich (Konflikt) -- mögliche Gründe: der übermittelte Hinweistext " +
        "entspricht nicht mehr der aktuellen Version (erneut abrufen), oder es wurden zu viele Anfragen in kurzer " +
        "Zeit gestellt. Ein Administrator prüft das ggf. unter Mahnwesen-Konfiguration."

/**
 * `issueDunningNotice`'s own conflict causes (`DunningService.kt:481-494`) plus the rate-limit
 * case (10/min pro Mitglied, `requireWithinRate`, `DunningService.kt:456`) -- this is the only
 * text a treasurer ever sees when "Mahnung ausstellen" fails, so every structurally distinct
 * cause is named in one sentence rather than left to a generic disjunction. Also used by
 * `resetDunning`/`cancelDunningNotice`, which share the SAME per-member rate-limit budget
 * (`DunningService.kt:628`/`:757`) and the same DUNNABLE-status/gate causes, even though their own
 * specific slot/level causes differ slightly -- the enumeration below stays accurate as an
 * open-ended "mögliche Gründe" list for all three.
 *
 * Deliberately does NOT name [DunningIssueOutcome.NotDue] ("die nächste Mahnstufe ist noch nicht
 * fällig") -- `issueDunningNotice` always calls `issueDunningNoticeInternal` with
 * `respectSchedule = false` (`DunningService.kt:468`), so that outcome is structurally unreachable
 * on this call path. Naming it here would be actively misleading on the one flow where it would
 * otherwise surface: a treasurer who explicitly clicked "Vorzeitig ausstellen" and confirmed the
 * early-issuance dialog, then hits a genuinely different conflict, must not read a toast claiming
 * the exact thing they just knowingly overrode.
 */
internal const val DUNNING_ISSUE_CONFLICT_MESSAGE =
    "Die Aktion war nicht erfolgreich (Konflikt) -- mögliche Gründe: der Beitrag befindet sich nicht mehr im " +
        "mahnfähigen Zustand, es ist keine weitere Mahnstufe im laufenden Zyklus verfügbar, diese Mahnstufe wurde " +
        "soeben bereits ausgestellt, der Mahnzyklus hat sich zwischenzeitlich geändert (z. B. durch eine " +
        "gleichzeitige Stornierung/Zurücksetzung), es wurden zu viele Anfragen in kurzer Zeit gestellt, oder " +
        DUNNING_GATE_CONFLICT_HINT + "."

/**
 * `skipDunningLevel`'s own conflict causes (`DunningService.kt:517-551`) -- deliberately does NOT
 * mention a rate limit: unlike issue/reset/cancel, `skipDunningLevel` has no `requireWithinRate`
 * call at all (plan finding N3). Mentioning a rate limit here would blur that real distinction and
 * mislead a treasurer hitting one of the OTHER causes into retrying later for no reason.
 */
internal const val DUNNING_SKIP_CONFLICT_MESSAGE =
    "Die Mahnstufe konnte nicht übersprungen werden -- mögliche Gründe: der Beitrag befindet sich nicht mehr im " +
        "mahnfähigen Zustand, es ist keine weitere Mahnstufe im laufenden Zyklus verfügbar, diese Mahnstufe wurde " +
        "soeben bereits ausgestellt, oder " + DUNNING_GATE_CONFLICT_HINT + "."

/** `createDunningLevel`/`updateDunningLevel`'s own conflict causes (`DunningService.kt:869-887`
 * `validateLevelInput`, plus the level-number-already-exists duplicate check) -- the client-side
 * form pre-validation mirrors every one of these bounds already (see
 * `renderDunningSettingsScreen`'s `validateDunningLevelInput`), so this message is the fallback for
 * a bound this client failed to mirror correctly or a genuine concurrent duplicate. */
internal const val DUNNING_LEVEL_CONFLICT_MESSAGE =
    "Die Mahnstufe konnte nicht gespeichert werden -- mögliche Gründe: die Stufennummer existiert bereits, ein " +
        "Wert liegt außerhalb des zulässigen Bereichs, oder auf der ersten Mahnstufe wurde eine Gebühr angegeben " +
        "(unzulässig, § 286 BGB)."

/** Fallback read-conflict message for the ADMIN-only probes (`getDunningSettings`/
 * `listDunningLevels`/`getDunningComplianceDisclaimer`) when they are called through
 * [dunningGuarded] rather than the silent [dunningProbe] (i.e. on an explicit user-triggered
 * reload, where a toast is actually warranted). */
internal const val DUNNING_READ_CONFLICT_MESSAGE = "Mahnwesen-Daten konnten nicht geladen werden."
