package network.lapis.cloud.server.bulletin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AttachedImageTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BulletinNoticeBoostTable
import network.lapis.cloud.server.db.generated.BulletinNoticeTable
import network.lapis.cloud.server.messaging.DirectMessageBlockStore
import network.lapis.cloud.server.rpc.withServiceCaller
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.NoticeCategory
import network.lapis.cloud.shared.domain.NoticeListQuery
import network.lapis.cloud.shared.domain.NoticeRemovalInput
import network.lapis.cloud.shared.domain.NoticeRemovalReason
import network.lapis.cloud.shared.domain.NoticeRules
import network.lapis.cloud.shared.domain.NoticeStatus
import network.lapis.cloud.shared.domain.NoticeUpdateInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.DirectMessageRecipientUnavailableException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val SECRET_TITLE = "GEHEIMER-TITEL-4711"
private const val SECRET_BODY = "GEHEIMER-TEXT-4712"
private const val SECRET_NOTE = "GEHEIME-NOTIZ-4713"

private fun amount(value: String) = BigDecimal(value)

/**
 * V1.9.297 -- the bulletin-board service end to end on H2 AND PostgreSQL: the access gate on every method, the caps, visibility, editing,
 * the LTR boost (atomic ledger entry, no double spend, every negative case), the reply as a direct message, moderation, the audit entries
 * (no content), the order and the races (two debits, a boost against a withdrawal, the cap of five).
 */
