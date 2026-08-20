package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

// V1.2.2 SEPA-Client-UI wave -- see the approved plan §1/§2.4/Stolperfalle S-1/S-2. Kilua RPC never
// transmits an exception's own message (`AppState.guarded` KDoc, empirically verified), and THREE
// structurally different server-side causes -- `sepaDebitEnabled == false`, an out-of-date
// disclaimer acknowledgment, and a missing `LAPIS_SECRET_ENCRYPTION_KEY` -- all surface identically
// as a bare [ConflictException]. Every SEPA-specific call site in this client goes through one of
// these two functions instead of the generic `AppState.guarded`, so a member/treasurer sees an
// honest, SEPA-specific explanation instead of the generic "im Konflikt" toast four different times
// for four different reasons.

/**
 * Stille Probe: exakt das Boot-Session-Probe-Muster aus `App.kt` (`AppScope.launch` dort, Z.
 * 155–162) -- lässt [CancellationException] durch, schluckt jeden anderen [Throwable] zu `null`,
 * ohne Toast und ohne Konsolenausgabe. Für die häufigste SEPA-Leseoperation dieser Welle
 * (`getMyMandate`/`listMyPrenotifications` auf der Beitragsseite jedes Mitglieds, S-1): ein
 * [ConflictException] hier bedeutet lediglich "SEPA ist für diese Organisation nicht aktiviert" --
 * ein alltäglicher, erwarteter Zustand für die meisten Organisationen, kein Fehler, über den ein
 * Mitglied bei jedem Seitenaufruf informiert werden muss.
 */
suspend fun <T> sepaProbe(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

/**
 * Wie `AppState.guarded()`, aber [ConflictException] bekommt [conflictMessage] statt des
 * generischen "im Konflikt"-Texts -- für jeden SEPA-SCHREIB-Aufruf (grantMandate/previewDebitBatch/
 * createDebitBatch/notifyBatch/generateBatchFile/markBatchSubmitted/cancelBatch/settleBatch/
 * recordReturn), NIE für eine Leseoperation (die nutzt [sepaProbe] oder, wenn die Route selbst schon
 * rollen-gegattert ist, das gewöhnliche `guarded { }`). Jede andere Ausnahmeart (Unauthenticated/
 * Forbidden/NotFound/BadRequest/...) verhält sich exakt wie `AppState.guarded()`.
 *
 * **`revokeMandate` bewusst NICHT in dieser Aufzählung** (Review Round 2, 2026-08-20, MINOR -- war
 * vorher fälschlich mitgelistet, obwohl beide tatsächlichen Aufrufstellen, `SepaMandateSection.kt`
 * und `SepaMandatesScreen.kt`, das gewöhnliche `guarded { }` verwenden): `SepaService.revokeMandate`
 * hat als einzige SEPA-Schreibaktion **kein** `requireSepaUsable()`-Gate (Plan §1 Matrix, siehe auch
 * [SepaAuthzUi.canRevokeMandateOf] KDoc) -- ein `ConflictException` dort bedeutet ausschließlich
 * "Mandat ist nicht mehr ACTIVE", wofür die generische `guarded()`-Meldung ("steht im Konflikt mit
 * dem aktuellen Zustand -- bitte Ansicht aktualisieren") bereits zutreffend ist; keine der drei
 * SEPA-spezifischen Gate-Ursachen würde hier je zutreffen.
 */
suspend fun <T> sepaGuarded(
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
