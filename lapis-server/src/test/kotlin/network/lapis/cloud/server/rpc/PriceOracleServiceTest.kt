package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator
import network.lapis.cloud.server.economy.oracle.PriceOracleSource
import network.lapis.cloud.server.economy.oracle.SourcePriceResult
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.DonationConversionInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PriceOracleConfigInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/** A [PriceOracleSource] test double that always returns [price] -- never performs real network I/O. */
private class FixedPriceSource(
    override val id: String,
    private val price: BigDecimal,
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC,
) : PriceOracleSource {
    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? =
        SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
}

/** A [PriceOracleSource] test double that always fails -- used to force [PriceOracleOrchestrator.currentQuote] into a fresh-cache HALT. */
private class NeverRespondingSource(
    override val id: String,
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC,
) : PriceOracleSource {
    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? = null
}

/**
 * A [Clock] whose [now] can be advanced explicitly, without any real wall-clock sleep -- same
 * pattern as `PriceOracleOrchestratorTest`'s own `FakeClock` (kept as a separate private copy here
 * since that one is file-private). Needed since Security-Audit-Runde 1 / S1 introduced (and
 * Security-Audit-Runde 2 / S9 tightened) `PriceOracleOrchestrator`'s hard floor on real fan-outs
 * (`HARD_FLOOR_FANOUT_DIVISOR`-derived, currently 14_400s/4h for GOLD_XAU/FIAT): a test that
 * deliberately triggers two real fan-outs (e.g. a priming call, then a call proving
 * `updateOracleConfig` invalidation took effect) needs to advance PAST that floor, and doing so with
 * `Clock.System` + `Thread.sleep(14_400_000)` would make this test suite unbearably slow.
 */
private class ServiceTestFakeClock(
    private var instant: Instant = Clock.System.now(),
) : Clock {
    override fun now(): Instant = instant

    fun advanceBy(seconds: Long) {
        instant = instant.plus(seconds.seconds)
    }
}

/** Like [FixedPriceSource], but counts invocations -- used by the Review Round 2 / NEW-1 (second facet) `updateOracleConfig` invalidation test to prove whether a real fan-out ran or a stale [PriceOracleOrchestrator]-internal replay was served instead. */
private class CountingFixedPriceSource(
    override val id: String,
    private val price: BigDecimal,
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC,
) : PriceOracleSource {
    val callCount = AtomicInteger(0)

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? {
        callCount.incrementAndGet()
        return SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
    }
}

/** Two agreeing sources at a fixed BTC/EUR price -- combined with the seeded default `anchorUnitsPerLtr` (0.000001), yields a clean `1 LTR = 0.05 EUR` conversion rate. */
private fun liveOrchestrator(price: BigDecimal = BigDecimal("50000")): PriceOracleOrchestrator =
    PriceOracleOrchestrator(sources = listOf(FixedPriceSource(id = "a", price = price), FixedPriceSource(id = "b", price = price)))

/** A fresh orchestrator whose every source fails and which was never primed -- always HALTs. */
private fun haltingOrchestrator(): PriceOracleOrchestrator =
    PriceOracleOrchestrator(sources = listOf(NeverRespondingSource(id = "a"), NeverRespondingSource(id = "b")))

/** V0.6.6: an orchestrator wired with exactly [count] of the three GOLD_XAU sources this deployment "has keys for" (0..3) -- used to exercise `validateConfigInput`'s generic `configuredSourceCount >= quorumFloor` check without naming specific sources. */
private fun goldSourcesOrchestrator(
    count: Int,
    price: BigDecimal = BigDecimal("2000.00"),
): PriceOracleOrchestrator {
    val allThree =
        listOf(
            FixedPriceSource(id = "goldapi", price = price, anchor = AnchorAsset.GOLD_XAU),
            FixedPriceSource(id = "metalpriceapi", price = price, anchor = AnchorAsset.GOLD_XAU),
            FixedPriceSource(id = "alphavantage", price = price, anchor = AnchorAsset.GOLD_XAU),
        )
    return PriceOracleOrchestrator(sources = allThree.take(count))
}

/** V0.6.6: an orchestrator wired with exactly [count] of the one FIAT source (0 or 1). */
private fun fiatSourcesOrchestrator(
    count: Int,
    price: BigDecimal = BigDecimal.ONE,
): PriceOracleOrchestrator {
    val theOne = listOf(FixedPriceSource(id = "ecb", price = price, anchor = AnchorAsset.FIAT))
    return PriceOracleOrchestrator(sources = theOne.take(count))
}

