package network.lapis.cloud.server.bulletin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.BulletinNoticeBoostTable
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.NoticeStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.297 -- housekeeping: `ACTIVE` with a passed expiry becomes `EXPIRED` (cosmetic), a closed notice is deleted for good 30 days after it
 * closed (expired from `expires_at`, withdrawn from `withdrawn_at`, removed from `removed_at`), with its boost rows and picture rows; the picture
 * FILES go only after the commit; the LTR ledger rows stay.
 */
class NoticeRetentionTest :
    FunSpec({
        val gallery = GalleryFixtures()
        val fx = NoticeFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest {
            transaction {
                BulletinNoticeBoostTable.deleteAll()
                BulletinNoticeTable.deleteAll()
            }
        }
        afterSpec {
            transaction {
                BulletinNoticeBoostTable.deleteAll()
                BulletinNoticeTable.deleteAll()
            }
            fx.cleanUp()
            gallery.cleanUp()
        }

        fun exists(id: Uuid) = transaction { BulletinNoticeTable.selectAll().where { BulletinNoticeTable.id eq id }.count() } == 1L

        test("expireDue flips only the ACTIVE notices whose expiry passed, and drops their pin") {
            val a = fx.member(label = "author")
            val board = fx.member(label = "board")
            val live = fx.notice(author = a)
            val gone = fx.notice(author = a, pinned = board).also { fx.expire(it) }
            NoticeRetention.expireDue(DbClock.nowLocalDateTime()) shouldBe 1
            val rows = transaction { BulletinNoticeTable.selectAll().associateBy { it[BulletinNoticeTable.id] } }
            rows.getValue(live)[BulletinNoticeTable.status] shouldBe NoticeStatus.ACTIVE
            rows.getValue(gone)[BulletinNoticeTable.status] shouldBe NoticeStatus.EXPIRED
            rows.getValue(gone)[BulletinNoticeTable.pinnedAt] shouldBe null
            NoticeRetention.expireDue(DbClock.nowLocalDateTime()) shouldBe 0
        }

        test("deleteDue: day 29 stays, day 31 goes, for expired, withdrawn and removed notices alike") {
            val a = fx.member(label = "author")
            val now = DbClock.nowLocalDateTime()
            val expired31 = fx.notice(author = a, title = "Abgelaufen 31", createdHoursAgo = 24 * 45, expiresInDays = -31)
            val expired29 = fx.notice(author = a, title = "Abgelaufen 29", createdHoursAgo = 24 * 45, expiresInDays = -29)
            val withdrawn31 =
                fx.notice(
                    author = a,
                    title = "Zurueck 31",
                    status = NoticeStatus.WITHDRAWN,
                    createdHoursAgo = 24 * 45,
                    withdrawnAt = shift(base = now, days = -31),
                )
            val withdrawn29 =
                fx.notice(
                    author = a,
                    title = "Zurueck 29",
                    status = NoticeStatus.WITHDRAWN,
                    createdHoursAgo = 24 * 45,
                    withdrawnAt = shift(base = now, days = -29),
                )
            val removed31 =
                fx.notice(
                    author = a,
                    title = "Entfernt 31",
                    status = NoticeStatus.REMOVED,
                    createdHoursAgo = 24 * 45,
                    removedAt = shift(base = now, days = -31),
                )
            val removed29 =
                fx.notice(
                    author = a,
                    title = "Entfernt 29",
                    status = NoticeStatus.REMOVED,
                    createdHoursAgo = 24 * 45,
                    removedAt = shift(base = now, days = -29),
                )
            val live = fx.notice(author = a, title = "Lebendig")
            NoticeRetention.deleteDue(now).shouldBeEmpty()
            listOf(expired31, withdrawn31, removed31).forEach { exists(it) shouldBe false }
            listOf(expired29, withdrawn29, removed29, live).forEach { exists(it) shouldBe true }
        }

        test(
            "a due notice takes its boost rows and picture rows along, the files are returned for deletion after the commit, the ledger stays",
        ) {
            val author = gallery.member(label = "author")
            val booster = gallery.member(label = "booster")
            val notice = fx.notice(author = author, createdHoursAgo = 24 * 45)
            val image =
                gallery.addImage(
                    type = AttachedImageOwnerType.NOTICE,
                    ownerId = notice,
                    caller = gallery.current(memberId = author),
                )
            fx.boostRow(notice = notice, member = booster, amount = "2.00")
            fx.grant(member = booster, amount = "5.00")
            transaction {
                BulletinNoticeTable.update({ BulletinNoticeTable.id eq notice }) {
                    it[expiresAt] =
                        shift(base = DbClock.nowLocalDateTime(), days = -31)
                }
            }
            val keys = NoticeRetention.deleteDue(DbClock.nowLocalDateTime())
            keys shouldContainExactlyInAnyOrder listOf(image.storageKey)
            // the file is still on disk: only the caller deletes it, AFTER the commit
            (gallery.storage.resolve(storageKey = image.storageKey, mimeType = image.mimeType) != null) shouldBe true
            gallery.rows(type = AttachedImageOwnerType.NOTICE, ownerId = notice).shouldBeEmpty()
            transaction { BulletinNoticeBoostTable.selectAll().count() } shouldBe 0L
            gallery.storage.deleteAfterCommit(keys)
            gallery.storage.resolve(storageKey = image.storageKey, mimeType = image.mimeType) shouldBe null
            fx.ledgerRows(booster).count {
                it[network.lapis.cloud.server.db.generated.LtrLedgerEntryTable.entryType] ==
                    LtrLedgerEntryType.MINT
            } shouldBe
                1
        }

        test("the poller's tick does the whole job, deletes the files and never throws; start and stop are idempotent") {
            val author = gallery.member(label = "author")
            val notice = fx.notice(author = author, createdHoursAgo = 24 * 45)
            val image =
                gallery.addImage(
                    type = AttachedImageOwnerType.NOTICE,
                    ownerId = notice,
                    caller = gallery.current(memberId = author),
                )
            transaction {
                BulletinNoticeTable.update({ BulletinNoticeTable.id eq notice }) {
                    it[expiresAt] =
                        shift(base = DbClock.nowLocalDateTime(), days = -40)
                }
            }
            val poller = NoticeRetentionPoller(storage = gallery.storage)
            poller.tick()
            exists(notice) shouldBe false
            gallery.storage.resolve(storageKey = image.storageKey, mimeType = image.mimeType) shouldBe null
            poller.tick()
            poller.start()
            poller.start()
            poller.stop()
            poller.stop()
        }
    })
