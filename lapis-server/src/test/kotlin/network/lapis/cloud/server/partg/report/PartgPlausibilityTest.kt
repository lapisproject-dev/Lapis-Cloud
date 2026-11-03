package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.partg.PartgAnnexStore
import network.lapis.cloud.shared.domain.PartgAnnexRules
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgFindingLevel
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitLevel
import kotlin.uuid.Uuid

/** V1.9.269 (R3) -- P01..P06: positive and negative cases, the 50 percent / 1,000 EUR boundary, ordering and the content rule. */
class PartgPlausibilityTest :
    FunSpec({
        val year = 2025
        val units = ReportFixtures.units()
        val federalKey = PartgUnit.FEDERAL_KEY

        fun donor(
            name: String = "Max Muster",
            street: String? = "Weg 1",
            postal: String? = "12345",
            city: String? = "Ort",
            kind: PartgPersonKind = PartgPersonKind.NATURAL,
            cents: Long = 2_000_000L,
        ) = PartgAnnexStore.DonorRow(
            id = Uuid.random(),
            annexId = Uuid.random(),
            personKind = kind,
            name = name,
            street = street,
            postalCode = postal,
            city = city,
            country = "DE",
            contributionKind = PartgContributionKind.DONATION,
            amountCents = cents,
        )

        fun annex(
            donors: List<PartgAnnexStore.DonorRow> = emptyList(),
            cash: Long? = 0L,
            members: Int? = 10,
            otherDeductions: Long? = 0L,
        ) = PartgAnnexStore.AnnexData(
            revision = 1,
            deductions =
                mapOf(
                    PartgDeduction.UNASSIGNABLE to otherDeductions,
                    PartgDeduction.CASH_OVER_1000 to cash,
                    PartgDeduction.OVER_3300 to otherDeductions,
                    PartgDeduction.WRONGLY_REPORTED to otherDeductions,
                ),
            memberCount = members,
            donors = PartgAnnexStore.sortDonors(donors),
        )

        val generalIncluded =
            mapOf(
                year to
                    PartgAnnexStore.NotesData(
                        revision = 1,
                        notes =
                            PartgNoteBlock.entries.map {
                                PartgNoteDto(
                                    block = it,
                                    included = it == PartgNoteBlock.GENERAL_ACCOUNTING,
                                    body = null,
                                    reliefAbs1 = null,
                                    reliefAbs3 = null,
                                )
                            },
                        holdings = emptyList(),
                    ),
            )

        fun run(
            annex: Map<String, PartgAnnexStore.AnnexData> = emptyMap(),
            current: Map<String, Map<String, Long>> = emptyMap(),
            prior: Map<String, Map<String, Long>> = emptyMap(),
            notes: Map<Int, PartgAnnexStore.NotesData> = generalIncluded,
        ) = PartgPlausibility.check(
            year = year,
            units = units,
            entered = mapOf(year to current, year - 1 to prior),
            annex = mapOf(year to annex),
            notes = notes,
        )

        fun List<PartgFindingDto>.codes() = map { it.code }

        test("P01: no address at all, one finding per unit with every row number") {
            val f =
                run(
                    mapOf(
                        federalKey to
                            annex(
                                listOf(donor(), donor("Anna", null, null, null), donor("Berta", " ", "", null), donor("Cäsar")),
                            ),
                    ),
                )
            val p01 = f.single { it.code == PartgFindingCode.P01 }
            p01.level shouldBe PartgFindingLevel.CHECK
            p01.unitKey shouldBe federalKey
            p01.rowNumbers.size shouldBe 2
        }

        test("P02: some but not all address parts are empty; a complete address triggers neither P01 nor P02") {
            val f =
                run(
                    mapOf(
                        federalKey to annex(listOf(donor(city = null), donor("Zweiter", postal = null, street = null), donor("Dritter"))),
                    ),
                )
            f.single { it.code == PartgFindingCode.P02 }.rowNumbers.size shouldBe 2
            f.none { it.code == PartgFindingCode.P01 } shouldBe true
            run(
                mapOf(federalKey to annex(listOf(donor()))),
            ).none { it.code == PartgFindingCode.P01 || it.code == PartgFindingCode.P02 } shouldBe
                true
        }

        test("P03: cash over 1,000 EUR is a hint from one cent on; zero and not entered are not") {
            run(mapOf(federalKey to annex(cash = 1L))).any { it.code == PartgFindingCode.P03 } shouldBe true
            run(mapOf(federalKey to annex(cash = 0L))).none { it.code == PartgFindingCode.P03 } shouldBe true
            run(mapOf(federalKey to annex(cash = null))).none { it.code == PartgFindingCode.P03 } shouldBe true
        }

        test("P04 (INFO): only legal persons") {
            val f = run(mapOf(federalKey to annex(listOf(donor(kind = PartgPersonKind.LEGAL), donor("Privat")))))
            val p04 = f.single { it.code == PartgFindingCode.P04 }
            p04.level shouldBe PartgFindingLevel.INFO
            p04.rowNumbers.size shouldBe 1
            run(mapOf(federalKey to annex(listOf(donor())))).none { it.code == PartgFindingCode.P04 } shouldBe true
        }

        test("P05 (INFO): exactly 1,000.00 EUR / exactly 50 percent is NOT more than; one cent above both is") {
            fun p05(
                cur: Long?,
                old: Long?,
            ) = run(
                current = if (cur == null) emptyMap() else mapOf(federalKey to mapOf("E01" to cur)),
                prior = if (old == null) emptyMap() else mapOf(federalKey to mapOf("E01" to old)),
            ).filter { it.code == PartgFindingCode.P05 }
            // +50 percent and +1,000.00 EUR exactly: 200,000 -> 300,000 is +100,000 cents = +50 percent
            p05(300_000L, 200_000L).size shouldBe 0
            // +50.01 percent and +1,000.01 EUR
            p05(300_001L, 200_000L).size shouldBe 1
            // +60 percent but only 40 EUR
            p05(8_000L, 5_000L).size shouldBe 0
            // prior year missing
            p05(500_000L, null).size shouldBe 0
            // prior 0 and +1,000.01 EUR
            p05(100_001L, 0L).size shouldBe 1
            // decreases count as well
            p05(100_000L, 300_000L).size shouldBe 1
            p05(300_001L, 200_000L).single().positionCode shouldBe "E01"
        }

        test("P06: member count (federal and state only), deductions, and the accounting-principles note") {
            val f =
                run(
                    annex =
                        mapOf(
                            federalKey to annex(members = null, otherDeductions = null),
                            PartgUnit.State(ReportFixtures.CHAPTER_A).key() to annex(members = null),
                            PartgUnit.Subordinate(ReportFixtures.CHAPTER_A).key() to annex(members = null, otherDeductions = null),
                        ),
                    notes = emptyMap(),
                ).filter { it.code == PartgFindingCode.P06 }
            f.map { it.gap to it.unitKey } shouldContainExactly
                listOf(
                    "DEDUCTIONS" to federalKey,
                    "MEMBER_COUNT" to federalKey,
                    "MEMBER_COUNT" to PartgUnit.State(ReportFixtures.CHAPTER_A).key(),
                    "DEDUCTIONS" to PartgUnit.Subordinate(ReportFixtures.CHAPTER_A).key(),
                    "GENERAL_ACCOUNTING" to null,
                )
            f.last().target.name shouldBe "NOTES"
            // a subordinate summary never gets a member-count hint; with the note included there is no organisation hint
            run().none { it.gap == "GENERAL_ACCOUNTING" } shouldBe true
        }

        test(
            "a unit without any data produces no deduction hint (no noise for 100 untouched chapters), the federal unit always asks for its count",
        ) {
            val f = run().filter { it.code == PartgFindingCode.P06 }
            f.map { it.gap to it.unitKey } shouldContainExactly listOf("MEMBER_COUNT" to federalKey)
        }

        test("the order is deterministic: unit order, code, first row") {
            val a = mapOf(federalKey to annex(listOf(donor(kind = PartgPersonKind.LEGAL), donor("B", null, null, null)), cash = 5L))
            run(a) shouldBe run(a)
            run(a).codes() shouldContainExactly listOf(PartgFindingCode.P01, PartgFindingCode.P03, PartgFindingCode.P04)
        }

        test("a finding never shows a name, an address or an amount") {
            val f =
                run(
                    mapOf(
                        federalKey to
                            annex(
                                listOf(
                                    donor("Geheimname Schmidt", "Geheimstraße", "99999", "Geheimstadt", PartgPersonKind.LEGAL, 7_777_777L),
                                ),
                            ),
                    ),
                )
            f.joinToString { it.toString() }.let { s ->
                listOf("Geheim", "Schmidt", "7777777", "99999").forEach { s shouldNotContain it }
            }
        }

        test("the number of findings is capped; the cut is marked by ONE P05 hint without positions") {
            val many = (1..200).map { PartgUnit.State("00000000-0000-4000-8000-%012d".format(it)).key() }
            val bigUnits =
                ReportFixtures.units() +
                    many.map { ReportFixtures.unitDto(level = PartgUnitLevel.STATE, chapter = it.removePrefix("STATE:"), name = "V$it") }
            val values = bigUnits.associate { u -> u.unitKey to PartgReportCaps.allLeafs(1_000_000L) }
            val prior = bigUnits.associate { u -> u.unitKey to PartgReportCaps.allLeafs(10L) }
            val f =
                PartgPlausibility.check(
                    year = year,
                    units = bigUnits,
                    entered = mapOf(year to values, year - 1 to prior),
                    annex = emptyMap(),
                    notes = emptyMap(),
                )
            (f.size <= PartgAnnexRules.MAX_FINDINGS) shouldBe true
            f.count { it.code == PartgFindingCode.P05 && it.positionCode == null && it.unitKey == null } shouldBe 1
        }
    })

private object PartgReportCaps {
    fun allLeafs(cents: Long): Map<String, Long> =
        network.lapis.cloud.shared.domain.PartgPositions.LEAF_CODES
            .associateWith { cents }
}
