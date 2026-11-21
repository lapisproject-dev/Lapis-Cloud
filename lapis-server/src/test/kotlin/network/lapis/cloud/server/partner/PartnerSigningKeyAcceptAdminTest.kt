package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.db.generated.PartnerSigningKeyHoldTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.federation.SigningKeyFingerprint
import network.lapis.cloud.server.rpc.CommitteeSeatFixture
import network.lapis.cloud.server.rpc.PartnerServerService
import network.lapis.cloud.server.rpc.withServiceCaller
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartnerAuditKind
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val NOW = LocalDateTime(2026, 11, 21, 10, 0, 0)

/** V1.9.319 -- the two admin methods of the signing key change (accept a pending key, release a hold) through a real RPC call context, on H2. */
class PartnerSigningKeyAcceptAdminTest :
    FunSpec({
        val fx = CommitteeSeatFixture()
        val partnerIds = mutableListOf<Uuid>()
        val oldKey = FederationKeyPairGenerator.generate().publicKeyPem
        val newKey = FederationKeyPairGenerator.generate().publicKeyPem

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            transaction {
                partnerIds.forEach { id -> PartnerServerTable.deleteWhere { PartnerServerTable.id eq id } }
                PartnerSigningKeyHoldTable.deleteWhere { PartnerSigningKeyHoldTable.host eq "held.example.org" }
            }
            fx.cleanUp()
        }

        fun service(call: io.ktor.server.application.ApplicationCall) =
            PartnerServerService(
                call = call,
                probeRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
                fetcher = fakeFetcher { json(body = feedJson()) },
                publicBaseUrl = "https://own.example.com",
                clock = { NOW },
            )

        fun changedPartner(): Uuid {
            val id = Uuid.random()
            val host = "kc-${id.toString().take(8)}.example.org"
            transaction {
                PartnerServerTable.insert {
                    it[PartnerServerTable.id] = id
                    it[PartnerServerTable.host] = host
                    it[createdAt] = NOW
                    it[nextFetchAt] = NOW
                    it[signingKeyId] = "https://$host/federation/actor#main-key"
                    it[signingPublicKeyPem] = oldKey
                    it[signingKeyPendingId] = "https://$host/federation/actor#main-key"
                    it[signingKeyPendingPem] = newKey
                    it[signingKeyChangedAt] = NOW
                }
            }
            partnerIds += id
            return id
        }

        fun audits(kind: PartnerAuditKind) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTNER_SERVER }
                    .filter { (it[AuditLogEntryTable.afterSnapshot] ?: "").contains("\"${kind.name}\"") }
            }

        test(
            "the list shows both fingerprints and the moment; the accept needs ADMIN, the exact fingerprint, and audits fingerprints only",
        ) {
            val admin = fx.member(role = AccountRole.ADMIN)
            val member = fx.member(role = AccountRole.MEMBER)
            val id = changedPartner()
            withServiceCaller {
                val dto = call(caller = admin) { service(it).list() }.getOrThrow().partners.single { it.id == id.toString() }
                dto.signingKeyFingerprint shouldBe SigningKeyFingerprint.of(oldKey)
                dto.signingKeyPendingFingerprint shouldBe SigningKeyFingerprint.of(newKey)
                dto.signingKeyChangedAt shouldBe NOW
                // not an admin: refused, nothing changes
                call(caller = member) {
                    service(it).acceptPartnerSigningKey(partnerId = id.toString(), pendingFingerprint = SigningKeyFingerprint.of(newKey))
                }.exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                // wrong fingerprint, malformed id, unknown id
                call(caller = admin) {
                    service(it).acceptPartnerSigningKey(partnerId = id.toString(), pendingFingerprint = SigningKeyFingerprint.of(oldKey))
                }.exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
                call(caller = admin) { service(it).acceptPartnerSigningKey(partnerId = id.toString(), pendingFingerprint = "short") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
                call(caller = admin) {
                    service(it).acceptPartnerSigningKey(partnerId = "not-a-uuid", pendingFingerprint = SigningKeyFingerprint.of(newKey))
                }.exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                audits(PartnerAuditKind.PARTNER_SIGNING_KEY_ACCEPTED) shouldHaveSize 0
                call(caller = admin) {
                    service(it).acceptPartnerSigningKey(partnerId = id.toString(), pendingFingerprint = SigningKeyFingerprint.of(newKey))
                }.getOrThrow()
                val after = call(caller = admin) { service(it).list() }.getOrThrow().partners.single { it.id == id.toString() }
                after.signingKeyFingerprint shouldBe SigningKeyFingerprint.of(newKey)
                after.signingKeyPendingFingerprint shouldBe null
                after.signingKeyChangedAt shouldBe null
                val entry = audits(PartnerAuditKind.PARTNER_SIGNING_KEY_ACCEPTED).single()
                val snapshot = entry[AuditLogEntryTable.afterSnapshot] ?: ""
                snapshot shouldNotContain "BEGIN"
                snapshot shouldNotContain newKey.take(40)
            }
        }

        test("releasing a hold needs ADMIN, a normalised host, audits once, and an unknown host is not found") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val member = fx.member(role = AccountRole.MEMBER)
            transaction {
                PartnerSigningKeyHoldTable.insert {
                    it[host] = "held.example.org"
                    it[trustedKeySha256] = SigningKeyFingerprint.of(oldKey)
                    it[detectedAt] = NOW
                }
            }
            withServiceCaller {
                call(caller = member) { service(it).releaseDiscoveredSigningKeyHold("held.example.org") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                transaction { PartnerSigningKeyHoldTable.selectAll().count() } shouldBe 1L
                call(caller = admin) { service(it).releaseDiscoveredSigningKeyHold("") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
                call(caller = admin) { service(it).releaseDiscoveredSigningKeyHold("Held.Example.Org.") }.getOrThrow()
                transaction { PartnerSigningKeyHoldTable.selectAll().count() } shouldBe 0L
                audits(PartnerAuditKind.DISCOVERED_SIGNING_KEY_HOLD_RELEASED) shouldHaveSize 1
                call(caller = admin) { service(it).releaseDiscoveredSigningKeyHold("held.example.org") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
            }
        }
    })
