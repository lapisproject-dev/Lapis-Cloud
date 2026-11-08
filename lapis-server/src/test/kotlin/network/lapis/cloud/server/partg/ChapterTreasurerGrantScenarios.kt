package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.db.generated.RegionalChapterTreasurerTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RegionalChapterInUseException
import network.lapis.cloud.shared.rpc.RegionalChapterLimitReachedException
import network.lapis.cloud.shared.rpc.RegionalChapterOfficerIneligibleException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.289 (R4a) -- granting and revoking the chapter treasurer, on H2 AND PostgreSQL: ADMIN only, the caps (10 per member, 5 per chapter), the duplicate
 * grant (idempotent), the audit entry (ids only), and that `deleteChapter` refuses a chapter with bookings, a bookkeeping mode or an active grant.
 */
abstract class ChapterTreasurerGrantScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PartgFixtures()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest { fx.setParty(true) }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        test("only ADMIN grants, lists and revokes; the grant is audited with ids only") {
            val chapter = fx.chapter()
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val member = fx.member(label = "m", role = AccountRole.MEMBER)
            partgTestApplication(fixtures = fx) {
                listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.MEMBER).forEach { role ->
                    val caller = fx.member(label = "c-$role", role = role)
                    attempt(
                        member = caller,
                    ) { fx.chapterService(it).grantChapterTreasurer(chapterId = chapter.toString(), memberId = member.toString()) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                    attempt(member = caller) { fx.chapterService(it).listChapterTreasurers(chapter.toString()) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                    attempt(member = caller) { fx.chapterService(it).revokeChapterTreasurer(Uuid.random().toString()) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                }
                val grant =
                    call(
                        member = admin,
                    ) { fx.chapterService(it).grantChapterTreasurer(chapterId = chapter.toString(), memberId = member.toString()) }
                grant.chapterId shouldBe chapter.toString()
                call(member = admin) { fx.chapterService(it).listChapterTreasurers(chapter.toString()) }.map { it.memberId } shouldBe
                    listOf(member.toString())
                val audits =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.REGIONAL_CHAPTER_TREASURER) and
                                    (AuditLogEntryTable.actorMemberId eq admin)
                            }.mapNotNull { it[AuditLogEntryTable.afterSnapshot] }
                    }
                audits.size shouldBe 1
                audits.single().contains("PartG-Test") shouldBe false // no name
                call(member = admin) { fx.chapterService(it).revokeChapterTreasurer(grant.grantId) }
                call(member = admin) { fx.chapterService(it).revokeChapterTreasurer(grant.grantId) } // idempotent
                call(member = admin) { fx.chapterService(it).listChapterTreasurers(chapter.toString()) } shouldBe emptyList()
                attempt(member = admin) { fx.chapterService(it).revokeChapterTreasurer(Uuid.random().toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("a duplicate active grant returns the existing one; the inactive and the ineligible are refused; the caps hold") {
            val chapter = fx.chapter()
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val member = fx.member(label = "dup", role = AccountRole.MEMBER)
            partgTestApplication(fixtures = fx) {
                val a =
                    call(
                        member = admin,
                    ) { fx.chapterService(it).grantChapterTreasurer(chapterId = chapter.toString(), memberId = member.toString()) }
                val b =
                    call(
                        member = admin,
                    ) { fx.chapterService(it).grantChapterTreasurer(chapterId = chapter.toString(), memberId = member.toString()) }
                b.grantId shouldBe a.grantId
                transaction {
                    RegionalChapterTreasurerTable.selectAll().where { RegionalChapterTreasurerTable.memberId eq member }.count()
                } shouldBe 1
                // a member that is not an organization member
                val applicant = fx.member(label = "app", role = AccountRole.MEMBER, status = MemberStatus.APPLICATION)
                attempt(
                    member = admin,
                ) { fx.chapterService(it).grantChapterTreasurer(chapterId = chapter.toString(), memberId = applicant.toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<RegionalChapterOfficerIneligibleException>()
                // unknown ids
                attempt(
                    member = admin,
                ) { fx.chapterService(it).grantChapterTreasurer(chapterId = Uuid.random().toString(), memberId = member.toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                attempt(
                    member = admin,
                ) { fx.chapterService(it).grantChapterTreasurer(chapterId = chapter.toString(), memberId = Uuid.random().toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                // the cap per chapter: 5 (one is there already)
                repeat(4) { index ->
                    val m = fx.member(label = "cap-$index", role = AccountRole.MEMBER)
                    call(
                        member = admin,
                    ) { call -> fx.chapterService(call).grantChapterTreasurer(chapterId = chapter.toString(), memberId = m.toString()) }
                }
                val sixth = fx.member(label = "cap-6", role = AccountRole.MEMBER)
                attempt(
                    member = admin,
                ) { fx.chapterService(it).grantChapterTreasurer(chapterId = chapter.toString(), memberId = sixth.toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<RegionalChapterLimitReachedException>()
            }
        }

        test("the cap per member is 10 active grants") {
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val member = fx.member(label = "ten", role = AccountRole.MEMBER)
            val chapters = (1..11).map { fx.chapter() }
            partgTestApplication(fixtures = fx) {
                chapters.take(10).forEach { c ->
                    call(
                        member = admin,
                    ) { fx.chapterService(it).grantChapterTreasurer(chapterId = c.toString(), memberId = member.toString()) }
                }
                attempt(member = admin) {
                    fx.chapterService(it).grantChapterTreasurer(chapterId = chapters.last().toString(), memberId = member.toString())
                }.exceptionOrNull()
                    .shouldBeInstanceOf<RegionalChapterLimitReachedException>()
                // a revoked grant frees a slot
                val first = call(member = admin) { fx.chapterService(it).listChapterTreasurers(chapters.first().toString()) }.single()
                call(member = admin) { fx.chapterService(it).revokeChapterTreasurer(first.grantId) }
                call(
                    member = admin,
                ) { fx.chapterService(it).grantChapterTreasurer(chapterId = chapters.last().toString(), memberId = member.toString()) }
            }
        }

        test(
            "deleteChapter refuses a chapter with bookings, with a bookkeeping mode or with an active grant; a revoked grant history goes with it",
        ) {
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val member = fx.member(label = "m", role = AccountRole.MEMBER)
            val withBooking = fx.chapter()
            val withMode = fx.chapter()
            val withGrant = fx.chapter()
            val clean = fx.chapter()
            val bank = fx.ledgerAccount(type = LedgerAccountType.ASSET)
            val income = fx.ledgerAccount(type = LedgerAccountType.INCOME)
            val year = PartgYearRules.maxYear() - 1
            partgTestApplication(fixtures = fx) {
                call(member = admin) {
                    fx.accounting(it).postJournalEntry(
                        JournalEntryInput(
                            entryDate = LocalDate(year, 3, 1),
                            description = "x",
                            postings =
                                listOf(
                                    PostingInput(
                                        ledgerAccountId = bank.toString(),
                                        side = PostingSide.DEBIT,
                                        amount = BigDecimal("1.00"),
                                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                    ),
                                    PostingInput(
                                        ledgerAccountId = income.toString(),
                                        side = PostingSide.CREDIT,
                                        amount = BigDecimal("1.00"),
                                        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                    ),
                                ),
                            regionalChapterId = withBooking.toString(),
                        ),
                    )
                }
                attempt(member = admin) { fx.chapterService(it).deleteChapter(withBooking.toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                call(member = admin) {
                    fx
                        .service(
                            it,
                        ).setUnitMode(
                            year = year,
                            unitKey = PartgUnit.State(withMode.toString()).key(),
                            mode = PartgBookkeepingMode.A,
                            confirmedBookingCount = 0,
                        )
                }
                attempt(member = admin) { fx.chapterService(it).deleteChapter(withMode.toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                call(
                    member = admin,
                ) { fx.chapterService(it).grantChapterTreasurer(chapterId = withGrant.toString(), memberId = member.toString()) }
                attempt(member = admin) { fx.chapterService(it).deleteChapter(withGrant.toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<RegionalChapterInUseException>()
                // revoked history only: the chapter can go, its grant rows with it
                val cleanGrant =
                    call(
                        member = admin,
                    ) { fx.chapterService(it).grantChapterTreasurer(chapterId = clean.toString(), memberId = member.toString()) }
                call(member = admin) { fx.chapterService(it).revokeChapterTreasurer(cleanGrant.grantId) }
                call(member = admin) { fx.chapterService(it).deleteChapter(clean.toString()) }
                transaction { RegionalChapterTable.selectAll().where { RegionalChapterTable.id eq clean }.count() } shouldBe 0
                transaction {
                    RegionalChapterTreasurerTable
                        .selectAll()
                        .where {
                            RegionalChapterTreasurerTable.regionalChapterId inList
                                listOf(
                                    clean,
                                )
                        }.count()
                } shouldBe 0
            }
        }
    })
