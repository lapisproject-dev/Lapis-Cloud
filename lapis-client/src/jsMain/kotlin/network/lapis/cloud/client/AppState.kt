package network.lapis.cloud.client

import dev.kilua.rpc.getService
import io.kvision.i18n.tr
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceEventCaptureState
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ActivismInvalidTransitionException
import network.lapis.cloud.shared.rpc.ActivismLimitReachedException
import network.lapis.cloud.shared.rpc.ActivismNotRunningException
import network.lapis.cloud.shared.rpc.ActivismReportOpenException
import network.lapis.cloud.shared.rpc.ActivismResponsibleLimitException
import network.lapis.cloud.shared.rpc.ActivismRevisionConflictException
import network.lapis.cloud.shared.rpc.ActivismSelfApprovalException
import network.lapis.cloud.shared.rpc.AgreementTextDraftChangedException
import network.lapis.cloud.shared.rpc.AgreementTextInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextLayoutInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextOutdatedException
import network.lapis.cloud.shared.rpc.AgreementTextVersionLabelTakenException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConferenceRecordingAlreadyActiveException
import network.lapis.cloud.shared.rpc.ConferenceRoomEndedException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.DirectMessageGroupTooLargeException
import network.lapis.cloud.shared.rpc.DirectMessageGroupUnavailableException
import network.lapis.cloud.shared.rpc.DirectMessageRateLimitedException
import network.lapis.cloud.shared.rpc.DirectMessageRecipientUnavailableException
import network.lapis.cloud.shared.rpc.EmailChangeAlreadyCurrentException
import network.lapis.cloud.shared.rpc.EmailChangeMailUnavailableException
import network.lapis.cloud.shared.rpc.EmailChangeNotAllowedException
import network.lapis.cloud.shared.rpc.EmailChangePendingNotFoundException
import network.lapis.cloud.shared.rpc.EmailChangeRateLimitedException
import network.lapis.cloud.shared.rpc.EmailChangeRepeatMismatchException
import network.lapis.cloud.shared.rpc.EventAlreadyLinkedException
import network.lapis.cloud.shared.rpc.EventCaptureBusyException
import network.lapis.cloud.shared.rpc.EventCaptureChangeLockedException
import network.lapis.cloud.shared.rpc.EventCaptureNoticeOutdatedException
import network.lapis.cloud.shared.rpc.EventCaptureNoticeRequiredException
import network.lapis.cloud.shared.rpc.EventCaptureOperatorNoticeOutdatedException
import network.lapis.cloud.shared.rpc.EventCaptureTextMissingException
import network.lapis.cloud.shared.rpc.EventNotLinkableException
import network.lapis.cloud.shared.rpc.EventRoomCaptureBlockedByAdminException
import network.lapis.cloud.shared.rpc.EventRoomCaptureBlockedByOrganizerException
import network.lapis.cloud.shared.rpc.EventRoomRecordingAccessLevelNotAllowedException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.GalleryLockedException
import network.lapis.cloud.shared.rpc.GalleryOrderMismatchException
import network.lapis.cloud.shared.rpc.InvalidPasswordException
import network.lapis.cloud.shared.rpc.LupaBbAuthRejectedException
import network.lapis.cloud.shared.rpc.LupaBbCredentialsMissingException
import network.lapis.cloud.shared.rpc.LupaBbEncryptionKeyMissingException
import network.lapis.cloud.shared.rpc.LupaBbLimitExceededException
import network.lapis.cloud.shared.rpc.LupaBbPreviewExpiredException
import network.lapis.cloud.shared.rpc.LupaBbSyncBusyException
import network.lapis.cloud.shared.rpc.LupaBbSyncRateLimitedException
import network.lapis.cloud.shared.rpc.LupaBbUnavailableException
import network.lapis.cloud.shared.rpc.LupaBbUnexpectedResponseException
import network.lapis.cloud.shared.rpc.MailingListNotDeletableException
import network.lapis.cloud.shared.rpc.MailingListSendingInProgressException
import network.lapis.cloud.shared.rpc.MeetingAlreadyLinkedException
import network.lapis.cloud.shared.rpc.MeetingEventCancelReservedException
import network.lapis.cloud.shared.rpc.MeetingEventFieldReservedException
import network.lapis.cloud.shared.rpc.MeetingEventLinkStaleException
import network.lapis.cloud.shared.rpc.MeetingEventVisibilityReservedException
import network.lapis.cloud.shared.rpc.MeetingLocationMissingException
import network.lapis.cloud.shared.rpc.MeetingNotAnnounceableException
import network.lapis.cloud.shared.rpc.MemberPhotoConsentOutdatedException
import network.lapis.cloud.shared.rpc.MemberPhotoMissingException
import network.lapis.cloud.shared.rpc.MemberPhotoNotEligibleException
import network.lapis.cloud.shared.rpc.MemberPhotoRateLimitedException
import network.lapis.cloud.shared.rpc.MemberPublicBioConsentOutdatedException
import network.lapis.cloud.shared.rpc.MemberPublicBioMissingException
import network.lapis.cloud.shared.rpc.MemberPublicBioNotEligibleException
import network.lapis.cloud.shared.rpc.MemberPublicBioRateLimitedException
import network.lapis.cloud.shared.rpc.MemberPublicBioValidationException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportNotEnabledException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import network.lapis.cloud.shared.rpc.RateLimitedException
import network.lapis.cloud.shared.rpc.RecordingRawFilesMissingException
import network.lapis.cloud.shared.rpc.RecordingRecomputeInProgressException
import network.lapis.cloud.shared.rpc.RecordingRecomputeInsufficientDiskException
import network.lapis.cloud.shared.rpc.RecordingRecomputeNotAllowedException
import network.lapis.cloud.shared.rpc.RecordingRecomputeRateLimitedException
import network.lapis.cloud.shared.rpc.RecordingRetryNotAllowedException
import network.lapis.cloud.shared.rpc.RecordingRetryRateLimitedException
import network.lapis.cloud.shared.rpc.RegionalChapterHasPartyReportValuesException
import network.lapis.cloud.shared.rpc.SearchRateLimitedException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import network.lapis.cloud.shared.rpc.WeakPasswordException

