package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.TrustAnchorSigningKeyTable
import network.lapis.cloud.server.db.generated.TrustedExternalAnchorTable
import network.lapis.cloud.server.federation.OneHopResolution
import network.lapis.cloud.server.federation.TrustAnchorEventStore
import network.lapis.cloud.server.federation.TrustAnchorKeyMaterial
import network.lapis.cloud.server.federation.TrustAnchorPoolStore
import network.lapis.cloud.server.federation.TrustAnchorResolver
import network.lapis.cloud.server.federation.TrustAnchorSigningKeyStore
import network.lapis.cloud.server.federation.TrustedAnchorStore
import network.lapis.cloud.server.federation.requireSafeFederationUrl
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.TrustAnchorEventDto
import network.lapis.cloud.shared.domain.TrustAnchorEventType
import network.lapis.cloud.shared.domain.TrustAnchorPoolMemberDto
import network.lapis.cloud.shared.domain.TrustAnchorSigningKeyDto
import network.lapis.cloud.shared.domain.TrustAnchorSigningKeyStatus
import network.lapis.cloud.shared.domain.TrustChainResolutionDto
import network.lapis.cloud.shared.domain.TrustedExternalAnchorDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ITrustAnchorService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock

/**
 * V0.8.3 Trust-Anchor-Governance RPC surface -- see [ITrustAnchorService] KDoc and
 * `26-trust-anchor.kuml.kts` file header for the full fachlich model. Every method is ADMIN-only,
 * same tier as [FederationService].
 */
