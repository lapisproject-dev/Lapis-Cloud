package network.lapis.cloud.server.db

import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.AuditLogChainStateTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.OpenItemTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PollOptionTable
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.db.generated.SepaDebitItemTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Row counts of every table the staging seed writes plus the head of the audit hash chain -- "did anything change?" in one
 * comparable value. The ballot tables are NOT read here (only specs of the election area may do that, and only in `src/test`).
 */
internal object StagingSeedProbe {
    private val touchedTables: List<Table> =
        listOf(
            MemberTable,
            AccountTable,
            MemberStatusHistoryTable,
            MembershipTierTable,
            OrganizationSettingsTable,
            LedgerAccountTable,
            ContributionTable,
            JournalEntryTable,
            PostingTable,
            OpenItemTable,
            SepaMandateTable,
            SepaDebitBatchTable,
            SepaDebitItemTable,
            DunningLevelTable,
            CommitteeTable,
            CommitteeMembershipTable,
            MeetingTable,
            AgendaItemTable,
            AttendanceTable,
            MotionTable,
            ResolutionTable,
            ElectionTable,
            ElectionOptionTable,
            ElectionBoardMemberTable,
            ElectionEligibleVoterTable,
            ElectionParticipationTable,
            PollTable,
            PollOptionTable,
            PollParticipationTable,
            PollResponseTable,
            RegionalChapterTable,
            RegionalChapterOfficerTable,
            ArticleTable,
            DocumentFolderTable,
            EventTable,
            EventSeriesTable,
            EventRegistrationTable,
            CarpoolPostingTable,
            AuditLogEntryTable,
        )

    data class Snapshot(
        val counts: Map<String, Long>,
        val chainLastSequence: Long,
        val chainLastHash: String?,
    )

    fun snapshot(db: Database): Snapshot =
        transaction(db) {
            val chain = AuditLogChainStateTable.selectAll().single()
            Snapshot(
                counts = touchedTables.associate { it.tableName to it.selectAll().count() },
                chainLastSequence = chain[AuditLogChainStateTable.lastSequenceNumber],
                chainLastHash = chain[AuditLogChainStateTable.lastEntryHash],
            )
        }
}
