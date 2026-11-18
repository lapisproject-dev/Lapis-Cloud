package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.allTombstones
import network.lapis.cloud.server.carpool.feed.TEST_IDS
import network.lapis.cloud.server.carpool.withWithdrawalKey
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.server.rpc.CommitteeSeatFixture
import network.lapis.cloud.server.rpc.PartnerServerService
import network.lapis.cloud.server.rpc.withServiceCaller
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val NOW = LocalDateTime(2026, 11, 18, 10, 0, 0)

/**
 * V1.9.313 -- the admin triggers of the withdrawal (B4 instance off, B5 partner off, partner removed) and the two admin counters, on H2 through a real
 * RPC call context. Postgres coverage of the same store calls is in `CarpoolWithdrawalPostgresTest`.
 */
class CarpoolWithdrawalAdminTest :
    FunSpec({
        val fx = CommitteeSeatFixture()
        val data = CarpoolTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest {
            transaction {
                exec("UPDATE carpool_posting SET federation_opt_in = FALSE")
                PartnerServerTable.deleteAll()
                CarpoolTeaserTombstoneTable.deleteAll()
                CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2)
            }
        }
        afterSpec {
            transaction {
                PartnerServerTable.deleteAll()
                CarpoolTeaserTombstoneTable.deleteAll()
                CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2)
            }
            data.cleanUp()
            fx.cleanUp()
        }

        fun service(call: io.ktor.server.application.ApplicationCall) =
            PartnerServerService(
                call = call,
                probeRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
                fetcher = fakeFetcher { json(body = feedJson()) },
                publicBaseUrl = "https://own.example.com",
                clock = { DbClock.nowLocalDateTime() },
            )

        fun partner(): Uuid {
            val id = Uuid.random()
            transaction {
                PartnerServerTable.insert {
                    it[PartnerServerTable.id] = id
                    it[host] = "wa-${id.toString().take(8)}.example.org"
                    it[createdAt] = NOW
                    it[nextFetchAt] = NOW
                    it[carpoolSharing] = true
                }
            }
            return id
        }

        fun sharedTrip(label: String): Uuid {
            val author = data.member(label = label)
            val id = data.posting(author = author, fromPlace = "38100 Braunschweig", toPlace = "30159 Hannover")
            transaction { CarpoolFederationStore.setOptIn(postingId = id, value = true) }
            return id
        }

        test("B4: switching the exchange off reports every shareable hint for every partner and keeps the consents; the counter shows it") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val a = sharedTrip("wa-b4a")
            val b = sharedTrip("wa-b4b")
            partner()
            withWithdrawalKey {
                withServiceCaller {
                    call(caller = admin) { service(it).setCarpoolFederationSettings(enabled = false, hopLimit = 2) }.getOrThrow()
                    val rows = allTombstones()
                    rows.map { it[CarpoolTeaserTombstoneTable.teaserId] }.toSet() shouldBe setOf(TEST_IDS.of(a), TEST_IDS.of(b))
                    rows.forEach { it[CarpoolTeaserTombstoneTable.partnerId] shouldBe null }
                    transaction { CarpoolFederationStore.optIn(a) && CarpoolFederationStore.optIn(b) } shouldBe true
                    call(caller = admin) { service(it).getCarpoolFederationSettings() }.getOrThrow().carpoolWithdrawalsActive shouldBe 2
                }
            }
        }

        test("B5: switching ONE partner off reports the hints for that partner only; switching it on again forgets them") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val trip = sharedTrip("wa-b5")
            val p = partner()
            val q = partner()
            withWithdrawalKey {
                withServiceCaller {
                    call(caller = admin) { service(it).setPartnerCarpoolSharing(partnerId = p.toString(), enabled = false) }.getOrThrow()
                    val row = allTombstones().single()
                    row[CarpoolTeaserTombstoneTable.teaserId] shouldBe TEST_IDS.of(trip)
                    row[CarpoolTeaserTombstoneTable.partnerId] shouldBe p
                    val overview = call(caller = admin) { service(it).list() }.getOrThrow().partners
                    overview.single { it.id == p.toString() }.carpoolWithdrawalsScoped shouldBe 1
                    overview.single { it.id == q.toString() }.carpoolWithdrawalsScoped shouldBe 0
                    transaction { CarpoolFederationStore.optIn(trip) } shouldBe true
                    call(caller = admin) { service(it).setPartnerCarpoolSharing(partnerId = p.toString(), enabled = true) }.getOrThrow()
                    allTombstones().size shouldBe 0
                }
            }
        }

        test("a partner that is removed takes its scoped tombstones with it (explicitly, independent of the cascade)") {
            val trip = sharedTrip("wa-rm")
            val p = partner()
            withWithdrawalKey {
                transaction {
                    CarpoolFederationStore.withdraw(
                        targets =
                            listOf(
                                network.lapis.cloud.server.carpool.CarpoolWithdrawalTarget(
                                    postingId = trip,
                                    departureDate = kotlinx.datetime.LocalDate(2099, 1, 1),
                                ),
                            ),
                        partnerId = p,
                        ids = TEST_IDS,
                        now = DbClock.nowLocalDateTime(),
                        clearOptIn = false,
                        requireServed = false,
                    )
                }
                allTombstones().size shouldBe 1
                transaction { PartnerServerStore.remove(p) }
                allTombstones().size shouldBe 0
                transaction { PartnerServerTable.deleteWhere { PartnerServerTable.id eq p } }
            }
        }
    })
