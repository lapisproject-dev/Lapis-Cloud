package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [ArticleTable] — `author_id`/`reviewed_by` both FK to `member(id)`. Welle V1.4.34
 * "Nachrichten-/Artikel-Modul mit redaktionellem Workflow" -- see `PersonalDataCoverageTest` KDoc
 * for why this registration is not optional.
 *
 * A published article is organizational content (the party's own public communication), not
 * purely the author's/reviewer's personal data -- retained on erasure with the `author_id`/
 * `reviewed_by` pointer resolving to the anonymized member row, same "organizational record
 * outlives the author" posture [DocumentPersonalData] already establishes for `document`.
 */
object ArticlePersonalData : MemberPersonalDataContributor {
    override val sectionKey = "articles"
    override val displayName = "Artikel"
    override val coveredTables = setOf(ArticleTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("authoredArticles") {
                ArticleTable
                    .selectAll()
                    .where { ArticleTable.authorId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ArticleTable.id].toString())
                                put("title", row[ArticleTable.title])
                                put("status", row[ArticleTable.status].name)
                                put("createdAt", row[ArticleTable.createdAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("reviewedArticles") {
                ArticleTable
                    .selectAll()
                    .where { ArticleTable.reviewedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ArticleTable.id].toString())
                                put("title", row[ArticleTable.title])
                                put("status", row[ArticleTable.status].name)
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val count =
            ArticleTable
                .selectAll()
                .where { (ArticleTable.authorId eq memberId) or (ArticleTable.reviewedBy eq memberId) }
                .count()
        return listOf(
            TableErasureOutcome(
                table = "article",
                rowsRetained = count.toInt(),
                retentionReason =
                    "Organisationsinhalt (öffentliche Publikation), kein reines Personendatum; Autor-/" +
                        "Freigabe-Zeiger zeigt nach der Anonymisierung auf den anonymisierten Mitgliedsdatensatz",
            ),
        )
    }
}
