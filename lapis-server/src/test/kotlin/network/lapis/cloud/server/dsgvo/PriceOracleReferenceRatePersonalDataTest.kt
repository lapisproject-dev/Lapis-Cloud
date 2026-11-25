package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.DonationTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.server.db.generated.PriceOracleReferenceRateTable
import network.lapis.cloud.server.rpc.DonationFixtures
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** V1.9.324 -- the Price-Oracle contributor also covers the manual reference rates a member confirmed (export and retain-with-reason erasure). */
class PriceOracleReferenceRatePersonalDataTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        test("it covers the conversion table, the reference rate table and the donation table") {
            PriceOraclePersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf("price_oracle_conversion", "price_oracle_reference_rate", "donation")
        }

        test("the export lists the own confirmed rates with the evidence LENGTH, and erasure retains them with a reason") {
            val member = Uuid.random()
            val rateId = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[id] = member
                    it[displayName] = "Bestaetiger Export"
                    it[email] = "oracle-pd-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = member
                    it.legacyAccountRole(role = AccountRole.TREASURER)
                }
                PriceOracleReferenceRateTable.insert {
                    it[id] = rateId
                    it[anchorAsset] = AnchorAsset.GOLD_XAU
                    it[donationCurrency] = "EUR"
                    it[effectiveDate] = LocalDate(1999, 12, 30) // a day no other test uses
                    it[price] = BigDecimal("2012.5")
                    it[evidenceText] = EVIDENCE
                    it[confirmedBy] = member
                    it[confirmedAt] = LocalDateTime(2026, 11, 20, 9, 0)
                    it[referenceSnapshotMedian] = null
                    it[deviationAcknowledged] = false
                }
            }
            try {
                val export = transaction { PriceOraclePersonalData.exportMember(member) }
                val rate =
                    export
                        .getValue("referenceRatesConfirmed")
                        .jsonArray
                        .single()
                        .jsonObject
                rate.getValue("id").jsonPrimitive.content shouldBe rateId.toString()
                rate.getValue("evidenceLength").jsonPrimitive.content shouldBe EVIDENCE.length.toString()
                (rate.toString().contains("Beleg-Text")) shouldBe false

                val outcomes = transaction { PriceOraclePersonalData.eraseMember(memberId = member, mode = ErasureMode.ANONYMIZE) }
                val rateOutcome = outcomes.single { it.table == "price_oracle_reference_rate" }
                rateOutcome.rowsRetained shouldBe 1
                (rateOutcome.retentionReason?.isNotBlank()) shouldBe true
                transaction {
                    PriceOracleReferenceRateTable.selectAll().where { PriceOracleReferenceRateTable.id eq rateId }.count()
                } shouldBe
                    1L
            } finally {
                transaction {
                    PriceOracleReferenceRateTable.deleteWhere { PriceOracleReferenceRateTable.id eq rateId }
                    PriceOracleConversionTable.deleteWhere { PriceOracleConversionTable.memberId eq member }
                    AccountTable.deleteWhere { AccountTable.memberId eq member }
                    MemberTable.deleteWhere { MemberTable.id eq member }
                }
            }
        }

        test(
            "V1.9.328: the export lists the member's donations, the actor's created ones by id only, and erasure retains the donation rows with a reason",
        ) {
            val member = Uuid.random()
            val actor = Uuid.random()

            fun createMember(id: Uuid) =
                transaction {
                    MemberTable.insert {
                        it[MemberTable.id] = id
                        it[displayName] = "Spenden Export"
                        it[email] = "donation-pd-${Uuid.random()}@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2026, 1, 1)
                        it[membershipTierId] = null
                    }
                    AccountTable.insert {
                        it[AccountTable.id] = Uuid.random()
                        it[memberId] = id
                        it.legacyAccountRole(role = AccountRole.TREASURER)
                    }
                }
            createMember(member)
            createMember(actor)
            val payment = DonationFixtures.bankedDonation(member = member, amount = "42.00", bookingDate = LocalDate(2026, 11, 20))
            val donationId = Uuid.random()
            transaction {
                DonationTable.insert {
                    it[id] = donationId
                    it[paymentTransactionId] = payment
                    it[memberId] = member
                    it[amount] = BigDecimal("42.00")
                    it[currency] = "EUR"
                    it[bookingDate] = LocalDate(2026, 11, 20)
                    it[createdAt] = LocalDateTime(2026, 11, 26, 10, 0)
                    it[createdBy] = actor
                }
            }
            try {
                val export = transaction { PriceOraclePersonalData.exportMember(member) }
                val donation =
                    export
                        .getValue("donations")
                        .jsonArray
                        .single()
                        .jsonObject
                donation.getValue("id").jsonPrimitive.content shouldBe donationId.toString()
                donation.getValue("amount").jsonPrimitive.content shouldBe "42.00"
                donation.getValue("bookingDate").jsonPrimitive.content shouldBe "2026-11-20"
                export.getValue("donationsCreated").jsonArray.size shouldBe 0
                val actorExport = transaction { PriceOraclePersonalData.exportMember(actor) }
                actorExport.getValue("donations").jsonArray.size shouldBe 0
                actorExport
                    .getValue("donationsCreated")
                    .jsonArray
                    .single()
                    .jsonPrimitive.content shouldBe donationId.toString()

                val outcome =
                    transaction { PriceOraclePersonalData.eraseMember(memberId = member, mode = ErasureMode.ANONYMIZE) }
                        .single { it.table == "donation" }
                outcome.rowsRetained shouldBe 1
                (outcome.retentionReason?.isNotBlank()) shouldBe true
                transaction { DonationTable.selectAll().where { DonationTable.id eq donationId }.count() } shouldBe 1L
            } finally {
                transaction { DonationTable.deleteWhere { DonationTable.id eq donationId } }
                DonationFixtures.cleanup()
                transaction {
                    AccountTable.deleteWhere { AccountTable.memberId inList listOf(member, actor) }
                    MemberTable.deleteWhere { MemberTable.id inList listOf(member, actor) }
                }
            }
        }
    })

private const val EVIDENCE = "Beleg-Text mit genau einunddreissig Z"
