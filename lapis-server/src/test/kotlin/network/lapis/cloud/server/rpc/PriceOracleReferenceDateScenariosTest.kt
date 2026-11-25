package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.server.db.generated.PriceOracleReferenceRateTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotSourceTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
import network.lapis.cloud.server.economy.oracle.NO_LEGACY_CUTOFF
import network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator
import network.lapis.cloud.server.economy.oracle.PriceOracleSource
import network.lapis.cloud.server.economy.oracle.RawValuesCutoff
import network.lapis.cloud.server.economy.oracle.RawValuesCutoffHolder
import network.lapis.cloud.server.economy.oracle.SourcePriceResult
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConversionRateBasis
import network.lapis.cloud.shared.domain.DonationConversionInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PriceOracleConfigInput
import network.lapis.cloud.shared.domain.PriceStatus
import network.lapis.cloud.shared.domain.ReferenceDateFallback
import network.lapis.cloud.shared.domain.ReferenceRateBasis
import network.lapis.cloud.shared.domain.ReferenceRateConfirmationInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val ADMIN = "00000000-0000-0000-0000-000000000001"
private const val BOARD = "00000000-0000-0000-0000-000000000002"
private const val TREASURER = "00000000-0000-0000-0000-000000000003"
private const val PLAIN_MEMBER = "00000000-0000-0000-0000-000000000004"

/** "Today" for every scenario: Thursday 2026-11-26, 11:00 in Berlin. */
private val FIXED_NOW: Instant = Instant.parse("2026-11-26T10:00:00Z")

/** A distinctive price (no UUID can contain a dot) so a digit scan of an audit payload is meaningful. */
private val PRICE = BigDecimal("2012.345678")

private class TestSource(
    override val id: String,
    override val anchor: AnchorAsset,
    private val price: BigDecimal,
) : PriceOracleSource {
    override suspend fun fetchPrice(donationCurrency: String): SourcePriceResult? =
        SourcePriceResult(sourceId = id, price = price, observedAt = Clock.System.now())
}

private fun goldOrchestrator() =
    PriceOracleOrchestrator(
        sources =
            listOf(
                TestSource(id = "goldapi", anchor = AnchorAsset.GOLD_XAU, price = BigDecimal("2000")),
                TestSource(id = "metalpriceapi", anchor = AnchorAsset.GOLD_XAU, price = BigDecimal("2000")),
                TestSource(id = "coinbase", anchor = AnchorAsset.BITCOIN_BTC, price = BigDecimal("50000")),
                TestSource(id = "kraken", anchor = AnchorAsset.BITCOIN_BTC, price = BigDecimal("50000")),
                TestSource(id = "ecb", anchor = AnchorAsset.FIAT, price = BigDecimal.ONE),
            ),
    )

private fun setConfig(
    anchor: AnchorAsset = AnchorAsset.GOLD_XAU,
    basis: ConversionRateBasis = ConversionRateBasis.REFERENCE_DATE,
    minQuorum: Int = 2,
    maxSpreadBps: Int = 1000,
) {
    transaction {
        PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
            it[anchorAsset] = anchor
            it[donationCurrency] = "EUR"
            it[anchorUnitsPerLtr] = if (anchor == AnchorAsset.GOLD_XAU) BigDecimal("0.0001") else BigDecimal("0.000001")
            it[cacheTtlSeconds] = 172_800
            it[PriceOracleConfigTable.minQuorum] = minQuorum
            it[outlierThresholdBps] = 300
            it[PriceOracleConfigTable.maxSpreadBps] = maxSpreadBps
            it[conversionRateBasis] = basis
            it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
        }
    }
}

