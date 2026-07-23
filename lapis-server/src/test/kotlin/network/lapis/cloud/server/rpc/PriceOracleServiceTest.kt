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
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/** A [PriceOracleSource] test double that always returns [price] -- never performs real network I/O. */
private class FixedPriceSource(
    override val id: String,
    private val price: BigDecimal,
) : PriceOracleSource {
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? =
        SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
}

/** A [PriceOracleSource] test double that always fails -- used to force [PriceOracleOrchestrator.currentQuote] into a fresh-cache HALT. */
private class NeverRespondingSource(
    override val id: String,
) : PriceOracleSource {
    override val anchor: AnchorAsset = AnchorAsset.BITCOIN_BTC

    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? = null
}

/** Two agreeing sources at a fixed BTC/EUR price -- combined with the seeded default `anchorUnitsPerLtr` (0.000001), yields a clean `1 LTR = 0.05 EUR` conversion rate. */
private fun liveOrchestrator(price: BigDecimal = BigDecimal("50000")): PriceOracleOrchestrator =
    PriceOracleOrchestrator(sources = listOf(FixedPriceSource("a", price), FixedPriceSource("b", price)))

/** A fresh orchestrator whose every source fails and which was never primed -- always HALTs. */
private fun haltingOrchestrator(): PriceOracleOrchestrator =
    PriceOracleOrchestrator(sources = listOf(NeverRespondingSource("a"), NeverRespondingSource("b")))

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
                    it[status] = MemberStatus.AKTIV
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
        val service = PriceOracleService(call, orchestrator)
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
        val service = PriceOracleService(call, orchestrator)
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
        val service = PriceOracleService(call, orchestrator)
        val r = service.getOracleConfig()
        call.respondText("${r.anchorAsset}:${r.donationCurrency}")
    }
}