class TrustAnchorService(
    private val call: ApplicationCall,
) : ITrustAnchorService {
    override suspend fun listSigningKeys(): List<TrustAnchorSigningKeyDto> {
        requireAdmin()
        return transaction {
            with(TrustAnchorSigningKeyStore) { TrustAnchorSigningKeyStore.listAll().map { it.toDto() } }
        }
    }

    override suspend fun rotateSigningKey(): TrustAnchorSigningKeyDto {
        requireAdmin()
        val now = nowLocalDateTime()
        return transaction {
            // forUpdate=true -- see TrustAnchorSigningKeyStore KDoc "Concurrency".
            val activeRow =
                TrustAnchorSigningKeyStore.findActive(forUpdate = true)
                    ?: throw ConflictException("No ACTIVE Trust-Anchor signing key found -- provisioning missing?")
            TrustAnchorSigningKeyStore.retire(activeRow[TrustAnchorSigningKeyTable.id], now)
            val newKid = TrustAnchorKeyMaterial.insertNewKey(status = TrustAnchorSigningKeyStatus.ACTIVE, now = now)
            TrustAnchorEventStore.record(TrustAnchorEventType.KEY_ROTATED, subject = newKid, now = now)
            with(TrustAnchorSigningKeyStore) { TrustAnchorSigningKeyStore.findByKid(newKid)!!.toDto() }
        }
    }

    override suspend fun revokeSigningKey(kid: String): TrustAnchorSigningKeyDto {
        requireAdmin()
        val now = nowLocalDateTime()
        return transaction {
            // forUpdate=true -- see TrustAnchorSigningKeyStore KDoc "Concurrency".
            val target =
                TrustAnchorSigningKeyStore.findByKid(kid, forUpdate = true)
                    ?: throw NotFoundException("Trust-Anchor signing key '$kid' not found")
            if (target[TrustAnchorSigningKeyTable.status] == TrustAnchorSigningKeyStatus.REVOKED) {
                throw ConflictException("Trust-Anchor signing key '$kid' is already revoked")
            }
            val wasActive = target[TrustAnchorSigningKeyTable.status] == TrustAnchorSigningKeyStatus.ACTIVE
            TrustAnchorSigningKeyStore.revokeRow(target[TrustAnchorSigningKeyTable.id], now)
            TrustAnchorEventStore.record(TrustAnchorEventType.KEY_REVOKED, subject = kid, now = now)
            // This server must always have exactly one ACTIVE signing key to keep functioning as a
            // Trust Anchor -- see ITrustAnchorService.revokeSigningKey KDoc.
            if (wasActive) {
                val newKid = TrustAnchorKeyMaterial.insertNewKey(status = TrustAnchorSigningKeyStatus.ACTIVE, now = now)
                TrustAnchorEventStore.record(TrustAnchorEventType.KEY_ROTATED, subject = newKid, now = now)
            }
            with(TrustAnchorSigningKeyStore) { TrustAnchorSigningKeyStore.findByKid(kid)!!.toDto() }
        }
    }

    override suspend fun listPoolMembers(): List<TrustAnchorPoolMemberDto> {
        requireAdmin()
        return transaction {
            with(TrustAnchorPoolStore) { TrustAnchorPoolStore.listAll().map { it.toDto() } }
        }
    }

    override suspend fun addPoolMember(homeServerUri: String): TrustAnchorPoolMemberDto {
        requireAdmin()
        runCatching { requireSafeFederationUrl(homeServerUri) }
            .onFailure { throw BadRequestException(it.message ?: "Invalid homeServerUri: $homeServerUri") }
        val now = nowLocalDateTime()
        return transaction {
            if (TrustAnchorPoolStore.findByUri(homeServerUri) != null) {
                throw ConflictException("Home server '$homeServerUri' is already in the Trust-Anchor pool")
            }
            try {
                TrustAnchorPoolStore.insert(homeServerUri, now)
            } catch (e: ExposedSQLException) {
                // Concurrent double-add race -- see TrustAnchorPoolStore KDoc "Concurrency".
                throw ConflictException("Home server '$homeServerUri' is already in the Trust-Anchor pool")
            }
            TrustAnchorEventStore.record(TrustAnchorEventType.POOL_MEMBER_ADDED, subject = homeServerUri, now = now)
            with(TrustAnchorPoolStore) { TrustAnchorPoolStore.findByUri(homeServerUri)!!.toDto() }
        }
    }

    override suspend fun removePoolMember(homeServerUri: String) {
        requireAdmin()
        val now = nowLocalDateTime()
        transaction {
            val removed = TrustAnchorPoolStore.remove(homeServerUri)
            if (!removed) throw NotFoundException("Home server '$homeServerUri' is not in the Trust-Anchor pool")
            TrustAnchorEventStore.record(TrustAnchorEventType.POOL_MEMBER_REMOVED, subject = homeServerUri, now = now)
        }
    }

    override suspend fun listTrustedAnchors(): List<TrustedExternalAnchorDto> {
        requireAdmin()
        return transaction {
            with(TrustedAnchorStore) { TrustedAnchorStore.listAll().map { it.toDto() } }
        }
    }

    override suspend fun addTrustedAnchor(anchorEntityUri: String): TrustedExternalAnchorDto {
        requireAdmin()
        runCatching { requireSafeFederationUrl(anchorEntityUri) }
            .onFailure { throw BadRequestException(it.message ?: "Invalid anchorEntityUri: $anchorEntityUri") }
        val now = nowLocalDateTime()
        return transaction {
            if (TrustedAnchorStore.findByUri(anchorEntityUri) != null) {
                throw ConflictException("Anchor '$anchorEntityUri' is already trusted")
            }
            try {
                TrustedAnchorStore.insert(anchorEntityUri, now)
            } catch (e: ExposedSQLException) {
                // Concurrent double-add race -- see TrustedAnchorStore KDoc "Concurrency".
                throw ConflictException("Anchor '$anchorEntityUri' is already trusted")
            }
            TrustAnchorEventStore.record(TrustAnchorEventType.TRUSTED_ANCHOR_ADDED, subject = anchorEntityUri, now = now)
            with(TrustedAnchorStore) { TrustedAnchorStore.findByUri(anchorEntityUri)!!.toDto() }
        }
    }

    override suspend fun removeTrustedAnchor(anchorEntityUri: String) {
        requireAdmin()
        val now = nowLocalDateTime()
        transaction {
            val removed = TrustedAnchorStore.remove(anchorEntityUri)
            if (!removed) throw NotFoundException("Anchor '$anchorEntityUri' is not trusted")
            TrustAnchorEventStore.record(TrustAnchorEventType.TRUSTED_ANCHOR_REMOVED, subject = anchorEntityUri, now = now)
        }
    }

    override suspend fun listEvents(): List<TrustAnchorEventDto> {
        requireAdmin()
        return transaction {
            with(TrustAnchorEventStore) { TrustAnchorEventStore.listRecent().map { it.toEventDto() } }
        }
    }

    override suspend fun resolveTrustChain(homeServerUri: String): TrustChainResolutionDto {
        requireAdmin()
        runCatching { requireSafeFederationUrl(homeServerUri) }
            .onFailure { throw BadRequestException(it.message ?: "Invalid homeServerUri: $homeServerUri") }

        val trustedAnchorUris =
            transaction {
                TrustedAnchorStore.listAll().map { it[TrustedExternalAnchorTable.anchorEntityUri] }
            }
        if (trustedAnchorUris.isEmpty()) {
            return TrustChainResolutionDto(homeServerUri, trusted = false, reason = "No trusted anchors configured")
        }

        var lastReason = "No trusted anchors configured"
        for (anchorUri in trustedAnchorUris) {
            when (val outcome = TrustAnchorResolver.resolveOneHop(homeServerUri, anchorUri)) {
                is OneHopResolution.Trusted ->
                    return TrustChainResolutionDto(homeServerUri, trusted = true, anchorEntityUri = outcome.anchorEntityUri)
                is OneHopResolution.NotTrusted -> lastReason = "$anchorUri: ${outcome.reason}"
            }
        }
        return TrustChainResolutionDto(homeServerUri, trusted = false, reason = lastReason)
    }

    private suspend fun requireAdmin() {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
    }

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
}
