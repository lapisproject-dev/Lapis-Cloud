package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.MemberCardCodeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.member.MemberCardResolution
import network.lapis.cloud.server.member.MemberCardStore
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- exercises [MemberCardPersonalData] directly (no HTTP
 * layer needed), same house style [MemberHonorPersonalDataTest]/[FoundationPersonalDataTest]
 * establish. [PersonalDataCoverageTest] only proves [MemberCardCodeTable] is covered by SOME
 * contributor -- this file pins the two security-relevant behaviors [MemberCardPersonalData]'s own
 * KDoc claims: `code_hash` never leaves via [MemberCardPersonalData.exportMember], and
 * [MemberCardPersonalData.eraseMember] hard-deletes (not retain-and-redact), reported with the
 * correct `rowsDeleted` count, and a code presented after erasure resolves to [MemberCardResolution
 * .Unknown] via [MemberCardStore.resolve].
 */
class MemberCardPersonalDataTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val now = LocalDateTime(2038, 1, 1, 12, 0)

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    MemberCardCodeTable.deleteWhere { memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "MemberCardPersonalDataTest Mitglied"
                    it[email] = "membercarddsgvo-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2038, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        test("exportMember lists issued codes by id/issuedAt/revokedAt but NEVER the code_hash column") {
            val memberId = createMember()
            transaction { MemberCardStore.issueNewCode(memberId = memberId, now = now) }

            val export = transaction { MemberCardPersonalData.exportMember(memberId) }
            val codes = export["memberCardCodes"]!!.jsonArray
            codes.size shouldBe 1
            val entry = codes[0].jsonObject
            entry.containsKey("id") shouldBe true
            entry.containsKey("issuedAt") shouldBe true
            entry.containsKey("revokedAt") shouldBe true
            entry.containsKey("codeHash") shouldBe false
            entry.containsKey("code_hash") shouldBe false
            entry.containsKey("hash") shouldBe false
        }

        test("exportMember for a member with no card codes yields an empty array, not an error") {
            val memberId = createMember()
            val export = transaction { MemberCardPersonalData.exportMember(memberId) }
            export["memberCardCodes"]!!.jsonArray.size shouldBe 0
        }

        test("eraseMember hard-deletes every member_card_code row and reports the correct rowsDeleted count") {
            val memberId = createMember()
            transaction { MemberCardStore.issueNewCode(memberId = memberId, now = now) }
            transaction { MemberCardStore.revokeAndReissue(memberId = memberId, now = now) }
            // Two rows now: the revoked original plus the fresh active one.
            transaction {
                MemberCardCodeTable.selectAll().where { MemberCardCodeTable.memberId eq memberId }.count()
            } shouldBe 2L

            val outcomes =
                transaction {
                    MemberCardPersonalData.eraseMember(memberId = memberId, mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED)
                }

            val outcome = outcomes.single { it.table == "member_card_code" }
            outcome.rowsDeleted shouldBe 2
            transaction {
                MemberCardCodeTable.selectAll().where { MemberCardCodeTable.memberId eq memberId }.count()
            } shouldBe 0L
        }

        test("a code presented after eraseMember resolves to Unknown, not Revoked -- the row is gone, not just marked") {
            val memberId = createMember()
            val raw = transaction { MemberCardStore.issueNewCode(memberId = memberId, now = now) }
            transaction { MemberCardStore.resolve(raw) }.shouldBeInstanceOf<MemberCardResolution.Valid>()

            transaction { MemberCardPersonalData.eraseMember(memberId = memberId, mode = ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED) }

            transaction { MemberCardStore.resolve(raw) } shouldBe MemberCardResolution.Unknown
        }
    })
