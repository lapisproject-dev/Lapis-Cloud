package network.lapis.cloud.server.memberfinder

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberFinderConsentEventTable
import network.lapis.cloud.server.dsgvo.MemberFinderPersonalData
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val PINNED = "2026-10-16T10:00:00Z"

/**
 * V1.9.144 -- the GDPR contributor: the export shows the member's own consent events, the own outgoing requests ONLY in the sender's view (never the stored
 * DECLINED), the own incoming requests with the own decision but without the sender, and two counts -- never a foreign id or name; the erasure removes
 * everything that names the member and detaches (not deletes) the requests the member only received.
 */
class MemberFinderPersonalDataTest :
    FunSpec({
        val data = FinderTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterTest { data.reset() }

        test("the contributor is registered and covers exactly the three tables with a member key") {
            PersonalDataRegistry.contributors.contains(MemberFinderPersonalData) shouldBe true
            MemberFinderPersonalData.sectionKey shouldBe "memberFinder"
            MemberFinderPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf("member_finder_consent_event", "member_finder_request", "member_finder_block")
        }

        test("the export never names another member: no foreign id, no foreign name; a declined request reads PENDING to its sender") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                val six = data.region(count = 6, label = "six")
                val me = six[0]
                val x = six[1]
                val y = six[2]
                data.request(from = me, to = x, status = MemberFinderRequestStatus.DECLINED, note = "Meine Notiz")
                data.request(from = me, to = null)
                data.request(from = y, to = me, status = MemberFinderRequestStatus.DECLINED, note = "Notiz an mich")
                data.request(from = six[3], to = me, status = MemberFinderRequestStatus.WITHDRAWN)
                data.request(from = six[4], to = me, status = MemberFinderRequestStatus.ACCEPTED)
                transaction { MemberFinderBlockStore.block(blockerMemberId = me, blockedMemberId = six[5], now = ServerClock.now()) }
                val json = transaction { MemberFinderPersonalData.exportMember(me) }.jsonObject
                val text = json.toString()
                // the note of an outgoing request is never exported (it is nulled for some states only -> would be an oracle)
                text shouldNotContain "Meine Notiz"
                for (other in six.drop(1)) text shouldNotContain other.toString()
                for (name in listOf("six2", "six3", "six4", "six5", "six6")) text shouldNotContain name

                val outgoing = json["outgoingRequests"]!!.jsonArray.map { it.jsonObject }
                outgoing.size shouldBe 2
                // the sender's view: both are PENDING (until they end); the stored DECLINED never appears for an outgoing request
                outgoing.all { it["state"]!!.jsonPrimitive.content == "PENDING" } shouldBe true
                val incoming = json["incomingRequests"]!!.jsonArray.map { it.jsonObject }
                incoming.map { it["decision"]!!.jsonPrimitive.content }.sorted() shouldBe listOf("ACCEPTED", "DECLINED", "NONE")
                // a withdrawal by the sender is not reported to the addressee
                text shouldNotContain "WITHDRAWN"
                json["connectionCount"]!!.jsonPrimitive.int shouldBe 1
                json["ownBlockCount"]!!.jsonPrimitive.int shouldBe 1
                json["truncated"]!!.jsonPrimitive.boolean shouldBe false
                json["consentEvents"]!!.jsonArray.size shouldBe 1
            }
        }

        test("the export of the consent log is capped at 2000 newest events and says so") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                val m = data.member(label = "many")
                transaction {
                    repeat(2005) { i ->
                        MemberFinderConsentEventTable.insert {
                            it[id] = Uuid.random()
                            it[memberId] = m
                            it[eventType] = MemberFinderConsentEventType.REVOKE
                            it[occurredAt] = ServerClock.now()
                            it[supersededAt] = ServerClock.now()
                            it[revokeReason] = MemberFinderRevokeReason.MEMBER
                            it[adultConfirmed] = false
                        }
                    }
                }
                val json = transaction { MemberFinderPersonalData.exportMember(m) }.jsonObject
                json["truncated"]!!.jsonPrimitive.boolean shouldBe true
                json["consentEvents"]!!.jsonArray.size shouldBe 2000
            }
        }

        test("the erasure deletes the events, own requests and blocks of both sides, and detaches the requests the member received") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                val six = data.region(count = 6, label = "six")
                val me = six[0]
                data.request(from = me, to = six[1])
                val received = data.request(from = six[2], to = me, note = "an mich")
                data.request(from = six[3], to = me, status = MemberFinderRequestStatus.ACCEPTED)
                transaction {
                    MemberFinderBlockStore.block(blockerMemberId = me, blockedMemberId = six[4], now = ServerClock.now())
                    MemberFinderBlockStore.block(blockerMemberId = six[5], blockedMemberId = me, now = ServerClock.now())
                }
                val outcomes = transaction { MemberFinderPersonalData.eraseMember(memberId = me, mode = ErasureMode.entries.first()) }
                outcomes.map { it.table }.toSet() shouldBe
                    setOf("member_finder_consent_event", "member_finder_request", "member_finder_block")
                data.eventCount(me) shouldBe 0
                data.blockCount() shouldBe 0
                val rows = data.requests()
                // nothing references the member any more, and the received request survives as a phantom without the note
                rows.none { it.fromMemberId == me || it.toMemberId == me } shouldBe true
                val phantom = rows.single { it.id == received }
                phantom.toMemberId shouldBe null
                phantom.note shouldBe null
                phantom.status shouldBe MemberFinderRequestStatus.OPEN
            }
        }
    })
