package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.FinancialHistoryEntryDto
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind.CONTRIBUTION_DEBIT_IN_FLIGHT
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind.CONTRIBUTION_OUTSTANDING
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind.CONTRIBUTION_PAID
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind.CONTRIBUTION_WAIVED
import network.lapis.cloud.shared.domain.FinancialHistoryEntryKind.DONATION
import network.lapis.cloud.shared.domain.FinancialHistoryYearDto
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberFinancialHistoryDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IMemberFinancialHistoryService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private const val ANONYMIZED_DISPLAY_NAME = "(DSGVO-gelöscht)"

/**
 * Welle V1.4.4.1 "Beitragshistorie" -- see [IMemberFinancialHistoryService] and
 * [MemberFinancialHistoryDto] for the design ("vier getrennte Größen", exclusions, Zeitachse).
 */
class MemberFinancialHistoryService(
    private val call: ApplicationCall,
) : IMemberFinancialHistoryService {
    override suspend fun getMemberFinancialHistory(memberId: String): MemberFinancialHistoryDto {
        val current = resolveCurrentMember(call)
        val requestedId = memberId.toMemberUuidOrThrow()
        if (!current.isPrivileged && current.role != AccountRole.TREASURER && current.memberId != requestedId) {
            throw ForbiddenException()
        }
        if (current.memberId != requestedId) {
            // Datenschutz-Nachweis für den FREMDbezogenen Lesezugriff. Bewusst KEIN AuditLogEntry:
            // der Audit-Log ist eine GoBD-Hashkette für buchungsrelevante Veränderungen, ein
            // Seitenaufruf ist keine. Niemals Beträge loggen.
            logger.info {
                "member financial history read: actor=${current.memberId} actorRole=${current.role} subject=$requestedId"
            }
        }
        return transaction { loadHistory(requestedId = requestedId) }
    }

    private fun loadHistory(requestedId: Uuid): MemberFinancialHistoryDto {
        val memberRow =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq requestedId }
                .singleOrNull()
                ?: throw NotFoundException("Member $requestedId not found")
        val anonymized = memberRow[MemberTable.anonymizedAt] != null
        val displayName = if (anonymized) ANONYMIZED_DISPLAY_NAME else memberRow[MemberTable.displayName]
        val joinedAt = memberRow[MemberTable.joinedAt]
        val friendSince = memberRow[MemberTable.friendSince]

        val contributionEntries = loadContributionEntries(requestedId = requestedId)
        val donationResult = loadDonationEntries(requestedId = requestedId)
        val donationEntries = donationResult.displayEntries

        val contributionsPaid = contributionEntries.filter { it.kind == CONTRIBUTION_PAID }.sumAmount().setScale(2)
        val contributionsWaived = contributionEntries.filter { it.kind == CONTRIBUTION_WAIVED }.sumAmount().setScale(2)
        val contributionsOutstanding = contributionEntries.filter { it.kind == CONTRIBUTION_OUTSTANDING }.sumAmount().setScale(2)
        // Genettet über ALLE Journal-Einträge (auch die, deren eigener Netto-Betrag <= 0 ist und
        // deshalb aus `donationResult.displayEntries` herausfällt) -- exakt dieselbe Pro-Spender-
        // Netting-Disziplin wie `AccountingService.priorPostedDonationTotalThisYear` und
        // `PublicTransparencyReader.loadTopDonors` (dort pro Spender/Jahr, hier pro Spender/gesamt).
        // Andernfalls verschwindet eine als EIGENER journal_entry gebuchte Storno-/Korrekturbuchung
        // spurlos aus dieser Summe, obwohl sie in den beiden anderen Sichten korrekt gegengerechnet
        // wird (Review MAJOR, siehe donationResult.totalsByYear KDoc).
        val donationsTotal =
            donationResult.totalsByYear.values
                .fold(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2)

        val entryYears = (contributionEntries + donationEntries).map { it.date.year }.toMutableSet()
        entryYears += donationResult.totalsByYear.keys
        val years =
            entryYears
                .map { year ->
                    val yearEntries =
                        (contributionEntries + donationEntries)
                            .filter { it.date.year == year }
                            .sortedWith(compareByDescending<FinancialHistoryEntryDto> { it.date }.thenBy { it.sourceId })
                    FinancialHistoryYearDto(
                        year = year,
                        contributionsPaid = yearEntries.filter { it.kind == CONTRIBUTION_PAID }.sumAmount().setScale(2),
                        // Aus `totalsByYear` statt aus den (positiv gefilterten) `yearEntries` --
                        // sonst würde exakt derselbe Cross-Entry-Storno-Fehler wie oben bei
                        // `donationsTotal` hier auf Jahresebene erneut auftreten.
                        donationsTotal = (donationResult.totalsByYear[year] ?: BigDecimal.ZERO).setScale(2),
                        entries = yearEntries,
                    )
                    // Ein Jahr ohne jede Anzeigezeile UND mit exakt auf null genettetem
                    // Spenden-Saldo trägt keine Information und darf keinen leeren Jahresblock
                    // erzeugen (Review MINOR: `entryYears` wird u.a. aus `totalsByYear.keys`
                    // gebildet, was auch Jahre mit Netto 0,00 ohne jede Zeile einschließt). Ein
                    // negativ genettetes Jahr (Storno im Folgejahr) bleibt bewusst erhalten --
                    // signum() != 0 statt > 0.
                }.filter { it.entries.isNotEmpty() || it.donationsTotal.signum() != 0 }
                .sortedByDescending { it.year }

        return MemberFinancialHistoryDto(
            memberId = requestedId.toString(),
            memberDisplayName = displayName,
            anonymized = anonymized,
            joinedAt = joinedAt,
            friendSince = friendSince,
            contributionsPaid = contributionsPaid,
            contributionsWaived = contributionsWaived,
            contributionsOutstanding = contributionsOutstanding,
            donationsTotal = donationsTotal,
            years = years,
        )
    }

    /**
     * `ContributionTable` alone, deliberately WITHOUT [ContributionService.contributionJoin] --
     * that explicit join exists only to resolve `memberDisplayName`/`membershipTierName` for a
     * LISTING, neither of which this per-member, already-identified-member view needs. Joining
     * anyway would just re-import that method's own Exposed multi-FK-ambiguity workaround for no
     * benefit.
     */
    private fun loadContributionEntries(requestedId: Uuid): List<FinancialHistoryEntryDto> =
        ContributionTable
            .selectAll()
            .where { ContributionTable.memberId eq requestedId }
            .mapNotNull { row ->
                val status = row[ContributionTable.status]
                val amountDue = row[ContributionTable.amountDue]
                val periodStart = row[ContributionTable.periodStart]
                val periodEnd = row[ContributionTable.periodEnd]
                val label = "$periodStart – $periodEnd"
                val sourceId = row[ContributionTable.id].toString()
                when {
                    status == ContributionStatus.PAID -> {
                        val paidAt = row[ContributionTable.paidAt]
                        val date =
                            paidAt?.date ?: periodStart.also {
                                // Vor der V1.2.1-Zahlungsbrücke geschriebene Altbestände können PAID
                                // ohne paidAt tragen -- ein `!!` wäre hier verboten (Stolperfalle §8).
                                logger.warn {
                                    "contribution $sourceId is PAID without paidAt -- falling back to periodStart for the financial history timeline"
                                }
                            }
                        FinancialHistoryEntryDto(
                            kind = CONTRIBUTION_PAID,
                            date = date,
                            amount = row[ContributionTable.paidAmount] ?: amountDue,
                            label = label,
                            contributionStatus = status,
                            sourceId = sourceId,
                        )
                    }
                    status == ContributionStatus.WAIVED ->
                        FinancialHistoryEntryDto(
                            kind = CONTRIBUTION_WAIVED,
                            date = periodStart,
                            amount = amountDue,
                            label = label,
                            contributionStatus = status,
                            sourceId = sourceId,
                        )
                    status in ContributionStatusSets.OUTSTANDING ->
                        FinancialHistoryEntryDto(
                            kind = CONTRIBUTION_OUTSTANDING,
                            date = row[ContributionTable.dueDate],
                            amount = amountDue,
                            label = label,
                            contributionStatus = status,
                            sourceId = sourceId,
                        )
                    // ContributionStatusSets.DEBIT_IN_FLIGHT (DEBIT_SCHEDULED/DEBIT_SUBMITTED):
                    // neither gezahlt, erlassen, noch (per ContributionStatusSets) offen -- bleibt
                    // deshalb aus allen vier Summen heraus (Sets-Semantik unverändert), bekommt aber
                    // (Review MEDIUM) eine eigene Zeile, damit ein laufender SEPA-Einzug zwischen
                    // Lauf-Erstellung und Rücklauf-Verbuchung nicht spurlos aus der Historie
                    // verschwindet -- siehe [MemberFinancialHistoryDto] KDoc "Zeitachse".
                    status in ContributionStatusSets.DEBIT_IN_FLIGHT ->
                        FinancialHistoryEntryDto(
                            kind = CONTRIBUTION_DEBIT_IN_FLIGHT,
                            date = row[ContributionTable.dueDate],
                            amount = amountDue,
                            label = label,
                            contributionStatus = status,
                            sourceId = sourceId,
                        )
                    else -> null
                }
            }

    /**
     * Donation-Ertragszeilen UND Jahres-Nettosummen dieses Mitglieds -- gleiches
     * `journal_entry ⋈ posting ⋈ ledger_account` Muster wie
     * [network.lapis.cloud.server.routes.PublicTransparencyReader.loadTopDonors], bewusst OHNE
     * dessen `PublicRankingConsentEventTable`-Join und OHNE `MemberStatus.ACTIVE`-Filter -- das sind
     * Bedingungen der ÖFFENTLICHEN Rangliste, nicht dieser internen Treuhandsicht auf die
     * eigenen/fremden Finanzen. `donorMemberId eq requestedId` schließt jede `externalDonorId`-
     * Spende aus, siehe [MemberFinancialHistoryDto] KDoc "`ExternalDonor` bewusst ausgeschlossen".
     *
     * **Zeilen vs. Summen (Review MAJOR fix)**: `GROUP BY journal_entry.id` bildet den Netto-Betrag
     * pro EINZELNEM Journal-Eintrag -- richtig für eine Zeile ("was wurde an diesem Tag gebucht"),
     * aber falsch für eine Summe, sobald eine Storno-/Korrekturbuchung als eigener, zweiter
     * `journal_entry` gebucht wird (dieses Schema kennt keinen `REVERSED`-Status, siehe
     * `Accounting.kt` "Extend with e.g. REVERSED in a later Storno wave" -- eine Korrektur ist daher
     * IMMER ein zweiter Eintrag, nie eine Statusänderung am ersten). Ein Eintrag, dessen eigener
     * Netto-Betrag `<= 0` ist, hat keine sinnvolle Zeile ("negative Spende" ergibt keinen Sinn) --
     * er fällt deshalb aus [DonationEntriesResult.displayEntries]. Er bleibt aber vollständig Teil
     * von [DonationEntriesResult.totalsByYear], das über ALLE Einträge des Jahres nettet, exakt wie
     * `AccountingService.priorPostedDonationTotalThisYear` (Spender/Jahr) und
     * [network.lapis.cloud.server.routes.PublicTransparencyReader.loadTopDonors] (Spender/Jahr,
     * öffentliche Rangliste) es bereits tun -- sonst würde ein cross-entry Storno spurlos aus
     * `donationsTotal`/`FinancialHistoryYearDto.donationsTotal` verschwinden, obwohl die beiden
     * anderen Sichten es korrekt gegenrechnen (Bug, den [MemberFinancialHistoryDto] KDoc "so the two
     * views can never derive the sign rule differently" eigentlich ausschließen sollte).
     */
    private fun loadDonationEntries(requestedId: Uuid): DonationEntriesResult {
        val signedAmount = DonationIncomeAmount.signedAmount()
        val donationTotal = signedAmount.sum()
        val rows =
            JournalEntryTable
                .join(PostingTable, JoinType.INNER, JournalEntryTable.id, PostingTable.journalEntryId)
                .join(LedgerAccountTable, JoinType.INNER, PostingTable.ledgerAccountId, LedgerAccountTable.id)
                .select(JournalEntryTable.id, JournalEntryTable.entryDate, JournalEntryTable.description, donationTotal)
                .where {
                    (JournalEntryTable.status eq JournalEntryStatus.POSTED) and
                        (JournalEntryTable.donorMemberId eq requestedId) and
                        JournalEntryTable.donorCategory.isNotNull() and
                        (LedgerAccountTable.type eq LedgerAccountType.INCOME)
                }.groupBy(JournalEntryTable.id, JournalEntryTable.entryDate, JournalEntryTable.description)

        val totalsByYear = mutableMapOf<Int, BigDecimal>()
        val displayEntries = mutableListOf<FinancialHistoryEntryDto>()
        for (row in rows) {
            val sum = row[donationTotal]?.setScale(2) ?: continue
            val entryDate = row[JournalEntryTable.entryDate]
            totalsByYear.merge(entryDate.year, sum, BigDecimal::add)
            // Nur die ZEILEN-Darstellung ist auf positive Einträge beschränkt -- die Summe oben
            // (totalsByYear) hat den Betrag bereits egal welchen Vorzeichens verrechnet.
            if (sum > BigDecimal.ZERO) {
                displayEntries +=
                    FinancialHistoryEntryDto(
                        kind = DONATION,
                        date = entryDate,
                        amount = sum,
                        label = row[JournalEntryTable.description],
                        contributionStatus = null,
                        sourceId = row[JournalEntryTable.id].toString(),
                    )
            }
        }
        return DonationEntriesResult(displayEntries = displayEntries, totalsByYear = totalsByYear)
    }
}

/**
 * Result of [MemberFinancialHistoryService.loadDonationEntries]: [displayEntries] are the
 * positive-only rows shown in [FinancialHistoryYearDto.entries], while [totalsByYear] nets
 * EVERY matching journal entry for that calendar year (any sign) and is the sole source for
 * both [MemberFinancialHistoryDto.donationsTotal] and [FinancialHistoryYearDto.donationsTotal] --
 * see [MemberFinancialHistoryService.loadDonationEntries] KDoc "Zeilen vs. Summen".
 */
private data class DonationEntriesResult(
    val displayEntries: List<FinancialHistoryEntryDto>,
    val totalsByYear: Map<Int, BigDecimal>,
)

private fun List<FinancialHistoryEntryDto>.sumAmount(): BigDecimal = fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount }

private fun String.toMemberUuidOrThrow(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
