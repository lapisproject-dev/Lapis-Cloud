package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.CommitteeSeatFixture
import network.lapis.cloud.server.rpc.PartnerServerService
import network.lapis.cloud.server.rpc.withServiceCaller
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val NOW = LocalDateTime(2026, 11, 5, 10, 0, 0)
private val T0 = Instant.parse("2026-11-05T10:00:00Z")

/** V1.9.258 -- the guest sign-in partner list against real rows (H2): narrow read, memo, epoch invalidation, fresh hidden check. */
class PartnerLoginChoicesDbTest :
    FunSpec({
        val fx = CommitteeSeatFixture()

        fun reset() {
            wipePartnerTables()
            transaction { OrganizationSettingsTable.update { it[partnerListHidden] = false } }
            PartnerLoginChoices.resetMemoForTests()
        }

        fun addDiscovered(
            host: String,
            root: Uuid,
            name: String? = null,
            bigJson: String? = null,
        ) {
            transaction {
                PartnerDiscoveredServerTable.insert {
                    it[id] = Uuid.random()
                    it[PartnerDiscoveredServerTable.host] = host
                    it[remoteName] = name
                    it[rootPartnerServerId] = root
                    it[hops] = 2.toShort()
                    it[viaHosts] = PartnerProvenance.encode(listOf("a.beispiel.de"))
                    it[createdAt] = NOW
                    it[lastReachedAt] = NOW
                    it[nextFetchAt] = NOW
                    if (bigJson != null) it[remotePartnersJson] = bigJson
                }
            }
        }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { reset() }
        afterSpec {
            reset()
            fx.cleanUp()
        }

        test("listForLoginChoices reads the narrow columns even when a row carries a large stored partner list") {
            val root = addDirectPartner(host = "a.beispiel.de", displayName = "Alpha")
            addDiscovered("b.beispiel.de", root, name = "Beta", bigJson = "[" + "\"x\",".repeat(10_000) + "\"y\"]")
            val rows = transaction { PartnerDiscoveryStore.listForLoginChoices() }
            rows.single().host shouldBe "b.beispiel.de"
            rows.single().remoteName shouldBe "Beta"
            rows.single().rootPartnerId shouldBe root
            rows.single().hops shouldBe 2
            rows.single().via shouldContainExactly listOf("a.beispiel.de")
        }

        test("loadInTx combines the direct partner and the discovered server from real rows") {
            val root = addDirectPartner(host = "a.beispiel.de", displayName = "Alpha")
            addDiscovered("b.beispiel.de", root, name = "Beta")
            transaction { PartnerLoginChoices.loadInTx(ownHost = "own.example.com") }.map { it.host } shouldContainExactly
                listOf("a.beispiel.de", "b.beispiel.de")
        }

        test("memo: a hit inside the TTL does not read again, a bump or the TTL does") {
            addDirectPartner(host = "a.beispiel.de")
            PartnerLoginChoices.current(T0).map { it.host } shouldContainExactly listOf("a.beispiel.de")
            addDirectPartner(host = "b.beispiel.de")
            PartnerLoginChoices.current(T0 + 5.seconds).map { it.host } shouldContainExactly listOf("a.beispiel.de")
            PartnerDisplayEpoch.bump()
            PartnerLoginChoices.current(T0 + 6.seconds).map { it.host } shouldContainExactly listOf("a.beispiel.de", "b.beispiel.de")
            addDirectPartner(host = "c.beispiel.de")
            PartnerLoginChoices.current(T0 + 10.seconds).size shouldBe 2
            PartnerLoginChoices.current(T0 + 37.seconds).size shouldBe 3
        }

        test("setPartnerListHidden bumps the epoch: the list is empty at once and a choice is refused") {
            addDirectPartner(host = "a.beispiel.de")
            val admin = fx.member(role = AccountRole.ADMIN)
            PartnerLoginChoices.current(T0).size shouldBe 1
            PartnerLoginChoices.acceptChoice(raw = "a.beispiel.de", now = T0) shouldBe "a.beispiel.de"
            val epochBefore = PartnerDisplayEpoch.current()
            withServiceCaller {
                call(caller = admin) {
                    PartnerServerService(
                        call = it,
                        probeRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
                        fetcher = fakeFetcher { json(body = ByteArray(0)) },
                        publicBaseUrl = "https://own.example.com",
                        clock = { NOW },
                    ).setPartnerListHidden(true)
                }.getOrThrow()
            }
            (PartnerDisplayEpoch.current() > epochBefore) shouldBe true
            PartnerLoginChoices.current(T0 + 1.seconds).shouldBeEmpty()
            PartnerLoginChoices.acceptChoice(raw = "a.beispiel.de", now = T0 + 1.seconds) shouldBe null
        }

        test("acceptChoice reads the hidden flag FRESH even inside the memo window") {
            addDirectPartner(host = "a.beispiel.de")
            PartnerLoginChoices.current(T0).size shouldBe 1
            transaction { OrganizationSettingsTable.update { it[partnerListHidden] = true } }
            PartnerLoginChoices.acceptChoice(raw = "a.beispiel.de", now = T0 + 1.seconds) shouldBe null
        }

        test("acceptChoice: exact canonical membership only") {
            addDirectPartner(host = "a.beispiel.de")
            PartnerLoginChoices.acceptChoice(raw = "A.Beispiel.DE ", now = T0) shouldBe "a.beispiel.de"
            listOf("https://a.beispiel.de", "a.beispiel.de:443", "a.beispiel.de.", "b.beispiel.de", "", null, "a".repeat(10_000)).forEach {
                PartnerLoginChoices.acceptChoice(raw = it, now = T0) shouldBe null
            }
        }
    })