/** V0.6.6: directly overwrites the seeded `price_oracle_config` row -- bypasses `updateOracleConfig`'s own validation on purpose (that validation is exercised separately), so the gold/fiat conversion-integration tests can set up an anchor config `updateOracleConfig` itself would also accept. */
private fun setOracleConfig(
    anchorAsset: AnchorAsset,
    donationCurrency: String,
    anchorUnitsPerLtr: BigDecimal,
    minQuorum: Int,
    cacheTtlSeconds: Int = 172_800,
    outlierThresholdBps: Int = 300,
    maxSpreadBps: Int = 1000,
) {
    transaction {
        PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
            it[PriceOracleConfigTable.anchorAsset] = anchorAsset
            it[PriceOracleConfigTable.donationCurrency] = donationCurrency
            it[PriceOracleConfigTable.anchorUnitsPerLtr] = anchorUnitsPerLtr
            it[PriceOracleConfigTable.cacheTtlSeconds] = cacheTtlSeconds
            it[PriceOracleConfigTable.minQuorum] = minQuorum
            it[PriceOracleConfigTable.outlierThresholdBps] = outlierThresholdBps
            it[PriceOracleConfigTable.maxSpreadBps] = maxSpreadBps
            it[PriceOracleConfigTable.updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
        }
    }
}

/**
 * Exercises [PriceOracleService] end to end -- same "throwaway routes calling the service class
 * directly" house style as [PeerTransferServiceTest]. [afterTest] restores `price_oracle_config`
 * to its seeded defaults and deletes every row a test created, so tests remain order-independent.
 */
class PriceOracleServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction {
                PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
                    it[anchorAsset] = AnchorAsset.BITCOIN_BTC
                    it[donationCurrency] = "EUR"
                    it[anchorUnitsPerLtr] = BigDecimal("0.000001")
                    it[cacheTtlSeconds] = 300
                    it[minQuorum] = 2
                    it[outlierThresholdBps] = 300
                    it[maxSpreadBps] = 1000
                    it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            cleanUpPriceOracleTestData(createdMemberIds)
            createdMemberIds.clear()
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Price-Oracle Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = kotlinx.datetime.LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun freeBalanceOf(memberId: Uuid): BigDecimal =
            transaction {
                LtrLedgerEntryTable
                    .selectAll()
                    .where { LtrLedgerEntryTable.memberId eq memberId }
                    .fold(BigDecimal.ZERO.setScale(2)) { acc, row -> acc + row[LtrLedgerEntryTable.amountLtr] }
            }

