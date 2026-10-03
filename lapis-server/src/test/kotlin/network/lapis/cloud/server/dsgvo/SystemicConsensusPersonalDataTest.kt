package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.server.rpc.SystemicConsensusRationaleTestData
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** V1.9.39 -- [SystemicConsensusPersonalData]: the text and the rationale of a proposal are exported to their author and retained on erasure. */
class SystemicConsensusPersonalDataTest :
    FunSpec({
        val data = SystemicConsensusRationaleTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { data.cleanUp() }

        test("registered with its section key and covers the option table") {
            SystemicConsensusPersonalData.sectionKey shouldBe "systemic_consensus"
            (SystemicConsensusPersonalData in PersonalDataRegistry.contributors) shouldBe true
            SystemicConsensusPersonalData.coveredTables
                .map { it.tableName }
                .toSet()
                .contains("systemic_consensus_option") shouldBe true
        }

        test("export carries label and rationale of the member's OWN proposals and nothing of other members' proposals") {
            val f = data.fixture("dsgvo-export")
            transaction {
                SystemicConsensusOptionTable.update({ SystemicConsensusOptionTable.id eq Uuid.parse(f.optionId) }) {
                    it[rationale] = "Meine Begruendung"
                }
            }
            val mine = transaction { SystemicConsensusPersonalData.exportMember(f.proposer) }.jsonObject
            val proposal =
                mine
                    .getValue("optionsProposed")
                    .jsonArray
                    .single()
                    .jsonObject
            proposal.getValue("rationale").jsonPrimitive.content shouldBe "Meine Begruendung"
            proposal.getValue("id").jsonPrimitive.content shouldBe f.optionId

            val others = transaction { SystemicConsensusPersonalData.exportMember(f.other) }.jsonObject
            others.getValue("optionsProposed").jsonArray.size shouldBe 0
            others.toString() shouldNotContain "Meine Begruendung"

            transaction {
                SystemicConsensusOptionTable.update({ SystemicConsensusOptionTable.id eq Uuid.parse(f.optionId) }) {
                    it[rationale] = null
                }
            }
            val cleared = transaction { SystemicConsensusPersonalData.exportMember(f.proposer) }.jsonObject
            cleared
                .getValue("optionsProposed")
                .jsonArray
                .single()
                .jsonObject
                .getValue("rationale") shouldBe JsonNull
        }

        test("erasure retains the proposal rows and their rationale, with the new reason") {
            val f = data.fixture("dsgvo-erase")
            transaction {
                SystemicConsensusOptionTable.update({ SystemicConsensusOptionTable.id eq Uuid.parse(f.optionId) }) {
                    it[rationale] = "Bleibt erhalten"
                }
            }
            for (mode in ErasureMode.entries) {
                val outcome =
                    transaction { SystemicConsensusPersonalData.eraseMember(memberId = f.proposer, mode = mode) }
                        .single { it.table == "systemic_consensus_option" }
                outcome.rowsRetained shouldBe 1
                outcome.retentionReason shouldBe "Procedure record (text+rationale)."
            }
            transaction {
                SystemicConsensusOptionTable
                    .selectAll()
                    .where { SystemicConsensusOptionTable.id eq Uuid.parse(f.optionId) }
                    .single()[SystemicConsensusOptionTable.rationale]
            } shouldBe "Bleibt erhalten"
        }
    })