/**
 * V0.7.3 Basis-Mehrseiten-UI: real session-cookie auth replaces the V0.1.5 "acting as" member
 * switcher this object's own KDoc always pointed towards ("Swapping this out for real
 * session-based auth later only touches this file") -- see [AuthHttp] for the login/logout HTTP
 * calls that establish the `lapis_session` cookie, and [rpcService] KDoc for why no client-side
 * Kilua RPC configuration (header injection, request filters) is needed any more.
 */
object AppState {
    /** The current session, or `null` if unauthenticated. */
    var session: SessionInfoDto? = null
        private set

    /** Runs after every [setSession] call -- wired once by `App.start()` to re-render the navbar. */
    var onSessionChange: () -> Unit = {}

    val isAuthenticated: Boolean
        get() = session != null

    fun hasRole(vararg roles: AccountRole): Boolean = session?.role in roles

    /**
     * The single place [session] is ever mutated -- guarantees [onSessionChange] fires whenever
     * the session actually changes.
     *
     * Review-Fund 2026-09-08 Runde 4 (Finding 1, KRITISCH, live verifiziert): no-op guard added --
     * previously this fired [onSessionChange] unconditionally, including a `null` -> `null` call,
     * which is not a theoretical edge case but the ROUTINE outcome of `App.kt`'s boot-time session
     * probe for every anonymous first-time visitor (`App.start()`'s `AppScope.launch` block calls
     * this with whatever the probe resolved to, right after the shell's own initial, synchronous
     * `refreshShell()` call already rendered the anonymous state once). That redundant second
     * [onSessionChange] call -- wired to `App.kt`'s `refreshShell` -- used to force a real
     * unmount/remount cycle of the sidebar `Offcanvas` on a desktop viewport, which is the other
     * half of the fix for that same finding (see `App.kt`'s `refreshShell` KDoc). [SessionInfoDto]
     * is a plain `data class` (structural `==`), so this comparison is cheap and correct for the
     * genuine no-op case; a real login/logout, or a session actually being refreshed with a new
     * `expiresAt`, still compares unequal and fires as before.
     */
    fun setSession(newSession: SessionInfoDto?) {
        if (newSession == session) return
        val previousSession = session
        session = newSession
        // V1.9.145: the cached answer "is the member finder offered" belongs to one identity and one status.
        if (previousSession?.memberId != newSession?.memberId || previousSession?.status != newSession?.status) MemberFinderNav.clear()
        // V1.9.156: an acknowledged event-capture consent belongs to the person who gave it; a sign-out (an SPA navigation, no reload) or another account asks again.
        if (previousSession?.memberId != newSession?.memberId) EventCaptureConsentMemory.clear()
        // V1.9.70: a sign-out or another identity ends a running conference (before any screen re-renders); a refresh of the same
        // identity (new `expiresAt`) does not.
        ConferenceDock.onAuthSessionChanged(previousSession, newSession)
        // V1.9.38: the organization's zone travels with the session; it must be in place before any screen re-renders.
        OrganizationTime.zoneId = newSession?.organizationTimeZone ?: DEFAULT_ORGANIZATION_ZONE_ID
        onSessionChange()
    }
}

