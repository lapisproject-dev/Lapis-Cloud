package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.StagingSeedLedger.covers
import network.lapis.cloud.server.db.StagingSeedLedger.insertRows
import network.lapis.cloud.server.db.StagingSeedLedger.statusesOf
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.CrowdfundingReactionTable
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.CrowdfundingReactionValue
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Sample data for internal crowdfunding (V1.9.205): five projects in every state the model knows and the reactions of the members.
 *
 * Writes the rows `CrowdfundingService.submitProject` (project row + `PROJECT_STAKE` ledger row with the negative initial weight, same time)
 * and `approveProject`/`rejectProject` (status, reviewer, time) and `castReaction` leave behind. The model has no pledges and no funded/expired
 * status: the only LTR movement is the submitter's stake, the visibility weight decays by 10 percent per day and a project that nobody decided
 * is approved by silence after 14 days (both computed at read time, never stored). No distribution is computed (that is a live treasurer action).
 */
internal object StagingSeedCrowdfunding : StagingSeedPart {
    override val name: String = "crowdfunding"

    data class PlannedProject(
        val index: Int,
        val title: String,
        val description: String,
        val submitter: Int,
        val weight: BigDecimal,
        val submittedDaysAgo: Int,
        val status: CrowdfundingProjectStatus,
        val reviewer: Int?,
        val reviewedDaysAgo: Int?,
        val rejectionReason: String?,
    ) {
        val id: Uuid get() = SeedIds.crowdfunding(index)
    }

    data class PlannedReaction(
        val index: Int,
        val project: Int,
        val member: Int,
        val value: CrowdfundingReactionValue,
        val daysAgo: Int,
    )

    /** Append-only: the index is the id, never the list position. The weights satisfy the entry hurdle at every submission (pinned by a test). */
    val projects: List<PlannedProject> =
        listOf(
            PlannedProject(
                index = 1,
                title = "Mitgliederzeitung neu auflegen (Demodaten)",
                description = "Eine gedruckte Mitgliederzeitung, vier Ausgaben im Jahr. Fiktives Beispielprojekt der Demodaten.",
                submitter = 1,
                weight = BigDecimal("5.00"),
                submittedDaysAgo = 120,
                status = CrowdfundingProjectStatus.APPROVED,
                reviewer = 2,
                reviewedDaysAgo = 118,
                rejectionReason = null,
            ),
            PlannedProject(
                index = 2,
                title = "Vereinsbus anschaffen (Demodaten)",
                description = "Ein gebrauchter Kleinbus fuer Ausfluege. Fiktives Beispielprojekt der Demodaten.",
                submitter = 3,
                weight = BigDecimal("2.00"),
                submittedDaysAgo = 60,
                status = CrowdfundingProjectStatus.REJECTED,
                reviewer = 2,
                reviewedDaysAgo = 57,
                rejectionReason = "Die laufenden Kosten sind fuer den Verein derzeit nicht tragbar (Demodaten).",
            ),
            PlannedProject(
                index = 3,
                title = "Gemeinschaftsgarten am Vereinsheim (Demodaten)",
                description = "Hochbeete, Werkzeug und eine Regentonne fuer einen Garten. Fiktives Beispielprojekt der Demodaten.",
                submitter = 1,
                weight = BigDecimal("10.00"),
                submittedDaysAgo = 30,
                status = CrowdfundingProjectStatus.APPROVED,
                reviewer = 2,
                reviewedDaysAgo = 27,
                rejectionReason = null,
            ),
            PlannedProject(
                index = 4,
                title = "Beamer fuer den Vortragsraum (Demodaten)",
                description = "Beamer mit Leinwand. Fiktives Beispielprojekt der Demodaten, nach 14 Tagen ohne Entscheidung freigegeben.",
                submitter = 9,
                weight = BigDecimal("5.00"),
                submittedDaysAgo = 20,
                status = CrowdfundingProjectStatus.PENDING,
                reviewer = null,
                reviewedDaysAgo = null,
                rejectionReason = null,
            ),
            PlannedProject(
                index = 5,
                title = "Fahrradwerkstatt einrichten (Demodaten)",
                description = "Werkzeug fuer eine offene Fahrradwerkstatt. Fiktives Beispielprojekt der Demodaten, frisch eingereicht.",
                submitter = 2,
                weight = BigDecimal("3.00"),
                submittedDaysAgo = 2,
                status = CrowdfundingProjectStatus.PENDING,
                reviewer = null,
                reviewedDaysAgo = null,
                rejectionReason = null,
            ),
        )

