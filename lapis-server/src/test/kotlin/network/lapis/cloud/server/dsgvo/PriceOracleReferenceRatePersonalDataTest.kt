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
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PriceOracleConversionTable
import network.lapis.cloud.server.db.generated.PriceOracleReferenceRateTable
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
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

        test("it covers the conversion table and the reference rate table") {
            PriceOraclePersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf("price_oracle_conversion", "price_oracle_reference_rate")
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
    })

private const val EVIDENCE = "Beleg-Text mit genau einunddreissig Z"
