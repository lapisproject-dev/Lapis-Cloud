package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotSourceTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
import network.lapis.cloud.server.economy.oracle.NO_LEGACY_CUTOFF
import network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator
import network.lapis.cloud.server.economy.oracle.RawValuesCutoff
import network.lapis.cloud.server.economy.oracle.RawValuesCutoffHolder
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.ConversionRateBasis
import network.lapis.cloud.shared.domain.DonationConversionInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import network.lapis.cloud.shared.domain.PriceStatus
import network.lapis.cloud.shared.domain.ReferenceDateSource
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
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

/** "Today" for every scenario: Thursday 2026-11-26 in Berlin. */
private val NOW: Instant = Instant.parse("2026-11-26T10:00:00Z")
private val PRICE = BigDecimal("2012.345678")

/** V1.9.327: the bank payment link of the reference-date conversion, end to end through [PriceOracleService]. */
class PriceOracleBankBookingConversionTest :
    FunSpec({
        val createdMembers = mutableListOf<Uuid>()
        var originalSource: Clock = Clock.System

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            originalSource = ServerClock.source
            ServerClock.source =
                object : Clock {
                    override fun now(): Instant = NOW
                }
        }
        afterSpec { ServerClock.source = originalSource }

        fun setConfig(basis: ConversionRateBasis = ConversionRateBasis.REFERENCE_DATE) {
            transaction {
                PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
                    it[anchorAsset] = AnchorAsset.GOLD_XAU
                    it[donationCurrency] = "EUR"
                    it[anchorUnitsPerLtr] = BigDecimal("0.0001")
                    it[cacheTtlSeconds] = 172_800
                    it[minQuorum] = 2
                    it[outlierThresholdBps] = 300
                    it[maxSpreadBps] = 1000
                    it[conversionRateBasis] = basis
                    it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
        }

        beforeTest {
            RawValuesCutoffHolder.current = RawValuesCutoff { NO_LEGACY_CUTOFF }
            setConfig()
        }
        afterTest {
            transaction {
                PriceOracleConversionTable.deleteWhere { PriceOracleConversionTable.memberId inList createdMembers }
                DonationFixtures.cleanup()
                PriceOracleSnapshotSourceTable.deleteAll()
                PriceOracleSnapshotTable.deleteAll()
                LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList createdMembers }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMembers }
                MemberTable.deleteWhere { MemberTable.id inList createdMembers }
                PriceOracleConfigTable.update({ PriceOracleConfigTable.id eq PRICE_ORACLE_CONFIG_ID }) {
                    it[anchorAsset] = AnchorAsset.BITCOIN_BTC
                    it[anchorUnitsPerLtr] = BigDecimal("0.000001")
                    it[cacheTtlSeconds] = 300
                    it[conversionRateBasis] = ConversionRateBasis.CURRENT
                }
            }
            createdMembers.clear()
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Bankbuchung Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = network.lapis.cloud.shared.domain.MemberStatus.ACTIVE
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

        fun addLine(
            payment: Uuid,
            booking: LocalDate,
            status: BankStatementLineStatus = BankStatementLineStatus.POSTED,
        ) = DonationFixtures.addLine(payment = payment, booking = booking, status = status)

        /** A captured EUR donation of 25.00 with one POSTED bank line on [booking] (unless [booking] is null). */
        fun donation(
            member: Uuid,
            booking: LocalDate? = LocalDate(2026, 11, 20),
            status: PaymentTransactionStatus = PaymentTransactionStatus.CAPTURED,
            intent: PaymentIntent = PaymentIntent.DONATION,
            currency: String = "EUR",
            amount: String = "25.00",
            receivedAt: LocalDateTime = LocalDateTime(2026, 11, 20, 0, 0),
        ): Uuid =
            DonationFixtures.bankedDonation(
                member = member,
                amount = amount,
                bookingDate = booking,
                status = status,
                intent = intent,
                currency = currency,
                receivedAt = receivedAt,
            )

        fun snapshot(at: String = "2026-11-20T15:00:00") {
            val id = Uuid.random()
            val ts = LocalDateTime.parse(at)
            transaction {
                PriceOracleSnapshotTable.insert {
                    it[PriceOracleSnapshotTable.id] = id
                    it[anchorAsset] = AnchorAsset.GOLD_XAU
                    it[donationCurrency] = "EUR"
                    it[medianPrice] = PRICE
                    it[priceStatus] = PriceStatus.LIVE
                    it[sourceCount] = 2
                    it[sourcesUsed] = "goldapi,metalpriceapi"
                    it[priceTimestamp] = ts
                    it[capturedAt] = ts
                }
                repeat(2) { i ->
                    PriceOracleSnapshotSourceTable.insert {
                        it[PriceOracleSnapshotSourceTable.id] = Uuid.random()
                        it[snapshotId] = id
                        it[sourceId] = "src-$i"
                        it[rawPrice] = PRICE
                        it[observedAt] = ts
                        it[included] = true
                    }
                }
            }
        }

        fun mints(member: Uuid): Long =
            transaction {
                LtrLedgerEntryTable
                    .selectAll()
                    .where { (LtrLedgerEntryTable.memberId eq member) and (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.MINT) }
                    .count()
            }

        suspend fun io.ktor.client.HttpClient.candidates(
            member: Uuid,
            caller: String = TREASURER,
        ) = post("/test/candidates?memberId=$member") { header("X-Member-Id", caller) }

        suspend fun io.ktor.client.HttpClient.convert(
            member: Uuid,
            date: String?,
            payment: String?,
            amount: String = "25.00",
            caller: String = TREASURER,
            reason: String? = null,
        ) = post(
            "/test/convert?memberId=$member&amount=$amount" +
                (date?.let { "&date=$it" } ?: "") +
                (payment?.let { "&payment=$it" } ?: "") +
                (reason?.let { "&reason=${it.replace(" ", "%20")}" } ?: ""),
        ) { header("X-Member-Id", caller) }

        fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) { installBankBookingExceptionHandlers() }
                    routing { registerBankBookingRoutes(PriceOracleOrchestrator(sources = emptyList())) }
                }
                block()
            }

        fun conversionRow(member: Uuid) =
            transaction { PriceOracleConversionTable.selectAll().where { PriceOracleConversionTable.memberId eq member }.single() }

        // ---- happy paths ----

        test("the reference date equals the booking day: source BANK_BOOKING, payment id stored, audit entry carries ids and flags only") {
            app {
                snapshot()
                val member = createMember("bb-same@example.org")
                val pay = donation(member, booking = LocalDate(2026, 11, 20))
                val r = client.convert(member, "2026-11-20", pay.toString())
                r.status shouldBe HttpStatusCode.OK
                r.bodyAsText().split("|").drop(1) shouldBe listOf("BANK_BOOKING", pay.toString(), "true")
                val row = conversionRow(member)
                row[PriceOracleConversionTable.paymentTransactionId] shouldBe pay
                row[PriceOracleConversionTable.donationId] shouldNotBe null
                row[PriceOracleConversionTable.referenceDateSource] shouldBe ReferenceDateSource.BANK_BOOKING
                val audit =
                    transaction {
                        AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq row[PriceOracleConversionTable.id] }.single()
                    }
                val payload = audit[AuditLogEntryTable.afterSnapshot]!!
                payload.contains(pay.toString()) shouldBe true
                payload.contains("BANK_BOOKING") shouldBe true
                payload.contains("payer-secret") shouldBe false
                payload.contains("25.00") shouldBe false
                transaction {
                    LtrLedgerEntryTable
                        .selectAll()
                        .where { LtrLedgerEntryTable.id eq row[PriceOracleConversionTable.ltrLedgerEntryId] }
                        .single()[LtrLedgerEntryTable.note]
                }.let {
                    it!!.contains(row[PriceOracleConversionTable.donationId]!!.toString()) shouldBe true
                    it!!.contains("payer-secret") shouldBe false
                }
            }
        }

        test("an earlier reference date than the booking day is allowed and recorded as MANUAL, still linked to the payment") {
            app {
                snapshot(at = "2026-11-19T15:00:00")
                val member = createMember("bb-earlier@example.org")
                val pay = donation(member, booking = LocalDate(2026, 11, 20))
                client.convert(member, "2026-11-19", pay.toString(), reason = "Spende am Vortag zugesagt").status shouldBe HttpStatusCode.OK
                val row = conversionRow(member)
                row[PriceOracleConversionTable.referenceDateSource] shouldBe ReferenceDateSource.MANUAL
                row[PriceOracleConversionTable.paymentTransactionId] shouldBe pay
            }
        }

        test("without a donation nothing is converted in mode REFERENCE_DATE (V174)") {
            app {
                snapshot()
                val member = createMember("bb-none@example.org")
                client.convert(member, "2026-11-20", null).status shouldBe HttpStatusCode.Conflict
                mints(member) shouldBe 0L
            }
        }

        // ---- candidate list ----

        test(
            "the candidate list holds only captured donations of the member in the donation currency with exactly one POSTED bank line, not converted yet",
        ) {
            app {
                val member = createMember("bb-list@example.org")
                val other = createMember("bb-list-other@example.org")
                val good = donation(member, booking = LocalDate(2026, 11, 20), receivedAt = LocalDateTime(2026, 11, 20, 0, 0))
                val newer =
                    donation(member, booking = LocalDate(2026, 11, 24), amount = "30.00", receivedAt = LocalDateTime(2026, 11, 24, 0, 0))
                donation(member, status = PaymentTransactionStatus.PENDING)
                donation(member, status = PaymentTransactionStatus.REFUNDED)
                donation(member, intent = PaymentIntent.CONTRIBUTION)
                donation(member, currency = "USD")
                donation(member, booking = null)
                donation(other)
                val unposted = donation(member, booking = null)
                addLine(unposted, LocalDate(2026, 11, 21), status = BankStatementLineStatus.UNMATCHED)
                val twoLines = donation(member)
                addLine(twoLines, LocalDate(2026, 11, 21))
                val converted = donation(member)
                snapshot()
                client.convert(member, "2026-11-20", converted.toString()).status shouldBe HttpStatusCode.OK

                val body = client.candidates(member).bodyAsText()
                body shouldBe "$newer,2026-11-24,30.00;$good,2026-11-20,25.00"
            }
        }

        test("the candidate list is capped at 50 and works for a member without payments") {
            app {
                val member = createMember("bb-cap@example.org")
                client.candidates(member).bodyAsText() shouldBe ""
                repeat(52) { donation(member, receivedAt = LocalDateTime(2026, 11, 20, 0, 0)) }
                client
                    .candidates(member)
                    .bodyAsText()
                    .split(";")
                    .size shouldBe 50
            }
        }

        test("the candidate list is refused in mode CURRENT and for a plain member") {
            app {
                val member = createMember("bb-mode@example.org")
                client.candidates(member, caller = PLAIN_MEMBER).status shouldBe HttpStatusCode.Forbidden
                client.candidates(member, caller = BOARD).status shouldBe HttpStatusCode.OK
                client.candidates(member, caller = ADMIN).status shouldBe HttpStatusCode.OK
                setConfig(ConversionRateBasis.CURRENT)
                client.candidates(member).status shouldBe HttpStatusCode.Conflict
            }
        }

        // ---- errors and tampering ----

        test("the booking day of today is no reference date: the whole day is not over, nothing is minted") {
            app {
                val member = createMember("bb-today@example.org")
                val pay = donation(member, booking = LocalDate(2026, 11, 26))
                client.convert(member, "2026-11-26", pay.toString()).status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-27", pay.toString()).status shouldBe HttpStatusCode.Conflict
                mints(member) shouldBe 0L
            }
        }

        test("a payment that is not a captured donation in the donation currency with a bank line is refused") {
            app {
                snapshot()
                val member = createMember("bb-bad@example.org")
                listOf(
                    donation(member, status = PaymentTransactionStatus.PENDING),
                    donation(member, status = PaymentTransactionStatus.REFUNDED),
                    donation(member, status = PaymentTransactionStatus.DISPUTED),
                    donation(member, intent = PaymentIntent.CONTRIBUTION),
                    donation(member, currency = "USD"),
                    donation(member, booking = null),
                ).forEach { pay ->
                    client.convert(member, "2026-11-20", pay.toString()).status shouldBe HttpStatusCode.Conflict
                }
                mints(member) shouldBe 0L
            }
        }

        test(
            "tampering: the payment of another member, a different amount, an unknown id and a malformed id are refused, nothing is minted",
        ) {
            app {
                snapshot()
                val member = createMember("bb-tamper@example.org")
                val other = createMember("bb-tamper-other@example.org")
                val foreign = donation(other)
                val own = donation(member)
                val foreignResponse = client.convert(member, "2026-11-20", foreign.toString())
                foreignResponse.status shouldBe HttpStatusCode.Conflict
                foreignResponse.bodyAsText().contains("25.00") shouldBe false
                client.convert(member, "2026-11-20", own.toString(), amount = "25.01").status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-20", own.toString(), amount = "24.99").status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-20", Uuid.random().toString()).status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-20", "not-a-uuid").status shouldBe HttpStatusCode.Conflict
                mints(member) shouldBe 0L
                mints(other) shouldBe 0L
            }
        }

        test("a payment is converted once: the second try is refused with one MINT only") {
            app {
                snapshot()
                val member = createMember("bb-twice@example.org")
                val pay = donation(member)
                client.convert(member, "2026-11-20", pay.toString()).status shouldBe HttpStatusCode.OK
                client.convert(member, "2026-11-20", pay.toString()).status shouldBe HttpStatusCode.Conflict
                mints(member) shouldBe 1L
            }
        }

        test("two simultaneous conversions of the same payment: exactly one wins, one MINT") {
            app {
                snapshot()
                val member = createMember("bb-race@example.org")
                val pay = donation(member)
                val statuses =
                    coroutineScope {
                        listOf(
                            async { client.convert(member, "2026-11-20", pay.toString()).status },
                            async { client.convert(member, "2026-11-20", pay.toString()).status },
                        ).awaitAll()
                    }
                statuses.count { it == HttpStatusCode.OK } shouldBe 1
                statuses.count { it == HttpStatusCode.Conflict } shouldBe 1
                mints(member) shouldBe 1L
            }
        }

        test("a payment id in mode CURRENT is refused and mints nothing") {
            app {
                val member = createMember("bb-current@example.org")
                val pay = donation(member)
                setConfig(ConversionRateBasis.CURRENT)
                client.convert(member, null, pay.toString()).status shouldBe HttpStatusCode.Conflict
                mints(member) shouldBe 0L
            }
        }

        test("roles: a plain member may not convert, treasurer, board and admin may") {
            app {
                snapshot()
                val member = createMember("bb-roles@example.org")
                val pay = donation(member)
                client.convert(member, "2026-11-20", pay.toString(), caller = PLAIN_MEMBER).status shouldBe HttpStatusCode.Forbidden
                client.convert(member, "2026-11-20", pay.toString(), caller = BOARD).status shouldBe HttpStatusCode.OK
                mints(member) shouldBe 1L
            }
        }
    })