private fun insertSnapshot(
    at: String,
    price: BigDecimal = PRICE,
    status: PriceStatus = PriceStatus.LIVE,
    sourceCount: Int = 2,
    withSources: Boolean = true,
): Uuid {
    val id = Uuid.random()
    val ts = LocalDateTime.parse(at.removeSuffix("Z"))
    transaction {
        PriceOracleSnapshotTable.insert {
            it[PriceOracleSnapshotTable.id] = id
            it[anchorAsset] = AnchorAsset.GOLD_XAU
            it[donationCurrency] = "EUR"
            it[medianPrice] = price
            it[priceStatus] = status
            it[PriceOracleSnapshotTable.sourceCount] = sourceCount
            it[sourcesUsed] = "goldapi,metalpriceapi"
            it[priceTimestamp] = ts
            it[capturedAt] = ts
        }
        if (withSources) {
            repeat(sourceCount) { i ->
                PriceOracleSnapshotSourceTable.insert {
                    it[PriceOracleSnapshotSourceTable.id] = Uuid.random()
                    it[snapshotId] = id
                    it[sourceId] = "src-$i"
                    it[rawPrice] = price
                    it[observedAt] = ts
                    it[included] = true
                }
            }
        }
    }
    return id
}

/** Exercises the reference-date conversion end to end through [PriceOracleService] (V1.9.324), same throwaway-route house style as [PriceOracleServiceTest]. */
class PriceOracleReferenceDateScenariosTest :
    FunSpec({
        val createdMembers = mutableListOf<Uuid>()
        var originalSource: Clock = Clock.System

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            originalSource = ServerClock.source
            ServerClock.source =
                object : Clock {
                    override fun now(): Instant = FIXED_NOW
                }
        }
        afterSpec { ServerClock.source = originalSource }

        // The raw-values cutoff is process-global (set by any module() start): these scenarios want "no legacy snapshot", the default.
        beforeTest {
            RawValuesCutoffHolder.current = RawValuesCutoff { NO_LEGACY_CUTOFF }
            setConfig()
        }
        afterTest {
            transaction {
                PriceOracleConversionTable.deleteWhere { PriceOracleConversionTable.memberId inList createdMembers }
                PriceOracleConversionTable.deleteWhere { PriceOracleConversionTable.rateBasis neq ReferenceRateBasis.CURRENT }
                PriceOracleReferenceRateTable.deleteAll()
                PriceOracleSnapshotSourceTable.deleteAll()
                PriceOracleSnapshotTable.deleteAll()
                LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList createdMembers }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMembers }
                MemberTable.deleteWhere { MemberTable.id inList createdMembers }
                PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
                    it[anchorAsset] = AnchorAsset.BITCOIN_BTC
                    it[donationCurrency] = "EUR"
                    it[anchorUnitsPerLtr] = BigDecimal("0.000001")
                    it[cacheTtlSeconds] = 300
                    it[minQuorum] = 2
                    it[outlierThresholdBps] = 300
                    it[maxSpreadBps] = 1000
                    it[conversionRateBasis] = ConversionRateBasis.CURRENT
                }
            }
            createdMembers.clear()
        }

        fun createMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Stichtag Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it.legacyAccountRole(role = AccountRole.MEMBER)
                }
            }
            createdMembers += id
            return id
        }

        fun ledgerCount(member: Uuid): Long =
            transaction {
                LtrLedgerEntryTable
                    .selectAll()
                    .where { (LtrLedgerEntryTable.memberId eq member) and (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.MINT) }
                    .count()
            }

        fun conversionCount(member: Uuid): Long =
            transaction { PriceOracleConversionTable.selectAll().where { PriceOracleConversionTable.memberId eq member }.count() }

        // preview response: outcome|basis|price|effectiveDate|fallback|refId|selfBlocked|sourceCount|legacyHint
        suspend fun io.ktor.client.HttpClient.preview(
            date: String,
            caller: String = TREASURER,
            member: Uuid? = null,
        ) = post("/test/preview?date=$date" + (member?.let { "&memberId=$it" } ?: "")) { header("X-Member-Id", caller) }

        suspend fun io.ktor.client.HttpClient.confirm(
            date: String,
            price: String = PRICE.toPlainString(),
            evidence: String = "Fixing LBMA PM, Quelle: Bank-Auszug vom Tag",
            ack: Boolean = false,
            caller: String = TREASURER,
        ) = post(
            "/test/confirm?date=$date&price=$price&evidence=${evidence.replace(" ", "%20")}&ack=$ack",
        ) { header("X-Member-Id", caller) }

        suspend fun io.ktor.client.HttpClient.convert(
            member: Uuid,
            date: String?,
            expectedPrice: String?,
            expectedRef: String?,
            caller: String = TREASURER,
            amount: String = "10.00",
        ) = post(
            "/test/convert?memberId=$member&amount=$amount" +
                (date?.let { "&date=$it" } ?: "") +
                (expectedPrice?.let { "&expectedPrice=$it" } ?: "") +
                (expectedRef?.let { "&expectedRef=$it" } ?: ""),
        ) { header("X-Member-Id", caller) }

        fun fields(text: String) = text.split("|")

        fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) { installReferenceDateExceptionHandlers() }
                    routing { registerReferenceDateRoutes(goldOrchestrator()) }
                }
                block()
            }

        // ---- the automatic rate from the stored snapshots ----

        test(
            "snapshot rate: preview resolves the latest qualified snapshot of the day; the conversion mints, records the basis and the audit entry",
        ) {
            app {
                val snapshotId = insertSnapshot(at = "2026-11-20T15:00:00")
                val member = createMember("rd-snap@example.org")

                val p = fields(client.preview("2026-11-20").bodyAsText())
                p[0] shouldBe "RESOLVED"
                p[1] shouldBe "SNAPSHOT"
                BigDecimal(p[2]).compareTo(PRICE) shouldBe 0
                p[3] shouldBe "2026-11-20"
                p[4] shouldBe "NONE"
                p[5] shouldBe snapshotId.toString()
                p[7] shouldBe "2"

                val response = client.convert(member, "2026-11-20", PRICE.toPlainString(), snapshotId.toString())
                response.status shouldBe HttpStatusCode.OK
                // 10.00 EUR / (0.0001 * 2012.345678) = 49.69 LTR
                ledgerCount(member) shouldBe 1L
                val row =
                    transaction { PriceOracleConversionTable.selectAll().where { PriceOracleConversionTable.memberId eq member }.single() }
                row[PriceOracleConversionTable.rateBasis] shouldBe ReferenceRateBasis.SNAPSHOT
                row[PriceOracleConversionTable.snapshotId] shouldBe snapshotId
                row[PriceOracleConversionTable.referenceRateId] shouldBe null
                row[PriceOracleConversionTable.referenceDate] shouldBe LocalDate(2026, 11, 20)
                row[PriceOracleConversionTable.effectiveDate] shouldBe LocalDate(2026, 11, 20)
                row[PriceOracleConversionTable.fallbackReason] shouldBe ReferenceDateFallback.NONE
                row[PriceOracleConversionTable.quorumRequired] shouldBe 2
                row[PriceOracleConversionTable.ltrMinted].compareTo(BigDecimal("49.69")) shouldBe 0
                row[PriceOracleConversionTable.priceStatus] shouldBe PriceStatus.LIVE

                val audit =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityId eq row[PriceOracleConversionTable.id] }
                            .single()
                    }
                audit[AuditLogEntryTable.entityType] shouldBe AuditEntityType.PRICE_ORACLE_CONVERSION
                val payload = audit[AuditLogEntryTable.afterSnapshot]!!
                payload.contains("2012.345678") shouldBe false
                payload.contains("49.69") shouldBe false
                payload.contains("10.00") shouldBe false
            }
        }

        test("a Saturday falls back to the Friday (WEEKEND), Good Friday to the Thursday before (HOLIDAY)") {
            app {
                insertSnapshot(at = "2026-11-20T15:00:00")
                insertSnapshot(at = "2026-04-02T12:00:00", price = BigDecimal("1999.5"))
                val sat = fields(client.preview("2026-11-21").bodyAsText())
                sat[0] shouldBe "RESOLVED"
                sat[3] shouldBe "2026-11-20"
                sat[4] shouldBe "WEEKEND"
                val goodFriday = fields(client.preview("2026-04-03").bodyAsText())
                goodFriday[0] shouldBe "RESOLVED"
                goodFriday[3] shouldBe "2026-04-02"
                goodFriday[4] shouldBe "HOLIDAY"
            }
        }

        test("the calendar day is the Berlin day: 23:30 UTC in winter and 22:30 UTC in summer already belong to the next day") {
            app {
                insertSnapshot(at = "2026-11-19T23:30:00", price = BigDecimal("2001.5"))
                insertSnapshot(at = "2026-11-20T23:30:00", price = BigDecimal("2002.5"))
                // Berlin 2026-11-20 00:30 -> resolves for the 20th; Berlin 2026-11-21 00:30 is the next day.
                BigDecimal(fields(client.preview("2026-11-20").bodyAsText())[2]).compareTo(BigDecimal("2001.5")) shouldBe 0
                fields(client.preview("2026-11-19").bodyAsText())[0] shouldBe "NO_DATA"
                // Summer: UTC+2.
                insertSnapshot(at = "2026-07-14T22:30:00", price = BigDecimal("2003.5"))
                BigDecimal(fields(client.preview("2026-07-15").bodyAsText())[2]).compareTo(BigDecimal("2003.5")) shouldBe 0
                fields(client.preview("2026-07-14").bodyAsText())[0] shouldBe "NO_DATA"
            }
        }

        test("a median with more than 17 significant digits converts after the double round trip of the RPC Decimal") {
            app {
                val median = BigDecimal("3218.123456789012345678")
                val id = insertSnapshot(at = "2026-11-20T10:00:00", price = median)
                val member = createMember("rd-wire@example.org")
                val p = fields(client.preview("2026-11-20").bodyAsText())
                p[0] shouldBe "RESOLVED"
                p[5] shouldBe id.toString()
                // Sanity: the wire value really differs from the stored median (so an exact comparison would have refused it).
                (BigDecimal(median.toDouble().toString()).compareTo(median) != 0) shouldBe true
                client.convert(member, "2026-11-20", p[2], p[5]).status shouldBe HttpStatusCode.OK
            }
        }

        test("a day with only DEGRADED snapshots that meet the quorum: STATUS_NOT_LIVE, not QUORUM_MISSING") {
            app {
                insertSnapshot(at = "2026-11-20T10:00:00", status = PriceStatus.DEGRADED)
                fields(client.preview("2026-11-20").bodyAsText())[0] shouldBe "STATUS_NOT_LIVE"
            }
        }

        test("the latest QUALIFIED snapshot wins; a later DEGRADED or quorum-short snapshot is skipped") {
            app {
                insertSnapshot(at = "2026-11-20T10:00:00", price = BigDecimal("2000.1"))
                val wanted = insertSnapshot(at = "2026-11-20T14:00:00", price = BigDecimal("2001.2"))
                insertSnapshot(at = "2026-11-20T16:00:00", price = BigDecimal("2002.3"), status = PriceStatus.DEGRADED)
                insertSnapshot(at = "2026-11-20T18:00:00", price = BigDecimal("2003.4"), sourceCount = 1)
                val p = fields(client.preview("2026-11-20").bodyAsText())
                p[0] shouldBe "RESOLVED"
                p[5] shouldBe wanted.toString()
                BigDecimal(p[2]).compareTo(BigDecimal("2001.2")) shouldBe 0
            }
        }

        test(
            "no stored snapshot: NO_DATA; a snapshot below the quorum: QUORUM_MISSING; no raw values: RAW_VALUES_MISSING with the median as a hint",
        ) {
            app {
                fields(client.preview("2026-11-20").bodyAsText())[0] shouldBe "NO_DATA"
                insertSnapshot(at = "2026-11-20T10:00:00", sourceCount = 1)
                fields(client.preview("2026-11-20").bodyAsText())[0] shouldBe "QUORUM_MISSING"
                transaction { PriceOracleSnapshotSourceTable.deleteAll() }
                transaction { PriceOracleSnapshotTable.deleteAll() }
                insertSnapshot(at = "2026-11-20T10:00:00", price = BigDecimal("2000.5"), withSources = false)
                val raw = fields(client.preview("2026-11-20").bodyAsText())
                raw[0] shouldBe "RAW_VALUES_MISSING"
                BigDecimal(raw[8]).compareTo(BigDecimal("2000.5")) shouldBe 0
                raw[2] shouldBe "null"
            }
        }

        test("a higher configured minQuorum than the anchor floor applies") {
            app {
                insertSnapshot(at = "2026-11-20T10:00:00", sourceCount = 2)
                setConfig(minQuorum = 3)
                fields(client.preview("2026-11-20").bodyAsText())[0] shouldBe "QUORUM_MISSING"
            }
        }

        test("a quorum failure or a missing rate mints nothing") {
            app {
                val member = createMember("rd-nomint@example.org")
                val response = client.convert(member, "2026-11-20", "2000", Uuid.random().toString())
                response.status shouldBe HttpStatusCode.Conflict
                ledgerCount(member) shouldBe 0L
                conversionCount(member) shouldBe 0L
            }
        }

        // ---- binding to the preview ----

        test("binding: a different price, a different id or a missing price is refused; 1.50 equals 1.5") {
            app {
                val member = createMember("rd-bind@example.org")
                val snapshotId = insertSnapshot(at = "2026-11-20T15:00:00", price = BigDecimal("2000.5"))
                client.convert(member, "2026-11-20", "2000.6", snapshotId.toString()).status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-20", "2000.5", Uuid.random().toString()).status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-20", null, snapshotId.toString()).status shouldBe HttpStatusCode.Conflict
                client.convert(member, null, "2000.5", snapshotId.toString()).status shouldBe HttpStatusCode.Conflict
                ledgerCount(member) shouldBe 0L
                client.convert(member, "2026-11-20", "2000.500000", snapshotId.toString()).status shouldBe HttpStatusCode.OK
                ledgerCount(member) shouldBe 1L
            }
        }

        // ---- the manual rate ----

        test(
            "manual rate: confirm closes the gap, a second confirmation fails, the conversion records MANUAL, the confirmer cannot receive LTR at it",
        ) {
            app {
                val member = createMember("rd-manual@example.org")
                val confirmed = fields(client.confirm("2026-11-20").bodyAsText())
                confirmed[0] shouldBe "RESOLVED"
                confirmed[1] shouldBe "MANUAL"
                val rateId = confirmed[5]
                client.confirm("2026-11-20").status shouldBe HttpStatusCode.Conflict

                // the confirmer himself as the recipient: blocked in the preview and refused by the server
                val own = fields(client.preview("2026-11-20", member = Uuid.parse(TREASURER)).bodyAsText())
                own[6] shouldBe "true"
                client.convert(Uuid.parse(TREASURER), "2026-11-20", PRICE.toPlainString(), rateId).status shouldBe HttpStatusCode.Forbidden
                fields(client.preview("2026-11-20", member = member).bodyAsText())[6] shouldBe "false"

                // another treasurer role (BOARD) converts for a third member -- allowed, also the confirmer for someone else
                client.convert(member, "2026-11-20", PRICE.toPlainString(), rateId, caller = TREASURER).status shouldBe HttpStatusCode.OK
                val row =
                    transaction { PriceOracleConversionTable.selectAll().where { PriceOracleConversionTable.memberId eq member }.single() }
                row[PriceOracleConversionTable.rateBasis] shouldBe ReferenceRateBasis.MANUAL
                row[PriceOracleConversionTable.referenceRateId] shouldBe Uuid.parse(rateId)
                row[PriceOracleConversionTable.snapshotId] shouldBe null
                row[PriceOracleConversionTable.sourceCount] shouldBe 0
                row[PriceOracleConversionTable.sourcesUsed] shouldBe "manual"
            }
        }

        test("a Saturday confirmation is stored for the trading day, decided by the server") {
            app {
                client.confirm("2026-11-21").status shouldBe HttpStatusCode.OK
                val stored = transaction { PriceOracleReferenceRateTable.selectAll().single() }
                stored[PriceOracleReferenceRateTable.effectiveDate] shouldBe LocalDate(2026, 11, 20)
                stored[PriceOracleReferenceRateTable.anchorAsset] shouldBe AnchorAsset.GOLD_XAU
                stored[PriceOracleReferenceRateTable.donationCurrency] shouldBe "EUR"
            }
        }

        test("a manual confirmation cannot override a qualified stored snapshot") {
            app {
                insertSnapshot(at = "2026-11-20T15:00:00")
                client.confirm("2026-11-20").status shouldBe HttpStatusCode.Conflict
                transaction { PriceOracleReferenceRateTable.selectAll().count() } shouldBe 0L
            }
        }

        test("a manual rate that deviates from the stored price of the day needs the acknowledgement") {
            app {
                insertSnapshot(at = "2026-11-20T15:00:00", price = BigDecimal("2000"), sourceCount = 1)
                client.confirm("2026-11-20", price = "2500").status shouldBe HttpStatusCode.Conflict
                client.confirm("2026-11-20", price = "2500", ack = true).status shouldBe HttpStatusCode.OK
                val stored = transaction { PriceOracleReferenceRateTable.selectAll().single() }
                stored[PriceOracleReferenceRateTable.deviationAcknowledged] shouldBe true
                stored[PriceOracleReferenceRateTable.referenceSnapshotMedian]!!.compareTo(BigDecimal("2000")) shouldBe 0
            }
        }

        test("evidence text: 19 and 2001 characters are refused, whitespace is trimmed, 20 and 2000 pass") {
            app {
                client.confirm("2026-11-20", evidence = "a".repeat(19)).status shouldBe HttpStatusCode.Conflict
                client.confirm("2026-11-20", evidence = "a".repeat(2001)).status shouldBe HttpStatusCode.Conflict
                client.confirm("2026-11-20", evidence = "%20%20" + "a".repeat(19) + "%20").status shouldBe HttpStatusCode.Conflict
                client.confirm("2026-11-20", evidence = "a".repeat(20)).status shouldBe HttpStatusCode.OK
                client.confirm("2026-11-19", evidence = "b".repeat(2000)).status shouldBe HttpStatusCode.OK
            }
        }

        test("an implausible or non-positive price is refused") {
            app {
                client.confirm("2026-11-20", price = "0").status shouldBe HttpStatusCode.Conflict
                client.confirm("2026-11-20", price = "-5").status shouldBe HttpStatusCode.Conflict
                client.confirm("2026-11-20", price = "5").status shouldBe HttpStatusCode.Conflict
                client.confirm("2026-11-20", price = "100000000").status shouldBe HttpStatusCode.Conflict
            }
        }

        test("the audit entry of a confirmation has dates, ids and the evidence LENGTH, never the price or the text") {
            app {
                client.confirm("2026-11-20", evidence = "Geheimer-Beleg-Text-12345").status shouldBe HttpStatusCode.OK
                val rateId = transaction { PriceOracleReferenceRateTable.selectAll().single()[PriceOracleReferenceRateTable.id] }
                val entry =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.PRICE_ORACLE_REFERENCE_RATE) and
                                    (AuditLogEntryTable.entityId eq rateId)
                            }.single()
                    }
                val payload = entry[AuditLogEntryTable.afterSnapshot]!!
                payload.contains("2012.345678") shouldBe false
                payload.contains("Geheimer") shouldBe false
                payload.contains("\"evidenceLength\":25") shouldBe true
                payload.contains("2026-11-20") shouldBe true
            }
        }

        // ---- date validation ----

        test("reference date: today, the future and before 2000 are refused") {
            app {
                client.preview("2026-11-26").status shouldBe HttpStatusCode.Conflict
                client.preview("2026-12-01").status shouldBe HttpStatusCode.Conflict
                client.preview("1999-12-31").status shouldBe HttpStatusCode.Conflict
                client.preview("2026-11-25").status shouldBe HttpStatusCode.OK
            }
        }

        // ---- mode and roles ----

        test("in mode CURRENT the preview and the reference fields are refused, and a normal conversion keeps its old shape") {
            app {
                setConfig(basis = ConversionRateBasis.CURRENT)
                val member = createMember("rd-current@example.org")
                client.preview("2026-11-20").status shouldBe HttpStatusCode.Conflict
                client.confirm("2026-11-20").status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-20", null, null).status shouldBe HttpStatusCode.Conflict
                client.convert(member, null, "2000", null).status shouldBe HttpStatusCode.Conflict
                ledgerCount(member) shouldBe 0L
            }
        }

        test("an anchor other than gold is refused in the reference-date mode") {
            app {
                setConfig(anchor = AnchorAsset.BITCOIN_BTC, basis = ConversionRateBasis.REFERENCE_DATE)
                client.preview("2026-11-20").status shouldBe HttpStatusCode.Conflict
            }
        }

        test("roles: a plain member is refused everywhere, treasurer, board and admin may preview, confirm and convert") {
            app {
                val member = createMember("rd-roles@example.org")
                val snapshotId = insertSnapshot(at = "2026-11-20T15:00:00")
                client.preview("2026-11-20", caller = PLAIN_MEMBER).status shouldBe HttpStatusCode.Forbidden
                client.confirm("2026-11-19", caller = PLAIN_MEMBER).status shouldBe HttpStatusCode.Forbidden
                client.convert(member, "2026-11-20", PRICE.toPlainString(), snapshotId.toString(), caller = PLAIN_MEMBER).status shouldBe
                    HttpStatusCode.Forbidden
                listOf(TREASURER, BOARD, ADMIN).forEach { caller ->
                    client.preview("2026-11-20", caller = caller).status shouldBe HttpStatusCode.OK
                }
                client.confirm("2026-11-19", caller = BOARD).status shouldBe HttpStatusCode.OK
                client.convert(member, "2026-11-20", PRICE.toPlainString(), snapshotId.toString(), caller = ADMIN).status shouldBe
                    HttpStatusCode.OK
            }
        }

        test("a recipient who cannot receive LTR gets nothing") {
            app {
                val applicant = createMember("rd-applicant@example.org", status = MemberStatus.APPLICATION)
                val snapshotId = insertSnapshot(at = "2026-11-20T15:00:00")
                client.convert(applicant, "2026-11-20", PRICE.toPlainString(), snapshotId.toString()).status shouldBe
                    HttpStatusCode.Conflict
                ledgerCount(applicant) shouldBe 0L
                conversionCount(applicant) shouldBe 0L
            }
        }

        test(
            "the switch: only the ADMIN sets it, null leaves it unchanged, the gold anchor is required, leaving gold is refused while it is on",
        ) {
            app {
                setConfig(basis = ConversionRateBasis.CURRENT)
                client.post("/test/update-config?anchor=GOLD_XAU&basis=REFERENCE_DATE") { header("X-Member-Id", TREASURER) }.status shouldBe
                    HttpStatusCode.Forbidden
                currentBasis() shouldBe ConversionRateBasis.CURRENT
                client.post("/test/update-config?anchor=GOLD_XAU&basis=REFERENCE_DATE") { header("X-Member-Id", ADMIN) }.status shouldBe
                    HttpStatusCode.OK
                currentBasis() shouldBe ConversionRateBasis.REFERENCE_DATE
                // an older client sends no value: unchanged
                client.post("/test/update-config?anchor=GOLD_XAU") { header("X-Member-Id", ADMIN) }.status shouldBe HttpStatusCode.OK
                currentBasis() shouldBe ConversionRateBasis.REFERENCE_DATE
                // leaving gold while the mode is on is refused, and the stored anchor stays
                val leaving = client.post("/test/update-config?anchor=FIAT") { header("X-Member-Id", ADMIN) }
                leaving.status shouldBe HttpStatusCode.Conflict
                leaving.bodyAsText().contains("GOLD_XAU") shouldBe true
                transaction { PriceOracleConfigTable.selectAll().single()[PriceOracleConfigTable.anchorAsset] } shouldBe
                    AnchorAsset.GOLD_XAU
                // switch back, then the anchor may change; and the mode cannot be turned on for another anchor
                client.post("/test/update-config?anchor=GOLD_XAU&basis=CURRENT") { header("X-Member-Id", ADMIN) }.status shouldBe
                    HttpStatusCode.OK
                currentBasis() shouldBe ConversionRateBasis.CURRENT
                val wrongAnchor =
                    client.post(
                        "/test/update-config?anchor=BITCOIN_BTC&basis=REFERENCE_DATE",
                    ) { header("X-Member-Id", ADMIN) }
                wrongAnchor.status shouldBe HttpStatusCode.Conflict
                wrongAnchor.bodyAsText().contains("GOLD_XAU") shouldBe true
            }
        }
    })

