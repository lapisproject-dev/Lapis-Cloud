package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.MemberLupaBbProfileTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.lupabb.LupaBbApplier
import network.lapis.cloud.server.lupabb.LupaBbFixtures
import network.lapis.cloud.server.lupabb.LupaBbSuppression
import network.lapis.cloud.server.lupabb.TEST_BOX
import network.lapis.cloud.server.lupabb.datasetOf
import network.lapis.cloud.server.lupabb.entryOf
import network.lapis.cloud.server.lupabb.planOf
import network.lapis.cloud.server.lupabb.row
import network.lapis.cloud.server.lupabb.wireMember
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.226 -- [LupaBbPersonalData] end to end: the export, the erasure in the order of the [PersonalDataRegistry] (this contributor runs BEFORE
 * the Foundation contributor anonymises the member), the suppression list and the next sync that must not create the erased person again.
 */
class LupaBbPersonalDataTest :
    FunSpec({
        val fx = LupaBbFixtures()
        beforeSpec { DatabaseConfig.connect() }
        beforeTest { LupaBbSuppression.configure(TEST_BOX) }
        afterTest { fx.cleanUp() }

        fun unique() = "Testfeld${Uuid.random().toString().take(8)}"

        fun erase(memberId: Uuid) {
            val order = PersonalDataRegistry.contributors
            (order.indexOf(LupaBbPersonalData) < order.indexOf(FoundationPersonalData)) shouldBe true
            transaction {
                LupaBbPersonalData.eraseMember(memberId = memberId, mode = ErasureMode.ANONYMIZE)
                FoundationPersonalData.eraseMember(memberId = memberId, mode = ErasureMode.ANONYMIZE)
            }
        }

        test("export: person number, missing day and every profile field") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_070)
            LupaBbApplier(chapterEnforced = false).apply(
                entry =
                    entryOf(
                        plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_070, family = family)), actor = admin),
                        admin = admin,
                    ),
                actor = fx.current(id = admin),
            )
            val json = transaction { LupaBbPersonalData.exportMember(id) }.jsonObject
            json.getValue("lupabbPersonNumber").jsonPrimitive.content shouldBe "9000000070"
            json
                .getValue("profile")
                .jsonObject
                .getValue("familyName")
                .jsonPrimitive.content shouldBe family
        }

        test(
            "erasure of a linked member: profile and columns cleared, the HMAC (not the number) is listed, the next sync skips the record",
        ) {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_071)
            val data = datasetOf(wireMember(personNumber = 9_000_000_071, family = family))
            LupaBbApplier(chapterEnforced = false).apply(
                entry = entryOf(plan = planOf(dataset = data, actor = admin), admin = admin),
                actor = fx.current(id = admin),
            )
            erase(id)
            transaction { MemberLupaBbProfileTable.selectAll().where { MemberLupaBbProfileTable.memberId eq id }.count() } shouldBe 0
            fx.memberRow(id)[MemberTable.lupabbPersonNumber] shouldBe null
            val listed = transaction { LupaBbSuppression.allHmacs() }
            listed shouldContain LupaBbSuppression.hmac(secretBox = TEST_BOX, personNumber = 9_000_000_071)
            listed.joinToString() shouldNotContain "9000000071"
            val replan = planOf(dataset = data, actor = admin)
            replan.row(9_000_000_071).category shouldBe LupaBbCategory.SKIPPED
            replan.row(9_000_000_071).reasons.map { it.code } shouldContain LupaBbRowReasonCode.ERASED_IN_LAPIS
        }

        test("erasure of a CSV-imported member that was never linked also suppresses the person number kept in external_reference") {
            val admin = fx.admin()
            val id = fx.member(displayName = "Importiert ${unique()}", externalReference = "9000000072")
            val data = datasetOf(wireMember(personNumber = 9_000_000_072, family = unique()))
            erase(id)
            transaction { LupaBbSuppression.allHmacs() } shouldContain
                LupaBbSuppression.hmac(secretBox = TEST_BOX, personNumber = 9_000_000_072)
            val replan = planOf(dataset = data, actor = admin)
            replan.row(9_000_000_072).category shouldBe LupaBbCategory.SKIPPED
            replan.row(9_000_000_072).reasons.map { it.code } shouldContain LupaBbRowReasonCode.ERASED_IN_LAPIS
        }
    })