        test(
            "convertDonationToLtr: happy path mints the correctly computed LTR amount, writes exactly one MINT ledger row " +
                "plus one matching provenance row",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(liveOrchestrator()) }
                }
                val member = createTestMember("po-happy@example.org")

                val response =
                    client.post("/test/convert?memberId=$member&donationAmount=10.00") {
                        header("X-Member-Id", TREASURER_ID)
                    }
                response.status shouldBe HttpStatusCode.OK
                val (conversionId, respMemberId, ltrMinted, priceStatus, ledgerEntryId) = response.bodyAsText().split(":")
                respMemberId shouldBe member.toString()
                // donationAmount 10.00 EUR / (anchorUnitsPerLtr 0.000001 * anchorPrice 50000) = 10.00 / 0.05 = 200.00 LTR
                BigDecimal(ltrMinted).compareTo(BigDecimal("200.00")) shouldBe 0
                priceStatus shouldBe "LIVE"

                freeBalanceOf(member).compareTo(BigDecimal("200.00")) shouldBe 0

                val ledgerRow =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq member) and (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.MINT)
                            }.single()
                    }
                ledgerRow[LtrLedgerEntryTable.id].toString() shouldBe ledgerEntryId
                ledgerRow[LtrLedgerEntryTable.amountLtr].compareTo(BigDecimal("200.00")) shouldBe 0

                val conversionRow =
                    transaction {
                        PriceOracleConversionTable.selectAll().where { PriceOracleConversionTable.id eq Uuid.parse(conversionId) }.single()
                    }
                conversionRow[PriceOracleConversionTable.ltrLedgerEntryId].toString() shouldBe ledgerEntryId
                conversionRow[PriceOracleConversionTable.sourcesUsed] shouldBe "a,b"
                conversionRow[PriceOracleConversionTable.sourceCount] shouldBe 2
                conversionRow[PriceOracleConversionTable.memberId] shouldBe member
            }
        }

        test("convertDonationToLtr: a HALTed oracle quote rejects the request and writes NOTHING -- no ledger row, no provenance row") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(haltingOrchestrator()) }
                }
                val member = createTestMember("po-halt@example.org")

                val response =
                    client.post("/test/convert?memberId=$member&donationAmount=10.00") {
                        header("X-Member-Id", TREASURER_ID)
                    }
                response.status shouldBe HttpStatusCode.Conflict

                freeBalanceOf(member).compareTo(BigDecimal.ZERO) shouldBe 0
                transaction {
                    LtrLedgerEntryTable.selectAll().where { LtrLedgerEntryTable.memberId eq member }.count()
                } shouldBe 0L
                transaction {
                    PriceOracleConversionTable.selectAll().where { PriceOracleConversionTable.memberId eq member }.count()
                } shouldBe 0L
            }
        }

        test("convertDonationToLtr: a MEMBER (non-TREASURER/BOARD/ADMIN) caller is rejected before any write") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(liveOrchestrator()) }
                }
                val member = createTestMember("po-forbidden@example.org")

                val response =
                    client.post("/test/convert?memberId=$member&donationAmount=10.00") {
                        header("X-Member-Id", MEMBER_ID)
                    }
                response.status shouldBe HttpStatusCode.Forbidden
                freeBalanceOf(member).compareTo(BigDecimal.ZERO) shouldBe 0
            }
        }

        test("convertDonationToLtr: non-positive and >2-decimal donationAmount are rejected; a dust-level amount is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(liveOrchestrator()) }
                }
                val member = createTestMember("po-validation@example.org")

                val zero = client.post("/test/convert?memberId=$member&donationAmount=0.00") { header("X-Member-Id", TREASURER_ID) }
                zero.status shouldBe HttpStatusCode.Conflict

                val negative = client.post("/test/convert?memberId=$member&donationAmount=-1.00") { header("X-Member-Id", TREASURER_ID) }
                negative.status shouldBe HttpStatusCode.Conflict

                val tooManyDecimals =
                    client.post("/test/convert?memberId=$member&donationAmount=1.005") { header("X-Member-Id", TREASURER_ID) }
                tooManyDecimals.status shouldBe HttpStatusCode.Conflict

                freeBalanceOf(member).compareTo(BigDecimal.ZERO) shouldBe 0
            }
        }

        test("convertDonationToLtr: an amount that rounds to less than the dust floor is rejected, no partial state") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    // A very high anchor price makes even a 0.01 EUR donation compute to well under 0.01 LTR.
                    routing { registerPriceOracleTestRoutes(liveOrchestrator(price = BigDecimal("5000000"))) }
                }
                val member = createTestMember("po-dust@example.org")

                val response =
                    client.post("/test/convert?memberId=$member&donationAmount=0.01") { header("X-Member-Id", TREASURER_ID) }
                response.status shouldBe HttpStatusCode.Conflict
                freeBalanceOf(member).compareTo(BigDecimal.ZERO) shouldBe 0
            }
        }

        test("convertDonationToLtr: an unknown memberId is rejected with NotFound, no partial state") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(liveOrchestrator()) }
                }
                val unknown = Uuid.random()

                val response =
                    client.post("/test/convert?memberId=$unknown&donationAmount=10.00") { header("X-Member-Id", TREASURER_ID) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "updateOracleConfig: ADMIN-only gate, minQuorum<2 rejected, non-BTC anchor rejected, bad currency rejected, " +
                "maxSpreadBps<outlierThresholdBps rejected",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(liveOrchestrator()) }
                }

                val forbiddenBoard =
                    client.post(
                        "/test/update-config?anchorAsset=BITCOIN_BTC&donationCurrency=EUR&anchorUnitsPerLtr=0.000001" +
                            "&cacheTtlSeconds=300&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", BOARD_ID) }
                forbiddenBoard.status shouldBe HttpStatusCode.Forbidden

                val forbiddenTreasurer =
                    client.post(
                        "/test/update-config?anchorAsset=BITCOIN_BTC&donationCurrency=EUR&anchorUnitsPerLtr=0.000001" +
                            "&cacheTtlSeconds=300&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", TREASURER_ID) }
                forbiddenTreasurer.status shouldBe HttpStatusCode.Forbidden

                val lowQuorum =
                    client.post(
                        "/test/update-config?anchorAsset=BITCOIN_BTC&donationCurrency=EUR&anchorUnitsPerLtr=0.000001" +
                            "&cacheTtlSeconds=300&minQuorum=1&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                lowQuorum.status shouldBe HttpStatusCode.Conflict

                val nonBtcAnchor =
                    client.post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.000001" +
                            "&cacheTtlSeconds=300&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                nonBtcAnchor.status shouldBe HttpStatusCode.Conflict

                val badCurrency =
                    client.post(
                        "/test/update-config?anchorAsset=BITCOIN_BTC&donationCurrency=XYZ&anchorUnitsPerLtr=0.000001" +
                            "&cacheTtlSeconds=300&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                badCurrency.status shouldBe HttpStatusCode.Conflict

                val spreadBelowOutlier =
                    client.post(
                        "/test/update-config?anchorAsset=BITCOIN_BTC&donationCurrency=EUR&anchorUnitsPerLtr=0.000001" +
                            "&cacheTtlSeconds=300&minQuorum=2&outlierThresholdBps=500&maxSpreadBps=100",
                    ) { header("X-Member-Id", ADMIN_ID) }
                spreadBelowOutlier.status shouldBe HttpStatusCode.Conflict

                val accepted =
                    client.post(
                        "/test/update-config?anchorAsset=BITCOIN_BTC&donationCurrency=USD&anchorUnitsPerLtr=0.000002" +
                            "&cacheTtlSeconds=120&minQuorum=2&outlierThresholdBps=250&maxSpreadBps=900",
                    ) { header("X-Member-Id", ADMIN_ID) }
                accepted.status shouldBe HttpStatusCode.OK
                accepted.bodyAsText() shouldBe "USD:120:2"
            }
        }

        test("PriceOracleConfigInput exposes no URL/host/source field -- the SSRF invariant, source allowlist stays code-fixed") {
            val fieldNames = PriceOracleConfigInput::class.java.declaredFields.map { it.name.lowercase() }
            fieldNames.none { it.contains("url") || it.contains("host") || it.contains("source") } shouldBe true
        }

        // ── V0.6.6 "Price-Oracle: Gold- und Fiat-Anker" ──────────────────────────────────────────

        test("updateOracleConfig: GOLD_XAU with all 3 configured gold sources and minQuorum=2 is accepted and persisted") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(goldSourcesOrchestrator(count = 3)) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.01" +
                            "&cacheTtlSeconds=172800&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "EUR:172800:2"
            }
        }

        test("updateOracleConfig: GOLD_XAU with exactly 2 configured gold sources (the floor, not 'all 3') is also accepted") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(goldSourcesOrchestrator(count = 2)) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.01" +
                            "&cacheTtlSeconds=172800&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "updateOracleConfig: GOLD_XAU with only 1 configured gold source is rejected, naming all three LAPIS_ORACLE_* env " +
                "vars and no key value, config row unchanged",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(goldSourcesOrchestrator(count = 1)) }
                }
                val before =
                    transaction {
                        PriceOracleConfigTable
                            .selectAll()
                            .where { PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }
                            .single()[PriceOracleConfigTable.anchorAsset]
                    }

                val response =
                    client.post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.01" +
                            "&cacheTtlSeconds=172800&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
                val body = response.bodyAsText()
                body.contains("LAPIS_ORACLE_GOLDAPI_KEY") shouldBe true
                body.contains("LAPIS_ORACLE_METALPRICEAPI_KEY") shouldBe true
                body.contains("LAPIS_ORACLE_ALPHAVANTAGE_KEY") shouldBe true

                val after =
                    transaction {
                        PriceOracleConfigTable
                            .selectAll()
                            .where { PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }
                            .single()[PriceOracleConfigTable.anchorAsset]
                    }
                after shouldBe before
            }
        }

        test("updateOracleConfig: GOLD_XAU with 0 configured gold sources is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(goldSourcesOrchestrator(count = 0)) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.01" +
                            "&cacheTtlSeconds=172800&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "updateOracleConfig: GOLD_XAU minQuorum=3 with only 2 configured sources is rejected by the distinct 'minQuorum exceeds configured source count' guard",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(goldSourcesOrchestrator(count = 2)) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.01" +
                            "&cacheTtlSeconds=172800&minQuorum=3&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText().contains("exceeds") shouldBe true
            }
        }

        test("updateOracleConfig: FIAT with one configured ECB source and minQuorum=1 is accepted") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(fiatSourcesOrchestrator(count = 1)) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=FIAT&donationCurrency=EUR&anchorUnitsPerLtr=5" +
                            "&cacheTtlSeconds=172800&minQuorum=1&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("updateOracleConfig regression: BITCOIN_BTC minQuorum=1 is still rejected") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(liveOrchestrator()) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=BITCOIN_BTC&donationCurrency=EUR&anchorUnitsPerLtr=0.000001" +
                            "&cacheTtlSeconds=300&minQuorum=1&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("updateOracleConfig: GOLD_XAU minQuorum=1 is rejected (floor 2), even with all 3 sources configured") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(goldSourcesOrchestrator(count = 3)) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.01" +
                            "&cacheTtlSeconds=172800&minQuorum=1&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("updateOracleConfig: GOLD_XAU cacheTtlSeconds below the 43200s refresh interval is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(goldSourcesOrchestrator(count = 3)) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.01" +
                            "&cacheTtlSeconds=300&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── Review Round 1 / MAJOR-2: anchor-aware peg-magnitude sanity band ─────────────────────

        test(
            "MAJOR-2: a BTC-scale anchorUnitsPerLtr (the seeded 0.000001) left over after switching to FIAT is " +
                "rejected with a clear, actionable message naming the anchor",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(fiatSourcesOrchestrator(count = 1)) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=FIAT&donationCurrency=EUR&anchorUnitsPerLtr=0.000001" +
                            "&cacheTtlSeconds=172800&minQuorum=1&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
                val body = response.bodyAsText()
                body.contains("anchorUnitsPerLtr") shouldBe true
                body.contains("FIAT") shouldBe true
            }
        }

        test(
            "MAJOR-2: the same BTC-scale anchorUnitsPerLtr (0.000001) left over after switching to GOLD_XAU is also rejected",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(goldSourcesOrchestrator(count = 3)) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.000001" +
                            "&cacheTtlSeconds=172800&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText().contains("anchorUnitsPerLtr") shouldBe true
            }
        }

        test(
            "MAJOR-2: a peg in the correct order of magnitude for FIAT (5) is accepted -- the sanity band does not reject a " +
                "legitimate, anchor-appropriate policy choice",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(fiatSourcesOrchestrator(count = 1)) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=FIAT&donationCurrency=EUR&anchorUnitsPerLtr=5" +
                            "&cacheTtlSeconds=172800&minQuorum=1&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "MAJOR-2: a peg in the correct order of magnitude for GOLD_XAU (0.01) is accepted -- the sanity band does not " +
                "reject a legitimate, anchor-appropriate policy choice",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(goldSourcesOrchestrator(count = 3)) }
                }
                val response =
                    client.post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.01" +
                            "&cacheTtlSeconds=172800&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "Gold integration: convertDonationToLtr under a GOLD_XAU anchor mints the correctly computed LTR amount and writes " +
                "provenance with anchorAsset GOLD_XAU, sourceCount 3, priceStatus LIVE",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(goldSourcesOrchestrator(count = 3, price = BigDecimal("2000.00"))) }
                }
                val member = createTestMember("po-gold@example.org")
                setOracleConfig(
                    anchorAsset = AnchorAsset.GOLD_XAU,
                    donationCurrency = "EUR",
                    anchorUnitsPerLtr = BigDecimal("0.01"),
                    minQuorum = 2,
                )

                val response =
                    client.post("/test/convert?memberId=$member&donationAmount=100.00") { header("X-Member-Id", TREASURER_ID) }
                response.status shouldBe HttpStatusCode.OK
                val (conversionId, respMemberId, ltrMinted, priceStatus, ledgerEntryId) = response.bodyAsText().split(":")
                respMemberId shouldBe member.toString()
                // 100.00 EUR / (anchorUnitsPerLtr 0.01 * anchorPrice 2000.00) = 100.00 / 20.00 = 5.00 LTR
                BigDecimal(ltrMinted).compareTo(BigDecimal("5.00")) shouldBe 0
                priceStatus shouldBe "LIVE"

                val ledgerRow =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq member) and (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.MINT)
                            }.single()
                    }
                ledgerRow[LtrLedgerEntryTable.id].toString() shouldBe ledgerEntryId

                val conversionRow =
                    transaction {
                        PriceOracleConversionTable.selectAll().where { PriceOracleConversionTable.id eq Uuid.parse(conversionId) }.single()
                    }
                conversionRow[PriceOracleConversionTable.anchorAsset] shouldBe AnchorAsset.GOLD_XAU
                conversionRow[PriceOracleConversionTable.sourceCount] shouldBe 3
                conversionRow[PriceOracleConversionTable.sourcesUsed].split(",").toSet() shouldBe
                    setOf("goldapi", "metalpriceapi", "alphavantage")
                conversionRow[PriceOracleConversionTable.ltrLedgerEntryId].toString() shouldBe ledgerEntryId
            }
        }

        test(
            "Fiat integration: convertDonationToLtr under a FIAT (EUR) anchor mints the correctly computed LTR amount, " +
                "sourceCount 1, priceStatus LIVE",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(fiatSourcesOrchestrator(count = 1, price = BigDecimal.ONE)) }
                }
                val member = createTestMember("po-fiat@example.org")
                setOracleConfig(
                    anchorAsset = AnchorAsset.FIAT,
                    donationCurrency = "EUR",
                    anchorUnitsPerLtr = BigDecimal("5"),
                    minQuorum = 1,
                )

                val response =
                    client.post("/test/convert?memberId=$member&donationAmount=100.00") { header("X-Member-Id", TREASURER_ID) }
                response.status shouldBe HttpStatusCode.OK
                val (conversionId, respMemberId, ltrMinted, priceStatus, _) = response.bodyAsText().split(":")
                respMemberId shouldBe member.toString()
                // 100.00 EUR / (anchorUnitsPerLtr 5 * anchorPrice 1) = 20.00 LTR
                BigDecimal(ltrMinted).compareTo(BigDecimal("20.00")) shouldBe 0
                priceStatus shouldBe "LIVE"

                val conversionRow =
                    transaction {
                        PriceOracleConversionTable.selectAll().where { PriceOracleConversionTable.id eq Uuid.parse(conversionId) }.single()
                    }
                conversionRow[PriceOracleConversionTable.anchorAsset] shouldBe AnchorAsset.FIAT
                conversionRow[PriceOracleConversionTable.sourceCount] shouldBe 1
                conversionRow[PriceOracleConversionTable.sourcesUsed] shouldBe "ecb"
            }
        }

        // ── Review Round 2 / NEW-1 (second facet): updateOracleConfig must invalidate replay state ─

        test(
            "updateOracleConfig invalidates the orchestrator's pending replay state: a tightened threshold takes " +
                "effect on the very next currentQuote() call instead of waiting out the refresh-interval replay window",
        ) {
            testApplication {
                // GOLD_XAU has a non-zero refreshIntervalSeconds (43_200s), unlike the seeded default
                // BITCOIN_BTC (0) -- this test needs the refresh-interval replay gate to actually be
                // in play, which BTC never exercises.
                val goldA = CountingFixedPriceSource(id = "goldapi", price = BigDecimal("2000.00"), anchor = AnchorAsset.GOLD_XAU)
                val goldB = CountingFixedPriceSource(id = "metalpriceapi", price = BigDecimal("2001.00"), anchor = AnchorAsset.GOLD_XAU)
                val goldC = CountingFixedPriceSource(id = "alphavantage", price = BigDecimal("2002.00"), anchor = AnchorAsset.GOLD_XAU)
                // Security-Audit-Runde 1 / S1 introduced a hard, invalidation-proof floor on real
                // fan-out frequency, tightened by Security-Audit-Runde 2 / S9 to
                // refreshIntervalSeconds(GOLD_XAU) / HARD_FLOOR_FANOUT_DIVISOR = 43_200 / 3 = 14_400s
                // (4h) -- a FakeClock lets this test advance PAST that floor between the priming call
                // and the post-invalidation call below without a real 4h sleep, while still proving
                // the exact same thing this test always proved: a genuine config change invalidates
                // immediately, not after the full refresh interval.
                val clock = ServiceTestFakeClock()
                val orchestrator = PriceOracleOrchestrator(sources = listOf(goldA, goldB, goldC), clock = clock)
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(orchestrator) }
                }
                val member = createTestMember("po-invalidate@example.org")
                setOracleConfig(
                    anchorAsset = AnchorAsset.GOLD_XAU,
                    donationCurrency = "EUR",
                    anchorUnitsPerLtr = BigDecimal("0.01"),
                    minQuorum = 2,
                    cacheTtlSeconds = 172_800,
                )

                // First call: a real fan-out, priming the orchestrator's lastAttempts/cache state.
                client
                    .post("/test/convert?memberId=$member&donationAmount=100.00") {
                        header("X-Member-Id", TREASURER_ID)
                    }.status shouldBe HttpStatusCode.OK
                goldA.callCount.get() shouldBe 1

                // Second call, still well inside GOLD_XAU's 43_200s refresh window (far longer than
                // this test takes to run): replayed verbatim by the orchestrator, no new fan-out.
                client
                    .post("/test/convert?memberId=$member&donationAmount=100.00") {
                        header("X-Member-Id", TREASURER_ID)
                    }.status shouldBe HttpStatusCode.OK
                goldA.callCount.get() shouldBe 1

                // ADMIN tightens outlierThresholdBps in direct response to a bad quote -- this must
                // take effect immediately via PriceOracleOrchestrator.invalidateReplayState(), not
                // wait out the refresh interval the way a naive fix would.
                val updated =
                    client.post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.01" +
                            "&cacheTtlSeconds=172800&minQuorum=2&outlierThresholdBps=50&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                updated.status shouldBe HttpStatusCode.OK

                // Advance past the S1/S9 hard floor (14_400s / 4h) since the priming call above
                // already counts as this key's most recent real fan-out -- the floor is about REAL
                // TIME between fan-outs, not about how many updateOracleConfig calls happened in
                // between, so this advance is what makes the NEXT call eligible to fan out at all.
                // Still nowhere near GOLD_XAU's 43_200s refresh interval, so this in no way weakens
                // what the test proves.
                clock.advanceBy(14_401)

                // Third call, still well inside the ORIGINAL refresh window: a real fan-out happens
                // again (proving updateOracleConfig cleared lastAttempts), not a replay of the
                // pre-tightening quote.
                client
                    .post("/test/convert?memberId=$member&donationAmount=100.00") {
                        header("X-Member-Id", TREASURER_ID)
                    }.status shouldBe HttpStatusCode.OK
                goldA.callCount.get() shouldBe 2
                goldB.callCount.get() shouldBe 2
                goldC.callCount.get() shouldBe 2
            }
        }

        // ── Security-Audit-Runde 1 / S1: updateOracleConfig must NOT unconditionally invalidate ────

        test(
            "S1 regression: a no-op updateOracleConfig save (identical config re-saved field-for-field) does NOT " +
                "trigger a fresh fan-out on the next convertDonationToLtr call -- the free-tier quota guard survives " +
                "a no-op admin save",
        ) {
            testApplication {
                val goldA = CountingFixedPriceSource(id = "goldapi", price = BigDecimal("2000.00"), anchor = AnchorAsset.GOLD_XAU)
                val goldB = CountingFixedPriceSource(id = "metalpriceapi", price = BigDecimal("2001.00"), anchor = AnchorAsset.GOLD_XAU)
                val goldC = CountingFixedPriceSource(id = "alphavantage", price = BigDecimal("2002.00"), anchor = AnchorAsset.GOLD_XAU)
                val orchestrator = PriceOracleOrchestrator(sources = listOf(goldA, goldB, goldC))
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(orchestrator) }
                }
                val member = createTestMember("po-noop-save@example.org")
                setOracleConfig(
                    anchorAsset = AnchorAsset.GOLD_XAU,
                    donationCurrency = "EUR",
                    anchorUnitsPerLtr = BigDecimal("0.01"),
                    minQuorum = 2,
                    cacheTtlSeconds = 172_800,
                    outlierThresholdBps = 300,
                    maxSpreadBps = 1000,
                )

                // First call: a real fan-out, priming lastAttempts/cache.
                client
                    .post("/test/convert?memberId=$member&donationAmount=100.00") {
                        header("X-Member-Id", TREASURER_ID)
                    }.status shouldBe HttpStatusCode.OK
                goldA.callCount.get() shouldBe 1

                // ADMIN re-saves the config with EVERY field identical -- e.g. opened the form and
                // clicked Save without changing anything.
                client
                    .post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.01" +
                            "&cacheTtlSeconds=172800&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK

                // Second call, still well inside the refresh window: MUST be a replay, not a fresh
                // fan-out -- before the S1 fix, the unconditional invalidateReplayState() call above
                // would have cleared lastAttempts and forced a real fan-out here.
                client
                    .post("/test/convert?memberId=$member&donationAmount=100.00") {
                        header("X-Member-Id", TREASURER_ID)
                    }.status shouldBe HttpStatusCode.OK
                goldA.callCount.get() shouldBe 1
                goldB.callCount.get() shouldBe 1
                goldC.callCount.get() shouldBe 1
            }
        }

        test(
            "S1: a save that only changes anchorUnitsPerLtr (the LTR peg) does NOT invalidate the oracle's replay " +
                "state -- the peg is read only downstream of the quote (computeLtrMinted), never by " +
                "PriceOracleOrchestrator.currentQuote itself -- but the new peg IS genuinely applied to the next conversion",
        ) {
            testApplication {
                val goldA = CountingFixedPriceSource(id = "goldapi", price = BigDecimal("2000.00"), anchor = AnchorAsset.GOLD_XAU)
                val goldB = CountingFixedPriceSource(id = "metalpriceapi", price = BigDecimal("2000.00"), anchor = AnchorAsset.GOLD_XAU)
                val goldC = CountingFixedPriceSource(id = "alphavantage", price = BigDecimal("2000.00"), anchor = AnchorAsset.GOLD_XAU)
                val orchestrator = PriceOracleOrchestrator(sources = listOf(goldA, goldB, goldC))
                application {
                    install(StatusPages) { installPriceOracleExceptionHandlers() }
                    routing { registerPriceOracleTestRoutes(orchestrator) }
                }
                val member = createTestMember("po-peg-only@example.org")
                setOracleConfig(
                    anchorAsset = AnchorAsset.GOLD_XAU,
                    donationCurrency = "EUR",
                    anchorUnitsPerLtr = BigDecimal("0.01"),
                    minQuorum = 2,
                    cacheTtlSeconds = 172_800,
                )

                client
                    .post("/test/convert?memberId=$member&donationAmount=100.00") {
                        header("X-Member-Id", TREASURER_ID)
                    }.status shouldBe HttpStatusCode.OK
                goldA.callCount.get() shouldBe 1

                // ADMIN re-pegs LTR (0.01 -> 0.02) -- a routine operation whenever gold's real-world
                // price has moved a lot -- but changes NOTHING PriceOracleOrchestrator.currentQuote
                // reads (anchorAsset/donationCurrency/cacheTtlSeconds/minQuorum/outlierThresholdBps/
                // maxSpreadBps all stay identical).
                client
                    .post(
                        "/test/update-config?anchorAsset=GOLD_XAU&donationCurrency=EUR&anchorUnitsPerLtr=0.02" +
                            "&cacheTtlSeconds=172800&minQuorum=2&outlierThresholdBps=300&maxSpreadBps=1000",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK

                val response =
                    client.post("/test/convert?memberId=$member&donationAmount=100.00") {
                        header("X-Member-Id", TREASURER_ID)
                    }
                response.status shouldBe HttpStatusCode.OK
                // No fresh fan-out -- the quote itself was replayed from the orchestrator's untouched
                // lastAttempts state.
                goldA.callCount.get() shouldBe 1
                goldB.callCount.get() shouldBe 1
                goldC.callCount.get() shouldBe 1

                // But the NEW peg was genuinely used for THIS conversion's LTR math: three agreeing
                // sources at 2000.00 -> median 2000.00 (replayed), 100.00 EUR / (0.02 * 2000.00) = 2.50 LTR.
                val (_, _, ltrMinted, _, _) = response.bodyAsText().split(":")
                BigDecimal(ltrMinted).compareTo(BigDecimal("2.50")) shouldBe 0
            }
        }
    })

