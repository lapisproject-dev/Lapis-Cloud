package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.dsgvo.PartgReportPersonalData
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgUnit
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.269 -- the R3 tables in the data subject export and erasure: "changed by you" without names, addresses, amounts or texts; everything retained. */
class PartgReportR3PersonalDataTest :
    FunSpec({
        val fx = PartgFixtures()
        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.setParty(true) }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }

        test("the export lists year and unit of the changes but never a name, an address, an amount or a note text") {
            val board = fx.member()
            val unit = PartgUnit.State(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) {
                call(member = board) {
                    service(it).saveLargeDonor(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 0,
                        donor =
                            PartgLargeDonorInputDto(
                                id = null,
                                personKind = PartgPersonKind.NATURAL,
                                name = "Auskunft Geheimname",
                                street = "Geheimstrasse 9",
                                postalCode = "99999",
                                city = "Geheimstadt",
                                country = "DE",
                                contributionKind = PartgContributionKind.DONATION,
                                amountCents = 8_765_432L,
                            ),
                    )
                }
                call(member = board) {
                    service(it).saveAnnexFigures(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 1,
                        deductions =
                            mapOf(
                                PartgDeduction.OVER_3300 to 4_444_444L,
                            ),
                        memberCount = 55,
                    )
                }
                call(member = board) {
                    service(it).saveNotes(
                        year = year,
                        expectedRevision = 0,
                        notes =
                            listOf(
                                PartgNoteDto(
                                    block = PartgNoteBlock.MEDIA_PRODUCTS,
                                    included = true,
                                    body = "Auskunft Geheimtext",
                                    reliefAbs1 = null,
                                    reliefAbs3 = null,
                                ),
                            ),
                        holdings =
                            listOf(
                                PartgHoldingDto(
                                    name = "Geheim Holding",
                                    seat = null,
                                    shareBasisPoints = 1,
                                    capitalCents = 6_666_666L,
                                    resultCents = 7_777_777L,
                                ),
                            ),
                    )
                }
            }
            val json = transaction { PartgReportPersonalData.exportMember(board).toString() }
            json shouldContain "annexChangedByYou"
            json shouldContain "largeDonorRowsChangedByYou"
            json shouldContain "notesChangedByYou"
            json shouldContain "STATE:"
            listOf("Geheim", "8765432", "4444444", "6666666", "7777777", "99999", "Auskunft").forEach { json shouldNotContain it }
        }

        test("erasure retains every R3 row, names the reason per table, and deletes nothing") {
            val board = fx.member()
            val unit = PartgUnit.State(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) {
                call(member = board) {
                    service(it).saveLargeDonor(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 0,
                        donor =
                            PartgLargeDonorInputDto(
                                id = null,
                                personKind = PartgPersonKind.LEGAL,
                                name = "Loeschtest GmbH",
                                street = null,
                                postalCode = null,
                                city = null,
                                country = "DE",
                                contributionKind = PartgContributionKind.FEE,
                                amountCents = 1_000_001L,
                            ),
                    )
                }
                call(member = board) {
                    service(
                        it,
                    ).saveNotes(
                        year = year - 2,
                        expectedRevision = 0,
                        notes =
                            listOf(
                                PartgNoteDto(
                                    block = PartgNoteBlock.HOLDINGS,
                                    included = true,
                                    body = "x",
                                    reliefAbs1 = null,
                                    reliefAbs3 = null,
                                ),
                            ),
                        holdings = emptyList(),
                    )
                }
            }
            for (mode in ErasureMode.entries) {
                val outcomes = transaction { PartgReportPersonalData.eraseMember(memberId = board, mode = mode) }.associateBy { it.table }
                outcomes.getValue("partg_report_annex").rowsRetained shouldBe 1
                outcomes.getValue("partg_report_large_donor").rowsRetained shouldBe 1
                outcomes.getValue("partg_report_notes_sheet").rowsRetained shouldBe 1
                outcomes.getValue("partg_report_note").rowsRetained shouldBe 5 // all five blocks are always stored
                outcomes.getValue("partg_report_holding").rowsRetained shouldBe 0
                outcomes.values.all { it.retentionReason?.contains("§ 24 Abs. 2") == true } shouldBe true
            }
            // nothing was deleted
            partgTestApplication(fixtures = fx) {
                call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }.donors.size shouldBe 1
            }
        }

        test("the donor table is registered as a known uncovered third-party root, with the wave and the way to ask the board") {
            val reason = PersonalDataRegistry.knownUncoveredSubjectRoots.getValue("partg_report_large_donor")
            reason shouldContain "V1.9.269"
            reason shouldContain "Vorstand"
            PersonalDataRegistry.nonMemberPiiTables.contains("partg_report_large_donor") shouldBe true
        }
    })
