package network.lapis.cloud.server.keycloak

import com.nimbusds.jwt.JWTClaimsSet
import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.member.ExternalMemberIdConfig
import network.lapis.cloud.server.member.ExternalMemberIdStore
import network.lapis.cloud.server.rpc.keycloakSubjectFingerprint
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.307 -- takes the external member id from the verified ID token claim ([ExternalMemberIdConfig.claim]) into the member row at login.
 * Independent of `LAPIS_KEYCLOAK_SYNC_PROFILE` (the id is not profile data and decides the voting right). The claim comes ONLY from the verified ID
 * token. The login is never blocked: the caller catches everything this throws and logs a reason code. Own short transaction; no mail, HTTP or other
 * external effect in it. `oidc_subject` and the Keycloak link are never touched.
 */
internal open class KeycloakExternalIdSync(
    private val config: ExternalMemberIdConfig,
) {
    /** `true` when the claim sync is configured at all (a source AND a claim name). */
    val active: Boolean get() = config.enabled && config.claim != null

    open fun syncOnLogin(
        memberId: Uuid,
        subject: String,
        claims: JWTClaimsSet,
    ): ExternalMemberIdStore.ClaimOutcome? {
        val source = config.source
        val claimName = config.claim
        if (!active || source == null || claimName == null) return null
        val raw: Any? = claims.getClaim(claimName)
        val fingerprint = keycloakSubjectFingerprint(subject)
        val outcome =
            transaction {
                // NEVER retry: a replay would run the claim twice.
                maxAttempts = 1
                ExternalMemberIdStore.applyClaim(
                    memberId = memberId,
                    rawClaimValue = raw,
                    source = source,
                    subjectFingerprint = fingerprint,
                    now = DbClock.nowLocalDateTime(),
                )
            }
        logger.debug { "Keycloak external id sync: outcome=${outcome::class.simpleName} subjectFingerprint=$fingerprint" }
        return outcome
    }
}
