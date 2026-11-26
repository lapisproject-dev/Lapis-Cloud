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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.DonationTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.db.generated.PriceOracleConfigTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.server.db.generated.PriceOracleReferenceRateTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotSourceTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
import network.lapis.cloud.server.economy.oracle.DonationStore
import network.lapis.cloud.server.economy.oracle.NO_LEGACY_CUTOFF
import network.lapis.cloud.server.economy.oracle.PriceOracleOrchestrator
import network.lapis.cloud.server.economy.oracle.RawValuesCutoff
import network.lapis.cloud.server.economy.oracle.RawValuesCutoffHolder
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConversionRateBasis
import network.lapis.cloud.shared.domain.DonationConversionInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
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

/** A reason that is long enough, free of IBANs, with umlauts (counted as characters, not bytes). */
private const val REASON = "Zusage erfolgte schon am Vortag mündlich"

/** V1.9.328: the donation entity and the justification of a manual reference date, end to end through [PriceOracleService]. */
class PriceOracleDonationConversionTest :
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
                PriceOracleReferenceRateTable.deleteAll()
            }
            DonationFixtures.cleanup()
            transaction {
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
                    it[displayName] = "Spende Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
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

        fun snapshot(at: String) {
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

        /** Stored the way the V174 backfill writes it: id = payment id, no actor. */
        fun backfilledDonation(
            payment: Uuid,
            member: Uuid,
            booking: LocalDate = LocalDate(2026, 11, 20),
        ): Uuid {
            transaction {
                // The fixture already created the donation the way the entry points do; replace it by the backfill-shaped one (id = payment id).
                DonationTable.deleteWhere { DonationTable.paymentTransactionId eq payment }
                DonationTable.insert {
                    it[id] = payment
                    it[paymentTransactionId] = payment
                    it[memberId] = member
                    it[amount] = BigDecimal("25.00")
                    it[currency] = "EUR"
                    it[bookingDate] = booking
                    it[createdAt] = LocalDateTime(2026, 11, 20, 0, 0)
                    it[createdBy] = null
                }
            }
            return payment
        }

        fun mints(member: Uuid): Long =
            transaction {
                LtrLedgerEntryTable
                    .selectAll()
                    .where { (LtrLedgerEntryTable.memberId eq member) and (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.MINT) }
                    .count()
            }

        fun donationCount(member: Uuid): Long = transaction { DonationTable.selectAll().where { DonationTable.memberId eq member }.count() }

        fun conversionCount(member: Uuid): Long =
            transaction { PriceOracleConversionTable.selectAll().where { PriceOracleConversionTable.memberId eq member }.count() }

        fun conversionRow(member: Uuid) =
            transaction { PriceOracleConversionTable.selectAll().where { PriceOracleConversionTable.memberId eq member }.single() }

        suspend fun io.ktor.client.HttpClient.convert(
            member: Uuid,
            date: String?,
            payment: String? = null,
            donation: String? = null,
            reason: String? = null,
            amount: String = "25.00",
            caller: String = TREASURER,
        ) = post(
            "/test/convert?memberId=$member&amount=$amount" +
                (date?.let { "&date=$it" } ?: "") +
                (payment?.let { "&payment=$it" } ?: "") +
                (donation?.let { "&donation=$it" } ?: "") +
                (reason?.let { "&reason=${java.net.URLEncoder.encode(it, Charsets.UTF_8)}" } ?: ""),
        ) { header("X-Member-Id", caller) }

        fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) { installDonationExceptionHandlers() }
                    routing { registerDonationRoutes(PriceOracleOrchestrator(sources = emptyList())) }
                }
                block()
            }

        // ---- happy paths ----

        test("the booking day as reference date: a donation row is created for the actor, a conversion, a MINT and both audit entries") {
            app {
                snapshot("2026-11-20T15:00:00")
                val member = createMember("dc-happy@example.org")
                // The lazy path: no donation row yet (a payment from before V175 that nothing backfilled); the conversion creates it for the actor.
                val pay = DonationFixtures.bankedDonation(member = member, bookingDate = LocalDate(2026, 11, 20), withDonation = false)
                val r = client.convert(member, "2026-11-20", payment = pay.toString())
                r.status shouldBe HttpStatusCode.OK
                val donation = transaction { DonationTable.selectAll().where { DonationTable.memberId eq member }.single() }
                donation[DonationTable.paymentTransactionId] shouldBe pay
                donation[DonationTable.bookingDate] shouldBe LocalDate(2026, 11, 20)
                donation[DonationTable.createdBy] shouldBe Uuid.parse(TREASURER)
                donation[DonationTable.amount].compareTo(BigDecimal("25.00")) shouldBe 0
                val conv = conversionRow(member)
                conv[PriceOracleConversionTable.donationId] shouldBe donation[DonationTable.id]
                conv[PriceOracleConversionTable.referenceDateSource] shouldBe ReferenceDateSource.BANK_BOOKING
                conv[PriceOracleConversionTable.referenceDateOverrideReason] shouldBe null
                mints(member) shouldBe 1L
                transaction {
                    val donationAudit =
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.DONATION) and
                                    (AuditLogEntryTable.entityId eq donation[DonationTable.id])
                            }.single()
                    donationAudit[AuditLogEntryTable.action] shouldBe AuditAction.CREATE
                    val payload = donationAudit[AuditLogEntryTable.afterSnapshot]!!
                    payload.contains("25.00") shouldBe false
                    payload.contains("payer-secret") shouldBe false
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityType eq AuditEntityType.PRICE_ORACLE_CONVERSION) and
                                (AuditLogEntryTable.entityId eq conv[PriceOracleConversionTable.id])
                        }.count() shouldBe 1L
                }
            }
        }

        test("a different date needs a reason: MANUAL with 10 characters (umlauts) and with 500 characters") {
            app {
                snapshot("2026-11-19T15:00:00")
                val a = createMember("dc-r10@example.org")
                val b = createMember("dc-r500@example.org")
                val payA = DonationFixtures.bankedDonation(member = a)
                val payB = DonationFixtures.bankedDonation(member = b)
                client.convert(a, "2026-11-19", payment = payA.toString(), reason = "äöüäöüäöüä").status shouldBe HttpStatusCode.OK
                conversionRow(a)[PriceOracleConversionTable.referenceDateOverrideReason] shouldBe "äöüäöüäöüä"
                conversionRow(a)[PriceOracleConversionTable.referenceDateSource] shouldBe ReferenceDateSource.MANUAL
                client.convert(b, "2026-11-19", payment = payB.toString(), reason = "y".repeat(500)).status shouldBe HttpStatusCode.OK
                conversionRow(b)[PriceOracleConversionTable.referenceDateOverrideReason]!!.length shouldBe 500
            }
        }

        test("a stored (backfill-like) donation is converted by donationId alone; the candidate list shows its id") {
            app {
                snapshot("2026-11-20T15:00:00")
                val member = createMember("dc-backfill@example.org")
                val pay = DonationFixtures.bankedDonation(member = member)
                val donationId = backfilledDonation(pay, member)
                val list = client.post("/test/candidates?memberId=$member") { header("X-Member-Id", TREASURER) }.bodyAsText()
                list shouldBe "$pay,$donationId"
                client.convert(member, "2026-11-20", donation = donationId.toString()).status shouldBe HttpStatusCode.OK
                conversionRow(member)[PriceOracleConversionTable.donationId] shouldBe donationId
                // the audit log has no DONATION/CREATE entry for a donation this call did not create
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityType eq AuditEntityType.DONATION) and (AuditLogEntryTable.entityId eq donationId)
                        }.count()
                } shouldBe 0L
            }
        }

        // ---- the reason ----

        test(
            "the reason: missing, too short, too long, blank, with an IBAN, or given for the booking day is refused; a wrong checksum is no IBAN",
        ) {
            app {
                snapshot("2026-11-19T15:00:00")
                snapshot("2026-11-20T15:00:00")
                val member = createMember("dc-reason@example.org")
                val pay = DonationFixtures.bankedDonation(member = member)
                val p = pay.toString()
                client.convert(member, "2026-11-19", payment = p).status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-19", payment = p, reason = "123456789").status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-19", payment = p, reason = "z".repeat(501)).status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-19", payment = p, reason = "              ").status shouldBe HttpStatusCode.Conflict
                val iban = client.convert(member, "2026-11-19", payment = p, reason = "Bitte an DE02 1203 0000 0000 2020 51 zurueck")
                iban.status shouldBe HttpStatusCode.Conflict
                iban.bodyAsText() shouldBe "The reason must not contain an IBAN"
                client.convert(member, "2026-11-20", payment = p, reason = REASON).status shouldBe HttpStatusCode.Conflict
                mints(member) shouldBe 0L
                // the donation row exists since the payment came into being (V1.9.329); a refused conversion changes nothing
                donationCount(member) shouldBe 1L
                conversionCount(member) shouldBe 0L
                client.convert(member, "2026-11-19", payment = p, reason = "Kontonr DE00 1203 0000 0000 2020 51 falsch").status shouldBe
                    HttpStatusCode.OK
            }
        }

        test("neither the audit entry nor the ledger note carries the reason text; the audit entry has its length and digest") {
            app {
                snapshot("2026-11-19T15:00:00")
                val member = createMember("dc-leak@example.org")
                val pay = DonationFixtures.bankedDonation(member = member)
                client.convert(member, "2026-11-19", payment = pay.toString(), reason = REASON).status shouldBe HttpStatusCode.OK
                val conv = conversionRow(member)
                val audit =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityId eq conv[PriceOracleConversionTable.id] }
                            .single()[AuditLogEntryTable.afterSnapshot]!!
                    }
                audit.contains("Zusage") shouldBe false
                audit.contains("overrideReasonLength") shouldBe true
                audit.contains("overrideReasonSha256") shouldBe true
                audit.contains("DE02") shouldBe false
                val note =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where { LtrLedgerEntryTable.id eq conv[PriceOracleConversionTable.ltrLedgerEntryId] }
                            .single()[LtrLedgerEntryTable.note]!!
                    }
                note.contains("Zusage") shouldBe false
                note.contains("payer-secret") shouldBe false
            }
        }

        // ---- input errors ----

        test("today and the future are no reference dates; a missing donation, a mismatching pair and malformed ids are refused") {
            app {
                snapshot("2026-11-20T15:00:00")
                val member = createMember("dc-input@example.org")
                val pay = DonationFixtures.bankedDonation(member = member, bookingDate = LocalDate(2026, 11, 26))
                client.convert(member, "2026-11-26", payment = pay.toString()).status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-27", payment = pay.toString(), reason = REASON).status shouldBe HttpStatusCode.Conflict
                val good = DonationFixtures.bankedDonation(member = member)
                val other = DonationFixtures.bankedDonation(member = member)
                val donation = backfilledDonation(good, member)
                client.convert(member, "2026-11-20").status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-20", payment = other.toString(), donation = donation.toString()).status shouldBe
                    HttpStatusCode.Conflict
                client.convert(member, "2026-11-20", donation = "not-a-uuid").status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-20", payment = "not-a-uuid").status shouldBe HttpStatusCode.Conflict
                mints(member) shouldBe 0L
            }
        }

        test("a stored donation whose bank booking changed since (second POSTED line) is no longer convertible") {
            app {
                snapshot("2026-11-20T15:00:00")
                val member = createMember("dc-changed@example.org")
                val pay = DonationFixtures.bankedDonation(member = member)
                val donation = backfilledDonation(pay, member)
                DonationFixtures.addLine(payment = pay, booking = LocalDate(2026, 11, 21))
                client.convert(member, "2026-11-20", donation = donation.toString()).status shouldBe HttpStatusCode.Conflict
                val moved = DonationFixtures.bankedDonation(member = member, bookingDate = LocalDate(2026, 11, 21))
                val movedDonation = backfilledDonation(moved, member, booking = LocalDate(2026, 11, 20))
                client.convert(member, "2026-11-20", donation = movedDonation.toString()).status shouldBe HttpStatusCode.Conflict
                mints(member) shouldBe 0L
            }
        }

        test("the candidate list leaves out a donation whose stored booking day no longer matches exactly one POSTED bank line") {
            app {
                snapshot("2026-11-20T15:00:00")
                val member = createMember("dc-stale-candidate@example.org")
                val pay = DonationFixtures.bankedDonation(member = member)
                backfilledDonation(pay, member)
                DonationFixtures.addLine(payment = pay, booking = LocalDate(2026, 11, 21))
                client.post("/test/candidates?memberId=$member") { header("X-Member-Id", TREASURER) }.bodyAsText() shouldBe ""
            }
        }

        test("a conversion by payment id of an unposted payment with a reconciliation note is refused and leaves no donation row") {
            app {
                snapshot("2026-11-20T15:00:00")
                val member = createMember("dc-mismatch@example.org")
                val pay = DonationFixtures.bankedDonation(member = member, bookingDate = null, withDonation = false)
                transaction {
                    PaymentTransactionTable.update({ PaymentTransactionTable.id eq pay }) {
                        it[reconciliationNote] = "Amount mismatch: session 25.00, received 2.50"
                    }
                }
                client.convert(member, "2026-11-19", payment = pay.toString(), reason = REASON).status shouldBe HttpStatusCode.Conflict
                donationCount(member) shouldBe 0L
                conversionCount(member) shouldBe 0L
                mints(member) shouldBe 0L
            }
        }

        // ---- tampering and roles ----

        test("a donation of another member and an unknown donation give the same neutral refusal; a plain member is forbidden") {
            app {
                snapshot("2026-11-20T15:00:00")
                val member = createMember("dc-tamper@example.org")
                val other = createMember("dc-tamper-other@example.org")
                val foreignPay = DonationFixtures.bankedDonation(member = other)
                val foreign = backfilledDonation(foreignPay, other)
                val foreignResponse = client.convert(member, "2026-11-20", donation = foreign.toString())
                val unknownResponse = client.convert(member, "2026-11-20", donation = Uuid.random().toString())
                foreignResponse.status shouldBe HttpStatusCode.Conflict
                unknownResponse.status shouldBe HttpStatusCode.Conflict
                foreignResponse.bodyAsText() shouldBe unknownResponse.bodyAsText()
                val own = DonationFixtures.bankedDonation(member = member)
                client.convert(member, "2026-11-20", payment = foreignPay.toString()).bodyAsText() shouldBe unknownResponse.bodyAsText()
                client.convert(member, "2026-11-20", payment = own.toString(), amount = "25.01").status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-20", payment = own.toString(), caller = PLAIN_MEMBER).status shouldBe
                    HttpStatusCode.Forbidden
                mints(member) shouldBe 0L
                mints(other) shouldBe 0L
            }
        }

        test("a member must not receive LTR converted at a rate he confirmed himself") {
            app {
                val member = createMember("dc-self@example.org")
                val pay = DonationFixtures.bankedDonation(member = member, bookingDate = LocalDate(2026, 11, 19))
                transaction {
                    PriceOracleReferenceRateTable.insert {
                        it[id] = Uuid.random()
                        it[anchorAsset] = AnchorAsset.GOLD_XAU
                        it[donationCurrency] = "EUR"
                        it[effectiveDate] = LocalDate(2026, 11, 19)
                        it[price] = PRICE
                        it[evidenceText] = "Fixing LBMA PM, Quelle: Bank-Auszug vom Tag"
                        it[confirmedBy] = member
                        it[confirmedAt] = LocalDateTime(2026, 11, 20, 9, 0)
                        it[referenceSnapshotMedian] = null
                        it[deviationAcknowledged] = false
                    }
                }
                client.convert(member, "2026-11-19", payment = pay.toString()).status shouldBe HttpStatusCode.Forbidden
                mints(member) shouldBe 0L
                conversionCount(member) shouldBe 0L
            }
        }

        // ---- once only ----

        test(
            "a donation is converted once, whichever id the second try uses; a failed conversion leaves no lazily created donation row behind",
        ) {
            app {
                snapshot("2026-11-20T15:00:00")
                val member = createMember("dc-twice@example.org")
                val pay = DonationFixtures.bankedDonation(member = member)
                client.convert(member, "2026-11-20", payment = pay.toString()).status shouldBe HttpStatusCode.OK
                val donation =
                    transaction { DonationTable.selectAll().where { DonationTable.memberId eq member }.single()[DonationTable.id] }
                val byPayment = client.convert(member, "2026-11-20", payment = pay.toString())
                byPayment.status shouldBe HttpStatusCode.Conflict
                byPayment.bodyAsText() shouldBe "This donation was already converted"
                client.convert(member, "2026-11-20", donation = donation.toString()).bodyAsText() shouldBe
                    "This donation was already converted"
                mints(member) shouldBe 1L
                conversionCount(member) shouldBe 1L
                donationCount(member) shouldBe 1L

                // no rate for the day -> the whole transaction, including a donation row it would have created, is rolled back
                val fresh = createMember("dc-norate@example.org")
                val freshPay = DonationFixtures.bankedDonation(member = fresh, bookingDate = LocalDate(2026, 11, 3), withDonation = false)
                client.convert(fresh, "2026-11-03", payment = freshPay.toString()).status shouldBe HttpStatusCode.Conflict
                donationCount(fresh) shouldBe 0L
            }
        }

        test("two simultaneous conversions of one payment: exactly one donation, one conversion, one MINT") {
            app {
                snapshot("2026-11-20T15:00:00")
                val member = createMember("dc-race1@example.org")
                val pay = DonationFixtures.bankedDonation(member = member)
                val statuses =
                    coroutineScope {
                        listOf(
                            async { client.convert(member, "2026-11-20", payment = pay.toString()).status },
                            async { client.convert(member, "2026-11-20", payment = pay.toString()).status },
                        ).awaitAll()
                    }
                statuses.count { it == HttpStatusCode.OK } shouldBe 1
                statuses.count { it == HttpStatusCode.Conflict } shouldBe 1
                mints(member) shouldBe 1L
                conversionCount(member) shouldBe 1L
                donationCount(member) shouldBe 1L
            }
        }

        test("two simultaneous conversions through both entries (donationId and paymentTransactionId): exactly one wins") {
            app {
                snapshot("2026-11-20T15:00:00")
                val member = createMember("dc-race2@example.org")
                val pay = DonationFixtures.bankedDonation(member = member)
                val donation = backfilledDonation(pay, member)
                val statuses =
                    coroutineScope {
                        listOf(
                            async { client.convert(member, "2026-11-20", donation = donation.toString()).status },
                            async { client.convert(member, "2026-11-20", payment = pay.toString()).status },
                        ).awaitAll()
                    }
                statuses.count { it == HttpStatusCode.OK } shouldBe 1
                statuses.count { it == HttpStatusCode.Conflict } shouldBe 1
                mints(member) shouldBe 1L
                conversionCount(member) shouldBe 1L
                donationCount(member) shouldBe 1L
            }
        }

        // ---- the constraints behind the service ----

        test("direct inserts that break the donation link are rejected by the database") {
            val member = createMember("dc-sql@example.org")
            val pay = DonationFixtures.bankedDonation(member = member)
            val donation = backfilledDonation(pay, member)
            snapshot("2026-11-20T15:00:00")
            val snapshotId = transaction { PriceOracleSnapshotTable.selectAll().single()[PriceOracleSnapshotTable.id] }

            fun insertConversion(
                amount: String = "25.00",
                withDonation: Boolean = true,
                withPayment: Boolean = true,
                source: ReferenceDateSource = ReferenceDateSource.BANK_BOOKING,
                reason: String? = null,
                owner: Uuid = member,
            ) = runCatching {
                transaction {
                    PriceOracleConversionTable.insert {
                        it[id] = Uuid.random()
                        it[memberId] = owner
                        it[donationAmount] = BigDecimal(amount)
                        it[donationCurrency] = "EUR"
                        it[anchorAsset] = AnchorAsset.GOLD_XAU
                        it[anchorPrice] = PRICE
                        it[anchorUnitsPerLtr] = BigDecimal("0.0001")
                        it[ltrMinted] = BigDecimal("1.00")
                        it[priceStatus] = PriceStatus.LIVE
                        it[sourceCount] = 2
                        it[sourcesUsed] = "a,b"
                        it[priceTimestamp] = LocalDateTime(2026, 11, 20, 15, 0)
                        it[ltrLedgerEntryId] = Uuid.random()
                        it[createdAt] = LocalDateTime(2026, 11, 26, 10, 0)
                        it[rateBasis] = network.lapis.cloud.shared.domain.ReferenceRateBasis.SNAPSHOT
                        it[referenceDate] = LocalDate(2026, 11, 20)
                        it[effectiveDate] = LocalDate(2026, 11, 20)
                        it[fallbackReason] = network.lapis.cloud.shared.domain.ReferenceDateFallback.NONE
                        it[PriceOracleConversionTable.snapshotId] = snapshotId
                        it[quorumRequired] = 2
                        it[referenceDateSource] = source
                        it[paymentTransactionId] = if (withPayment) pay else null
                        it[PriceOracleConversionTable.donationId] = if (withDonation) donation else null
                        it[referenceDateOverrideReason] = reason
                    }
                }
            }
            // a deviating amount, a deviating member, a donation without its payment, MANUAL without a reason, BANK_BOOKING with one: all refused
            insertConversion(amount = "26.00").isFailure shouldBe true
            insertConversion(owner = createMember("dc-sql-other@example.org")).isFailure shouldBe true
            insertConversion(withPayment = false).isFailure shouldBe true
            insertConversion(source = ReferenceDateSource.MANUAL).isFailure shouldBe true
            insertConversion(reason = REASON).isFailure shouldBe true
            insertConversion(source = ReferenceDateSource.MANUAL, reason = "kurz").isFailure shouldBe true
            // the matching row is accepted, a second conversion of the same donation is not
            insertConversion().isSuccess shouldBe true
            insertConversion().isFailure shouldBe true
            conversionCount(member) shouldBe 1L
        }

        // ---- V1.9.329: a donation for every captured DONATION payment ----

        test("a PayPal donation without a bank booking converts with a manual date and a reason; the source is MANUAL") {
            app {
                snapshot("2026-11-19T15:00:00")
                val member = createMember("dc-paypal@example.org")
                val pay = DonationFixtures.bankedDonation(member = member, bookingDate = null, provider = PaymentProvider.PAYPAL)
                val donation = transaction { DonationTable.selectAll().where { DonationTable.paymentTransactionId eq pay }.single() }
                donation[DonationTable.bookingDate] shouldBe null
                client.post("/test/candidates?memberId=$member") { header("X-Member-Id", TREASURER) }.bodyAsText() shouldBe
                    "$pay,${donation[DonationTable.id]}"
                client.convert(member, "2026-11-19", payment = pay.toString(), reason = REASON).status shouldBe HttpStatusCode.OK
                val conv = conversionRow(member)
                conv[PriceOracleConversionTable.referenceDateSource] shouldBe ReferenceDateSource.MANUAL
                conv[PriceOracleConversionTable.referenceDateOverrideReason] shouldBe REASON
                conv[PriceOracleConversionTable.donationId] shouldBe donation[DonationTable.id]
                mints(member) shouldBe 1L
                // once only, by payment id and by donation id
                client.convert(member, "2026-11-19", payment = pay.toString(), reason = REASON).status shouldBe HttpStatusCode.Conflict
                client.convert(member, "2026-11-19", donation = donation[DonationTable.id].toString(), reason = REASON).status shouldBe
                    HttpStatusCode.Conflict
                mints(member) shouldBe 1L
            }
        }

        test("without a booking day the reason is always required: missing, short, long and with an IBAN write nothing") {
            app {
                snapshot("2026-11-19T15:00:00")
                val member = createMember("dc-paypal-reason@example.org")
                val pay = DonationFixtures.bankedDonation(member = member, bookingDate = null, provider = PaymentProvider.STRIPE)
                val bad = listOf(null, "kurz", "x".repeat(501), "   ", "Siehe DE89 3704 0044 0532 0130 00 bitte")
                bad.forEach {
                    client.convert(member, "2026-11-19", payment = pay.toString(), reason = it).status shouldBe HttpStatusCode.Conflict
                }
                conversionCount(member) shouldBe 0L
                mints(member) shouldBe 0L
            }
        }

        test(
            "the source is never taken from the client: with no booking day every date is MANUAL, with one only the booking day is BANK_BOOKING",
        ) {
            app {
                snapshot("2026-11-20T15:00:00")
                val a = createMember("dc-src-a@example.org")
                val b = createMember("dc-src-b@example.org")
                val noBooking = DonationFixtures.bankedDonation(member = a, bookingDate = null, provider = PaymentProvider.PAYPAL)
                client.convert(a, "2026-11-20", payment = noBooking.toString()).status shouldBe HttpStatusCode.Conflict
                client.convert(a, "2026-11-20", payment = noBooking.toString(), reason = REASON).status shouldBe HttpStatusCode.OK
                conversionRow(a)[PriceOracleConversionTable.referenceDateSource] shouldBe ReferenceDateSource.MANUAL
                val banked = DonationFixtures.bankedDonation(member = b, bookingDate = LocalDate(2026, 11, 20))
                client.convert(b, "2026-11-20", payment = banked.toString(), reason = REASON).status shouldBe HttpStatusCode.Conflict
                client.convert(b, "2026-11-20", payment = banked.toString()).status shouldBe HttpStatusCode.OK
                conversionRow(b)[PriceOracleConversionTable.referenceDateSource] shouldBe ReferenceDateSource.BANK_BOOKING
            }
        }

        test("an anonymous donation, another member's donation, a refunded one and a wrong currency are refused alike") {
            app {
                snapshot("2026-11-19T15:00:00")
                val member = createMember("dc-tamper@example.org")
                val other = createMember("dc-tamper-other@example.org")
                val anonymous =
                    DonationFixtures.bankedDonation(
                        member = member,
                        bookingDate = null,
                        provider = PaymentProvider.STRIPE,
                        memberOrNull = true,
                    )
                val foreign = DonationFixtures.bankedDonation(member = other, bookingDate = null, provider = PaymentProvider.PAYPAL)
                val refunded =
                    DonationFixtures.bankedDonation(member = member, bookingDate = null, status = PaymentTransactionStatus.REFUNDED)
                val usd = DonationFixtures.bankedDonation(member = member, bookingDate = null, currency = "USD")
                val messages =
                    listOf(anonymous, foreign, refunded, usd).map {
                        val r = client.convert(member, "2026-11-19", payment = it.toString(), reason = REASON)
                        r.status shouldBe HttpStatusCode.Conflict
                        r.bodyAsText()
                    }
                messages.toSet().size shouldBe 1
                // the anonymous donation exists, but belongs to nobody
                transaction {
                    DonationTable.selectAll().where { DonationTable.paymentTransactionId eq anonymous }.single()[DonationTable.memberId]
                } shouldBe
                    null
                // a non-treasurer is refused
                client.convert(member, "2026-11-19", payment = foreign.toString(), reason = REASON, caller = PLAIN_MEMBER).status shouldBe
                    HttpStatusCode.Forbidden
                mints(member) shouldBe 0L
                conversionCount(member) shouldBe 0L
            }
        }

        test("the database refuses a conversion that points at an anonymous donation") {
            app {
                snapshot("2026-11-19T15:00:00")
                val member = createMember("dc-anon-sql@example.org")
                val anonymous =
                    DonationFixtures.bankedDonation(
                        member = member,
                        bookingDate = null,
                        provider = PaymentProvider.STRIPE,
                        memberOrNull = true,
                    )
                val donationId =
                    transaction {
                        DonationTable.selectAll().where { DonationTable.paymentTransactionId eq anonymous }.single()[DonationTable.id]
                    }
                val snapshotId = transaction { PriceOracleSnapshotTable.selectAll().first()[PriceOracleSnapshotTable.id] }
                val result =
                    runCatching {
                        transaction {
                            PriceOracleConversionTable.insert {
                                it[id] = Uuid.random()
                                it[PriceOracleConversionTable.memberId] = member
                                it[donationAmount] = BigDecimal("25.00")
                                it[donationCurrency] = "EUR"
                                it[anchorAsset] = AnchorAsset.GOLD_XAU
                                it[anchorPrice] = PRICE
                                it[anchorUnitsPerLtr] = BigDecimal("0.0001")
                                it[ltrMinted] = BigDecimal("1.00")
                                it[priceStatus] = PriceStatus.LIVE
                                it[sourceCount] = 2
                                it[sourcesUsed] = "a,b"
                                it[priceTimestamp] = LocalDateTime(2026, 11, 19, 15, 0)
                                it[ltrLedgerEntryId] = Uuid.random()
                                it[createdAt] = LocalDateTime(2026, 11, 26, 10, 0)
                                it[rateBasis] = network.lapis.cloud.shared.domain.ReferenceRateBasis.SNAPSHOT
                                it[referenceDate] = LocalDate(2026, 11, 19)
                                it[effectiveDate] = LocalDate(2026, 11, 19)
                                it[fallbackReason] = network.lapis.cloud.shared.domain.ReferenceDateFallback.NONE
                                it[PriceOracleConversionTable.snapshotId] = snapshotId
                                it[quorumRequired] = 2
                                it[referenceDateSource] = ReferenceDateSource.MANUAL
                                it[paymentTransactionId] = anonymous
                                it[PriceOracleConversionTable.donationId] = donationId
                                it[referenceDateOverrideReason] = REASON
                            }
                        }
                    }
                result.isFailure shouldBe true
            }
        }

        test("ensureForPayment is idempotent and writes nothing for a payment that is no captured positive donation") {
            val member = createMember("dc-ensure@example.org")
            val pay = DonationFixtures.bankedDonation(member = member, bookingDate = null, withDonation = false)
            val pending = DonationFixtures.bankedDonation(member = member, status = PaymentTransactionStatus.PENDING, withDonation = false)
            val contribution = DonationFixtures.bankedDonation(member = member, intent = PaymentIntent.CONTRIBUTION, withDonation = false)
            val now = LocalDateTime(2026, 11, 26, 10, 0)
            val first = transaction { DonationStore.ensureForPayment(paymentId = pay, createdBy = null, now = now) }
            val second = transaction { DonationStore.ensureForPayment(paymentId = pay, createdBy = null, now = now) }
            first!!.second shouldBe true
            second!!.second shouldBe false
            second.first.id shouldBe first.first.id
            transaction { DonationStore.ensureForPayment(paymentId = pending, createdBy = null, now = now) } shouldBe null
            transaction { DonationStore.ensureForPayment(paymentId = contribution, createdBy = null, now = now) } shouldBe null
            transaction { DonationStore.ensureForPayment(paymentId = Uuid.random(), createdBy = null, now = now) } shouldBe null
            donationCount(member) shouldBe 1L
        }

        // ---- mode CURRENT ----

        test("mode CURRENT refuses a donation id and a reason, and mints nothing") {
            app {
                val member = createMember("dc-current@example.org")
                val pay = DonationFixtures.bankedDonation(member = member)
                val donation = backfilledDonation(pay, member)
                setConfig(ConversionRateBasis.CURRENT)
                client.convert(member, null, donation = donation.toString()).status shouldBe HttpStatusCode.Conflict
                client.convert(member, null, reason = REASON).status shouldBe HttpStatusCode.Conflict
                mints(member) shouldBe 0L
                donationCount(member) shouldBe 1L
            }
        }

        test("the candidate list is refused for a plain member and still works for the treasury roles") {
            app {
                val member = createMember("dc-list@example.org")
                client.post("/test/candidates?memberId=$member") { header("X-Member-Id", PLAIN_MEMBER) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.post("/test/candidates?memberId=$member") { header("X-Member-Id", BOARD) }.status shouldBe HttpStatusCode.OK
                client.post("/test/candidates?memberId=$member") { header("X-Member-Id", ADMIN) }.status shouldBe HttpStatusCode.OK
            }
        }
    })

private fun StatusPagesConfig.installDonationExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
}

private fun Route.registerDonationRoutes(orchestrator: PriceOracleOrchestrator) {
    post("/test/candidates") {
        val service = PriceOracleService(call = call, orchestrator = orchestrator)
        val list = service.listDonationPaymentCandidates(call.request.queryParameters["memberId"]!!)
        call.respondText(list.joinToString(";") { "${it.id},${it.donationId}" })
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
                    donationId = q["donation"],
                    referenceDateOverrideReason = q["reason"],
                ),
            )
        call.respondText("${r.id}|${r.referenceDateSource}|${r.donationId}|${r.referenceDateOverrideReason}")
    }
}