private fun StatusPagesConfig.installBankBookingExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
}

private fun Route.registerBankBookingRoutes(orchestrator: PriceOracleOrchestrator) {
    post("/test/candidates") {
        val service = PriceOracleService(call = call, orchestrator = orchestrator)
        val list = service.listDonationPaymentCandidates(call.request.queryParameters["memberId"]!!)
        call.respondText(list.joinToString(";") { "${it.id},${it.bookingDate},${it.amount.toPlainString()}" })
    }
    post("/test/convert") {
        val service = PriceOracleService(call = call, orchestrator = orchestrator)
        val q = call.request.queryParameters
        val date = q["date"]?.let { LocalDate.parse(it) }
        // The preview first (as the client does), then the conversion bound to it. Without a date there is no preview (mode CURRENT).
        val preview = date?.let { service.previewReferenceRate(referenceDate = it, memberId = q["memberId"]) }
        val r =
            service.convertDonationToLtr(
                DonationConversionInput(
                    memberId = q["memberId"]!!,
                    donationAmount = BigDecimal(q["amount"]!!),
                    referenceDate = date,
                    expectedPrice = preview?.price?.let { BigDecimal(BigDecimal(it.toPlainString()).toDouble().toString()) },
                    expectedRateRefId = preview?.snapshotId ?: preview?.referenceRateId,
                    paymentTransactionId = q["payment"],
                    referenceDateOverrideReason = q["reason"],
                ),
            )
        call.respondText("${r.id}|${r.referenceDateSource}|${r.paymentTransactionId}|${r.rawValuesStored}")
    }
}
