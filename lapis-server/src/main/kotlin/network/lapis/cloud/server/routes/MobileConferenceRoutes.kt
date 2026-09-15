package network.lapis.cloud.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ConferenceService
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException

/**
 * V1.5.1 Mobile App -- dünner, session-authentifizierter REST-Wrapper um [ConferenceService] für
 * die native Meeting-Liste (siehe `Lapis-Cloud-Mobile`-Repo, `docs/mobile-rest-api.adoc`). KEINE
 * eigene Geschäftslogik: jede Handler-Methode instanziiert [ConferenceService] mit denselben
 * Shared-Singletons, die auch [network.lapis.cloud.server.Application.module]s Kilua-RPC-
 * Registrierung (`IConferenceService::class`) verwendet, und ruft exakt dieselbe
 * Interface-Methode auf -- Autorisierung, Rate-Limiting, Reconciliation-Logik etc. laufen dadurch
 * identisch zum bestehenden Web-Pfad, keine Duplikation.
 *
 * Auth: `Authorization: Bearer <sessionToken>` -- [network.lapis.cloud.server.security
 * .resolveCurrentMember] (aufgerufen INNERHALB jeder [ConferenceService]-Methode) unterstützt das
 * bereits nativ (siehe [network.lapis.cloud.server.security.extractSessionToken] KDoc). Ein
 * fehlender/ungültiger Token wirft [network.lapis.cloud.shared.rpc.UnauthenticatedException],
 * global auf 401 gemappt durch die in `Application.module` installierte `StatusPages`-Konfiguration
 * -- diese Datei braucht dafür KEINEN eigenen try/catch-Block.
 *
 * [NotFoundException]/[ConflictException] sind NICHT global auf StatusPages gemappt (anders als
 * Unauthenticated/Forbidden) -- jeder Handler unten fängt sie explizit ab, siehe
 * [respondConferenceServiceCall].
 */
fun Route.registerMobileConferenceRoutes(
    liveKitAdminClient: LiveKitAdminClient,
    conferenceRoomRateLimiter: LoginRateLimiter,
    conferenceJoinRateLimiter: FederationInboxRateLimiter,
    conferenceLeaveRateLimiter: FederationInboxRateLimiter,
    conferenceListRateLimiter: FederationInboxRateLimiter,
    conferenceGuestInfoRateLimiter: FederationInboxRateLimiter,
    conferenceGuestAccessRateLimiter: FederationInboxRateLimiter,
    conferenceWhiteboardState: ConferenceWhiteboardState,
    conferenceNotesState: ConferenceNotesState,
    conferenceMeetingBindRateLimiter: FederationInboxRateLimiter,
    config: ConferenceConfig = ConferenceConfig.load(),
) {
    fun buildService(call: ApplicationCall) =
        ConferenceService(
            call = call,
            liveKitAdminClient = liveKitAdminClient,
            createRoomRateLimiter = conferenceRoomRateLimiter,
            config = config,
            joinRoomRateLimiter = conferenceJoinRateLimiter,
            leaveRoomRateLimiter = conferenceLeaveRateLimiter,
            listRateLimiter = conferenceListRateLimiter,
            guestInfoRateLimiter = conferenceGuestInfoRateLimiter,
            guestAccessRateLimiter = conferenceGuestAccessRateLimiter,
            whiteboardState = conferenceWhiteboardState,
            notesState = conferenceNotesState,
            conferenceMeetingBindRateLimiter = conferenceMeetingBindRateLimiter,
        )

    get("/api/mobile/v1/conference/availability") {
        call.respond(buildService(call).getAvailability())
    }

    get("/api/mobile/v1/conference/rooms") {
        respondConferenceServiceCall(call = call) { buildService(call).listActiveRooms() }
    }

    get("/api/mobile/v1/conference/rooms/{roomId}") {
        val roomId = call.parameters["roomId"]
        if (roomId == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        respondConferenceServiceCall(call = call) { buildService(call).getRoom(roomId) }
    }

    post("/api/mobile/v1/conference/rooms/{roomId}/join") {
        val roomId = call.parameters["roomId"]
        if (roomId == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        // V1.5.1: kein Federations-Gast-Consent-Flow auf Mobile -- guestConsent bleibt null. Ein
        // GUEST/FRIEND-Nutzer, der versucht auf Mobile beizutreten, bekommt exakt dieselbe
        // ForbiddenException wie ein Web-Nutzer ohne Consent -- kein Scope-Loch, nur (noch) keine
        // Mobile-UI für den Consent-Dialog (siehe Lapis-Cloud-Mobile-Repo,
        // docs/known-limitations.adoc).
        respondConferenceServiceCall(call = call) { buildService(call).joinRoom(roomId = roomId, guestConsent = null) }
    }

    post("/api/mobile/v1/conference/rooms/{roomId}/leave") {
        val roomId = call.parameters["roomId"]
        if (roomId == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        respondConferenceServiceCall(call = call) {
            buildService(call).leaveRoom(roomId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend inline fun <T> respondConferenceServiceCall(
    call: ApplicationCall,
    crossinline block: suspend () -> T,
) {
    try {
        val result = block()
        // `leaveRoom`'s own branch above already calls call.respond(NoContent) itself and returns
        // Unit -- respond()-ing Unit again here would double-write the response, so skip it.
        if (result != Unit) call.respond(result as Any)
    } catch (e: NotFoundException) {
        call.respond(HttpStatusCode.NotFound, e.message)
    } catch (e: ConflictException) {
        call.respond(HttpStatusCode.Conflict, e.message)
    }
    // UnauthenticatedException/ForbiddenException bewusst NICHT hier gefangen -- laufen in die
    // global installierte StatusPages-Behandlung (siehe Application.module, install(StatusPages)).
}
