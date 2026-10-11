package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleBylineKind
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.105 -- the author line in the DSGVO export (Art. 15) and on erasure (Art. 17): the export shows byline, kind and consent moment of
 * the member's own articles, lists byline changes the member made on others' articles and the imports they executed (without treating those
 * articles as their authorship); erasure deletes a PERSONAL line (text, kind, consent) and leaves ORGANIZATION and IMPORTED lines alone.
 */
class ArticlePersonalDataBylineTest :
    FunSpec({
        val member = Uuid.random()
        val other = Uuid.random()
        val articleIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            transaction {
                for ((id, label) in listOf(member to "Betroffen", other to "Dritte")) {
                    MemberTable.insert {
                        it[MemberTable.id] = id
                        it[displayName] = "Byline DSGVO $label"
                        it[email] = "byline-dsgvo-$id@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2026, 1, 1)
                    }
                    AccountTable.insert {
                        it[AccountTable.id] = Uuid.random()
                        it[memberId] = id
                        it[role] = AccountRole.BOARD
                    }
                }
            }
        }

        afterSpec {
            transaction {
                ArticleTable.deleteWhere { ArticleTable.id inList articleIds }
                AccountTable.deleteWhere { memberId inList listOf(member, other) }
                MemberTable.deleteWhere { MemberTable.id inList listOf(member, other) }
            }
        }

        fun article(
            author: Uuid,
            byline: String?,
            kind: ArticleBylineKind?,
            imported: Boolean = false,
            setBy: Uuid? = null,
            title: String = "Artikel",
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[slug] = "dsgvo-byline-$id"
                    it[ArticleTable.title] = title
                    it[excerpt] = "x"
                    it[body] = "y"
                    it[authorId] = author
                    it[reviewedBy] = if (imported) author else null
                    it[status] = ArticleStatus.PUBLISHED
                    it[publishedAt] = now
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[ArticleTable.byline] = byline
                    it[bylineKind] = kind
                    it[bylineConsentAt] = if (kind == ArticleBylineKind.PERSONAL) now else null
                    it[bylineSetBy] = setBy
                    it[ArticleTable.imported] = imported
                }
            }
            articleIds += id
            return id
        }

        fun rowOf(id: Uuid) = transaction { ArticleTable.selectAll().where { ArticleTable.id eq id }.single() }

        fun JsonObject.array(name: String) = this[name]!!.jsonArray.map { it.jsonObject }

        test(
            "export: byline, kind and consent moment of own articles; byline changes made on others' articles; imports executed, not as authorship",
        ) {
            val personal = article(member, "Max Muster", ArticleBylineKind.PERSONAL, setBy = member)
            val organization = article(member, "Redaktion", ArticleBylineKind.ORGANIZATION, setBy = other)
            val none = article(member, null, null)
            val imported = article(member, "PdV", ArticleBylineKind.IMPORTED, imported = true, setBy = member)
            val othersArticle = article(other, "Partei der Vernunft", ArticleBylineKind.ORGANIZATION, setBy = member)

            val export = transaction { ArticlePersonalData.exportMember(member) }
            val authored = export.array("authoredArticles").associateBy { it["id"]!!.jsonPrimitive.content }

            authored[personal.toString()]!!.let {
                it["byline"]!!.jsonPrimitive.content shouldBe "Max Muster"
                it["bylineKind"]!!.jsonPrimitive.content shouldBe "PERSONAL"
                (it["bylineConsentAt"] != null) shouldBe true
            }
            authored[organization.toString()]!!.let {
                it["bylineKind"]!!.jsonPrimitive.content shouldBe "ORGANIZATION"
                it["bylineConsentAt"] shouldBe null
            }
            authored[none.toString()]!!.let {
                it["byline"] shouldBe null
                it["bylineKind"] shouldBe null
            }
            // an imported article is NOT the member's authorship ...
            (imported.toString() in authored) shouldBe false
            (imported.toString() in export.array("reviewedArticles").map { it["id"]!!.jsonPrimitive.content }) shouldBe false
            // ... but the member is told that they executed imports
            export["articleImportsExecuted"]!!.jsonPrimitive.content shouldBe "1"

            val changes = export.array("bylineChangesMade").associateBy { it["id"]!!.jsonPrimitive.content }
            (othersArticle.toString() in changes) shouldBe true
            changes[othersArticle.toString()]!!["bylineKind"]!!.jsonPrimitive.content shouldBe "ORGANIZATION"
            // the changes list names kinds and ids, never the text of an author line
            export.toString() shouldNotContain "Partei der Vernunft"
            export.toString() shouldContain "Max Muster" // the member's OWN byline is in the export
        }

        test("erasure deletes a PERSONAL line (text, kind, consent) and leaves ORGANIZATION and IMPORTED lines; the article itself stays") {
            val personal = article(member, "Erika Beispiel", ArticleBylineKind.PERSONAL, setBy = member)
            val organization = article(member, "Pressestelle", ArticleBylineKind.ORGANIZATION, setBy = other)
            val imported = article(member, "PdV", ArticleBylineKind.IMPORTED, imported = true, setBy = member)
            val foreignPersonal = article(other, "Fremde Autorin", ArticleBylineKind.PERSONAL, setBy = other)

            val outcomes = transaction { ArticlePersonalData.eraseMember(memberId = member, mode = ErasureMode.ANONYMIZE) }

            rowOf(personal).let {
                it[ArticleTable.byline] shouldBe null
                it[ArticleTable.bylineKind] shouldBe null
                it[ArticleTable.bylineConsentAt] shouldBe null
                (it[ArticleTable.bylineSetAt] != null) shouldBe true
                it[ArticleTable.status] shouldBe ArticleStatus.PUBLISHED
            }
            rowOf(organization)[ArticleTable.byline] shouldBe "Pressestelle"
            rowOf(organization)[ArticleTable.bylineKind] shouldBe ArticleBylineKind.ORGANIZATION
            rowOf(imported)[ArticleTable.byline] shouldBe "PdV"
            rowOf(imported)[ArticleTable.bylineKind] shouldBe ArticleBylineKind.IMPORTED
            // somebody else's personal line is not touched
            rowOf(foreignPersonal)[ArticleTable.byline] shouldBe "Fremde Autorin"
            // imported articles do not count as retained authorship
            val retained =
                transaction {
                    ArticleTable
                        .selectAll()
                        .where {
                            ((ArticleTable.authorId eq member) or (ArticleTable.reviewedBy eq member)) and
                                (ArticleTable.imported eq false)
                        }.count()
                        .toInt()
                }
            outcomes.single().rowsRetained shouldBe retained
            outcomes.single().retentionReason shouldContain "Autorenzeile"
        }
    })
