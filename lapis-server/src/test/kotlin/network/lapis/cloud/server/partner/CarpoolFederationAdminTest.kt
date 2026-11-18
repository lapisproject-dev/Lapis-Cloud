package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.CommitteeSeatFixture
import network.lapis.cloud.server.rpc.PartnerServerService
import network.lapis.cloud.server.rpc.withServiceCaller
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartnerAuditKind
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val NOW = LocalDateTime(2026, 11, 18, 10, 0, 0)

/** V1.9.311 -- the three admin methods of the carpool exchange (switches only) on H2, through a real RPC call context. */
class CarpoolFederationAdminTest :
    FunSpec({
        val fx = CommitteeSeatFixture()
        val partnerIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) } }
        afterSpec {
            transaction { partnerIds.forEach { id -> PartnerServerTable.deleteWhere { PartnerServerTable.id eq id } } }
            transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
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

        fun partner(): Uuid {
            val id = Uuid.random()
            transaction {
                PartnerServerTable.insert {
                    it[PartnerServerTable.id] = id
                    it[host] = "cf-${id.toString().take(8)}.example.org"
                    it[createdAt] = NOW
                    it[nextFetchAt] = NOW
                }
            }
            partnerIds += id
            return id
        }

        fun partnerAudits(kind: PartnerAuditKind) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTNER_SERVER }
                    .filter { (it[AuditLogEntryTable.afterSnapshot] ?: "").contains("\"${kind.name}\"") }
            }

        test("the settings start switched off with a reach of two; an ADMIN switches on and picks the reach; one audit per real change") {
            val admin = fx.member(role = AccountRole.ADMIN)
            withServiceCaller {
                val start = call(caller = admin) { service(it).getCarpoolFederationSettings() }.getOrThrow()
                start.enabled shouldBe false
                start.hopLimit shouldBe 2
                val enabledBefore = partnerAudits(PartnerAuditKind.CARPOOL_EXCHANGE_ENABLED).size
                val hopBefore = partnerAudits(PartnerAuditKind.CARPOOL_HOP_LIMIT_CHANGED).size
                call(caller = admin) { service(it).setCarpoolFederationSettings(enabled = true, hopLimit = 3) }.getOrThrow()
                val now = call(caller = admin) { service(it).getCarpoolFederationSettings() }.getOrThrow()
                now.enabled shouldBe true
                now.hopLimit shouldBe 3
                partnerAudits(PartnerAuditKind.CARPOOL_EXCHANGE_ENABLED) shouldHaveSize enabledBefore + 1
                partnerAudits(PartnerAuditKind.CARPOOL_HOP_LIMIT_CHANGED) shouldHaveSize hopBefore + 1
                // unchanged values: no further audit
                call(caller = admin) { service(it).setCarpoolFederationSettings(enabled = true, hopLimit = 3) }.getOrThrow()
                partnerAudits(PartnerAuditKind.CARPOOL_EXCHANGE_ENABLED) shouldHaveSize enabledBefore + 1
                partnerAudits(PartnerAuditKind.CARPOOL_HOP_LIMIT_CHANGED) shouldHaveSize hopBefore + 1
                call(caller = admin) { service(it).setCarpoolFederationSettings(enabled = false, hopLimit = 3) }.getOrThrow()
                partnerAudits(PartnerAuditKind.CARPOOL_EXCHANGE_DISABLED).isNotEmpty() shouldBe true
            }
        }

        test("a hop limit outside 1..3 is a bad request and changes nothing (never silently clamped)") {
            val admin = fx.member(role = AccountRole.ADMIN)
            withServiceCaller {
                listOf(0, 4, -1, Int.MAX_VALUE).forEach { bad ->
                    call(caller = admin) { service(it).setCarpoolFederationSettings(enabled = true, hopLimit = bad) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<BadRequestException>()
                }
                call(caller = admin) { service(it).getCarpoolFederationSettings() }.getOrThrow().enabled shouldBe false
            }
        }

        test(
            "a partner flag defaults to off, is switched by an ADMIN with one audit (host as subject); an unknown or malformed id is not found",
        ) {
            val admin = fx.member(role = AccountRole.ADMIN)
            val id = partner()
            withServiceCaller {
                call(caller = admin) { service(it).list() }
                    .getOrThrow()
                    .partners
                    .single { it.id == id.toString() }
                    .carpoolSharing shouldBe false
                val before = partnerAudits(PartnerAuditKind.PARTNER_CARPOOL_SHARING_ON).size
                call(caller = admin) { service(it).setPartnerCarpoolSharing(partnerId = id.toString(), enabled = true) }.getOrThrow()
                call(caller = admin) { service(it).list() }
                    .getOrThrow()
                    .partners
                    .single { it.id == id.toString() }
                    .carpoolSharing shouldBe true
                partnerAudits(PartnerAuditKind.PARTNER_CARPOOL_SHARING_ON) shouldHaveSize before + 1
                call(caller = admin) { service(it).setPartnerCarpoolSharing(partnerId = id.toString(), enabled = true) }.getOrThrow()
                partnerAudits(PartnerAuditKind.PARTNER_CARPOOL_SHARING_ON) shouldHaveSize before + 1
                call(caller = admin) { service(it).setPartnerCarpoolSharing(partnerId = id.toString(), enabled = false) }.getOrThrow()
                call(caller = admin) { service(it).list() }
                    .getOrThrow()
                    .partners
                    .single { it.id == id.toString() }
                    .carpoolSharing shouldBe false
                partnerAudits(PartnerAuditKind.PARTNER_CARPOOL_SHARING_OFF).isNotEmpty() shouldBe true
                call(caller = admin) { service(it).setPartnerCarpoolSharing(partnerId = Uuid.random().toString(), enabled = true) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                call(caller = admin) { service(it).setPartnerCarpoolSharing(partnerId = "not-a-uuid", enabled = true) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("the audit snapshots of the exchange carry no place, no date and no member") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val id = partner()
            withServiceCaller {
                call(caller = admin) { service(it).setPartnerCarpoolSharing(partnerId = id.toString(), enabled = true) }.getOrThrow()
            }
            partnerAudits(PartnerAuditKind.PARTNER_CARPOOL_SHARING_ON).forEach {
                (it[AuditLogEntryTable.afterSnapshot] ?: "") shouldNotContain "Braunschweig"
            }
        }
    })
