package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.shared.domain.ArticleBylineKind
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
                    // V1.9.105: imported articles have no human author in Lapis -- they are listed under articleImportsExecuted instead.
                    .where { (ArticleTable.authorId eq memberId) and (ArticleTable.imported eq false) }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ArticleTable.id].toString())
                                put("title", row[ArticleTable.title])
                                put("status", row[ArticleTable.status].name)
                                put("createdAt", row[ArticleTable.createdAt].toString())
                                row[ArticleTable.mcpAgentLabel]?.let { put("mcpAgentLabel", it) }
                                // V1.9.105: the author line (Art. 15) -- text, kind and the moment of the consent.
                                row[ArticleTable.byline]?.let { put("byline", it) }
                                row[ArticleTable.bylineKind]?.let { put("bylineKind", it.name) }
                                row[ArticleTable.bylineConsentAt]?.let { put("bylineConsentAt", it.toString()) }
                            },
                        )
                    }
            }
            putJsonArray("reviewedArticles") {
                ArticleTable
                    .selectAll()
                    .where { (ArticleTable.reviewedBy eq memberId) and (ArticleTable.imported eq false) }
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
            // V1.9.105: byline changes this member made on articles of OTHERS (organization lines, removals) -- id/slug and the resulting kind, never the text.
            putJsonArray("bylineChangesMade") {
                ArticleTable
                    .selectAll()
                    .where { ArticleTable.bylineSetBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ArticleTable.id].toString())
                                row[ArticleTable.slug]?.let { put("slug", it) }
                                put("bylineKind", row[ArticleTable.bylineKind]?.name ?: "NONE")
                            },
                        )
                    }
            }
            // V1.9.105: Art. 15 notice that this member executed imports of legacy articles. They did NOT write them and are not their author.
            put(
                "articleImportsExecuted",
                ArticleTable
                    .selectAll()
                    .where { (ArticleTable.authorId eq memberId) and (ArticleTable.imported eq true) }
                    .count(),
            )
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
        // Welle V1.9.105 -- a PERSONAL byline is the member's own name, shown only on consent: gone on erasure (text, kind and consent moment).
        // ORGANIZATION and IMPORTED lines are not personal data of this member and stay.
        ArticleTable.update(
            {
                (ArticleTable.authorId eq memberId) and (ArticleTable.bylineKind eq ArticleBylineKind.PERSONAL)
            },
        ) {
            it[byline] = null
            it[bylineKind] = null
            it[bylineConsentAt] = null
            it[bylineSetAt] = DbClock.nowLocalDateTime()
        }
        val count =
            ArticleTable
                .selectAll()
                .where {
                    ((ArticleTable.authorId eq memberId) or (ArticleTable.reviewedBy eq memberId)) and (ArticleTable.imported eq false)
                }.count()
        return listOf(
            TableErasureOutcome(
                table = "article",
                rowsRetained = count.toInt(),
                retentionReason =
                    "Organisationsinhalt (öffentliche Publikation), kein reines Personendatum; Autor-/" +
                        "Freigabe-Zeiger zeigt nach der Anonymisierung auf den anonymisierten Mitgliedsdatensatz; " +
                        "eine persönliche Autorenzeile (Name) wird gelöscht, Organisations- und importierte Zeilen bleiben " +
                        "stehen; importierte Artikel verweisen nur als ausführende Person auf das Mitglied",
            ),
        )
    }
}
