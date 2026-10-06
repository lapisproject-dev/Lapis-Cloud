package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.rpc.MemberStatisticsFixtures
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Welle V1.9.59 -- [MemberStatusHistoryPersonalData]: the Art. 15 export carries the subject's own rows, erasure RETAINS them (in
 * both modes) and says why -- deleting would silently rewrite every past member count.
 */
class MemberStatusHistoryPersonalDataTest :
    FunSpec({
        val fixtures = MemberStatisticsFixtures()

        beforeSpec { DatabaseConfig.connect() }
        afterEach { fixtures.cleanUp() }

        fun seed(): Pair<kotlin.uuid.Uuid, kotlin.uuid.Uuid> {
            val (m1, m2) = fixtures.window1950()
            return m1 to m2
        }

        test("the export lists the subject's own rows only, in order, with status, previous status, instant and source") {
            val (m1, m2) = seed()
            val json = transaction { MemberStatusHistoryPersonalData.exportMember(m1) } as JsonObject
            val rows = json.getValue("memberStatusHistory") as JsonArray
            rows.size shouldBe 3
            rows.map { (it as JsonObject).getValue("status").jsonPrimitive.content } shouldBe listOf("APPLICATION", "ACTIVE", "WITHDRAWN")
            (rows[1] as JsonObject).getValue("previousStatus").jsonPrimitive.content shouldBe "APPLICATION"
            (rows[0] as JsonObject).getValue("source").jsonPrimitive.content shouldBe "LIVE"
            json.toString() shouldNotContain m2.toString()
        }

        test("erasure keeps every row in BOTH modes and reports the retention with its reason") {
            val (m1, _) = seed()
            ErasureMode.entries.forEach { mode ->
                val outcome = transaction { MemberStatusHistoryPersonalData.eraseMember(memberId = m1, mode = mode) }.single()
                outcome.table shouldBe "member_status_history"
                outcome.rowsRetained shouldBe 3
                outcome.rowsDeleted shouldBe 0
                outcome.rowsAnonymized shouldBe 0
                outcome.retentionReason!! shouldContain "Mitgliederstatistik"
                transaction { MemberStatusHistoryTable.selectAll().where { MemberStatusHistoryTable.memberId eq m1 }.count() } shouldBe 3L
            }
        }

        test("the contributor is registered and covers the table") {
            PersonalDataRegistry.contributors.any { it === MemberStatusHistoryPersonalData } shouldBe true
            MemberStatusHistoryPersonalData.coveredTables shouldBe setOf(MemberStatusHistoryTable)
            // a member without rows exports an empty list and retains nothing
            val lonely = fixtures.member(role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)
            val json = transaction { MemberStatusHistoryPersonalData.exportMember(lonely) } as JsonObject
            (json.getValue("memberStatusHistory") as JsonArray).size shouldBe 0
            transaction {
                MemberStatusHistoryPersonalData.eraseMember(
                    memberId = lonely,
                    mode = ErasureMode.ANONYMIZE,
                )
            }.single().rowsRetained shouldBe
                0
        }
    })