/**
 * Plain [getService] call, no `requestFilter`/header injection -- every Kilua RPC call already
 * sends the `lapis_session` cookie automatically (`credentials: "include"` is baked into Kilua
 * RPC's own `CallAgent.getRequestInit`, verified against the pinned kilua-rpc-core-js 0.0.45
 * artifact). The old `X-Member-Id` trusted-header fallback this file used to inject has no effect
 * against a real (non-H2-in-memory) deployment in any case -- see
 * `network.lapis.cloud.server.security.RequestContext.resolveCurrentMember` KDoc.
 */
inline fun <reified T : Any> rpcService(): T = getService()

/**
 * Runs [block]; on failure, shows an error toast and returns `null` instead of propagating. Now
 * that `lapis-shared` (not the JVM-only `lapis-server`) is where the project's
 * `@RpcServiceException` subclasses live, these deserialize to their real typed form on the JS
 * side instead of crashing Kilua RPC's polymorphic exception decoding -- see `ServiceExceptions.kt`
 * KDoc. [UnauthenticatedException] additionally clears [AppState.session] and routes back to the
 * login screen, so a mid-session expiry returns the user cleanly to login instead of leaving a
 * broken, half-loaded screen behind -- see the V0.7.3 plan "Mid-session 401s". A plain
 * "Unauthorized"/401 the generic Kilua RPC exception path can also surface (not a service-exception
 * body at all, e.g. a raw HTTP-layer rejection) is handled the same way as a defensive fallback.
 *
 * **Every other named exception gets a static, type-appropriate German toast, not the server's own
 * exception message.** Verified empirically (V0.7.4 review) against the actual wire payload: Kilua
 * RPC's polymorphic exception protocol only ever transmits the `AbstractServiceException` subclass
 * discriminator, never the subclass's own `message` -- the reconstructed client-side exception's
 * `message` is always empty, regardless of what the server's `throw XException("...")` call site
 * passed. This is upstream `kilua-rpc-core` 0.0.45 protocol behavior (confirmed via its
 * KSP-generated `registerRpcServiceExceptions()` and the raw JSON-RPC response body), not something
 * this project's exception classes can opt into or out of. Every screen's data-loading/mutating
 * coroutine goes through this wrapper.
 */
suspend fun <T> guarded(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // V1.4.20: every failed RPC lifts an active "new version available" snooze (a stale client
        // failing against a newer server contract is exactly when the hint matters). Deliberately
        // hooked HERE (AFTER the toast, and never throwing) and not into `notifyError`, which ~200 non-RPC call sites (form validation)
        // also use.
        handleGuardedFailure(e)
        ClientVersionWatcher.notifyRpcFailure()
        null
    }

