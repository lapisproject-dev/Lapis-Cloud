package network.lapis.cloud.server.bulletin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.BulletinNoticeBoostTable
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import network.lapis.cloud.server.dsgvo.NoticePersonalData
import network.lapis.cloud.server.gallery.AttachedImageStorage
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.NoticeStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * V1.9.297 -- the data-subject export and erasure of the bulletin board: the export carries the member's own notices (with a removal reason) and
 * the SUM and COUNT of foreign boosts but never who boosted; the erasure removes the own notices, the boosts on them, their picture rows AND
 * files in every mode, keeps the member's own boosts on other notices (booking history) with a reason, and unlinks a moderator from pins and
 * removals under hard deletion only.
 */
class NoticePersonalDataTest :
    FunSpec({
        val gallery =
            GalleryFixtures(sharedStorage = AttachedImageStorage.fromEnvironment(documentStorageRoot = File("build/document-storage")))
        val fx = NoticeFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            fx.cleanUp()
            gallery.cleanUp()
        }

        test("the export holds the own notices and boosts, the boost sum without any identity of the boosters") {
            val subject = gallery.member(label = "subject")
            val booster = gallery.member(label = "geheimer-booster")
            val notice = fx.notice(author = subject, title = "Mein Aushang", body = "Mein Text")
            fx.boostRow(notice = notice, member = booster, amount = "2.50")
            fx.boostRow(notice = notice, member = subject, amount = "1.00")
            val foreign = fx.notice(author = booster, title = "Fremder Aushang")
            fx.boostRow(notice = foreign, member = subject, amount = "4.00")
            val json = transaction { NoticePersonalData.exportMember(subject) }
            val own =
                json.jsonObject
                    .getValue("notices")
                    .jsonArray
                    .single()
                    .jsonObject
            own.getValue("title").jsonPrimitive.content shouldBe "Mein Aushang"
            own.getValue("body").jsonPrimitive.content shouldBe "Mein Text"
            own.getValue("boostsReceivedFromOthersCount").jsonPrimitive.content shouldBe "1"
            own.getValue("boostsReceivedFromOthersSumLtr").jsonPrimitive.content shouldBe "2.50"
            val given = json.jsonObject.getValue("boostsGiven").jsonArray
            given shouldHaveSize 2
            given
                .map {
                    it.jsonObject
                        .getValue("amountLtr")
                        .jsonPrimitive.content
                }.toSet() shouldBe setOf("1.00", "4.00")
            json.toString() shouldNotContain booster.toString()
            json.toString() shouldNotContain "geheimer-booster"
            json.toString() shouldNotContain "Fremder Aushang"
        }

        test("the export carries the removal reason and note the board gave") {
            val subject = gallery.member(label = "subject")
            fx.notice(author = subject, status = NoticeStatus.REMOVED, removedBy = null)
            val own =
                transaction { NoticePersonalData.exportMember(subject) }
                    .jsonObject
                    .getValue("notices")
                    .jsonArray
                    .single()
                    .jsonObject
            own.getValue("status").jsonPrimitive.content shouldBe "REMOVED"
            own.getValue("removalReason").jsonPrimitive.content shouldBe "SPAM"
            own.getValue("removalNote").jsonPrimitive.content shouldBe "Notiz"
        }

        ErasureMode.entries.forEach { mode ->
            test(
                "erasure ($mode): own notices, boosts on them, picture rows and FILES are gone; other people's notices and ledger rows stay",
            ) {
                val subject = gallery.member(label = "subject-$mode")
                val other = gallery.member(label = "other-$mode")
                val ownNotice = fx.notice(author = subject)
                val image =
                    gallery.addImage(
                        type = AttachedImageOwnerType.NOTICE,
                        ownerId = ownNotice,
                        caller = gallery.current(memberId = subject),
                    )
                fx.boostRow(notice = ownNotice, member = other, amount = "3.00")
                fx.grant(member = other, amount = "10.00")
                val otherNotice = fx.notice(author = other)
                val otherImage =
                    gallery.addImage(
                        type = AttachedImageOwnerType.NOTICE,
                        ownerId = otherNotice,
                        caller = gallery.current(memberId = other),
                    )
                fx.boostRow(notice = otherNotice, member = subject, amount = "1.50")
                val outcomes = transaction { NoticePersonalData.eraseMember(memberId = subject, mode = mode) }
                transaction { BulletinNoticeTable.selectAll().where { BulletinNoticeTable.authorMemberId eq subject }.toList() }
                    .shouldBeEmpty()
                transaction { BulletinNoticeBoostTable.selectAll().where { BulletinNoticeBoostTable.noticeId eq ownNotice }.toList() }
                    .shouldBeEmpty()
                gallery.rows(type = AttachedImageOwnerType.NOTICE, ownerId = ownNotice).shouldBeEmpty()
                gallery.storage.resolve(storageKey = image.storageKey, mimeType = image.mimeType).shouldBeNull()
                // the other member's notice, its picture and its boost row stay
                transaction { BulletinNoticeTable.selectAll().where { BulletinNoticeTable.id eq otherNotice }.count() } shouldBe 1L
                gallery.rows(type = AttachedImageOwnerType.NOTICE, ownerId = otherNotice) shouldHaveSize 1
                gallery.storage.resolve(storageKey = otherImage.storageKey, mimeType = otherImage.mimeType).shouldNotBeNull()
                transaction { BulletinNoticeBoostTable.selectAll().where { BulletinNoticeBoostTable.memberId eq subject }.count() } shouldBe
                    1L
                outcomes.single { it.table == "bulletin_notice" }.rowsDeleted shouldBe 1
                outcomes.single { it.table == "bulletin_notice_boost" && it.rowsRetained > 0 }.retentionReason.orEmpty() shouldContain
                    "LTR-Buchungshistorie"
                // ledger history of the OTHER member is untouched
                fx.ledgerRows(other).count {
                    it[network.lapis.cloud.server.db.generated.LtrLedgerEntryTable.entryType] ==
                        LtrLedgerEntryType.MINT
                } shouldBe
                    1
            }
        }

        test("a moderator's pin and removal are unlinked under HARD_DELETE (time and reason stay) and kept under ANONYMIZE") {
            for (mode in ErasureMode.entries) {
                val moderator = gallery.member(label = "mod-$mode", role = AccountRole.BOARD)
                val author = gallery.member(label = "author-$mode")
                val pinned = fx.notice(author = author, pinned = moderator)
                val removed = fx.notice(author = author, status = NoticeStatus.REMOVED, removedBy = moderator)
                transaction { NoticePersonalData.eraseMember(memberId = moderator, mode = mode) }
                val pinRow = transaction { BulletinNoticeTable.selectAll().where { BulletinNoticeTable.id eq pinned }.single() }
                val removalRow = transaction { BulletinNoticeTable.selectAll().where { BulletinNoticeTable.id eq removed }.single() }
                pinRow[BulletinNoticeTable.pinnedAt].shouldNotBeNull()
                removalRow[BulletinNoticeTable.removedAt].shouldNotBeNull()
                removalRow[BulletinNoticeTable.removalReasonCode].shouldNotBeNull()
                if (mode == ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED) {
                    pinRow[BulletinNoticeTable.pinnedBy].shouldBeNull()
                    removalRow[BulletinNoticeTable.removedBy].shouldBeNull()
                } else {
                    pinRow[BulletinNoticeTable.pinnedBy] shouldBe moderator
                    removalRow[BulletinNoticeTable.removedBy] shouldBe moderator
                }
            }
        }

        test("the contributor is registered and covers both tables") {
            network.lapis.cloud.server.dsgvo.PersonalDataRegistry.contributors
                .any { it === NoticePersonalData } shouldBe true
            NoticePersonalData.coveredTables.map { it.tableName } shouldBe listOf("bulletin_notice", "bulletin_notice_boost")
        }
    })
