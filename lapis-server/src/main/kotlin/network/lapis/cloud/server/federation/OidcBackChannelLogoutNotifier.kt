package network.lapis.cloud.server.federation

import com.nimbusds.jwt.JWTClaimsSet
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import network.lapis.cloud.server.audit.OidcLoginAuditRecorder
import network.lapis.cloud.server.db.generated.OidcClientRegistrationTable
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.server.db.generated.OidcSigningKeyTable
import network.lapis.cloud.shared.domain.OidcLoginEventType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Bounded outbound-delivery timeout for a Logout Token POST -- same "one unresponsive remote server can never stall the caller" reasoning as [federationHttpClient]. */
private val LOGOUT_TOKEN_TTL = 5.minutes

/**
 * Outbound Back-Channel Logout (Issuer side) -- when one of OUR OWN local members (who went out as
 * a guest to other Lapis-Cloud instances) logs out or has all sessions revoked locally, every RP
 * holding a live [OidcIssuedTokenTable] grant for that member is notified with a signed Logout
 * Token, so the RP can proactively revoke its own local guest session for that identity.
 *
 * **Best-effort, no retry queue this wave** -- mirrors V0.8.1's own documented precedent for
 * outbound federation delivery ("Delivery failure ... does not prevent the relationship row from
 * being persisted -- a later manual retry is out of scope this wave"). MUST NEVER THROW into the
 * caller -- same non-fatal posture [network.lapis.cloud.server.security.SessionStore.createSession]'s
 * opportunistic purge already establishes. Callers (`AuthRoutes.registerAuthRoutes`'s
 * `/api/auth/logout` handler, `AuthService.changePassword`'s `revokeAllForMember` call site)
 * invoke [notifyAsync] AFTER their own local revocation has already succeeded -- this is a
 * best-effort courtesy notification, not a precondition for the local logout to complete.
 *
 * **Deliberately awaited inline, NOT launched on a separate background coroutine scope** --
 * this codebase has no established application-lifetime coroutine-scope idiom to launch onto
 * (no scheduler/background-job infrastructure exists at all, see CLAUDE.md), and an ad-hoc
 * `GlobalScope.launch` would be an unstructured-concurrency anti-pattern (no cancellation/lifecycle
 * owner) worse than the latency tradeoff. Bounded instead by [federationHttpClient]'s own
 * connect/socket timeouts (a few seconds per unreachable RP, at most) and a typically-small number
 * of live grants per member -- an accepted, documented latency tradeoff for this Grundgerüst wave,
 * not a silently-overclaimed "fire-and-forget".
 *
 * SSRF-guarded even though [OidcClientRegistrationTable.backchannelLogoutUri] values are our own
 * registered clients' self-declared URIs -- defense in depth, a registration's stored URI could
 * theoretically be stale/repointed to a private address after registration.
 */
object OidcBackChannelLogoutNotifier {
    /**
     * Synchronous, best-effort notification of every RP holding a live grant for [memberId] --
     * callers on a coroutine context should launch this on a bounded background scope rather than
     * awaiting it inline if logout latency matters; the function itself never throws.
     */
    suspend fun notifyAsync(memberId: Uuid) {
        runCatching { notify(memberId) }
            .onFailure { logger.warn(it) { "Outbound Back-Channel Logout notification failed (non-fatal)" } }
    }

    private suspend fun notify(memberId: Uuid) {
        val signingKey =
            transaction {
                OidcSigningKeyTable.selectAll().where { OidcSigningKeyTable.id eq OIDC_SIGNING_KEY_ID }.singleOrNull()
            } ?: return

        val targets =
            transaction {
                (OidcIssuedTokenTable innerJoin OidcClientRegistrationTable)
                    .selectAll()
                    .where {
                        (OidcIssuedTokenTable.memberId eq memberId) and
                            OidcIssuedTokenTable.revokedAt.isNull() and
                            OidcClientRegistrationTable.backchannelLogoutUri.isNotNull()
                    }.map {
                        it[OidcClientRegistrationTable.clientId] to it[OidcClientRegistrationTable.backchannelLogoutUri]
                    }.distinct()
            }

        val issuer = FederationConfig.publicBaseUrl
        val kid = signingKey[OidcSigningKeyTable.kid]
        val privateKeyPem = signingKey[OidcSigningKeyTable.privateKeyPem]

        for ((clientId, logoutUri) in targets) {
            if (logoutUri == null) continue
            val now = Clock.System.now()
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(issuer)
                    .subject(memberId.toString())
                    .audience(clientId)
                    .issueTime(OidcJwt.toJavaDate(now))
                    .expirationTime(OidcJwt.toJavaDate(now + LOGOUT_TOKEN_TTL))
                    .claim("jti", Uuid.random().toString())
                    .claim("events", OidcJwt.logoutEventsClaim())
                    .build()
            val logoutToken = OidcJwt.sign(claims, kid, privateKeyPem)

            val delivered =
                runCatching {
                    val target = requireSafeFederationUrl(logoutUri)
                    federationHttpClient(target).use { client ->
                        val response =
                            client.post(logoutUri) {
                                setBody(FormDataContent(Parameters.build { append("logout_token", logoutToken) }))
                            }
                        response.status.isSuccess()
                    }
                }.getOrElse { false }

            OidcLoginAuditRecorder.record(
                eventType = OidcLoginEventType.BACKCHANNEL_LOGOUT_SENT,
                memberId = memberId,
                remoteParty = clientId,
                reason = if (delivered) null else "DELIVERY_FAILED",
            )
        }
    }
}