/**
 * V1.9.158 -- the fixed German text of the "unavailable option" refusals and of the general rate limit (a pure function so it is testable
 * without a toast). They do not extend [ForbiddenException] / [ConflictException], so they never fall into those generic branches.
 * `RateLimitedException` is the general "too many requests" text; the article preview catches it itself for its own wording.
 */
internal fun typedRefusalText(e: Throwable): String? =
    when (e) {
        is RateLimitedException -> tr("Zu viele Anfragen – bitte kurz warten.")
        is EventRoomRecordingAccessLevelNotAllowedException ->
            tr("Die Aufzeichnung einer Veranstaltung ist nur für den Vorstand oder die Administration möglich.")
        is EventRoomCaptureBlockedByAdminException ->
            conferenceEventCaptureBlockedReason(ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_BLOCKED_BY_ADMIN)
        is EventRoomCaptureBlockedByOrganizerException ->
            conferenceEventCaptureBlockedReason(ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_BLOCKED_BY_ORGANIZER)
        is ConferenceRecordingAlreadyActiveException -> tr("Es läuft bereits eine Aufzeichnung.")
        is ConferenceRoomEndedException -> tr("Diese Konferenz ist bereits beendet.")
        // V1.9.222: the gallery of an auction with bids / a decided project is frozen; a re-order that is not a permutation of ALL
        // pictures.
        is GalleryLockedException -> tr("Die Bilder können nicht mehr geändert werden.")
        is GalleryOrderMismatchException -> tr("Die Bilder wurden inzwischen geändert -- bitte die Ansicht aktualisieren.")
        // V1.9.225 party report: the fixed texts of its typed refusals (Kilua RPC transmits only the exception type).
        is PartyReportNotEnabledException -> tr("Der Rechenschaftsbericht ist nur für politische Parteien verfügbar.")
        is PartyReportRevisionConflictException ->
            tr("Die Werte wurden inzwischen von jemand anderem geändert -- bitte laden Sie das Blatt neu.")
        is PartyReportInvalidInputException -> tr("Jahr, Gliederung oder Betrag sind ungültig.")
        is RegionalChapterHasPartyReportValuesException ->
            tr("Für diesen Landesverband sind Werte im Rechenschaftsbericht erfasst. Er kann nicht gelöscht werden.")
        // V1.9.226 LupaBB sync: the fixed texts of its typed refusals.
        is LupaBbAuthRejectedException ->
            tr(
                "LupaBB hat die Zugangsdaten abgelehnt. Bitte prüfen Sie Benutzername, Passwort und API-Schlüssel.",
            )
        is LupaBbUnavailableException -> tr("LupaBB ist gerade nicht erreichbar. Bitte versuchen Sie es später erneut.")
        is LupaBbUnexpectedResponseException -> tr("LupaBB hat eine unerwartete Antwort geschickt. Es wurde nichts geändert.")
        is LupaBbLimitExceededException -> tr("Die Datenmenge aus LupaBB übersteigt die Grenzen des Abgleichs. Es wurde nichts geändert.")
        is LupaBbSyncBusyException -> tr("Es läuft bereits ein Abgleich. Bitte warten Sie, bis er beendet ist.")
        is LupaBbSyncRateLimitedException -> tr("LupaBB wurde eben erst abgefragt. Bitte warten Sie eine Minute.")
        is LupaBbPreviewExpiredException -> tr("Die Vorschau ist nicht mehr gültig. Bitte laden Sie sie neu.")
        is LupaBbCredentialsMissingException ->
            tr(
                "Es sind keine LupaBB-Zugangsdaten hinterlegt oder sie sind nicht lesbar. Bitte geben Sie sie neu ein.",
            )
        is LupaBbEncryptionKeyMissingException ->
            tr(
                "Auf diesem Server ist kein Geheimnis-Schlüssel eingerichtet. Zugangsdaten können nicht gespeichert werden.",
            )
        // V1.9.227 Sitzung <-> Veranstaltung: the fixed texts of its typed refusals.
        is MeetingAlreadyLinkedException -> tr("Diese Sitzung ist bereits mit einer Veranstaltung verknüpft.")
        is EventAlreadyLinkedException -> tr("Diese Veranstaltung gehört bereits zu einer Sitzung.")
        is MeetingNotAnnounceableException ->
            tr("Diese Sitzung kann nicht angekündigt werden. Sie ist abgesagt, schon gehalten oder liegt in der Vergangenheit.")
        is MeetingLocationMissingException -> tr("Für die Ankündigung fehlt der Ort der Sitzung.")
        is MeetingEventLinkStaleException -> tr("Die Verknüpfung hat sich inzwischen geändert. Bitte laden Sie die Ansicht neu.")
        is EventNotLinkableException ->
            tr("Diese Veranstaltung kann nicht verknüpft werden. Sie ist abgesagt oder Teil einer laufenden Serie.")
        // V1.9.229: what an officer of a Sitzung may not do with its event.
        is MeetingEventVisibilityReservedException -> tr("Öffentlich machen kann nur der Vorstand.")
        is MeetingEventFieldReservedException -> tr("Dieses Feld ändert der Vorstand.")
        is MeetingEventCancelReservedException -> tr("Eine kostenpflichtige Veranstaltung sagt der Vorstand ab.")
        // V1.9.234: deleting a mailing list -- the fixed texts of its typed refusals.
        is MailingListNotDeletableException -> tr("Automatische Mailinglisten können nicht gelöscht werden.")
        is MailingListSendingInProgressException ->
            tr("Diese Mailingliste wird gerade versendet. Bitte löschen Sie sie erst, wenn der Versand abgeschlossen ist.")
        // V1.9.237 "Neue Nachricht": the fixed texts of its typed refusals. One text per type, never a hint WHY a recipient is unavailable.
        is DirectMessageRecipientUnavailableException -> tr("Diese Person kann nicht angeschrieben werden.")
        is DirectMessageGroupUnavailableException -> tr("Diese Gruppe ist nicht verfügbar.")
        is DirectMessageGroupTooLargeException -> tr("Diese Gruppe ist für eine Nachricht zu groß.")
        is DirectMessageRateLimitedException -> tr("Zu viele Nachrichten in kurzer Zeit. Bitte versuchen Sie es später erneut.")
        // V1.9.246 "Aktionen": the fixed texts of its typed refusals.
        is ActivismLimitReachedException -> tr("Das Limit für diese Aktion ist erreicht. Bitte versuchen Sie es später erneut.")
        is ActivismNotRunningException -> tr("Das geht nur, solange die Aktion läuft (Ergebnis: sobald sie abgeschlossen ist).")
        is ActivismSelfApprovalException -> tr("Eine Idee kann nicht von der Person freigegeben werden, die sie angelegt hat.")
        is ActivismRevisionConflictException -> tr("Die Aktion wurde inzwischen geändert -- bitte laden Sie die Ansicht neu.")
        is ActivismReportOpenException -> tr("Sie haben diese Aktion bereits gemeldet. Die Meldung wird geprüft.")
        is ActivismResponsibleLimitException -> tr("Eine Aktion hat mindestens eine und höchstens zehn verantwortliche Personen.")
        is ActivismInvalidTransitionException -> tr("Dieser Statuswechsel ist nicht möglich.")
        else -> null
    }

