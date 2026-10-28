package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ProtocolTaskStatusTable
import network.lapis.cloud.server.db.generated.ProtocolVersionConfirmationTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTaskTable
import network.lapis.cloud.server.governance.session.SessionFixture
import network.lapis.cloud.server.governance.session.finalOps
import network.lapis.cloud.server.governance.session.finaliseAs
import network.lapis.cloud.server.governance.session.newStorageRoot
import network.lapis.cloud.server.governance.session.openAndWrite
import network.lapis.cloud.server.governance.session.protocolOps
import network.lapis.cloud.server.governance.session.scene
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ProtocolTaskInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.240 -- DSGVO coverage of the finalised Protokoll: the export lists only the member's own rows, the erasure deletes the task states and keeps the record. */
class ProtocolVersionPersonalDataTest :
    FunSpec({
        val fx = SessionFixture()
        val storage = newStorageRoot()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            fx.cleanup()
            storage.deleteRecursively()
        }

        test("the four tables are covered; export and erasure touch only the member's own rows") {
            listOf(ProtocolVersionTable, ProtocolVersionConfirmationTable, ProtocolVersionTaskTable, ProtocolTaskStatusTable).forEach {
                (it in GovernancePersonalData.coveredTables) shouldBe true
            }
            val s = fx.scene()
            val f = fx.finalOps(storage)
            s.openAndWrite(final = f)
            fx.protocolOps().addTask(
                current = s.lead,
                meetingId = s.meetingId.toString(),
                input =
                    ProtocolTaskInput(
                        agendaItemId = s.agenda[0].toString(),
                        assigneeMemberId = s.peers[0].memberId.toString(),
                        description = "MSX meine Aufgabe",
                        dueDate = null,
                    ),
            )
            fx.protocolOps().addTask(
                current = s.lead,
                meetingId = s.meetingId.toString(),
                input =
                    ProtocolTaskInput(
                        agendaItemId = s.agenda[0].toString(),
                        assigneeMemberId = s.peers[1].memberId.toString(),
                        description = "MSX fremde Aufgabe",
                        dueDate = null,
                    ),
            )
            val version = s.finaliseAs(final = f, who = s.lead).version!!
            f.confirm(current = s.minutes, meetingId = s.meetingId.toString(), versionId = version.versionId)
            val mine = f.myTasks(s.peers[0]).single()
            f.setTaskDone(current = s.peers[0], taskKey = mine.taskKey, done = true)

            val export = transaction { GovernancePersonalData.exportMember(s.peers[0].memberId) }
            export["protocolVersionTasks"]!!
                .jsonArray
                .single()
                .jsonObject["description"]!!
                .jsonPrimitive.content shouldBe
                "MSX meine Aufgabe"
            export["protocolTaskStatus"]!!
                .jsonArray
                .single()
                .jsonObject["done"]!!
                .jsonPrimitive.content shouldBe "true"
            export.toString().contains("MSX fremde Aufgabe") shouldBe false
            val leadExport = transaction { GovernancePersonalData.exportMember(s.lead.memberId) }
            leadExport["protocolConfirmations"]!!
                .jsonArray
                .single()
                .jsonObject["role"]!!
                .jsonPrimitive.content shouldBe "LEAD"
            leadExport["protocolsFinalised"]!!.jsonArray.size shouldBe 1

            val outcomes = transaction { GovernancePersonalData.eraseMember(memberId = s.peers[0].memberId, mode = ErasureMode.ANONYMIZE) }
            outcomes.single { it.table == "protocol_task_status" }.rowsDeleted shouldBe 1
            val kept = outcomes.single { it.table == "protocol_version_task" }
            kept.rowsRetained shouldBe 1
            (kept.retentionReason?.contains("NOT anonymised") ?: false) shouldBe true
            transaction {
                ProtocolTaskStatusTable.selectAll().where { ProtocolTaskStatusTable.assigneeMemberId eq s.peers[0].memberId }.count()
            } shouldBe 0
            transaction {
                ProtocolVersionTaskTable.selectAll().where { ProtocolVersionTaskTable.assigneeMemberId eq s.peers[0].memberId }.count()
            } shouldBe 1
        }
    })
