package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.activism.ActivismSignalStore
import network.lapis.cloud.server.db.generated.ActivismActionTable
import network.lapis.cloud.server.db.generated.ActivismContributionTable
import network.lapis.cloud.server.db.generated.ActivismNoticeTextTable
import network.lapis.cloud.server.db.generated.ActivismReportTable
import network.lapis.cloud.server.db.generated.ActivismResponsibleTable
import network.lapis.cloud.server.db.generated.ActivismSignalTable
import network.lapis.cloud.server.db.generated.ActivismTaskTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.246 "Aktionen A1" -- owns the member-bearing tables of the activism module (see `80-activism.kuml.kts`).
 *
 * **Export** (Art. 15/20): the actions the member is responsible for or created, their own contributions, their own signals (action id and title
 * only), their own reports and their own tasks.
 *
 * **Erasure** (every [ErasureMode]): signals and responsible rows are deleted; contributions keep the quantity (the progress counter) but lose the
 * member and the note; the creator / approver / assignee / decider / reporter / resolver columns and the notice-text editor are set to null.
 * Free texts the member wrote into an action (title, description) are organisational content of the action and stay.
 */
object ActivismPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "activism"
    override val displayName = "Aktionen"
    override val coveredTables =
        setOf(
            ActivismActionTable,
            ActivismResponsibleTable,
            ActivismTaskTable,
            ActivismContributionTable,
            ActivismSignalTable,
            ActivismReportTable,
            ActivismNoticeTextTable,
        )

    override fun exportMember(memberId: Uuid): JsonElement {
        val titles =
            ActivismActionTable.selectAll().associate { it[ActivismActionTable.id] to it[ActivismActionTable.title] }
        return buildJsonObject {
            put(
                "actionsResponsibleFor",
                buildJsonArray {
                    ActivismResponsibleTable.selectAll().where { ActivismResponsibleTable.memberId eq memberId }.forEach {
                        add(buildJsonObject { put("title", titles[it[ActivismResponsibleTable.actionId]] ?: "") })
                    }
                },
            )
            put(
                "actionsCreated",
                buildJsonArray {
                    ActivismActionTable.selectAll().where { ActivismActionTable.createdBy eq memberId }.forEach {
                        add(buildJsonObject { put("title", it[ActivismActionTable.title]) })
                    }
                },
            )
            put(
                "contributions",
                buildJsonArray {
                    ActivismContributionTable.selectAll().where { ActivismContributionTable.memberId eq memberId }.forEach {
                        add(
                            buildJsonObject {
                                put("action", titles[it[ActivismContributionTable.actionId]] ?: "")
                                put("quantity", it[ActivismContributionTable.quantity])
                                it[ActivismContributionTable.area]?.let { area -> put("area", area) }
                                it[ActivismContributionTable.note]?.let { note -> put("note", note) }
                                put("status", it[ActivismContributionTable.status].name)
                                put("reportedAt", it[ActivismContributionTable.reportedAt].toString())
                            },
                        )
                    }
                },
            )
            put(
                "signals",
                buildJsonArray {
                    ActivismSignalStore.signalsOfMember(memberId).forEach {
                        add(buildJsonObject { put("action", titles[it] ?: "") })
                    }
                },
            )
            put(
                "reports",
                buildJsonArray {
                    ActivismReportTable.selectAll().where { ActivismReportTable.reporterMemberId eq memberId }.forEach {
                        add(
                            buildJsonObject {
                                put("action", titles[it[ActivismReportTable.actionId]] ?: "")
                                put("reason", it[ActivismReportTable.reason])
                                put("createdAt", it[ActivismReportTable.createdAt].toString())
                            },
                        )
                    }
                },
            )
            put(
                "tasks",
                buildJsonArray {
                    ActivismTaskTable.selectAll().where { ActivismTaskTable.assigneeMemberId eq memberId }.forEach {
                        add(buildJsonObject { put("text", it[ActivismTaskTable.text]) })
                    }
                },
            )
        }
    }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val signals = ActivismSignalStore.eraseMember(memberId)
        val responsible = ActivismResponsibleTable.deleteWhere { ActivismResponsibleTable.memberId eq memberId }
        val contributions =
            ActivismContributionTable.update({ ActivismContributionTable.memberId eq memberId }) {
                it[ActivismContributionTable.memberId] = null
                it[note] = null
            }
        val deciders = ActivismContributionTable.update({ ActivismContributionTable.decidedBy eq memberId }) { it[decidedBy] = null }
        val created = ActivismActionTable.update({ ActivismActionTable.createdBy eq memberId }) { it[createdBy] = null }
        val approved = ActivismActionTable.update({ ActivismActionTable.approvedBy eq memberId }) { it[approvedBy] = null }
        val assigned = ActivismTaskTable.update({ ActivismTaskTable.assigneeMemberId eq memberId }) { it[assigneeMemberId] = null }
        val doneBy = ActivismTaskTable.update({ ActivismTaskTable.doneBy eq memberId }) { it[ActivismTaskTable.doneBy] = null }
        val reporter = ActivismReportTable.update({ ActivismReportTable.reporterMemberId eq memberId }) { it[reporterMemberId] = null }
        val resolver = ActivismReportTable.update({ ActivismReportTable.resolvedBy eq memberId }) { it[resolvedBy] = null }
        val editor = ActivismNoticeTextTable.update({ ActivismNoticeTextTable.updatedBy eq memberId }) { it[updatedBy] = null }
        return listOf(
            TableErasureOutcome(table = "activism_signal", rowsDeleted = signals),
            TableErasureOutcome(table = "activism_responsible", rowsDeleted = responsible),
            TableErasureOutcome(table = "activism_contribution", rowsAnonymized = contributions + deciders),
            TableErasureOutcome(table = "activism_action", rowsAnonymized = created + approved),
            TableErasureOutcome(table = "activism_task", rowsAnonymized = assigned + doneBy),
            TableErasureOutcome(table = "activism_report", rowsAnonymized = reporter + resolver),
            TableErasureOutcome(table = "activism_notice_text", rowsAnonymized = editor),
        )
    }
}