abstract class NoticeBoardScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = NoticeFixtures()

        fun wipeBoard() {
            transaction {
                AttachedImageTable.deleteWhere { AttachedImageTable.ownerType eq AttachedImageOwnerType.NOTICE }
                BulletinNoticeBoostTable.deleteAll()
                BulletinNoticeTable.deleteAll()
            }
        }

        beforeSpec {
            db.activate()
            if (!db.isPostgres) {
                DatabaseConfig.connect()
                DevSeedData.seedIfEmpty(force = true)
            }
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest { wipeBoard() }
        afterSpec {
            try {
                wipeBoard()
                fx.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        context("access: only ACTIVE members, on every method") {
            val outsiders =
                listOf(
                    "GUEST" to MemberStatus.GUEST,
                    "FRIEND" to MemberStatus.FRIEND,
                    "APPLICATION" to MemberStatus.APPLICATION,
                    "WITHDRAWN" to MemberStatus.WITHDRAWN,
                    "DONOR" to MemberStatus.DONOR,
                )
            outsiders.forEach { (name, status) ->
                test("a $name is refused by all eleven methods, before any lookup") {
                    val author = fx.member(label = "author")
                    val notice = fx.notice(author = author)
                    val outsider = fx.member(label = name, status = status, role = AccountRole.BOARD)
                    val id = notice.toString()
                    withServiceCaller {
                        val results =
                            listOf(
                                asBoard(who = outsider) { it.listNotices(NoticeListQuery()) },
                                asBoard(who = outsider) { it.listMyNotices() },
                                asBoard(who = outsider) { it.getNotice(id) },
                                asBoard(who = outsider) { it.createNotice(noticeInput()) },
                                asBoard(
                                    who = outsider,
                                ) {
                                    it.updateNotice(
                                        id = id,
                                        input =
                                            NoticeUpdateInput(
                                                category = NoticeCategory.INFO,
                                                title = "Neuer Titel",
                                                body = "Text",
                                                expiresOn = inDays(5),
                                            ),
                                    )
                                },
                                asBoard(who = outsider) { it.withdrawNotice(id) },
                                asBoard(who = outsider) { it.boostNotice(id = id, amountLtr = amount("1.00")) },
                                asBoard(who = outsider) { it.replyToNotice(id = id, message = "Hallo") },
                                asBoard(who = outsider) { it.pinNotice(id) },
                                asBoard(who = outsider) { it.unpinNotice(id) },
                                asBoard(
                                    who = outsider,
                                ) { it.removeNotice(id = id, input = NoticeRemovalInput(reason = NoticeRemovalReason.SPAM)) },
                            )
                        results.forEachIndexed { index, r ->
                            r.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>().also { _ -> index.toString() }
                        }
                    }
                    fx.notices(author) shouldHaveSize 1
                }
            }

            test("an anonymized former member is refused as well") {
                val anonymous = fx.member(label = "anon", status = MemberStatus.WITHDRAWN, anonymized = true)
                withServiceCaller {
                    asBoard(
                        who = anonymous,
                    ) { it.listNotices(NoticeListQuery()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
            }

            test("pin, unpin and remove need BOARD or ADMIN on top of the membership") {
                val author = fx.member(label = "author")
                val plain = fx.member(label = "plain")
                val notice = fx.notice(author = author)
                withServiceCaller {
                    asBoard(who = plain) { it.pinNotice(notice.toString()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asBoard(who = plain) { it.unpinNotice(notice.toString()) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    asBoard(
                        who = plain,
                    ) { it.removeNotice(id = notice.toString(), input = NoticeRemovalInput(reason = NoticeRemovalReason.SPAM)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                }
                transaction { BulletinNoticeTable.selectAll().single()[BulletinNoticeTable.status] } shouldBe NoticeStatus.ACTIVE
            }
        }

        context("creating") {
            test("a notice is created ACTIVE with weight 0.00, the right expiry instant and one audit entry without content") {
                val a = fx.member(label = "author")
                withServiceCaller {
                    val dto =
                        asBoard(
                            who = a,
                        ) { it.createNotice(noticeInput(title = SECRET_TITLE, body = SECRET_BODY, expiresInDays = 14)) }.getOrThrow()
                    dto.status shouldBe NoticeStatus.ACTIVE
                    dto.weightLtr shouldBe amount("0.00")
                    dto.isMine shouldBe true
                    dto.canEdit shouldBe true
                    dto.canBoost shouldBe true
                    dto.canReply shouldBe false
                    dto.expiresOn shouldBe inDays(14)
                    dto.title shouldBe SECRET_TITLE
                    val stored = transaction { BulletinNoticeTable.selectAll().single() }
                    stored[BulletinNoticeTable.expiresAt] shouldBe NoticeValidation.expiresAtFor(expiresOn = inDays(14))
                    val audits = fx.audits(stored[BulletinNoticeTable.id])
                    audits shouldHaveSize 1
                    audits.single()[AuditLogEntryTable.entityType] shouldBe AuditEntityType.BULLETIN_NOTICE
                    val snapshot = audits.single()[AuditLogEntryTable.afterSnapshot].orEmpty()
                    snapshot shouldContain "CREATE"
                    snapshot shouldNotContain SECRET_TITLE
                    snapshot shouldNotContain SECRET_BODY
                }
            }

            test("a sixth active notice is refused; expired and withdrawn ones do not count") {
                val a = fx.member(label = "author")
                fx.notice(author = a, status = NoticeStatus.WITHDRAWN)
                val old = fx.notice(author = a)
                fx.expire(old)
                withServiceCaller {
                    repeat(NoticeRules.MAX_ACTIVE_PER_MEMBER) { n ->
                        asBoard(who = a) { it.createNotice(noticeInput(title = "Aushang Nummer $n")) }.getOrThrow()
                    }
                    asBoard(
                        who = a,
                    ) { it.createNotice(noticeInput(title = "Aushang sechs")) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    asBoard(who = a) { it.listNotices(NoticeListQuery()) }.getOrThrow().myActiveCount shouldBe
                        NoticeRules.MAX_ACTIVE_PER_MEMBER
                }
            }

            test("title, text and expiry are validated; an expiry today, tomorrow-1 or beyond 90 days is refused") {
                val a = fx.member(label = "author")
                withServiceCaller {
                    suspend fun bad(input: network.lapis.cloud.shared.domain.NoticeInput) =
                        asBoard(who = a) { it.createNotice(input) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    bad(noticeInput(title = "ab"))
                    bad(noticeInput(title = "x".repeat(121)))
                    bad(noticeInput(body = " "))
                    bad(noticeInput(body = "x".repeat(2001)))
                    bad(noticeInput(expiresInDays = 0))
                    bad(noticeInput(expiresInDays = 91))
                    asBoard(who = a) { it.createNotice(noticeInput(title = "Morgen", expiresInDays = 1)) }.getOrThrow()
                    asBoard(who = a) { it.createNotice(noticeInput(title = "Neunzig", expiresInDays = 89)) }.getOrThrow()
                }
            }

            test("the same title twice within five seconds is a double submit") {
                val a = fx.member(label = "author")
                withServiceCaller {
                    asBoard(who = a) { it.createNotice(noticeInput(title = "Doppelt")) }.getOrThrow()
                    asBoard(
                        who = a,
                    ) { it.createNotice(noticeInput(title = "Doppelt")) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                }
                fx.notices(a) shouldHaveSize 1
            }

            test("the write budget is enforced before the transaction") {
                val a = fx.member(label = "author")
                val limiter = NoticeBoardRateLimiter()
                repeat(NoticeBoardAction.WRITE.limit) { limiter.tryAcquire(memberId = a, action = NoticeBoardAction.WRITE) }
                val deps = NoticeBoardDeps(limiter = limiter)
                withServiceCaller {
                    val failure =
                        asBoard(
                            who = a,
                            deps = deps,
                        ) { it.createNotice(noticeInput()) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    failure.message.orEmpty() shouldContain "Zu viele"
                }
                fx.notices(a).shouldBeEmpty()
            }

            test("an initial stake books the boost atomically; with too little balance the notice is NOT created") {
                val rich = fx.member(label = "rich")
                val poor = fx.member(label = "poor")
                fx.grant(member = rich, amount = "10.00")
                fx.grant(member = poor, amount = "1.00")
                withServiceCaller {
                    val dto = asBoard(who = rich) { it.createNotice(noticeInput(stake = "4.00")) }.getOrThrow()
                    dto.weightLtr shouldBe amount("4.00")
                    asBoard(who = poor) { it.createNotice(noticeInput(title = "Zu teuer", stake = "2.00")) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                }
                fx.balance(rich) shouldBe amount("6.00")
                fx.ledgerRows(rich).count {
                    it[network.lapis.cloud.server.db.generated.LtrLedgerEntryTable.entryType] ==
                        LtrLedgerEntryType.NOTICE_BOOST
                } shouldBe
                    1
                fx.notices(poor).shouldBeEmpty()
                fx.balance(poor) shouldBe amount("1.00")
                transaction { BulletinNoticeBoostTable.selectAll().count() } shouldBe 1L
            }
        }

        context("visibility") {
            test("a notice whose expiry passed is gone for strangers although its status still says ACTIVE; the author still finds it") {
                val a = fx.member(label = "author")
                val b = fx.member(label = "reader")
                val gone = fx.notice(author = a, title = "Abgelaufen")
                fx.expire(gone)
                withServiceCaller {
                    asBoard(who = b) { it.listNotices(NoticeListQuery()) }.getOrThrow().let { it.items + it.pinned }.shouldBeEmpty()
                    asBoard(who = b) { it.getNotice(gone.toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    val mine = asBoard(who = a) { it.listMyNotices() }.getOrThrow()
                    mine.single().status shouldBe NoticeStatus.EXPIRED
                    mine.single().canBoost shouldBe false
                }
            }

            test("withdrawn and removed notices are invisible to others; the author sees them with the removal reason") {
                val a = fx.member(label = "author")
                val b = fx.member(label = "reader")
                val board = fx.member(label = "board", role = AccountRole.BOARD)
                val withdrawn = fx.notice(author = a, title = "Zurueckgezogen", status = NoticeStatus.WITHDRAWN)
                val removed = fx.notice(author = a, title = "Entfernt", status = NoticeStatus.REMOVED, removedBy = board)
                withServiceCaller {
                    asBoard(who = b) { it.listNotices(NoticeListQuery()) }.getOrThrow().items.shouldBeEmpty()
                    asBoard(who = b) { it.getNotice(withdrawn.toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    asBoard(who = b) { it.getNotice(removed.toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    val mine = asBoard(who = a) { it.listMyNotices() }.getOrThrow().associateBy { it.id }
                    mine.getValue(removed.toString()).status shouldBe NoticeStatus.REMOVED
                    mine.getValue(removed.toString()).removalReason shouldBe NoticeRemovalReason.SPAM
                    mine.getValue(removed.toString()).removalNote shouldBe "Notiz"
                    mine.getValue(withdrawn.toString()).status shouldBe NoticeStatus.WITHDRAWN
                    // a fellow member never sees reason or note
                    val other = asBoard(who = b) { it.getNotice(removed.toString()) }
                    other.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
            }

            test("a notice of an author who is no longer an active member disappears, and so does its id") {
                val a = fx.member(label = "author")
                val b = fx.member(label = "reader")
                val notice = fx.notice(author = a)
                fx.setStatus(member = a, status = MemberStatus.WITHDRAWN)
                withServiceCaller {
                    asBoard(who = b) { it.listNotices(NoticeListQuery()) }.getOrThrow().items.shouldBeEmpty()
                    asBoard(who = b) { it.getNotice(notice.toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    asBoard(
                        who = b,
                    ) {
                        it.boostNotice(
                            id = notice.toString(),
                            amountLtr = amount("1.00"),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    asBoard(
                        who = b,
                    ) {
                        it.replyToNotice(
                            id = notice.toString(),
                            message = "Hallo",
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
            }

            test("an unknown, a malformed and a foreign id are the SAME answer, which never echoes the input") {
                val b = fx.member(label = "reader")
                val socialPostId = Uuid.random().toString()
                withServiceCaller {
                    val answers =
                        listOf("not-a-uuid", Uuid.random().toString(), socialPostId).map { id ->
                            asBoard(who = b) { it.getNotice(id) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>().also {
                                it.message.orEmpty() shouldNotContain id
                            }
                        }
                    answers.map { it.message }.toSet() shouldHaveSize 1
                }
            }

            test("page size is capped at 100; pinned notices are always complete and independent of the offset") {
                val authors = (1..21).map { fx.member(label = "a$it") }
                val board = fx.member(label = "board", role = AccountRole.BOARD)
                authors.forEach { author ->
                    repeat(5) { n -> fx.notice(author = author, title = "Aushang $n von ${author.toString().take(4)}") }
                }
                val pinned = fx.notice(author = board, title = "Amtlich", pinned = board)
                val reader = fx.member(label = "reader")
                withServiceCaller {
                    val page = asBoard(who = reader) { it.listNotices(NoticeListQuery(limit = 101)) }.getOrThrow()
                    page.items shouldHaveSize 100
                    page.total shouldBe 105
                    page.pinned.map { it.id } shouldContainExactly listOf(pinned.toString())
                    val second = asBoard(who = reader) { it.listNotices(NoticeListQuery(offset = 100, limit = 100)) }.getOrThrow()
                    second.items shouldHaveSize 5
                    second.pinned.map { it.id } shouldContainExactly listOf(pinned.toString())
                    val negative = asBoard(who = reader) { it.listNotices(NoticeListQuery(offset = -4, limit = 0)) }.getOrThrow()
                    negative.offset shouldBe 0
                    negative.items shouldHaveSize 1
                }
            }

            test("the category filter narrows the board") {
                val a = fx.member(label = "author")
                val reader = fx.member(label = "reader")
                fx.notice(author = a, title = "Suche etwas", category = NoticeCategory.SEARCH)
                fx.notice(author = a, title = "Biete etwas", category = NoticeCategory.OFFER)
                withServiceCaller {
                    asBoard(who = reader) { it.listNotices(NoticeListQuery(category = NoticeCategory.SEARCH)) }
                        .getOrThrow()
                        .items
                        .map { it.title } shouldContainExactly listOf("Suche etwas")
                }
            }
        }

        context("order") {
            test("pinned first, then the heavier notice, then the newer one") {
                val a = fx.member(label = "author")
                val booster = fx.member(label = "booster")
                val board = fx.member(label = "board", role = AccountRole.BOARD)
                val light = fx.notice(author = a, title = "Leicht", createdHoursAgo = 3)
                val heavy = fx.notice(author = a, title = "Schwer", createdHoursAgo = 5)
                val plainOld = fx.notice(author = a, title = "Ohne Einsatz alt", createdHoursAgo = 8)
                val plainNew = fx.notice(author = a, title = "Ohne Einsatz neu", createdHoursAgo = 2)
                val official = fx.notice(author = board, title = "Amtlich", pinned = board)
                fx.boostRow(notice = heavy, member = booster, amount = "5.00")
                fx.boostRow(notice = light, member = booster, amount = "1.00")
                withServiceCaller {
                    val page = asBoard(who = booster) { it.listNotices(NoticeListQuery()) }.getOrThrow()
                    page.pinned.map { it.id } shouldContainExactly listOf(official.toString())
                    page.items.map { it.id } shouldContainExactly listOf(heavy, light, plainNew, plainOld).map { it.toString() }
                    page.items.first().weightLtr shouldBe amount("5.00")
                    page.items.last().weightLtr shouldBe amount("0.00")
                }
            }

            test("the weight decays by 10 percent per day and the order follows") {
                val a = fx.member(label = "author")
                val booster = fx.member(label = "booster")
                val older = fx.notice(author = a, title = "Alter Einsatz", createdHoursAgo = 100)
                val fresh = fx.notice(author = a, title = "Frischer Einsatz", createdHoursAgo = 100)
                fx.boostRow(notice = older, member = booster, amount = "10.00", hoursAgo = 48)
                fx.boostRow(notice = fresh, member = booster, amount = "9.00", hoursAgo = 0)
                withServiceCaller {
                    val items = asBoard(who = booster) { it.listNotices(NoticeListQuery()) }.getOrThrow().items
                    items.map { it.id } shouldContainExactly listOf(fresh, older).map { it.toString() }
                    items.first { it.id == older.toString() }.weightLtr shouldBe amount("8.10")
                }
            }
        }

        context("editing and withdrawing") {
            test("only the author edits; the edit sets updatedAt; a later extension beyond 90 days from the creation is refused") {
                val a = fx.member(label = "author")
                val b = fx.member(label = "other")
                withServiceCaller {
                    val dto = asBoard(who = a) { it.createNotice(noticeInput(title = "Original")) }.getOrThrow()
                    dto.updatedAt.shouldBeNull()
                    val update =
                        NoticeUpdateInput(category = NoticeCategory.INFO, title = "Geaendert", body = "Neuer Text", expiresOn = inDays(20))
                    asBoard(
                        who = b,
                    ) { it.updateNotice(id = dto.id, input = update) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    val edited = asBoard(who = a) { it.updateNotice(id = dto.id, input = update) }.getOrThrow()
                    edited.title shouldBe "Geaendert"
                    edited.category shouldBe NoticeCategory.INFO
                    edited.updatedAt.shouldNotBeNull()
                    edited.expiresOn shouldBe inDays(20)
                    asBoard(
                        who = a,
                    ) {
                        it.updateNotice(
                            id = dto.id,
                            input = update.copy(expiresOn = inDays(95)),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                }
            }

            test("the category is locked once somebody else spent LTR, but not after the author's own stake") {
                val a = fx.member(label = "author")
                val b = fx.member(label = "booster")
                fx.grant(member = a, amount = "5.00")
                fx.grant(member = b, amount = "5.00")
                withServiceCaller {
                    val dto = asBoard(who = a) { it.createNotice(noticeInput(stake = "1.00")) }.getOrThrow()
                    dto.categoryLocked shouldBe false
                    val toInfo =
                        NoticeUpdateInput(category = NoticeCategory.INFO, title = dto.title, body = dto.body, expiresOn = dto.expiresOn)
                    asBoard(who = a) { it.updateNotice(id = dto.id, input = toInfo) }.getOrThrow().category shouldBe NoticeCategory.INFO
                    asBoard(who = b) { it.boostNotice(id = dto.id, amountLtr = amount("1.00")) }.getOrThrow().categoryLocked shouldBe true
                    asBoard(who = a) { it.updateNotice(id = dto.id, input = toInfo.copy(category = NoticeCategory.OFFER)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                }
            }

            test("a withdrawn notice cannot be edited, withdrawn again or boosted; spent LTR is not refunded; a pin is dropped") {
                val a = fx.member(label = "author")
                val board = fx.member(label = "board", role = AccountRole.BOARD)
                fx.grant(member = a, amount = "5.00")
                withServiceCaller {
                    val dto = asBoard(who = a) { it.createNotice(noticeInput(stake = "2.00")) }.getOrThrow()
                    asBoard(who = board) { it.pinNotice(dto.id) }.getOrThrow().pinnedAt.shouldNotBeNull()
                    val withdrawn = asBoard(who = a) { it.withdrawNotice(dto.id) }.getOrThrow()
                    withdrawn.status shouldBe NoticeStatus.WITHDRAWN
                    withdrawn.pinnedAt.shouldBeNull()
                    asBoard(who = a) { it.withdrawNotice(dto.id) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    asBoard(who = a) {
                        it.updateNotice(
                            id = dto.id,
                            input = NoticeUpdateInput(category = NoticeCategory.INFO, title = "Neu", body = "Text", expiresOn = inDays(5)),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    asBoard(
                        who = a,
                    ) { it.boostNotice(id = dto.id, amountLtr = amount("1.00")) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
                fx.balance(a) shouldBe amount("3.00")
                transaction { BulletinNoticeTable.selectAll().single()[BulletinNoticeTable.withdrawnAt] }.shouldNotBeNull()
            }
        }

        context("boosting") {
            test("a boost books -amount as NOTICE_BOOST for the NOTICE, raises the weight and writes an audit entry without content") {
                val author = fx.member(label = "author")
                val booster = fx.member(label = "booster")
                fx.grant(member = booster, amount = "20.00")
                val notice = fx.notice(author = author, title = SECRET_TITLE, body = SECRET_BODY)
                withServiceCaller {
                    val dto = asBoard(who = booster) { it.boostNotice(id = notice.toString(), amountLtr = amount("7.50")) }.getOrThrow()
                    dto.weightLtr shouldBe amount("7.50")
                    dto.canBoost shouldBe true
                }
                fx.balance(booster) shouldBe amount("12.50")
                val entry =
                    fx.ledgerRows(booster).single {
                        it[network.lapis.cloud.server.db.generated.LtrLedgerEntryTable.entryType] ==
                            LtrLedgerEntryType.NOTICE_BOOST
                    }
                entry[network.lapis.cloud.server.db.generated.LtrLedgerEntryTable.amountLtr] shouldBe amount("-7.50")
                entry[network.lapis.cloud.server.db.generated.LtrLedgerEntryTable.referenceType] shouldBe LtrLedgerReferenceType.NOTICE
                entry[network.lapis.cloud.server.db.generated.LtrLedgerEntryTable.referenceId] shouldBe notice
                entry[network.lapis.cloud.server.db.generated.LtrLedgerEntryTable.note].orEmpty().let {
                    it shouldNotContain SECRET_TITLE
                    it shouldNotContain SECRET_BODY
                }
                val audits = fx.audits(notice)
                audits shouldHaveSize 1
                audits.single()[AuditLogEntryTable.afterSnapshot].orEmpty().let {
                    it shouldContain "BOOST"
                    it shouldNotContain SECRET_TITLE
                }
            }

            test("spending on one's own notice is allowed and booked correctly (open question: owner may forbid it)") {
                val a = fx.member(label = "author")
                fx.grant(member = a, amount = "3.00")
                val notice = fx.notice(author = a)
                withServiceCaller {
                    asBoard(who = a) { it.boostNotice(id = notice.toString(), amountLtr = amount("1.00")) }.getOrThrow().weightLtr shouldBe
                        amount("1.00")
                }
                fx.balance(a) shouldBe amount("2.00")
            }

            test("too little balance: Conflict, no boost row and no ledger row") {
                val author = fx.member(label = "author")
                val booster = fx.member(label = "booster")
                fx.grant(member = booster, amount = "1.00")
                val notice = fx.notice(author = author)
                withServiceCaller {
                    asBoard(
                        who = booster,
                    ) {
                        it.boostNotice(
                            id = notice.toString(),
                            amountLtr = amount("1.01"),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                }
                fx.balance(booster) shouldBe amount("1.00")
                transaction { BulletinNoticeBoostTable.selectAll().count() } shouldBe 0L
            }

            test("three decimals, zero and a negative amount are refused and book nothing") {
                val author = fx.member(label = "author")
                val booster = fx.member(label = "booster")
                fx.grant(member = booster, amount = "10.00")
                val notice = fx.notice(author = author)
                withServiceCaller {
                    listOf("1.001", "0.00", "0.009", "-2.00").forEach { value ->
                        asBoard(
                            who = booster,
                        ) {
                            it.boostNotice(
                                id = notice.toString(),
                                amountLtr = amount(value),
                            )
                        }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    }
                }
                fx.balance(booster) shouldBe amount("10.00")
                transaction { BulletinNoticeBoostTable.selectAll().count() } shouldBe 0L
            }

            test("an expired, a withdrawn and a removed notice cannot be boosted: NotFound and no booking") {
                val author = fx.member(label = "author")
                val booster = fx.member(label = "booster")
                fx.grant(member = booster, amount = "10.00")
                val expired = fx.notice(author = author).also { fx.expire(it) }
                val withdrawn = fx.notice(author = author, status = NoticeStatus.WITHDRAWN)
                val removed = fx.notice(author = author, status = NoticeStatus.REMOVED, removedBy = null)
                withServiceCaller {
                    listOf(expired, withdrawn, removed).forEach { id ->
                        asBoard(
                            who = booster,
                        ) {
                            it.boostNotice(
                                id = id.toString(),
                                amountLtr = amount("1.00"),
                            )
                        }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    }
                    asBoard(who = booster) {
                        it.boostNotice(id = "kaputt", amountLtr = amount("1.00"))
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    asBoard(
                        who = booster,
                    ) {
                        it.boostNotice(
                            id = Uuid.random().toString(),
                            amountLtr = amount("1.00"),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
                fx.balance(booster) shouldBe amount("10.00")
            }

            test("the same amount twice within five seconds is a double click: one booking only") {
                val author = fx.member(label = "author")
                val booster = fx.member(label = "booster")
                fx.grant(member = booster, amount = "10.00")
                val notice = fx.notice(author = author)
                withServiceCaller {
                    asBoard(who = booster) { it.boostNotice(id = notice.toString(), amountLtr = amount("2.00")) }.getOrThrow()
                    asBoard(
                        who = booster,
                    ) {
                        it.boostNotice(
                            id = notice.toString(),
                            amountLtr = amount("2.00"),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    asBoard(who = booster) { it.boostNotice(id = notice.toString(), amountLtr = amount("2.50")) }.getOrThrow()
                }
                fx.balance(booster) shouldBe amount("5.50")
            }

            test("the boost budget is enforced before the transaction") {
                val author = fx.member(label = "author")
                val booster = fx.member(label = "booster")
                fx.grant(member = booster, amount = "100.00")
                val notice = fx.notice(author = author)
                val limiter = NoticeBoardRateLimiter()
                repeat(NoticeBoardAction.BOOST.limit) { limiter.tryAcquire(memberId = booster, action = NoticeBoardAction.BOOST) }
                withServiceCaller {
                    asBoard(
                        who = booster,
                        deps = NoticeBoardDeps(limiter = limiter),
                    ) { it.boostNotice(id = notice.toString(), amountLtr = amount("1.00")) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                }
                fx.balance(booster) shouldBe amount("100.00")
            }
        }

        context("replying") {
            test("a reply is a direct message to the author with the prefix and the title from the database") {
                val author = fx.member(label = "author")
                val reader = fx.member(label = "reader")
                val notice = fx.notice(author = author, title = "Echter Titel")
                withServiceCaller {
                    val dto = asBoard(who = reader) { it.getNotice(notice.toString()) }.getOrThrow()
                    dto.canReply shouldBe true
                    val message =
                        asBoard(
                            who = reader,
                        ) { it.replyToNotice(id = notice.toString(), message = "  Ich habe Interesse.  ") }.getOrThrow()
                    message.body shouldBe "Schwarzes Brett: Echter Titel\n\nIch habe Interesse."
                    message.recipientId shouldBe author.toString()
                    message.senderId shouldBe reader.toString()
                }
                fx.messagesTo(author) shouldHaveSize 1
            }

            test("no reply to one's own notice, and a blank or oversize text is refused") {
                val author = fx.member(label = "author")
                val reader = fx.member(label = "reader")
                val notice = fx.notice(author = author)
                withServiceCaller {
                    asBoard(
                        who = author,
                    ) {
                        it.replyToNotice(
                            id = notice.toString(),
                            message = "Hallo",
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    asBoard(who = reader) {
                        it.replyToNotice(id = notice.toString(), message = "   ")
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    asBoard(
                        who = reader,
                    ) {
                        it.replyToNotice(
                            id = notice.toString(),
                            message = "x".repeat(2001),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                }
                fx.messagesTo(author).shouldBeEmpty()
            }

            test("a block in either direction refuses the reply and turns canReply off, without saying why") {
                val author = fx.member(label = "author")
                val blockedByAuthor = fx.member(label = "blocked")
                val blocker = fx.member(label = "blocker")
                val notice = fx.notice(author = author)
                transaction {
                    DirectMessageBlockStore.block(blocker = author, blocked = blockedByAuthor, now = DbClock.nowLocalDateTime())
                    DirectMessageBlockStore.block(blocker = blocker, blocked = author, now = DbClock.nowLocalDateTime())
                }
                withServiceCaller {
                    listOf(blockedByAuthor, blocker).forEach { reader ->
                        asBoard(who = reader) { it.getNotice(notice.toString()) }.getOrThrow().canReply shouldBe false
                        asBoard(who = reader) { it.replyToNotice(id = notice.toString(), message = "Hallo") }
                            .exceptionOrNull()
                            .shouldBeInstanceOf<DirectMessageRecipientUnavailableException>()
                    }
                }
                fx.messagesTo(author).shouldBeEmpty()
            }

            test("an expired notice cannot be answered") {
                val author = fx.member(label = "author")
                val reader = fx.member(label = "reader")
                val notice = fx.notice(author = author).also { fx.expire(it) }
                withServiceCaller {
                    asBoard(
                        who = reader,
                    ) {
                        it.replyToNotice(
                            id = notice.toString(),
                            message = "Hallo",
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
            }
        }

        context("moderation") {
            test("pin and unpin; the sixth pinned notice is refused") {
                val author = fx.member(label = "author")
                val board = fx.member(label = "board", role = AccountRole.BOARD)
                val notices = (1..6).map { fx.notice(author = author, title = "Aushang $it") }
                withServiceCaller {
                    notices
                        .take(
                            NoticeRules.MAX_PINNED,
                        ).forEach { asBoard(who = board) { service -> service.pinNotice(it.toString()) }.getOrThrow() }
                    asBoard(
                        who = board,
                    ) { it.pinNotice(notices.last().toString()) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    asBoard(who = board) { it.unpinNotice(notices.first().toString()) }.getOrThrow().pinnedAt.shouldBeNull()
                    asBoard(who = board) { it.pinNotice(notices.last().toString()) }.getOrThrow().pinnedAt.shouldNotBeNull()
                }
            }

            test("a pin of an author who is no longer eligible does not block a pin slot") {
                val gone = fx.member(label = "gone-author")
                val author = fx.member(label = "author")
                val board = fx.member(label = "board", role = AccountRole.BOARD)
                val goneNotice = fx.notice(author = gone, title = "Aushang gone")
                val notices = (1..NoticeRules.MAX_PINNED).map { fx.notice(author = author, title = "Aushang $it") }
                withServiceCaller {
                    asBoard(who = board) { it.pinNotice(goneNotice.toString()) }.getOrThrow()
                    notices
                        .take(NoticeRules.MAX_PINNED - 1)
                        .forEach { asBoard(who = board) { service -> service.pinNotice(it.toString()) }.getOrThrow() }
                    fx.setStatus(member = gone, status = MemberStatus.WITHDRAWN)
                    // Only MAX_PINNED - 1 pins are visible, so one more must fit.
                    asBoard(who = board) { it.pinNotice(notices.last().toString()) }.getOrThrow().pinnedAt.shouldNotBeNull()
                }
            }

            test("removal needs a reason, a note beyond 500 characters is refused, and an own notice is withdrawn, not removed") {
                val author = fx.member(label = "author")
                val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
                val notice = fx.notice(author = author)
                val own = fx.notice(author = admin, title = "Eigener")
                withServiceCaller {
                    asBoard(who = admin) {
                        it.removeNotice(
                            id = notice.toString(),
                            input = NoticeRemovalInput(reason = NoticeRemovalReason.RULES, note = "x".repeat(501)),
                        )
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    asBoard(
                        who = admin,
                    ) { it.removeNotice(id = own.toString(), input = NoticeRemovalInput(reason = NoticeRemovalReason.RULES)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    val removed =
                        asBoard(
                            who = admin,
                        ) {
                            it.removeNotice(
                                id = notice.toString(),
                                input = NoticeRemovalInput(reason = NoticeRemovalReason.DUPLICATE, note = SECRET_NOTE),
                            )
                        }.getOrThrow()
                    removed.status shouldBe NoticeStatus.REMOVED
                    removed.removalReason shouldBe NoticeRemovalReason.DUPLICATE
                    asBoard(who = author) { it.listMyNotices() }.getOrThrow().single { it.id == notice.toString() }.removalNote shouldBe
                        SECRET_NOTE
                }
                val snapshot = fx.audits(notice).single()[AuditLogEntryTable.afterSnapshot].orEmpty()
                snapshot shouldContain "DUPLICATE"
                snapshot shouldNotContain SECRET_NOTE
            }

            test("after a removal the notice cannot be boosted and spent LTR is not refunded") {
                val author = fx.member(label = "author")
                val booster = fx.member(label = "booster")
                val board = fx.member(label = "board", role = AccountRole.BOARD)
                fx.grant(member = booster, amount = "10.00")
                val notice = fx.notice(author = author)
                withServiceCaller {
                    asBoard(who = booster) { it.boostNotice(id = notice.toString(), amountLtr = amount("4.00")) }.getOrThrow()
                    asBoard(
                        who = board,
                    ) {
                        it.removeNotice(
                            id = notice.toString(),
                            input = NoticeRemovalInput(reason = NoticeRemovalReason.SPAM),
                        )
                    }.getOrThrow()
                    asBoard(
                        who = booster,
                    ) {
                        it.boostNotice(
                            id = notice.toString(),
                            amountLtr = amount("1.00"),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
                fx.balance(booster) shouldBe amount("6.00")
            }

            test("every operation writes exactly one audit entry and none of them carries a title, a text or a note") {
                val author = fx.member(label = "author")
                val board = fx.member(label = "board", role = AccountRole.BOARD)
                val booster = fx.member(label = "booster")
                fx.grant(member = booster, amount = "5.00")
                withServiceCaller {
                    val dto = asBoard(who = author) { it.createNotice(noticeInput(title = SECRET_TITLE, body = SECRET_BODY)) }.getOrThrow()
                    asBoard(who = author) {
                        it.updateNotice(
                            id = dto.id,
                            input =
                                NoticeUpdateInput(
                                    category = NoticeCategory.INFO,
                                    title = SECRET_TITLE,
                                    body = SECRET_BODY,
                                    expiresOn = inDays(9),
                                ),
                        )
                    }.getOrThrow()
                    asBoard(who = booster) { it.boostNotice(id = dto.id, amountLtr = amount("1.00")) }.getOrThrow()
                    asBoard(who = board) { it.pinNotice(dto.id) }.getOrThrow()
                    asBoard(who = board) { it.unpinNotice(dto.id) }.getOrThrow()
                    asBoard(who = board) {
                        it.removeNotice(id = dto.id, input = NoticeRemovalInput(reason = NoticeRemovalReason.LEGAL, note = SECRET_NOTE))
                    }.getOrThrow()
                    val audits = fx.audits(Uuid.parse(dto.id))
                    audits shouldHaveSize 6
                    audits.forEach { entry ->
                        val text = entry[AuditLogEntryTable.afterSnapshot].orEmpty() + entry[AuditLogEntryTable.beforeSnapshot].orEmpty()
                        text shouldNotContain SECRET_TITLE
                        text shouldNotContain SECRET_BODY
                        text shouldNotContain SECRET_NOTE
                    }
                }
            }
        }

        context("races") {
            test("two parallel debits of one member with balance for one: exactly one succeeds, the balance never goes negative") {
                val author = fx.member(label = "author")
                val booster = fx.member(label = "booster")
                fx.grant(member = booster, amount = "10.00")
                val one = fx.notice(author = author, title = "Eins")
                val two = fx.notice(author = author, title = "Zwei")
                val results =
                    runBlocking {
                        listOf(one to "6.00", two to "7.00")
                            .map { (id, value) ->
                                async(Dispatchers.IO) {
                                    var outcome: Result<Any?>? = null
                                    withServiceCaller {
                                        outcome =
                                            asBoard(who = booster) { it.boostNotice(id = id.toString(), amountLtr = amount(value)) }
                                    }
                                    outcome!!
                                }
                            }.awaitAll()
                    }
                results.count { it.isSuccess } shouldBe 1
                results.single { it.isFailure }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                fx.balance(booster) shouldBeGreaterThanOrEqualTo BigDecimal.ZERO
                fx.ledgerRows(booster).count {
                    it[network.lapis.cloud.server.db.generated.LtrLedgerEntryTable.entryType] ==
                        LtrLedgerEntryType.NOTICE_BOOST
                } shouldBe
                    1
                transaction { BulletinNoticeBoostTable.selectAll().count() } shouldBe 1L
            }

            test("a boost against a withdrawal: either booked before the status change or refused, never a boost on a closed notice") {
                val author = fx.member(label = "author")
                val booster = fx.member(label = "booster")
                fx.grant(member = booster, amount = "10.00")
                val notice = fx.notice(author = author)
                val results =
                    runBlocking {
                        listOf<suspend () -> Result<Any?>>(
                            {
                                var outcome: Result<Any?>? = null
                                withServiceCaller {
                                    outcome =
                                        asBoard(who = booster) { it.boostNotice(id = notice.toString(), amountLtr = amount("2.00")) }
                                }
                                outcome!!
                            },
                            {
                                var outcome: Result<Any?>? = null
                                withServiceCaller { outcome = asBoard(who = author) { it.withdrawNotice(notice.toString()) } }
                                outcome!!
                            },
                        ).map { run -> async(Dispatchers.IO) { run() } }.awaitAll()
                    }
                results[1].isSuccess shouldBe true
                val boosted = results[0].isSuccess
                transaction { BulletinNoticeBoostTable.selectAll().count() } shouldBe if (boosted) 1L else 0L
                fx.balance(booster) shouldBe if (boosted) amount("8.00") else amount("10.00")
                if (!boosted) results[0].exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
            }

            test("six parallel creations of one member end at exactly five active notices") {
                val a = fx.member(label = "author")
                val results =
                    runBlocking {
                        (1..6)
                            .map { n ->
                                async(Dispatchers.IO) {
                                    var outcome: Result<Any?>? = null
                                    withServiceCaller { outcome = asBoard(who = a) { it.createNotice(noticeInput(title = "Parallel $n")) } }
                                    outcome!!
                                }
                            }.awaitAll()
                    }
                results.count { it.isSuccess } shouldBe NoticeRules.MAX_ACTIVE_PER_MEMBER
                fx.notices(a) shouldHaveSize NoticeRules.MAX_ACTIVE_PER_MEMBER
            }

            test("a creation with a stake against a boost of the same member does not deadlock and never overdraws") {
                val a = fx.member(label = "author")
                val other = fx.member(label = "other")
                fx.grant(member = a, amount = "10.00")
                val target = fx.notice(author = other)
                val results =
                    runBlocking {
                        listOf<suspend () -> Result<Any?>>(
                            {
                                var outcome: Result<Any?>? = null
                                withServiceCaller {
                                    outcome =
                                        asBoard(who = a) { it.createNotice(noticeInput(title = "Mit Einsatz", stake = "6.00")) }
                                }
                                outcome!!
                            },
                            {
                                var outcome: Result<Any?>? = null
                                withServiceCaller {
                                    outcome =
                                        asBoard(who = a) { it.boostNotice(id = target.toString(), amountLtr = amount("7.00")) }
                                }
                                outcome!!
                            },
                        ).map { run -> async(Dispatchers.IO) { run() } }.awaitAll()
                    }
                results.count { it.isSuccess } shouldBe 1
                fx.balance(a) shouldBeGreaterThanOrEqualTo BigDecimal.ZERO
            }
        }
    })
