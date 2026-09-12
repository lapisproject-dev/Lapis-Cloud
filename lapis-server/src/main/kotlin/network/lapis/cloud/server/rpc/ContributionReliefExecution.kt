package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.ContributionReliefRequestTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" -- the ONE place any of the three relief effects is
 * actually applied. Runs inside the CALLER's already-open `transaction {}` (`ContributionReliefService
 * .decideReliefRequest`/`.retryReliefExecution`), exactly like every
 * [network.lapis.cloud.server.dsgvo.PersonalDataContributor] implementation and
 * [MembershipTierAssignment] -- never opens its own.
 *
 * **A throw here would roll back the caller's WHOLE transaction -- including the APPROVED decision
 * that was just written.** For every fachlich-expected failure ("the target contribution is no
 * longer deferrable", "the member has been anonymized since the request was filed") this therefore
 * returns [ReliefExecutionOutcome.Failed] rather than throwing -- the caller persists that as
 * `status = APPROVED, execution_error = <reason>`, a safe, retryable resting state. A genuine
 * infrastructure exception (e.g. from [MembershipTierAssignment.apply]) is still allowed to
 * propagate and roll back the transaction -- the request then stays `REQUESTED`, also a safe state.
 *
 * **Locking discipline (deadlock-avoidance contract, see [AuditLogRecorder] KDoc)**: the caller
 * already holds a `FOR UPDATE` lock on the `contribution_relief_request` row before calling this
 * object. Each payload path additionally locks its own target row (`contribution` or `member`)
 * under that SAME transaction, re-checking the fachlich precondition UNDER the lock -- request and
 * decision can be days apart, so the pre-check performed at request time must never be trusted
 * again here. [AuditLogRecorder.record] must remain the LAST locking operation of the caller's
 * transaction; nothing here takes a lock after [execute] returns.
 */
internal sealed interface ReliefExecutionOutcome {
    data class Executed(
        val previousDueDate: LocalDate? = null,
    ) : ReliefExecutionOutcome

    /** The under-lock state recheck rejected the execution. NEVER an exception -- see class KDoc. */
    data class Failed(
        val reason: String,
    ) : ReliefExecutionOutcome
}

internal object ContributionReliefExecution {
    fun execute(
        request: ResultRow,
        actor: CurrentMember,
        now: LocalDateTime,
    ): ReliefExecutionOutcome =
        when (request[ContributionReliefRequestTable.kind]) {
            ContributionReliefKind.DEFERRAL -> executeDeferral(request = request, now = now)
            ContributionReliefKind.EXEMPTION -> executeExemption(request)
            ContributionReliefKind.REDUCTION -> executeReduction(request = request, actor = actor, now = now)
        }

    private fun executeDeferral(
        request: ResultRow,
        now: LocalDateTime,
    ): ReliefExecutionOutcome {
        val contributionId =
            requireNotNull(request[ContributionReliefRequestTable.deferralContributionId]) {
                "DEFERRAL request ${request[ContributionReliefRequestTable.id]} has no deferral_contribution_id -- chk_crr_payload_shape violated?"
            }
        val newDueDate =
            requireNotNull(request[ContributionReliefRequestTable.deferralNewDueDate]) {
                "DEFERRAL request ${request[ContributionReliefRequestTable.id]} has no deferral_new_due_date -- chk_crr_payload_shape violated?"
            }
        val row =
            ContributionTable
                .selectAll()
                .where { ContributionTable.id eq contributionId }
                .forUpdate()
                .singleOrNull() ?: return ReliefExecutionOutcome.Failed("contribution_not_found")
        val status = row[ContributionTable.status]
        if (status !in ContributionStatusSets.DEFERRABLE) return ReliefExecutionOutcome.Failed("contribution_not_deferrable:$status")
        val previous = row[ContributionTable.dueDate]
        if (newDueDate <= previous || newDueDate <= now.date) return ReliefExecutionOutcome.Failed("new_due_date_not_in_future")

        // Status-Neuberechnung -- OHNE diese Zeile bliebe eine vorher OVERDUE-Zeile fuer immer als
        // ueberfaellig sichtbar: DunningPoller.runPhaseA schreibt NUR OPEN -> OVERDUE, nie zurueck.
        // Gleiches Idiom wie DunningService.kt (dunningReferenceDate-Neuberechnung).
        val recomputed = if (newDueDate >= now.date) ContributionStatus.OPEN else ContributionStatus.OVERDUE
        ContributionTable.update({ ContributionTable.id eq contributionId }) {
            it[dueDate] = newDueDate
            it[ContributionTable.status] = recomputed
        }
        return ReliefExecutionOutcome.Executed(previousDueDate = previous)
    }

    private fun executeExemption(request: ResultRow): ReliefExecutionOutcome {
        val subjectMemberId = request[ContributionReliefRequestTable.subjectMemberId]
        val requestId = request[ContributionReliefRequestTable.id]
        val exemptFrom =
            requireNotNull(request[ContributionReliefRequestTable.exemptionFrom]) {
                "EXEMPTION request $requestId has no exemption_from -- chk_crr_payload_shape violated?"
            }
        val exemptUntil = request[ContributionReliefRequestTable.exemptionUntil]
        val row =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq subjectMemberId }
                .forUpdate()
                .singleOrNull() ?: return ReliefExecutionOutcome.Failed("member_not_found")
        if (row[MemberTable.anonymizedAt] != null) return ReliefExecutionOutcome.Failed("member_anonymized")
        if (row[MemberTable.status] in MemberStatusSets.MEMBERSHIP_ENDED) return ReliefExecutionOutcome.Failed("membership_ended")

        // Beruehrt bewusst KEINE bestehende Beitragszeile -- bereits generierte offene Zeilen
        // bleiben stehen, siehe ContributionReliefService KDoc / CHANGELOG "bewusste Grenze".
        //
        // Bewusste Grenze -- keine Beendigung: dies ist per grep der EINZIGE Produktions-Writer
        // von contribution_exempt_from/_until/_request_id. Es gibt (noch) keinen RPC-Pfad, der eine
        // bereits EXECUTED-Befreiung beendet/verkuerzt/zuruecknimmt -- ein weiterer EXEMPTION-Antrag
        // kann `contributionExemptUntil` nur VORWAERTS auf ein Datum >= `exemptFrom` verschieben
        // (siehe ContributionReliefService.validatePayload), nie aufheben. Fuer eine UNBEFRISTETE
        // Befreiung (`exemptUntil == null`) bedeutet das: sie laeuft dauerhaft, bis irgendwann ein
        // dedizierter Beendigungs-Pfad nachgezogen wird (CHANGELOG "bewusste Grenze"). Der
        // potenziell Art.-9-relevante Freitext des ZUGRUNDE liegenden Antrags wird davon unabhaengig
        // trotzdem 12 Monate nach `executedAt` redigiert, siehe
        // `network.lapis.cloud.server.contribution.ContributionReliefRedaction.redactionAnchor`.
        MemberTable.update({ MemberTable.id eq subjectMemberId }) {
            it[contributionExemptFrom] = exemptFrom
            it[contributionExemptUntil] = exemptUntil
            it[contributionExemptRequestId] = requestId
        }
        return ReliefExecutionOutcome.Executed()
    }

    private fun executeReduction(
        request: ResultRow,
        actor: CurrentMember,
        now: LocalDateTime,
    ): ReliefExecutionOutcome {
        val subjectMemberId = request[ContributionReliefRequestTable.subjectMemberId]
        val requestId = request[ContributionReliefRequestTable.id]
        val targetTierId =
            requireNotNull(request[ContributionReliefRequestTable.reductionTargetTierId]) {
                "REDUCTION request $requestId has no reduction_target_tier_id -- chk_crr_payload_shape violated?"
            }
        // Member-Zeile hier sperren -- MembershipTierAssignment.apply sperrt dieselbe Zeile
        // erneut innerhalb DERSELBEN Transaktion (No-op-Re-Lock, kein Deadlock-Risiko), siehe
        // Klassen-KDoc "Locking discipline".
        val memberRow =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq subjectMemberId }
                .forUpdate()
                .singleOrNull() ?: return ReliefExecutionOutcome.Failed("member_not_found")
        if (memberRow[MemberTable.anonymizedAt] != null) return ReliefExecutionOutcome.Failed("member_anonymized")
        if (memberRow[MemberTable.status] in MemberStatusSets.MEMBERSHIP_ENDED) return ReliefExecutionOutcome.Failed("membership_ended")
        val tierRow =
            MembershipTierTable.selectAll().where { MembershipTierTable.id eq targetTierId }.singleOrNull()
                ?: return ReliefExecutionOutcome.Failed("target_tier_not_found")
        if (!tierRow[MembershipTierTable.active]) return ReliefExecutionOutcome.Failed("target_tier_not_active")
        // Recheck under THIS lock of the two fachlich preconditions
        // ContributionReliefService.validatePayload enforced at request time -- see class KDoc
        // "the pre-check performed at request time must never be trusted again here": the member's
        // tier assignment may have changed in the days between request and decision.
        val currentTierId = memberRow[MemberTable.membershipTierId]
        // Review fix (same guard as ContributionReliefService.validatePayload, re-checked under
        // lock because the member's tier assignment may have changed between request and
        // decision): `currentTierId == null` means a family DEPENDENT billed through the family's
        // payer -- assigning them a tier here would start billing them directly IN ADDITION to the
        // family invoice. Must come BEFORE the "already current"/"not cheaper" checks, both of
        // which are vacuously true for null and would otherwise let this fall through silently.
        if (currentTierId == null) return ReliefExecutionOutcome.Failed("member_has_no_tier_of_their_own")
        if (currentTierId == targetTierId) return ReliefExecutionOutcome.Failed("target_tier_already_current")
        val currentAmount =
            MembershipTierTable
                .selectAll()
                .where { MembershipTierTable.id eq currentTierId }
                .singleOrNull()
                ?.get(MembershipTierTable.contributionAmount)
        if (currentAmount != null && tierRow[MembershipTierTable.contributionAmount] > currentAmount) {
            return ReliefExecutionOutcome.Failed("target_tier_not_cheaper_than_current")
        }

        // Rueckgabewert `false` (No-op, Stufe stand schon so) gilt als ERFOLG -- siehe
        // ContributionReliefService KDoc "REDUCTION". Wirft `apply` dennoch (ConflictException/
        // BadRequestException/NotFoundException) -- praktisch ausgeschlossen, da alle vier
        // Wurf-Bedingungen bereits oben gelesen und geprueft sind -- rollt die GANZE Transaktion
        // zurueck, der Antrag bleibt REQUESTED/APPROVED, ein sicherer, wiederholbarer Zustand.
        MembershipTierAssignment.apply(
            targetMemberId = subjectMemberId,
            newTierId = targetTierId,
            actor = actor,
            reason = "Sozialermäßigung (Antrag $requestId)",
            familyId = null,
            now = now,
        )
        return ReliefExecutionOutcome.Executed()
    }
}
