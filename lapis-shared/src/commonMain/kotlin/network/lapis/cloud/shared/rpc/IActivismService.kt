package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ActivismActionDetailDto
import network.lapis.cloud.shared.domain.ActivismActionInput
import network.lapis.cloud.shared.domain.ActivismActionSummaryDto
import network.lapis.cloud.shared.domain.ActivismCandidateDto
import network.lapis.cloud.shared.domain.ActivismContributionDto
import network.lapis.cloud.shared.domain.ActivismContributionInput
import network.lapis.cloud.shared.domain.ActivismContributionsViewDto
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismListFilter
import network.lapis.cloud.shared.domain.ActivismNoticeSettingsDto
import network.lapis.cloud.shared.domain.ActivismReportDto
import network.lapis.cloud.shared.domain.ActivismResultInput
import network.lapis.cloud.shared.domain.ActivismSignalStateDto
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismTaskDto
import network.lapis.cloud.shared.domain.ActivismTaskInput
import network.lapis.cloud.shared.domain.ActivismTextBlockDto

/**
 * V1.9.246 "Aktionen A1" -- see `docs/features/activism.adoc`. THIS is the LAST `registerService` in `Application.kt` (after
 * `IPartnerServerService`); Kilua numbers routes by declaration order, so new methods go to the END of this interface.
 *
 * Access: every method needs a member in `MemberStatusSets.ACTIVISM_PARTICIPANT`. Per action the viewer is a MEMBER, a RESPONSIBLE person or a
 * MANAGER (role BOARD or ADMIN). An unknown action and an action the viewer must not see answer with the SAME [NotFoundException] (no existence
 * oracle); a role refusal is a bare [ForbiddenException]. The signals are never returned as a list of persons.
 */
@RpcService
interface IActivismService {
    suspend fun listActions(filter: ActivismListFilter): List<ActivismActionSummaryDto>

    suspend fun getAction(id: String): ActivismActionDetailDto

    suspend fun createAction(input: ActivismActionInput): ActivismActionDetailDto

    /** [expectedRevision] must match, else [ActivismRevisionConflictException]. */
    suspend fun updateAction(
        id: String,
        input: ActivismActionInput,
        expectedRevision: Int,
    ): ActivismActionDetailDto

    suspend fun setActionStatus(
        id: String,
        target: ActivismStatus,
    ): ActivismActionDetailDto

    suspend fun submitForReview(id: String): ActivismActionDetailDto

    suspend fun approveAction(id: String): ActivismActionDetailDto

    suspend fun rejectReview(
        id: String,
        reason: String,
    ): ActivismActionDetailDto

    /** MANAGER: 1..10 responsible persons. */
    suspend fun setResponsibles(
        id: String,
        memberIds: List<String>,
    ): ActivismActionDetailDto

    /** MANAGER: the optional cost centre (active only) and event. */
    suspend fun setPlanningLinks(
        id: String,
        costCenterId: String?,
        eventId: String?,
    ): ActivismActionDetailDto

    suspend fun withdrawAction(
        id: String,
        reason: String,
    ): ActivismActionDetailDto

    suspend fun restoreAction(id: String): ActivismActionDetailDto

    suspend fun recordResult(
        id: String,
        input: ActivismResultInput,
    ): ActivismActionDetailDto

    suspend fun addTask(
        actionId: String,
        input: ActivismTaskInput,
    ): ActivismTaskDto

    suspend fun updateTask(
        taskId: String,
        input: ActivismTaskInput,
    ): ActivismTaskDto

    suspend fun setTaskDone(
        taskId: String,
        done: Boolean,
    ): ActivismTaskDto

    suspend fun deleteTask(taskId: String)

    suspend fun moveTask(
        taskId: String,
        newPosition: Int,
    )

    suspend fun addTextBlock(
        actionId: String,
        title: String,
        body: String,
    ): ActivismTextBlockDto

    suspend fun updateTextBlock(
        blockId: String,
        title: String,
        body: String,
    ): ActivismTextBlockDto

    suspend fun deleteTextBlock(blockId: String)

    suspend fun linkMaterialDocument(
        actionId: String,
        documentId: String,
    )

    suspend fun unlinkMaterialDocument(
        actionId: String,
        documentId: String,
    )

    suspend fun reportContribution(
        actionId: String,
        input: ActivismContributionInput,
    ): ActivismContributionDto

    /** Within 24 hours of reporting and only while REPORTED. */
    suspend fun updateOwnContribution(
        contributionId: String,
        input: ActivismContributionInput,
    ): ActivismContributionDto

    suspend fun withdrawOwnContribution(contributionId: String)

    /** RESPONSIBLE / MANAGER; [reason] only with a rejection. */
    suspend fun decideContribution(
        contributionId: String,
        confirm: Boolean,
        reason: String?,
    ): ActivismContributionDto

    suspend fun listContributions(actionId: String): ActivismContributionsViewDto

    /** Switches the own signal; returns the aggregate. Only while the action is RUNNING. */
    suspend fun setSignal(
        actionId: String,
        on: Boolean,
    ): ActivismSignalStateDto

    suspend fun reportAction(
        actionId: String,
        reason: String,
    )

    /** ADMIN / BOARD. */
    suspend fun listReports(): List<ActivismReportDto>

    suspend fun resolveReport(reportId: String)

    /** ADMIN / BOARD. */
    suspend fun getNoticeSettings(): ActivismNoticeSettingsDto

    suspend fun setNoticeCountry(code: String)

    /** A null [body] resets the kind to the translated standard text. */
    suspend fun setNoticeText(
        kind: ActivismKind,
        body: String?,
    )

    suspend fun setReviewRequired(enabled: Boolean)

    /** RESPONSIBLE / MANAGER (any action) -- at most 20 hits, throttled. */
    suspend fun listActivismCandidates(query: String): List<ActivismCandidateDto>
}