private fun currentBasis(): ConversionRateBasis =
    transaction { PriceOracleConfigTable.selectAll().single()[PriceOracleConfigTable.conversionRateBasis] }

private fun StatusPagesConfig.installReferenceDateExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
}

private fun Route.registerReferenceDateRoutes(orchestrator: PriceOracleOrchestrator) {
    post("/test/preview") {
        val service = PriceOracleService(call = call, orchestrator = orchestrator)
        val q = call.request.queryParameters
        val r = service.previewReferenceRate(referenceDate = LocalDate.parse(q["date"]!!), memberId = q["memberId"])
        call.respondText(
            listOf(
                r.outcome,
                r.basis,
                r.price?.toPlainString(),
                r.effectiveDate,
                r.fallbackReason,
                r.snapshotId ?: r.referenceRateId,
                r.selfConfirmationBlocked,
                r.sources.size,
                r.legacyMedianHint?.toPlainString(),
            ).joinToString("|"),
        )
    }
    post("/test/confirm") {
        val service = PriceOracleService(call = call, orchestrator = orchestrator)
        val q = call.request.queryParameters
        val r =
            service.confirmReferenceRate(
                ReferenceRateConfirmationInput(
                    referenceDate = LocalDate.parse(q["date"]!!),
                    price = BigDecimal(q["price"]!!),
                    evidenceText = q["evidence"]!!,
                    acknowledgeDeviation = q["ack"] == "true",
                ),
            )
        call.respondText(
            listOf(
                r.outcome,
                r.basis,
                r.price?.toPlainString(),
                r.effectiveDate,
                r.fallbackReason,
                r.snapshotId ?: r.referenceRateId,
                r.selfConfirmationBlocked,
                r.sources.size,
                r.legacyMedianHint?.toPlainString(),
            ).joinToString("|"),
        )
    }
    post("/test/convert") {
        val service = PriceOracleService(call = call, orchestrator = orchestrator)
        val q = call.request.queryParameters
        val r =
            service.convertDonationToLtr(
                DonationConversionInput(
                    memberId = q["memberId"]!!,
                    donationAmount = BigDecimal(q["amount"]!!),
                    referenceDate = q["date"]?.let { LocalDate.parse(it) },
                    // The real client sends the price as an RPC `Decimal`, which travels as a JSON double -- reproduce that wire format.
                    expectedPrice = q["expectedPrice"]?.let { BigDecimal(BigDecimal(it).toDouble().toString()) },
                    expectedRateRefId = q["expectedRef"],
                ),
            )
        call.respondText("${r.id}|${r.ltrMinted}|${r.rateBasis}")
    }
    post("/test/update-config") {
        val service = PriceOracleService(call = call, orchestrator = orchestrator)
        val q = call.request.queryParameters
        val anchor = AnchorAsset.valueOf(q["anchor"]!!)
        val r =
            service.updateOracleConfig(
                PriceOracleConfigInput(
                    anchorAsset = anchor,
                    donationCurrency = "EUR",
                    anchorUnitsPerLtr =
                        when (anchor) {
                            AnchorAsset.GOLD_XAU -> BigDecimal("0.0001")
                            AnchorAsset.FIAT -> BigDecimal.ONE
                            AnchorAsset.BITCOIN_BTC -> BigDecimal("0.000001")
                        },
                    cacheTtlSeconds = 172_800,
                    minQuorum = if (anchor == AnchorAsset.FIAT) 1 else 2,
                    outlierThresholdBps = 300,
                    maxSpreadBps = 1000,
                    conversionRateBasis = q["basis"]?.let { ConversionRateBasis.valueOf(it) },
                ),
            )
        call.respondText("${r.anchorAsset}|${r.conversionRateBasis}")
    }
}