private fun cleanUpPriceOracleTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        PriceOracleConversionTable.deleteWhere {
            (PriceOracleConversionTable.memberId inList memberIds) or (PriceOracleConversionTable.createdById inList memberIds)
        }
        LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installPriceOracleExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
}

/** Shared throwaway routes for [PriceOracleService] -- mirrors [PeerTransferServiceTest]'s `registerPeerTransferTestRoutes` style. */
private fun Route.registerPriceOracleTestRoutes(orchestrator: PriceOracleOrchestrator) {
    post("/test/convert") {
        val service = PriceOracleService(call = call, orchestrator = orchestrator)
        val q = call.request.queryParameters
        val r =
            service.convertDonationToLtr(
                DonationConversionInput(
                    memberId = q["memberId"]!!,
                    donationAmount = BigDecimal(q["donationAmount"] ?: "1.00"),
                ),
            )
        call.respondText("${r.id}:${r.memberId}:${r.ltrMinted}:${r.priceStatus}:${r.ltrLedgerEntryId}")
    }
    post("/test/update-config") {
        val service = PriceOracleService(call = call, orchestrator = orchestrator)
        val q = call.request.queryParameters
        val r =
            service.updateOracleConfig(
                PriceOracleConfigInput(
                    anchorAsset = AnchorAsset.valueOf(q["anchorAsset"]!!),
                    donationCurrency = q["donationCurrency"]!!,
                    anchorUnitsPerLtr = BigDecimal(q["anchorUnitsPerLtr"]!!),
                    cacheTtlSeconds = q["cacheTtlSeconds"]!!.toInt(),
                    minQuorum = q["minQuorum"]!!.toInt(),
                    outlierThresholdBps = q["outlierThresholdBps"]!!.toInt(),
                    maxSpreadBps = q["maxSpreadBps"]!!.toInt(),
                ),
            )
        call.respondText("${r.donationCurrency}:${r.cacheTtlSeconds}:${r.minQuorum}")
    }
    get("/test/get-config") {
        val service = PriceOracleService(call = call, orchestrator = orchestrator)
        val r = service.getOracleConfig()
        call.respondText("${r.anchorAsset}:${r.donationCurrency}")
    }
}
