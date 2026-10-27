package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.PartgReportPositionTable
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgPositions
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.225 -- the migration seed is identical to the shared catalogue, and the sums are computed as the law lays them out. */
class PartgPositionsSeedTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        test("the catalogue has 42 rows: 35 typed-in and 7 computed positions, with unique codes and sort orders") {
            PartgPositions.ALL.size shouldBe 42
            PartgPositions.ALL.count { it.kind == PartgKind.LEAF } shouldBe 35
            PartgPositions.ALL.count { it.kind == PartgKind.SUM } shouldBe 7
            PartgPositions.LEAF_CODES.size shouldBe PartgPositions.LEAF_COUNT
            PartgPositions.ALL
                .map { it.code }
                .toSet()
                .size shouldBe 42
            PartgPositions.ALL.map { it.sortOrder } shouldBe (1..42).toList()
            PartgPositions.ALL
                .map { it.id }
                .toSet()
                .size shouldBe 42
        }

        test("the seeded rows equal the shared catalogue column for column") {
            val rows =
                transaction {
                    PartgReportPositionTable.selectAll().map {
                        PartgPositions.Position(
                            id = it[PartgReportPositionTable.id].toString(),
                            code = it[PartgReportPositionTable.code],
                            section = it[PartgReportPositionTable.section],
                            kind = it[PartgReportPositionTable.kind],
                            parent = it[PartgReportPositionTable.parentCode],
                            sortOrder = it[PartgReportPositionTable.sortOrder],
                            numberLabel = it[PartgReportPositionTable.numberLabel],
                            labelDe = it[PartgReportPositionTable.labelDe],
                            legalRef = it[PartgReportPositionTable.legalRef],
                        )
                    }
                }.sortedBy { it.sortOrder }
            rows shouldBe PartgPositions.ALL
        }

        test("sums follow the law: income, material expenses, total expenses, result, assets, liabilities, net assets") {
            val values =
                mapOf(
                    "E01" to 1_000_00L,
                    "E03" to 500_00L,
                    "E05A" to 50_00L,
                    "A01" to 200_00L,
                    "A02A" to 100_00L,
                    "A02G" to 20_00L,
                    "A03" to 10_00L,
                    "BP07" to 900_00L,
                    "SP05" to 300_00L,
                )
            val sums = PartgPositions.sums(values)
            sums["E11"] shouldBe 1_550_00L
            sums["A02"] shouldBe 120_00L
            sums["A04"] shouldBe 330_00L
            sums["RES"] shouldBe 1_220_00L
            sums["BP-SUM"] shouldBe 900_00L
            sums["SP-SUM"] shouldBe 300_00L
            sums["NET"] shouldBe 600_00L
        }

        test("result and net assets may be negative; a missing position counts as 0") {
            val sums = PartgPositions.sums(mapOf("A01" to 500L, "SP01" to 900L))
            sums["RES"] shouldBe -500L
            sums["NET"] shouldBe -900L
            PartgPositions.sums(emptyMap()).values.all { it == 0L } shouldBe true
        }
    })