/**
 * The typed-exception -> toast mapping of [guarded] (see its KDoc), extracted unchanged in V1.4.20
 * so [guarded] can hook every failure with a single line. Branch ORDER matters and is preserved:
 * the specific `@RpcServiceException` subclasses first, the generic `Throwable` fallback last.
 */
private fun handleGuardedFailure(e: Throwable) {
    typedRefusalText(e)?.let {
        notifyError(it)
        return
    }
    when (e) {
        is UnauthenticatedException -> sessionExpired()
        is ForbiddenException -> notifyError(tr("Keine Berechtigung für diese Aktion."))
        is NotFoundException -> notifyError(tr("Nicht gefunden."))
        is ConflictException -> notifyError(tr("Die Aktion steht im Konflikt mit dem aktuellen Zustand -- bitte Ansicht aktualisieren."))
        is BadRequestException -> notifyError(tr("Ungültige Anfrage."))
        is InvalidPasswordException -> notifyError(tr("Aktuelles Passwort ist falsch."))
        is WeakPasswordException ->
            notifyError(tr("Neues Passwort erfüllt nicht die Anforderungen (mind. 12, max. 128 Zeichen, nicht die E-Mail-Adresse)."))
        // Welle V1.4.36 -- ArticleService.previewArticle's 30/min-per-member Rate-Limit. Same
        // "typed exception -> fixed German toast" reasoning as every other branch above: Kilua RPC
        // never transmits the server's own message, only the exception TYPE.
        // Welle V1.9.55 -- synthesized by the server's RpcErrorSanitizer for DB lock/statement timeouts.
        is ServiceBusyException -> notifyError(tr("Der Server ist gerade ausgelastet. Bitte versuchen Sie es in einem Moment erneut."))
        // Welle V1.9.101 -- the committee-candidate search's per-member rate limit (a typed exception: Kilua RPC has no HTTP 429).
        is SearchRateLimitedException -> notifyError(tr("Zu viele Suchanfragen -- bitte kurz warten."))
        // Welle V1.9.19 "Mitglieder-Foto" -- typed, fixed toasts (Kilua RPC never transmits the server's own message).
        is MemberPhotoMissingException -> notifyError(tr("Bitte zuerst ein Foto hochladen."))
        is MemberPhotoNotEligibleException -> notifyError(tr("Nur Mitglieder können ein Foto hinterlegen."))
        is MemberPhotoRateLimitedException -> notifyError(tr("Zu viele Versuche. Bitte später erneut versuchen."))
        is MemberPhotoConsentOutdatedException -> notifyError(tr("Der Hinweistext wurde aktualisiert. Bitte erneut bestätigen."))
        // Welle V1.9.20 "Öffentliche Seiten" -- same "typed exception -> fixed toast" discipline for the public short introduction.
        is MemberPublicBioMissingException -> notifyError(tr("Bitte zuerst eine Kurzvorstellung speichern."))
        is MemberPublicBioNotEligibleException ->
            notifyError(
                tr("Die Kurzvorstellung kann derzeit nicht gespeichert oder veröffentlicht werden."),
            )
        is MemberPublicBioValidationException -> notifyError(tr("Die Kurzvorstellung ist zu lang oder enthält unzulässige Zeichen."))
        is MemberPublicBioRateLimitedException -> notifyError(tr("Zu viele Versuche. Bitte später erneut versuchen."))
        is MemberPublicBioConsentOutdatedException -> notifyError(tr("Der Hinweistext wurde aktualisiert. Bitte erneut bestätigen."))
        // Welle V1.9.56 "E-Mail-Änderung absichern" -- typed, fixed toasts (Kilua RPC never transmits the server's own message).
        is EmailChangeMailUnavailableException ->
            notifyError(
                tr(
                    "Auf dieser Instanz ist kein Mailversand eingerichtet. Adressänderungen für andere Mitglieder sind daher nicht möglich.",
                ),
            )
        is EmailChangeRepeatMismatchException -> notifyError(tr("Die beiden E-Mail-Adressen stimmen nicht überein."))
        is EmailChangeNotAllowedException -> notifyError(tr("Für dieses Mitglied ist hier keine Adressänderung möglich."))
        is EmailChangeAlreadyCurrentException -> notifyError(tr("Das ist bereits die aktuelle E-Mail-Adresse."))
        is EmailChangePendingNotFoundException -> notifyError(tr("Diese Änderung ist nicht mehr offen -- bitte Ansicht aktualisieren."))
        is EmailChangeRateLimitedException -> notifyError(tr("Zu viele Versuche. Bitte später erneut versuchen."))
        // Welle V1.9.107 "Umkodierung neu starten" -- typed, fixed toasts (see ConferenceRecordingsPanel, which handles the same three itself).
        is RecordingRetryNotAllowedException ->
            notifyError(tr("Diese Aufzeichnung kann nicht neu verarbeitet werden. Bitte Ansicht aktualisieren."))
        is RecordingRawFilesMissingException ->
            notifyError(tr("Die Rohaufnahmen sind nicht mehr vorhanden. Eine erneute Verarbeitung ist nicht möglich."))
        is RecordingRetryRateLimitedException -> notifyError(tr("Bitte versuchen Sie es später erneut."))
        // Welle V1.9.109 "Neu berechnen" -- the same typed refusals as fixed toasts (the recordings panel handles them itself first).
        is RecordingRecomputeInProgressException -> notifyError(tr("Die Aufzeichnung wird bereits neu berechnet."))
        is RecordingRecomputeRateLimitedException -> notifyError(tr("Zu viele Neuberechnungen – bitte später erneut versuchen."))
        is RecordingRecomputeInsufficientDiskException -> notifyError(tr("Zu wenig Speicherplatz für die Neuberechnung."))
        is RecordingRecomputeNotAllowedException -> notifyError(tr("Diese Aufzeichnung kann nicht neu berechnet werden."))
        // Welle V1.9.134 "Vertragstexte" -- typed, fixed toasts (Kilua RPC never transmits the server's own message). The registration screens
        // catch AgreementTextOutdatedException themselves first (reload the text, withdraw the consent); this is the fallback for every other caller.
        is AgreementTextOutdatedException -> notifyError(tr("Der Vertrag wurde aktualisiert. Bitte lesen Sie ihn erneut."))
        is AgreementTextInvalidException ->
            notifyError(tr("Der Vertragstext ist nicht zulässig. Bitte prüfen Sie Länge, Zeichen, Version und Datum."))
        is AgreementTextLayoutInvalidException -> notifyError(tr("Der Hinweis entspricht nicht der vorgegebenen Gliederung."))
        is AgreementTextVersionLabelTakenException -> notifyError(tr("Diese Versionsbezeichnung ist bereits vergeben."))
        is AgreementTextDraftChangedException ->
            notifyError(tr("Der Entwurf wurde inzwischen geändert. Bitte laden Sie die Ansicht neu und prüfen Sie ihn erneut."))
        // Welle V1.9.156 -- typed, fixed toasts of the capture consent in event rooms (the callers that can act on them catch them first).
        is EventCaptureNoticeRequiredException -> notifyError(tr("Bitte bestätigen Sie zuerst den Hinweis zur Aufzeichnung."))
        is EventCaptureNoticeOutdatedException ->
            notifyError(
                tr("Der Hinweis zur Aufzeichnung hat sich geändert. Bitte lesen Sie ihn erneut."),
            )
        is EventCaptureChangeLockedException -> notifyError(tr("Die Freigabe der Aufzeichnung kann gerade nicht geändert werden."))
        is EventCaptureOperatorNoticeOutdatedException -> notifyError(tr("Der Hinweis wurde geändert. Bitte lesen Sie ihn erneut."))
        is EventCaptureBusyException ->
            notifyError(tr("In einem Veranstaltungsraum läuft gerade eine Aufzeichnung oder Übertragung. Beenden Sie sie zuerst."))
        is EventCaptureTextMissingException ->
            notifyError(tr("Veröffentlichen Sie zuerst den Einwilligungstext für Veranstaltungen unter Vertragstexte."))
        else -> {
            val message = e.message?.takeIf { it.isNotBlank() } ?: tr("Unbekannter Fehler")
            if (message.contains("Unauthorized")) {
                sessionExpired()
            } else {
                notifyError(message)
            }
        }
    }
}

internal fun sessionExpired() {
    AppState.setSession(null)
    notifyError(tr("Sitzung abgelaufen -- bitte erneut anmelden."))
    navigateTo(Routes.LOGIN)
}
