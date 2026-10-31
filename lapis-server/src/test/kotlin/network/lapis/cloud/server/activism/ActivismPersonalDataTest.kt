package network.lapis.cloud.server.activism

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ActivismActionTable
import network.lapis.cloud.server.db.generated.ActivismContributionTable
import network.lapis.cloud.server.db.generated.ActivismReportTable
import network.lapis.cloud.server.db.generated.ActivismResponsibleTable
import network.lapis.cloud.server.db.generated.ActivismSignalTable
import network.lapis.cloud.server.dsgvo.ActivismPersonalData
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.server.rpc.CommitteeSeatFixture
import network.lapis.cloud.server.rpc.withServiceCaller
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ActivismContributionInput
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ActivismPersonalDataTest :
    FunSpec({
        val fx = CommitteeSeatFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { wipeActivism() }
        afterSpec {
            wipeActivism()
            fx.cleanUp()
        }

        test("the contributor is registered and covers the seven member-bearing tables, not the text and material tables") {
            PersonalDataRegistry.contributors.contains(ActivismPersonalData) shouldBe true
            ActivismPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf(
                    "activism_action",
                    "activism_responsible",
                    "activism_task",
                    "activism_contribution",
                    "activism_signal",
                    "activism_report",
                    "activism_notice_text",
                )
        }

        test("export carries the own data; erasure deletes signals, anonymises contributions and nulls every member column") {
            val creator = fx.member(displayName = "Ersteller Person")
            val board = fx.member(role = AccountRole.BOARD, displayName = "Vorstand Person")
            val helper = fx.member(displayName = "Helfende Person")
            val actionId =
                run {
                    var id = ""
                    withServiceCaller {
                        val a = asMember(who = creator) { it.createAction(activismInput(progress = true, signal = true)) }.getOrThrow()
                        id = a.id
                        asMember(who = board) { it.setActionStatus(id = a.id, target = ActivismStatus.PLANNED) }.getOrThrow()
                        asMember(who = creator) { it.setActionStatus(id = a.id, target = ActivismStatus.RUNNING) }.getOrThrow()
                        asMember(
                            who = helper,
                        ) {
                            it.reportContribution(
                                actionId = a.id,
                                input = ActivismContributionInput(quantity = 12, area = "Mitte", note = "Privates"),
                            )
                        }.getOrThrow()
                        asMember(who = helper) { it.setSignal(actionId = a.id, on = true) }.getOrThrow()
                        asMember(who = helper) { it.reportAction(actionId = a.id, reason = "Eine ausreichend lange Meldung.") }.getOrThrow()
                    }
                    Uuid.parse(id)
                }
            val export = transaction { ActivismPersonalData.exportMember(helper).toString() }
            export shouldContain "Privates"
            export shouldContain "Plakatieren Innenstadt"
            export shouldContain "Eine ausreichend lange Meldung."
            // the export of the helper does not contain the creator's data
            transaction { ActivismPersonalData.exportMember(creator).toString() } shouldNotContain "Privates"

            val outcomes = transaction { ActivismPersonalData.eraseMember(memberId = helper, mode = ErasureMode.ANONYMIZE) }
            outcomes.first { it.table == "activism_signal" }.rowsDeleted shouldBe 1
            transaction {
                ActivismSignalTable.selectAll().where { ActivismSignalTable.memberId eq helper }.count() shouldBe 0
                val contribution = ActivismContributionTable.selectAll().where { ActivismContributionTable.actionId eq actionId }.single()
                contribution[ActivismContributionTable.memberId].shouldBeNull()
                contribution[ActivismContributionTable.note].shouldBeNull()
                contribution[ActivismContributionTable.quantity] shouldBe 12
                ActivismReportTable
                    .selectAll()
                    .where {
                        ActivismReportTable.actionId eq actionId
                    }.single()[ActivismReportTable.reporterMemberId]
                    .shouldBeNull()
            }

            ActivismPersonalData.also { transaction { it.eraseMember(memberId = creator, mode = ErasureMode.ANONYMIZE) } }
            transaction {
                ActivismActionTable
                    .selectAll()
                    .where { ActivismActionTable.id eq actionId }
                    .single()[ActivismActionTable.createdBy]
                    .shouldBeNull()
                ActivismResponsibleTable.selectAll().where { ActivismResponsibleTable.memberId eq creator }.toList() shouldHaveSize 0
                ActivismActionTable
                    .selectAll()
                    .where {
                        ActivismActionTable.id eq actionId
                    }.single()[ActivismActionTable.approvedBy] shouldBe
                    null
            }
            transaction { ActivismPersonalData.eraseMember(memberId = board, mode = ErasureMode.ANONYMIZE) }
            transaction {
                ActivismActionTable
                    .selectAll()
                    .where { ActivismActionTable.id eq actionId }
                    .single()[ActivismActionTable.approvedBy]
                    .shouldBeNull()
            }
        }
    })
