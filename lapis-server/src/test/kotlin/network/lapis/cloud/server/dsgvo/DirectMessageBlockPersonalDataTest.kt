package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DirectMessageBlockTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.messaging.DirectMessageBlockStore
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** V1.9.237 -- DSGVO coverage of `direct_message_block`: export holds only the blocks the member SET, erasure deletes both sides. */
class DirectMessageBlockPersonalDataTest :
    FunSpec({
        val ids = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            transaction {
                DirectMessageBlockTable.deleteWhere { (blockerMemberId inList ids) or (blockedMemberId inList ids) }
                MemberTable.deleteWhere { MemberTable.id inList ids }
            }
        }

        fun member(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "DSGVO Block Test"
                    it[email] = "dm-block-dsgvo-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
            }
            ids += id
            return id
        }

        test("the table is covered, the export lists own blocks and the visibility but not blocks against the member") {
            (DirectMessageBlockTable in CommunicationPersonalData.coveredTables) shouldBe true
            val subject = member()
            val blockedBySubject = member()
            val blockerOfSubject = member()
            transaction {
                val now = DbClock.nowLocalDateTime()
                DirectMessageBlockStore.block(blocker = subject, blocked = blockedBySubject, now = now)
                DirectMessageBlockStore.block(blocker = blockerOfSubject, blocked = subject, now = now)
                MemberTable.update({ MemberTable.id eq subject }) { it[dmDiscoverable] = false }
            }
            val export = transaction { CommunicationPersonalData.exportMember(subject) }
            export["recipientSearchVisible"]!!.jsonPrimitive.boolean shouldBe false
            val blocks = export["directMessageBlocksSet"]!!.jsonArray
            blocks.size shouldBe 1
            blocks
                .single()
                .jsonObject["blockedMemberId"]!!
                .jsonPrimitive.content shouldBe blockedBySubject.toString()
            export.toString().contains(blockerOfSubject.toString()) shouldBe false
        }

        test("erasure deletes the blocks on both sides and reports the count") {
            val subject = member()
            val other1 = member()
            val other2 = member()
            transaction {
                val now = DbClock.nowLocalDateTime()
                DirectMessageBlockStore.block(blocker = subject, blocked = other1, now = now)
                DirectMessageBlockStore.block(blocker = other2, blocked = subject, now = now)
                DirectMessageBlockStore.block(blocker = other1, blocked = other2, now = now)
            }
            val outcomes = transaction { CommunicationPersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }
            outcomes.single { it.table == "direct_message_block" }.rowsDeleted shouldBe 2
            transaction {
                DirectMessageBlockTable
                    .selectAll()
                    .where { (DirectMessageBlockTable.blockerMemberId eq subject) or (DirectMessageBlockTable.blockedMemberId eq subject) }
                    .count()
            } shouldBe 0
            // a block between two OTHER people is untouched
            transaction { DirectMessageBlockTable.selectAll().where { DirectMessageBlockTable.blockerMemberId eq other1 }.count() } shouldBe
                1
        }
    })