    /** Reactions only on projects that are effectively approved (approved, or silent for 14 days). */
    val reactions: List<PlannedReaction> =
        listOf(
            PlannedReaction(index = 1, project = 1, member = 25, value = CrowdfundingReactionValue.LIKE, daysAgo = 110),
            PlannedReaction(index = 2, project = 1, member = 27, value = CrowdfundingReactionValue.LIKE, daysAgo = 100),
            PlannedReaction(index = 3, project = 3, member = 4, value = CrowdfundingReactionValue.LIKE, daysAgo = 25),
            PlannedReaction(index = 4, project = 3, member = 5, value = CrowdfundingReactionValue.LIKE, daysAgo = 24),
            PlannedReaction(index = 5, project = 3, member = 6, value = CrowdfundingReactionValue.LIKE, daysAgo = 22),
            PlannedReaction(index = 6, project = 3, member = 7, value = CrowdfundingReactionValue.LIKE, daysAgo = 20),
            PlannedReaction(index = 7, project = 3, member = 10, value = CrowdfundingReactionValue.LIKE, daysAgo = 15),
            PlannedReaction(index = 8, project = 3, member = 19, value = CrowdfundingReactionValue.LIKE, daysAgo = 9),
            PlannedReaction(index = 9, project = 3, member = 20, value = CrowdfundingReactionValue.DISLIKE, daysAgo = 8),
            PlannedReaction(index = 10, project = 4, member = 21, value = CrowdfundingReactionValue.LIKE, daysAgo = 5),
            PlannedReaction(index = 11, project = 4, member = 23, value = CrowdfundingReactionValue.LIKE, daysAgo = 4),
            PlannedReaction(index = 12, project = 4, member = 24, value = CrowdfundingReactionValue.DISLIKE, daysAgo = 3),
        )

    fun ledgerRow(
        project: PlannedProject,
        clock: SeedClock,
    ): SeedLedgerRow =
        SeedLedgerRow(
            id = SeedIds.crowdfunding(0x1000 + project.index),
            member = project.submitter,
            type = LtrLedgerEntryType.PROJECT_STAKE,
            amount = project.weight.negate(),
            referenceType = LtrLedgerReferenceType.CROWDFUNDING_PROJECT,
            referenceId = project.id,
            note = StagingSeedLedger.note("Sichtbarkeits-Gewicht fuer Crowdfunding-Projekt '${project.title}'"),
            at = submittedStamp(project = project, clock = clock),
        )

    fun submittedStamp(
        project: PlannedProject,
        clock: SeedClock,
    ): LocalDateTime = clock.utcAt(daysAgo = project.submittedDaysAgo, hour = 10)

    /** All ledger rows the plan writes, for the balance simulation of the plan test. */
    fun ledgerPlan(clock: SeedClock): List<SeedLedgerRow> = projects.map { ledgerRow(project = it, clock = clock) }

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        val members = projects.flatMap { listOfNotNull(it.submitter, it.reviewer) } + reactions.map { it.member }
        val statuses = statusesOf(members)
        var inserted = 0
        var skipped = 0
        projects.forEach { project ->
            val ledger = ledgerRow(project = project, clock = clock)
            val reviewerKnown = project.reviewer == null || project.reviewer in statuses
            val ok =
                CrowdfundingProjectTable.selectAll().where { CrowdfundingProjectTable.id eq project.id }.empty() &&
                    statuses[project.submitter] == MemberStatus.ACTIVE &&
                    reviewerKnown &&
                    covers(listOf(ledger))
            if (!ok) {
                skipped++
                return@forEach
            }
            CrowdfundingProjectTable.insert {
                it[id] = project.id
                it[title] = project.title
                it[description] = project.description
                it[submitterMemberId] = SeedIds.member(project.submitter)
                it[initialWeightLtr] = project.weight
                it[status] = project.status
                it[rejectionReason] = project.rejectionReason
                it[reviewedBy] = project.reviewer?.let { r -> SeedIds.member(r) }
                it[reviewedAt] = project.reviewedDaysAgo?.let { d -> clock.utcAt(daysAgo = d, hour = 14) }
                it[submittedAt] = submittedStamp(project = project, clock = clock)
            }
            insertRows(listOf(ledger))
            inserted++
        }
        reactions.forEach { reaction ->
            val projectId = SeedIds.crowdfunding(reaction.project)
            val reactionId = SeedIds.crowdfunding(0x2000 + reaction.index)
            val free =
                CrowdfundingReactionTable.selectAll().where { CrowdfundingReactionTable.id eq reactionId }.empty() &&
                    CrowdfundingReactionTable
                        .selectAll()
                        .where {
                            (CrowdfundingReactionTable.projectId eq projectId) and
                                (CrowdfundingReactionTable.memberId eq SeedIds.member(reaction.member))
                        }.empty()
            val projectThere = !CrowdfundingProjectTable.selectAll().where { CrowdfundingProjectTable.id eq projectId }.empty()
            if (!(free && projectThere && statuses[reaction.member] == MemberStatus.ACTIVE)) {
                skipped++
                return@forEach
            }
            CrowdfundingReactionTable.insert {
                it[id] = reactionId
                it[CrowdfundingReactionTable.projectId] = projectId
                it[memberId] = SeedIds.member(reaction.member)
                it[reactionValue] = reaction.value
                it[castAt] = clock.utcAt(daysAgo = reaction.daysAgo, hour = 16)
            }
            inserted++
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }
}
