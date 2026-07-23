package network.lapis.cloud.client

import dev.kilua.rpc.getService
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.InvalidPasswordException
import network.lapis.cloud.shared.rpc.NotFoundException
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

    /** The single place [session] is ever mutated -- guarantees [onSessionChange] always fires. */
    fun setSession(newSession: SessionInfoDto?) {
        session = newSession
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
    } catch (e: UnauthenticatedException) {
        sessionExpired()
        null
    } catch (e: ForbiddenException) {
        notifyError("Keine Berechtigung für diese Aktion.")
        null
    } catch (e: NotFoundException) {
        notifyError("Nicht gefunden.")
        null
    } catch (e: ConflictException) {
        notifyError("Die Aktion steht im Konflikt mit dem aktuellen Zustand -- bitte Ansicht aktualisieren.")
        null
    } catch (e: BadRequestException) {
        notifyError("Ungültige Anfrage.")
        null
    } catch (e: InvalidPasswordException) {
        notifyError("Aktuelles Passwort ist falsch.")
        null
    } catch (e: WeakPasswordException) {
        notifyError("Neues Passwort erfüllt nicht die Anforderungen (mind. 12, max. 128 Zeichen, nicht die E-Mail-Adresse).")
        null
    } catch (e: Throwable) {
        val message = e.message?.takeIf { it.isNotBlank() } ?: "Unbekannter Fehler"
        if (message.contains("Unauthorized")) {
            sessionExpired()
        } else {
            notifyError(message)
        }
        null
    }

private fun sessionExpired() {
    AppState.setSession(null)
    notifyError("Sitzung abgelaufen -- bitte erneut anmelden.")
    navigateTo(Routes.LOGIN)
}
