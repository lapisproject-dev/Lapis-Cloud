package network.lapis.cloud.server.bulletin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AttachedImageTable
import network.lapis.cloud.server.db.generated.BulletinNoticeBoostTable
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.NoticeCategory
import network.lapis.cloud.shared.domain.NoticeRemovalReason
import network.lapis.cloud.shared.domain.NoticeStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.297 -- the tables and CHECKs of `V149__bulletin_board.sql` on H2 AND PostgreSQL with the same assertions (the `INTERVAL '90' DAY` rule in
 * particular must run on both). Every refused statement runs in its OWN transaction (on PostgreSQL a failed statement aborts the transaction).
 * The file is purely additive and repeatable: it runs a second time on the live schema.
 */
abstract class BulletinBoardMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = NoticeFixtures()
        val created = LocalDateTime(2026, 11, 1, 10, 0, 0)

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest {
            transaction {
                BulletinNoticeBoostTable.deleteAll()
                BulletinNoticeTable.deleteAll()
            }
        }
        afterSpec {
            try {
                transaction {
                    BulletinNoticeBoostTable.deleteAll()
                    BulletinNoticeTable.deleteAll()
                    AttachedImageTable.deleteWhere { AttachedImageTable.uploadedBy inList fx.memberIds }
                }
                fx.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        fun refused(block: () -> Unit): Boolean =
            try {
                transaction { block() }
                false
            } catch (_: Exception) {
                true
            }

        fun insertNotice(
            author: Uuid,
            title: String = "Ein Aushang",
            body: String = "Ein Text",
            expiresAt: LocalDateTime = LocalDateTime(2026, 11, 20, 10, 0, 0),
            status: NoticeStatus = NoticeStatus.ACTIVE,
            withdrawnAt: LocalDateTime? = null,
            removedAt: LocalDateTime? = null,
            reason: NoticeRemovalReason? = null,
            note: String? = null,
            pinnedAt: LocalDateTime? = null,
            pinnedBy: Uuid? = null,
            removedBy: Uuid? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                BulletinNoticeTable.insert {
                    it[BulletinNoticeTable.id] = id
                    it[authorMemberId] = author
                    it[category] = NoticeCategory.INFO
                    it[BulletinNoticeTable.title] = title
                    it[BulletinNoticeTable.body] = body
                    it[createdAt] = created
                    it[updatedAt] = null
                    it[BulletinNoticeTable.expiresAt] = expiresAt
                    it[BulletinNoticeTable.pinnedAt] = pinnedAt
                    it[BulletinNoticeTable.pinnedBy] = pinnedBy
                    it[BulletinNoticeTable.status] = status
                    it[BulletinNoticeTable.withdrawnAt] = withdrawnAt
                    it[BulletinNoticeTable.removedAt] = removedAt
                    it[BulletinNoticeTable.removedBy] = removedBy
                    it[removalReasonCode] = reason
                    it[removalNote] = note
                }
            }
            return id
        }

        test("a valid notice is stored; title 3 and 120, text 1 and 2000 characters pass, 2 / 121 / 0 / 2001 are refused") {
            val a = fx.member()
            insertNotice(a, title = "abc")
            insertNotice(a, title = "x".repeat(120))
            insertNotice(a, body = "x")
            insertNotice(a, body = "x".repeat(2000))
            refused { insertNotice(a, title = "ab") } shouldBe true
            refused { insertNotice(a, title = "x".repeat(121)) } shouldBe true
            refused { insertNotice(a, body = "") } shouldBe true
            refused { insertNotice(a, body = "x".repeat(2001)) } shouldBe true
        }

        test("expiry: after the creation and at most 90 days after it, to the minute") {
            val a = fx.member()
            // created 1 Nov 2026 10:00; +90 days = 30 Jan 2027 10:00
            insertNotice(a, expiresAt = LocalDateTime(2026, 11, 1, 10, 1, 0))
            insertNotice(a, expiresAt = LocalDateTime(2027, 1, 30, 10, 0, 0))
            refused { insertNotice(a, expiresAt = LocalDateTime(2027, 1, 30, 10, 1, 0)) } shouldBe true
            refused { insertNotice(a, expiresAt = created) } shouldBe true
            refused { insertNotice(a, expiresAt = LocalDateTime(2026, 10, 31, 10, 0, 0)) } shouldBe true
        }

        test("REMOVED needs removed_at and a reason; a note and a reason without REMOVED are refused; removed_by may be NULL") {
            val a = fx.member()
            val board = fx.member(label = "board")
            val at = LocalDateTime(2026, 11, 2, 10, 0, 0)
            insertNotice(a, status = NoticeStatus.REMOVED, removedAt = at, reason = NoticeRemovalReason.SPAM, note = "n", removedBy = board)
            insertNotice(a, status = NoticeStatus.REMOVED, removedAt = at, reason = NoticeRemovalReason.LEGAL, removedBy = null)
            refused { insertNotice(a, status = NoticeStatus.REMOVED, removedAt = null, reason = NoticeRemovalReason.SPAM) } shouldBe true
            refused { insertNotice(a, status = NoticeStatus.REMOVED, removedAt = at, reason = null) } shouldBe true
            refused { insertNotice(a, status = NoticeStatus.ACTIVE, reason = NoticeRemovalReason.SPAM) } shouldBe true
            refused { insertNotice(a, status = NoticeStatus.ACTIVE, note = "Notiz") } shouldBe true
            refused { insertNotice(a, status = NoticeStatus.ACTIVE, removedAt = at) } shouldBe true
        }

        test("WITHDRAWN needs withdrawn_at and only then") {
            val a = fx.member()
            val at = LocalDateTime(2026, 11, 2, 10, 0, 0)
            insertNotice(a, status = NoticeStatus.WITHDRAWN, withdrawnAt = at)
            refused { insertNotice(a, status = NoticeStatus.WITHDRAWN, withdrawnAt = null) } shouldBe true
            refused { insertNotice(a, status = NoticeStatus.ACTIVE, withdrawnAt = at) } shouldBe true
        }

        test("pinned_by only together with pinned_at; a pin without a moderator (erased) is allowed") {
            val a = fx.member()
            val board = fx.member(label = "board")
            val at = LocalDateTime(2026, 11, 2, 10, 0, 0)
            insertNotice(a, pinnedAt = at, pinnedBy = board)
            insertNotice(a, pinnedAt = at, pinnedBy = null)
            refused { insertNotice(a, pinnedAt = null, pinnedBy = board) } shouldBe true
        }

        test("a boost of less than 0.01 is refused, 0.01 passes; the money column keeps two decimals") {
            val a = fx.member()
            val notice = insertNotice(a)

            fun boost(value: String) =
                transaction {
                    BulletinNoticeBoostTable.insert {
                        it[id] = Uuid.random()
                        it[noticeId] = notice
                        it[memberId] = a
                        it[amountLtr] = BigDecimal(value)
                        it[boostedAt] = created
                    }
                }
            boost("0.01")
            boost("12345678901234.56")
            refused { boost("0.00") } shouldBe true
            refused { boost("-1.00") } shouldBe true
        }

        test("the ledger, picture and audit CHECKs accept the new literals") {
            val a = fx.member()
            transaction {
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = a
                    it[entryType] = LtrLedgerEntryType.NOTICE_BOOST
                    it[amountLtr] = BigDecimal("-1.00")
                    it[referenceType] = LtrLedgerReferenceType.NOTICE
                    it[referenceId] = Uuid.random()
                    it[note] = "t"
                    it[createdBy] = null
                    it[createdAt] = created
                }
                AttachedImageTable.insert {
                    it[id] = Uuid.random()
                    it[ownerType] = AttachedImageOwnerType.NOTICE
                    it[ownerId] = Uuid.random()
                    it[position] = 0
                    it[caption] = null
                    it[altText] = null
                    it[storageKey] = Uuid.random()
                    it[mimeType] = "image/jpeg"
                    it[byteSize] = 10L
                    it[width] = 1
                    it[height] = 1
                    it[uploadedBy] = a
                    it[createdAt] = created
                }
            }
        }

        test("the migration is repeatable and keeps every rule in force") {
            val sql =
                checkNotNull(
                    BulletinBoardMigrationScenarios::class.java.classLoader.getResourceAsStream("db/migration/V149__bulletin_board.sql"),
                ).use { String(it.readBytes(), Charsets.UTF_8) }
            val statements =
                sql
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            (statements.size >= 20) shouldBe true
            transaction { statements.forEach { exec(it) } }
            val a = fx.member()
            refused { insertNotice(a, title = "ab") } shouldBe true
            refused { insertNotice(a, expiresAt = LocalDateTime(2027, 1, 30, 10, 1, 0)) } shouldBe true
            insertNotice(a)
        }
    })
