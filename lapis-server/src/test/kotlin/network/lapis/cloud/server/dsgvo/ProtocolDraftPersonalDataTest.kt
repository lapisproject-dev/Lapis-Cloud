package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ProtocolDraftTaskTable
import network.lapis.cloud.server.governance.session.SessionFixture
import network.lapis.cloud.server.governance.session.openedProtocol
import network.lapis.cloud.server.governance.session.scene
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ProtocolTaskInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.239 -- DSGVO coverage of `protocol_draft_task`: the export lists the tasks of the member, the erasure keeps the committee record with its reason. */
class ProtocolDraftPersonalDataTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        test("the task table is covered and the export lists only the tasks of the member, without the free texts of the draft") {
            (ProtocolDraftTaskTable in GovernancePersonalData.coveredTables) shouldBe true
            val s = fx.scene()
            val p = runBlocking { s.openedProtocol() }
            val meeting = s.meetingId.toString()
            p.saveBlock(
                current = s.lead,
                meetingId = meeting,
                agendaItemId = s.agenda[0].toString(),
                expectedVersion = 0,
                body = "MSX GEHEIMER BLOCK",
            )

            fun give(
                who: network.lapis.cloud.server.security.CurrentMember,
                text: String,
            ) = p.addTask(
                current = s.lead,
                meetingId = meeting,
                input =
                    ProtocolTaskInput(
                        agendaItemId = s.agenda[0].toString(),
                        assigneeMemberId = who.memberId.toString(),
                        description = text,
                        dueDate = null,
                    ),
            )
            give(s.peers[0], "MSX meine Aufgabe")
            give(s.peers[1], "MSX fremde Aufgabe")
            val export = transaction { GovernancePersonalData.exportMember(s.peers[0].memberId) }
            val tasks = export["protocolTasksAssigned"]!!.jsonArray
            tasks.size shouldBe 1
            tasks
                .single()
                .jsonObject["description"]!!
                .jsonPrimitive.content shouldBe "MSX meine Aufgabe"
            tasks
                .single()
                .jsonObject["agendaItemTitle"]!!
                .jsonPrimitive.content shouldBe "TOP 1"
            export.toString().contains("MSX GEHEIMER BLOCK") shouldBe false
            export.toString().contains("MSX fremde Aufgabe") shouldBe false
        }

        test("erasure keeps the task row (committee record, documented) and reports it; the registry stays complete") {
            val s = fx.scene()
            val p = runBlocking { s.openedProtocol() }
            p.addTask(
                current = s.lead,
                meetingId = s.meetingId.toString(),
                input =
                    ProtocolTaskInput(
                        agendaItemId = s.agenda[0].toString(),
                        assigneeMemberId = s.peers[0].memberId.toString(),
                        description = "MSX bleibt",
                        dueDate = null,
                    ),
            )
            val outcomes = transaction { GovernancePersonalData.eraseMember(memberId = s.peers[0].memberId, mode = ErasureMode.ANONYMIZE) }
            val outcome = outcomes.single { it.table == "protocol_draft_task" }
            outcome.rowsRetained shouldBe 1
            (outcome.retentionReason?.isNotBlank() ?: false) shouldBe true
            transaction {
                ProtocolDraftTaskTable.selectAll().where { ProtocolDraftTaskTable.assigneeMemberId eq s.peers[0].memberId }.count()
            } shouldBe 1
        }
    })
