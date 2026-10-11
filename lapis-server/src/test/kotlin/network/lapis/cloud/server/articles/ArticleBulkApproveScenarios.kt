package network.lapis.cloud.server.articles

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.audit.AuditChainVerifier
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleBulkSkipReason
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CountDownLatch
import kotlin.uuid.Uuid

/**
 * Welle V1.9.106 -- the bulk approval of imported articles ([ArticleBulkApprover]) on H2 AND on PostgreSQL: only a member OTHER than the importer
 * approves (four-eyes, per row, under the lock), only imported `SUBMITTED` rows are touched, ids outside the call stay `SUBMITTED`, the result
 * lists skips in request order, a repeated call is silent (no audit), the historic `published_at` survives, exactly ONE audit entry without any
 * title, and two parallel calls over the same ids approve every id exactly once without a deadlock.
 */
abstract class ArticleBulkApproveScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val slugPrefix = "artbulk-${Uuid.random().toString().take(8)}-"
        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db)

        fun cleanArticles() {
            transaction { ArticleTable.deleteWhere { slug like "$slugPrefix%" } }
        }
        beforeTest { cleanArticles() }
        afterSpec {
            cleanArticles()
            db.deactivate()
        }

        fun createBoard(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "ArticleBulkApproveScenarios BOARD"
                    it[email] = "article-bulk-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.BOARD
                }
            }
            return id
        }

        // H2 shares one in-memory database across the whole test JVM (other specs count the seeded members), so this spec uses the seeded demo
        // members there; on PostgreSQL every spec has a database of its own and may create what it needs.
        fun actor(role: AccountRole): Uuid = if (db.isPostgres) createBoard() else DevSeedData.demoMembers.first { it.role == role }.id

        val importer: Uuid by lazy { actor(AccountRole.BOARD) }
        val approver: Uuid by lazy { actor(AccountRole.ADMIN) }
        val thirdActor: Uuid by lazy { actor(AccountRole.MEMBER) }

        fun entry(
            n: Int,
            publishedAt: String = "2019-03-04",
        ) =
            """{"slug":"$slugPrefix$n","title":"Geheimer Titel $n","excerpt":"Teaser","body":"Text $n","publishedAt":"$publishedAt","byline":"PdV"}"""

        /** Imports [count] articles as [by]; returns their ids in slug order. */
        fun import(
            count: Int,
            by: Uuid = importer,
            from: Int = 1,
        ): List<Uuid> {
            val json = "[${(from until from + count).joinToString(",") { entry(it) }}]"
            val now = DbClock.nowLocalDateTime()
            ArticleImporter.commit(
                json = json,
                payloadSha256 = ArticleImportPolicy.sha256Hex(json),
                actorMemberId = by,
                actorRole = AccountRole.BOARD,
                now = now,
                wallNow = OrganizationTimeZone.wallNowOf(now),
                orgZone = TimeZone.of("Europe/Berlin"),
            )
            return (from until from + count).map { n ->
                transaction { ArticleTable.selectAll().where { ArticleTable.slug eq "$slugPrefix$n" }.single()[ArticleTable.id] }
            }
        }

        fun approve(
            ids: List<Uuid>,
            by: Uuid = approver,
        ) = ArticleBulkApprover.approve(ids = ids, actorMemberId = by, actorRole = AccountRole.BOARD, now = DbClock.nowLocalDateTime())

        fun rowOf(id: Uuid) = transaction { ArticleTable.selectAll().where { ArticleTable.id eq id }.single() }

        fun lastSeq() =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
            }?.get(AuditLogEntryTable.sequenceNumber) ?: 0L

        var baseline = 0L
        beforeTest { baseline = lastSeq() }

        /** ARTICLE_BULK_APPROVED entries written since the start of the current test. */
        fun bulkAudits() =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.ARTICLE_BULK_APPROVED) and
                            (AuditLogEntryTable.sequenceNumber greater baseline)
                    }.orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.ASC)
                    .toList()
            }

        test("a different board member approves imported SUBMITTED articles: PUBLISHED, reviewer set, the historic published_at kept") {
            val ids = import(3)
            val before = ids.map { rowOf(it)[ArticleTable.publishedAt] }
            val result = approve(ids)
            result.approvedCount shouldBe 3
            result.approvedIds shouldContainExactly ids.map { it.toString() }
            result.skipped shouldBe emptyList()
            ids.forEachIndexed { i, id ->
                val row = rowOf(id)
                row[ArticleTable.status] shouldBe ArticleStatus.PUBLISHED
                row[ArticleTable.reviewedBy] shouldBe approver
                row[ArticleTable.publishedAt] shouldBe before[i]
            }
        }

        test("the importer is skipped with OWN_IMPORT (four-eyes), the article stays SUBMITTED") {
            val ids = import(2)
            val result = approve(ids, by = importer)
            result.approvedCount shouldBe 0
            result.skipped.map { it.reason } shouldContainExactly listOf(ArticleBulkSkipReason.OWN_IMPORT, ArticleBulkSkipReason.OWN_IMPORT)
            ids.forEach { rowOf(it)[ArticleTable.status] shouldBe ArticleStatus.SUBMITTED }
            bulkAudits().size shouldBe 0
        }

        test(
            "only imported SUBMITTED rows: an ordinary draft, an unknown id and an already published row are reported, ids outside the call stay SUBMITTED",
        ) {
            val ids = import(3)
            val untouched = ids[2]
            val draftId = Uuid.random()
            transaction {
                ArticleTable.insert {
                    it[id] = draftId
                    it[slug] = "$slugPrefix-draft"
                    it[title] = "Ein Entwurf"
                    it[excerpt] = "x"
                    it[body] = "y"
                    it[authorId] = approver
                    it[status] = ArticleStatus.SUBMITTED
                    it[submittedAt] = DbClock.nowLocalDateTime()
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[updatedAt] = DbClock.nowLocalDateTime()
                }
                ArticleTable.update({ ArticleTable.id eq ids[1] }) { it[status] = ArticleStatus.PUBLISHED }
            }
            val unknown = Uuid.random()
            val result = approve(listOf(ids[0], draftId, unknown, ids[1]))
            result.approvedIds shouldContainExactly listOf(ids[0].toString())
            result.skipped.map { it.articleId to it.reason } shouldContainExactly
                listOf(
                    draftId.toString() to ArticleBulkSkipReason.NOT_IMPORTED,
                    unknown.toString() to ArticleBulkSkipReason.NOT_FOUND,
                    ids[1].toString() to ArticleBulkSkipReason.NOT_SUBMITTED,
                )
            result.skipped[0].title shouldBe "Ein Entwurf"
            result.skipped[1].title shouldBe null
            rowOf(untouched)[ArticleTable.status] shouldBe ArticleStatus.SUBMITTED
            rowOf(draftId)[ArticleTable.status] shouldBe ArticleStatus.SUBMITTED
        }

        test("a second call approves nothing, reports NOT_SUBMITTED and writes NO second audit entry; duplicate ids count once") {
            val ids = import(2)
            val first = approve(ids + ids)
            first.approvedCount shouldBe 2
            bulkAudits().size shouldBe 1
            val second = approve(ids)
            second.approvedCount shouldBe 0
            second.skipped.map { it.reason }.toSet() shouldBe setOf(ArticleBulkSkipReason.NOT_SUBMITTED)
            bulkAudits().size shouldBe 1
        }

        test(
            "exactly one ARTICLE_BULK_APPROVED entry with counts, batch ids and article ids, and never a title, text or byline; the chain stays valid",
        ) {
            val ids = import(2)
            val extra = Uuid.random()
            approve(ids + extra)
            val entry = bulkAudits().single()
            val raw = transaction { entry[AuditLogEntryTable.afterSnapshot]!! }
            raw shouldNotContain "Geheimer Titel"
            raw shouldNotContain "PdV"
            raw shouldNotContain "Text"
            val json = Json.parseToJsonElement(raw).jsonObject
            json["approvedCount"]!!.jsonPrimitive.content shouldBe "2"
            json["skippedCount"]!!.jsonPrimitive.content shouldBe "1"
            json["importBatchCount"]!!.jsonPrimitive.content shouldBe "1"
            json["importBatchIds"]!!.jsonArray.size shouldBe 1
            json["approvedArticleIds"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly ids.map { it.toString() }
            val seq = entry[AuditLogEntryTable.sequenceNumber]
            val slice =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.sequenceNumber greater seq - 2L }
                        .orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.ASC)
                        .toList()
                }
            transaction { AuditChainVerifier.verify(slice).valid } shouldBe true
        }

        test("two parallel bulk approvals over the same ids approve every id exactly once, without a deadlock") {
            val ids = import(20)
            val gate = CountDownLatch(1)
            val results = arrayOfNulls<Int>(2)
            val failures = arrayOfNulls<Throwable>(2)
            val actors = listOf(approver, thirdActor)
            val threads =
                actors.mapIndexed { i, actor ->
                    Thread {
                        runCatching {
                            gate.await()
                            results[i] = approve(if (i == 0) ids else ids.reversed(), by = actor).approvedCount
                        }.onFailure { failures[i] = it }
                    }.also { it.start() }
                }
            gate.countDown()
            threads.forEach { it.join(60_000) }
            failures.toList() shouldBe listOf(null, null)
            (results[0]!! + results[1]!!) shouldBe 20
            ids.forEach { rowOf(it)[ArticleTable.status] shouldBe ArticleStatus.PUBLISHED }
        }
    })

class ArticleBulkApproveTest : ArticleBulkApproveScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ArticleBulkApprovePostgresTest : ArticleBulkApproveScenarios(TestDatabase.Postgres())
