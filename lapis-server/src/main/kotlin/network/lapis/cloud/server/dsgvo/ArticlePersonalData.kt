package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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

    /** Replaces `article.mcp_agent_label` on erasure. */
    const val ERASED_AGENT_LABEL = "Agent"

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
                                row[ArticleTable.mcpAgentLabel]?.let { put("mcpAgentLabel", it) }
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
        // Welle V1.9.100 -- the MCP connection label is free text chosen by the member and may be
        // personal: overwrite it with a fixed placeholder (the "created by an assistant" marker stays).
        ArticleTable.update({ (ArticleTable.authorId eq memberId) and ArticleTable.mcpAgentLabel.isNotNull() }) {
            it[mcpAgentLabel] = ERASED_AGENT_LABEL
        }
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
