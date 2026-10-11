package network.lapis.cloud.server.articles

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.audit.AuditChainVerifier
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.routes.ArticleSetCoverOutcome
import network.lapis.cloud.server.routes.auditImportedCoverChange
import network.lapis.cloud.server.routes.coverEditDenial
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleBylineKind
import network.lapis.cloud.shared.domain.ArticleImportRowStatus
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * Welle V1.9.105 -- the admin import of legacy articles end to end, on H2 AND on PostgreSQL: dry run writes nothing, commit is all or nothing,
 * existing slugs (in ANY status) are skipped and never updated, the hash guards against an edit between preview and commit, the original
 * publication date is converted from the organization zone to the UTC system stamp, the audit chain stays valid and never carries content,
 * imported rows are hidden from the author paths, and a slug that appears concurrently is handled.
 */
abstract class ArticleImportScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val slugPrefix = "artimp-${Uuid.random().toString().take(8)}-"
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

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "ArticleImportScenarios ${role.name}"
                    it[email] = "article-import-${role.name.lowercase()}-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            return id
        }

        val board: Uuid by lazy {
            val seeded = DevSeedData.demoMembers.first { it.role == AccountRole.BOARD }.id
            if (!db.isPostgres && transaction { MemberTable.selectAll().where { MemberTable.id eq seeded }.any() }) {
                seeded
            } else {
                createMember(AccountRole.BOARD)
            }
        }

        fun entry(
            n: Int,
            publishedAt: String = "2024-05-05",
            byline: String? = "PdV",
            title: String = "Importierter Artikel $n",
            excerpt: String = "Teaser $n",
            body: String = "Erste Zeile  \\nZweite Zeile\\n\\nAbsatz $n",
        ): String {
            val parts =
                mutableListOf(
                    """"slug":"$slugPrefix$n"""",
                    """"title":"$title"""",
                    """"excerpt":"$excerpt"""",
                    """"body":"$body"""",
                    """"publishedAt":"$publishedAt"""",
                )
            if (byline != null) parts += """"byline":"$byline""""
            return "{${parts.joinToString(",")}}"
        }

        fun payload(vararg entries: String) = "[${entries.joinToString(",")}]"

        fun wallNow() = OrganizationTimeZone.wallNowOf(DbClock.nowLocalDateTime())

        fun preview(
            json: String,
            labels: List<String> = listOf("PdV"),
        ) = ArticleImporter.preview(json = json, organizationLabels = labels, wallNow = wallNow(), timeZoneId = "Europe/Berlin")

        fun commit(
            json: String,
            hash: String = ArticleImportPolicy.sha256Hex(json),
            orgZone: TimeZone = TimeZone.of("Europe/Berlin"),
            onAttempt: () -> Unit = {},
        ): network.lapis.cloud.shared.domain.ArticleImportResultDto {
            val now = DbClock.nowLocalDateTime()
            return ArticleImporter.commit(
                json = json,
                payloadSha256 = hash,
                actorMemberId = board,
                actorRole = AccountRole.BOARD,
                now = now,
                wallNow = OrganizationTimeZone.wallNowOf(now),
                orgZone = orgZone,
                onAttempt = onAttempt,
            )
        }

        fun articleCount() =
            transaction {
                ArticleTable
                    .selectAll()
                    .where { ArticleTable.slug like "$slugPrefix%" }
                    .count()
                    .toInt()
            }

        fun auditCount(type: AuditEntityType) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq type }
                    .count()
                    .toInt()
            }

        fun rowOf(n: Int) = transaction { ArticleTable.selectAll().where { ArticleTable.slug eq "$slugPrefix$n" }.single() }

        test("dry run writes nothing: no article, no audit entry") {
            val articles = articleCount()
            val audits = auditCount(AuditEntityType.ARTICLE) + auditCount(AuditEntityType.ARTICLE_IMPORT)
            val result = preview(payload(entry(1), entry(2)))
            result.createCount shouldBe 2
            result.skipCount shouldBe 0
            result.errorCount shouldBe 0
            result.timeZoneId shouldBe "Europe/Berlin"
            articleCount() shouldBe articles
            (auditCount(AuditEntityType.ARTICLE) + auditCount(AuditEntityType.ARTICLE_IMPORT)) shouldBe audits
        }

        test("preview rows: errors first, then by index; the byline and the date are shown") {
            val json = payload(entry(1), entry(2, publishedAt = "2099-01-01"), entry(3, byline = "Dirk Hesse"))
            val rows = preview(json).rows
            rows.map { it.status } shouldContainExactly
                listOf(ArticleImportRowStatus.ERROR, ArticleImportRowStatus.CREATE, ArticleImportRowStatus.CREATE)
            rows.map { it.index } shouldContainExactly listOf(1, 0, 2)
            rows.first().reasons.isEmpty() shouldBe false
            rows[1].publishedAt shouldBe "2024-05-05T00:00"
            rows[1].byline shouldBe "PdV"
            rows[2].byline shouldBe "Dirk Hesse"
            rows[2].hints.contains("Autorenzeile ist kein Organisationsname – Einwilligung klären.") shouldBe true
            rows[1].hints.contains("Autorenzeile ist kein Organisationsname – Einwilligung klären.") shouldBe false
        }

        test(
            "commit creates SUBMITTED (never PUBLISHED) imported articles: IMPORTED byline, no cover, importer as author, no reviewer, original date, Markdown intact",
        ) {
            val result = commit(payload(entry(1), entry(2, byline = null)))
            result.created shouldBe 2
            result.skipped shouldBe 0
            val row = rowOf(1)
            // four-eyes: a single member can never publish through the import, not even with today's date
            row[ArticleTable.status] shouldBe ArticleStatus.SUBMITTED
            row[ArticleTable.imported] shouldBe true
            row[ArticleTable.authorId] shouldBe board
            row[ArticleTable.reviewedBy] shouldBe null
            row[ArticleTable.reviewedAt] shouldBe null
            row[ArticleTable.coverImageId] shouldBe null
            row[ArticleTable.byline] shouldBe "PdV"
            row[ArticleTable.bylineKind] shouldBe ArticleBylineKind.IMPORTED
            row[ArticleTable.bylineConsentAt] shouldBe null
            row[ArticleTable.bylineSetBy] shouldBe board
            (row[ArticleTable.submittedAt] != null) shouldBe true // ArticleSummaryDto.submittedAt is not nullable
            row[ArticleTable.title] shouldBe "Importierter Artikel 1"
            row[ArticleTable.excerpt] shouldBe "Teaser 1"
            row[ArticleTable.body] shouldBe "Erste Zeile  \nZweite Zeile\n\nAbsatz 1"
            val noByline = rowOf(2)
            noByline[ArticleTable.byline] shouldBe null
            noByline[ArticleTable.bylineKind] shouldBe null
            noByline[ArticleTable.bylineSetBy] shouldBe null
        }

        test("a commit with TODAY's date still publishes nothing: nothing is public until a second member approves") {
            val today = wallNow().date.toString()
            commit(payload(entry(1, publishedAt = today)))
            transaction {
                ArticleStore.getPublishedBySlugOrNull("${slugPrefix}1") shouldBe null
                ArticleStore.listPublishedArchivePage(limit = 200, offset = 0).none { it[ArticleTable.slug] == "${slugPrefix}1" } shouldBe
                    true
            }
        }

        test("published_at is the organization wall-clock converted to the UTC system stamp, in summer and in winter time") {
            commit(
                payload(
                    entry(1, publishedAt = "2024-07-01T10:00"),
                    entry(2, publishedAt = "2024-01-15T10:00"),
                    entry(3, publishedAt = "2024-05-05"),
                ),
            )
            rowOf(1)[ArticleTable.publishedAt] shouldBe LocalDateTime(2024, 7, 1, 8, 0) // CEST = UTC+2
            rowOf(2)[ArticleTable.publishedAt] shouldBe LocalDateTime(2024, 1, 15, 9, 0) // CET = UTC+1
            rowOf(3)[ArticleTable.publishedAt] shouldBe LocalDateTime(2024, 5, 4, 22, 0) // midnight in CEST
        }

        test("an existing slug is skipped and never updated -- whatever its status") {
            commit(payload(entry(1, title = "Original Titel")))
            // a DRAFT and a REJECTED article of somebody else also block their slug
            transaction {
                for ((n, status) in listOf(2 to ArticleStatus.DRAFT, 3 to ArticleStatus.REJECTED)) {
                    ArticleTable.insert {
                        it[id] = Uuid.random()
                        it[slug] = "$slugPrefix$n"
                        it[title] = "Fremder Titel $n"
                        it[excerpt] = "x"
                        it[body] = "y"
                        it[authorId] = board
                        it[ArticleTable.status] = status
                        it[createdAt] = DbClock.nowLocalDateTime()
                        it[updatedAt] = DbClock.nowLocalDateTime()
                    }
                }
            }
            val changed = payload(entry(1, title = "Ueberschriebener Titel"), entry(2), entry(3), entry(4))
            val rows = preview(changed).rows
            rows.count { it.status == ArticleImportRowStatus.SKIP_SLUG_EXISTS } shouldBe 3
            rows.first { it.slug == "${slugPrefix}2" }.hints.contains("Slug existiert bereits, wird nicht verändert.") shouldBe true
            val result = commit(changed)
            result.created shouldBe 1
            result.skipped shouldBe 3
            result.skippedSlugs shouldContainExactly listOf("${slugPrefix}1", "${slugPrefix}2", "${slugPrefix}3")
            rowOf(1)[ArticleTable.title] shouldBe "Original Titel"
            rowOf(2)[ArticleTable.title] shouldBe "Fremder Titel 2"
            rowOf(2)[ArticleTable.imported] shouldBe false
            articleCount() shouldBe 4
        }

        test("a changed payload (hash mismatch) is a conflict and writes nothing") {
            val json = payload(entry(1))
            val hash = preview(json).payloadSha256
            shouldThrow<ConflictException> { commit(json + " ", hash = hash) }
            shouldThrow<ConflictException> { commit(json, hash = "0".repeat(64)) }
            articleCount() shouldBe 0
        }

        test("all or nothing: one error in the payload writes none of the valid entries") {
            val json = payload(entry(1), entry(2, publishedAt = "2099-01-01"), entry(3))
            shouldThrow<BadRequestException> { commit(json) }
            articleCount() shouldBe 0
        }

        test(
            "audit: one ARTICLE/CREATE per article plus one ARTICLE_IMPORT summary; the chain stays valid; no title, text or byline in the log",
        ) {
            val articles = auditCount(AuditEntityType.ARTICLE)
            val imports = auditCount(AuditEntityType.ARTICLE_IMPORT)
            val json = payload(entry(1, title = "GEHEIMER-TITEL-4711", byline = "Geheime Autorin"), entry(2))
            val hash = ArticleImportPolicy.sha256Hex(json)
            val lastSequenceBefore =
                transaction { AuditLogEntryTable.selectAll().maxOfOrNull { it[AuditLogEntryTable.sequenceNumber] } ?: 0L }
            commit(json)
            auditCount(AuditEntityType.ARTICLE) shouldBe articles + 2
            auditCount(AuditEntityType.ARTICLE_IMPORT) shouldBe imports + 1
            transaction {
                val rows =
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.sequenceNumber greater lastSequenceBefore }
                        .orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.ASC)
                        .toList()
                rows.size shouldBe 3
                AuditChainVerifier.verify(rows).valid shouldBe true
                val summaryRow = rows.last { it[AuditLogEntryTable.entityType] == AuditEntityType.ARTICLE_IMPORT }
                summaryRow[AuditLogEntryTable.action] shouldBe AuditAction.CREATE
                summaryRow[AuditLogEntryTable.actorMemberId] shouldBe board
                val summary = Json.parseToJsonElement(summaryRow[AuditLogEntryTable.afterSnapshot]!!).jsonObject
                summary["created"]!!.jsonPrimitive.content shouldBe "2"
                summary["skipped"]!!.jsonPrimitive.content shouldBe "0"
                summary["payloadSha256"]!!.jsonPrimitive.content shouldBe hash
                for (row in rows) {
                    (row[AuditLogEntryTable.afterSnapshot] ?: "") shouldNotContain "GEHEIMER-TITEL-4711"
                    (row[AuditLogEntryTable.afterSnapshot] ?: "") shouldNotContain "Geheime Autorin"
                }
                rows.filter { it[AuditLogEntryTable.entityType] == AuditEntityType.ARTICLE }.forEach { row ->
                    Json
                        .parseToJsonElement(row[AuditLogEntryTable.afterSnapshot]!!)
                        .jsonObject["imported"]!!
                        .jsonPrimitive.content shouldBe
                        "true"
                }
            }
        }

        test("a run that creates nothing writes no audit entry") {
            commit(payload(entry(1)))
            val before = auditCount(AuditEntityType.ARTICLE) + auditCount(AuditEntityType.ARTICLE_IMPORT)
            commit(payload(entry(1)))
            (auditCount(AuditEntityType.ARTICLE) + auditCount(AuditEntityType.ARTICLE_IMPORT)) shouldBe before
        }

        test("a slug created concurrently between read and insert is skipped on the retry; the block ran again and nothing is lost") {
            val runs = AtomicInteger(0)
            val racing = "${slugPrefix}2"
            val json = payload(entry(1), entry(2), entry(3))
            val result =
                commit(json) {
                    if (runs.incrementAndGet() == 1) {
                        val thread =
                            Thread {
                                transaction {
                                    ArticleTable.insert {
                                        it[id] = Uuid.random()
                                        it[slug] = racing
                                        it[title] = "Gleichzeitig angelegt"
                                        it[excerpt] = "x"
                                        it[body] = "y"
                                        it[authorId] = board
                                        it[status] = ArticleStatus.DRAFT
                                        it[createdAt] = DbClock.nowLocalDateTime()
                                        it[updatedAt] = DbClock.nowLocalDateTime()
                                    }
                                }
                            }
                        thread.start()
                        thread.join()
                    }
                }
            (runs.get() >= 2) shouldBe true
            result.created shouldBe 2
            result.skippedSlugs shouldContainExactly listOf(racing)
            rowOf(2)[ArticleTable.title] shouldBe "Gleichzeitig angelegt"
            rowOf(1)[ArticleTable.imported] shouldBe true
            rowOf(3)[ArticleTable.imported] shouldBe true
            articleCount() shouldBe 3
        }

        test(
            "imported articles are not 'mine': ArticleStore hides them from the author list and the draft cap, and refuses the own-row lock",
        ) {
            commit(payload(entry(1)))
            val mine = transaction { ArticleStore.listByAuthor(board).map { it[ArticleTable.slug] } }
            mine.contains("${slugPrefix}1") shouldBe false
            val id = rowOf(1)[ArticleTable.id]
            shouldThrow<network.lapis.cloud.shared.rpc.NotFoundException> {
                transaction { ArticleStore.requireOwnedRowForUpdate(id = id, authorId = board) }
            }
        }

        test(
            "cover rights: an imported article's cover is editable by BOARD/ADMIN in any status and by nobody else; an ordinary one stays with its author",
        ) {
            commit(payload(entry(1)))
            val imported = rowOf(1)
            val someone = Uuid.random()
            coverEditDenial(row = imported, isPrivileged = true, memberId = someone) shouldBe null
            coverEditDenial(row = imported, isPrivileged = false, memberId = board) shouldBe ArticleSetCoverOutcome.NotFound
            // the importing member's own id gives no authorship right either
            coverEditDenial(row = imported, isPrivileged = false, memberId = imported[ArticleTable.authorId]) shouldBe
                ArticleSetCoverOutcome.NotFound
            coverEditDenial(row = null, isPrivileged = true, memberId = board) shouldBe ArticleSetCoverOutcome.NotFound
        }

        test("MCP list_my_articles does not list imported articles of the importer") {
            commit(payload(entry(1)))
            val listed = McpArticleDraftStore.listOwn(memberId = board, limit = 200).map { it.id }
            listed.contains(rowOf(1)[ArticleTable.id]) shouldBe false
        }

        test("a cover change on an imported article leaves an audit entry without content") {
            commit(payload(entry(1)))
            val id = rowOf(1)[ArticleTable.id]
            transaction {
                auditImportedCoverChange(
                    row = ArticleTable.selectAll().where { ArticleTable.id eq id }.single(),
                    articleId = id,
                    current =
                        network.lapis.cloud.server.security.CurrentMember(
                            memberId = board,
                            role = AccountRole.BOARD,
                            status = MemberStatus.ACTIVE,
                        ),
                    change = "set",
                )
            }
            val after =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { (AuditLogEntryTable.entityId eq id) and (AuditLogEntryTable.action eq AuditAction.UPDATE) }
                        .map { it[AuditLogEntryTable.afterSnapshot] }
                }
            after.single() shouldBe """{"cover":"set"}"""
        }

        test("an imported article is in the public archive page, newest first, once a second member approved it") {
            commit(payload(entry(1, publishedAt = "2001-01-01"), entry(2, publishedAt = "2001-01-02")))
            // what `approveArticle` does for an imported row (the RPC itself is covered in ArticleBylineServiceTest)
            transaction {
                ArticleTable.update({ ArticleTable.slug like "$slugPrefix%" }) { it[status] = ArticleStatus.PUBLISHED }
            }
            val newer =
                transaction {
                    ArticleTable
                        .selectAll()
                        .where {
                            (ArticleTable.status eq ArticleStatus.PUBLISHED) and
                                (ArticleTable.publishedAt greater LocalDateTime(2001, 1, 1, 23, 0))
                        }.count()
                }
            val page = transaction { ArticleStore.listPublishedArchivePage(limit = 2, offset = newer).map { it[ArticleTable.slug] } }
            page.take(2) shouldContainExactly listOf("${slugPrefix}2", "${slugPrefix}1")
        }

        test(
            "V1.9.106: every article of one commit shares one import batch id, two commits differ, a commit that creates nothing returns none",
        ) {
            val first = commit(payload(entry(1), entry(2)))
            val second = commit(payload(entry(3)))
            val empty = commit(payload(entry(1)))
            val firstId = first.importBatchId!!
            firstId shouldBe rowOf(1)[ArticleTable.importBatchId].toString()
            rowOf(2)[ArticleTable.importBatchId].toString() shouldBe firstId
            rowOf(3)[ArticleTable.importBatchId].toString() shouldBe second.importBatchId
            (second.importBatchId != firstId) shouldBe true
            empty.created shouldBe 0
            empty.importBatchId shouldBe null
        }

        test(
            "V1.9.106: the batch id is stable when the transaction block runs again (drawn outside the block) and is part of the summary audit",
        ) {
            val runs = AtomicInteger(0)
            val racing = "${slugPrefix}2"
            val result =
                commit(payload(entry(1), entry(2), entry(3))) {
                    if (runs.incrementAndGet() == 1) {
                        val thread =
                            Thread {
                                transaction {
                                    ArticleTable.insert {
                                        it[id] = Uuid.random()
                                        it[slug] = racing
                                        it[title] = "Gleichzeitig angelegt"
                                        it[excerpt] = "x"
                                        it[body] = "y"
                                        it[authorId] = board
                                        it[status] = ArticleStatus.DRAFT
                                        it[createdAt] = DbClock.nowLocalDateTime()
                                        it[updatedAt] = DbClock.nowLocalDateTime()
                                    }
                                }
                            }
                        thread.start()
                        thread.join()
                    }
                }
            (runs.get() >= 2) shouldBe true
            val batch = result.importBatchId!!
            rowOf(1)[ArticleTable.importBatchId].toString() shouldBe batch
            rowOf(3)[ArticleTable.importBatchId].toString() shouldBe batch
            // the racing row is a plain draft: no batch id
            rowOf(2)[ArticleTable.importBatchId] shouldBe null
            val summary =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.entityType eq AuditEntityType.ARTICLE_IMPORT }
                        .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                        .first()[AuditLogEntryTable.afterSnapshot]
                }
            Json
                .parseToJsonElement(summary!!)
                .jsonObject["importBatchId"]!!
                .jsonPrimitive.content shouldBe batch
        }

        test("the set of imported slugs is exactly what the payload said") {
            commit(payload(entry(5), entry(6), entry(7)))
            transaction {
                ArticleTable.selectAll().where { ArticleTable.slug like "$slugPrefix%" }.map { it[ArticleTable.slug] }
            }.shouldContainExactlyInAnyOrder("${slugPrefix}5", "${slugPrefix}6", "${slugPrefix}7")
        }
    })

class ArticleImportTest : ArticleImportScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ArticleImportPostgresTest : ArticleImportScenarios(TestDatabase.Postgres())
