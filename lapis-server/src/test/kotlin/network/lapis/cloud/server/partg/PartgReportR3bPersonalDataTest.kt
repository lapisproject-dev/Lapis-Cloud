package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.dsgvo.PartgReportPersonalData
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgSponsorInputDto
import network.lapis.cloud.shared.domain.PartgSponsoringEntryInputDto
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgYouthOrgInputDto
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.283 (R3b) -- sponsors, sponsoring entries and youth organisations in the data subject export and erasure. */
class PartgReportR3bPersonalDataTest :
    FunSpec({
        val fx = PartgFixtures()
        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.setParty(true) }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }

        suspend fun PartgApp.seed(
            board: kotlin.uuid.Uuid,
            unit: String,
        ) {
            val dto =
                call(member = board) {
                    service(it).saveSponsor(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 0,
                        sponsor =
                            PartgSponsorInputDto(
                                id = null,
                                personKind = PartgPersonKind.NATURAL,
                                name = "Auskunft Geheimsponsor",
                                street = "Geheimweg 3",
                                postalCode = "99999",
                                city = "Geheimstadt",
                                country = "DE",
                                entries =
                                    listOf(
                                        PartgSponsoringEntryInputDto(
                                            id = null,
                                            date = LocalDate(year, 4, 1),
                                            amountCents = 8_765_432L,
                                            purpose = "Geheimzweck",
                                        ),
                                    ),
                            ),
                    )
                }
            call(member = board) {
                service(
                    it,
                ).saveYouthOrg(
                    year = year,
                    unitKey = unit,
                    expectedRevision = dto.revision,
                    youthOrg =
                        PartgYouthOrgInputDto(
                            id = null,
                            name = "Geheimjugend",
                            relationship = "Geheimrolle",
                            incomeCents = 4_444_444L,
                            expensesCents = 5L,
                        ),
                )
            }
        }

        test("the export lists year and unit of the changes but never a name, an address, a purpose or an amount") {
            val board = fx.member()
            val unit = PartgUnit.State(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) { seed(board, unit) }
            val json = transaction { PartgReportPersonalData.exportMember(board).toString() }
            json shouldContain "sponsorsChangedByYou"
            json shouldContain "sponsoringEntriesChangedByYou"
            json shouldContain "youthOrgsChangedByYou"
            json shouldContain "STATE:"
            listOf("Geheim", "8765432", "4444444", "99999", "Auskunft").forEach { json shouldNotContain it }
        }

        test("erasure retains every R3b row, names the reason per table, and deletes nothing") {
            val board = fx.member()
            val unit = PartgUnit.State(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) { seed(board, unit) }
            for (mode in ErasureMode.entries) {
                val outcomes = transaction { PartgReportPersonalData.eraseMember(memberId = board, mode = mode) }.associateBy { it.table }
                outcomes.getValue("partg_report_sponsor").rowsRetained shouldBe 1
                outcomes.getValue("partg_report_sponsoring_entry").rowsRetained shouldBe 1
                outcomes.getValue("partg_report_youth_org").rowsRetained shouldBe 1
                outcomes.values.all { it.retentionReason?.contains("§ 24 Abs. 2") == true } shouldBe true
            }
            partgTestApplication(fixtures = fx) {
                val dto = call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }
                dto.sponsors.size shouldBe 1
                dto.youthOrgs.size shouldBe 1
            }
        }

        test("the sponsor tables are registered as known uncovered third-party roots; the youth organisation is not") {
            listOf("partg_report_sponsor", "partg_report_sponsoring_entry").forEach {
                val reason = PersonalDataRegistry.knownUncoveredSubjectRoots.getValue(it)
                reason shouldContain "V1.9.283"
                PersonalDataRegistry.nonMemberPiiTables.contains(it) shouldBe true
            }
            PersonalDataRegistry.nonMemberPiiTables.contains("partg_report_youth_org") shouldBe false
            PersonalDataRegistry.knownUncoveredSubjectRoots.containsKey("partg_report_youth_org") shouldBe false
        }
    })
