package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/*
 * Welle V1.9.246 "Aktionen A1" -- the wire types between the client and `IActivismService`. See docs/features/activism.adoc.
 * All texts are plain text: the client sets them only as text, never as markup. New values go to the END of every enum.
 * Class names carry the prefix `Activism` (an `Action` would clash with `AuditAction` and Gradle's `Action`); the UI says "Aktion".
 */

/** What kind of action. Stored by name in `activism_action.kind` and `activism_notice_text.kind` (VARCHAR(20)). */
@Serializable
enum class ActivismKind { INFO_STAND, POSTERING, SIGNATURES, DEMONSTRATION, ACTION_DAY, DOOR_TO_DOOR, ONLINE, OTHER }

/** Lifecycle of an action. Stored by name in `activism_action.status` (VARCHAR(20)). */
@Serializable
enum class ActivismStatus { IDEA, PLANNED, RUNNING, COMPLETED, AWAITING_REVIEW, WITHDRAWN }

@Serializable
enum class ActivismContributionStatus { REPORTED, CONFIRMED, REJECTED }

@Serializable
enum class ActivismReportStatus { OPEN, RESOLVED }

/** What a viewer may do with ONE action: MEMBER (participant), RESPONSIBLE (one of its 1..10 persons), MANAGER (role BOARD or ADMIN). */
@Serializable
enum class ActivismViewerRole { MEMBER, RESPONSIBLE, MANAGER }

/** The optional forms of an action (a filter in the list). */
@Serializable
enum class ActivismForm { APPOINTMENT, PROGRESS, SIGNAL }

/** What the audit entries of the module record (ids, enums and counts only). */
@Serializable
enum class ActivismAuditKind {
    CREATED,
    UPDATED,
    STATUS_CHANGED,
    SUBMITTED,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    RESTORED,
    RESPONSIBLES_CHANGED,
    LINKS_CHANGED,
    RESULT_RECORDED,
    CONTRIBUTION_CONFIRMED,
    CONTRIBUTION_REJECTED,
    REPORT_RESOLVED,
    NOTICE_TEXT_CHANGED,
    NOTICE_TEXT_RESET,
    NOTICE_COUNTRY_CHANGED,
    REVIEW_REQUIRED_ON,
    REVIEW_REQUIRED_OFF,
}

/** After-snapshot of the four ACTIVISM_* audit entity types: enums, numbers and the length + hash of a text -- NEVER free text, NEVER a name. */
@Serializable
data class ActivismAuditSnapshot(
    val kind: ActivismAuditKind,
    val status: ActivismStatus? = null,
    val count: Int? = null,
    val changedFields: List<String> = emptyList(),
    val charCount: Int? = null,
    val sha256: String? = null,
    val country: String? = null,
)

/** Limits shared by server and client. */
object ActivismLimits {
    const val SIGNAL_MIN_DISPLAY = 5
    const val MAX_CONTRIBUTIONS_PER_MEMBER_ACTION_24H = 30
    const val MAX_REPORTS_PER_MEMBER_24H = 5
    val CONTRIBUTION_EDIT_WINDOW: Duration = 24.hours
    val RATE_WINDOW: Duration = 24.hours

    const val TITLE_MAX = 120
    const val DESCRIPTION_MAX = 4000
    const val GOAL_TEXT_MAX = 500
    const val LOCATION_MAX = 200
    const val UNIT_MAX = 40
    const val OWN_NOTICE_MAX = 2000
    const val GOAL_QUANTITY_MIN = 1
    const val GOAL_QUANTITY_MAX = 1_000_000
    const val RESPONSIBLES_MIN = 1
    const val RESPONSIBLES_MAX = 10
    const val TASKS_MAX = 100
    const val TASK_TEXT_MAX = 200
    const val TEXT_BLOCKS_MAX = 30
    const val TEXT_BLOCK_TITLE_MAX = 120
    const val TEXT_BLOCK_BODY_MAX = 4000
    const val MATERIAL_MAX = 30
    const val CONTRIBUTION_QUANTITY_MIN = 1
    const val CONTRIBUTION_QUANTITY_MAX = 10_000
    const val AREA_MAX = 120
    const val CONTRIBUTION_NOTE_MAX = 500
    const val REJECT_REASON_MAX = 200
    const val REPORT_REASON_MIN = 10
    const val REPORT_REASON_MAX = 1000
    const val WITHDRAW_REASON_MIN = 10
    const val WITHDRAW_REASON_MAX = 500
    const val RESULT_TEXT_MAX = 4000
    const val RESULT_NUMBER_MAX = 10_000_000
    const val NOTICE_TEXT_MAX = 2000
    const val CANDIDATES_MAX = 20
    const val CANDIDATE_QUERY_MIN = 2
    const val CANDIDATE_QUERY_MAX = 60
}

/** The state machine of an action. ONE place, used by the service and by the client (which buttons to show). */
object ActivismTransitions {
    private val CORE = setOf(ActivismStatus.PLANNED, ActivismStatus.RUNNING, ActivismStatus.COMPLETED)

    /**
     * Whether [role] may move an action from [from] to [to]. [reviewRequired] is the operator's switch. The creator-is-not-the-approver rule
     * (IDEA -> PLANNED via approval) is checked by the service against the locked row, not here. Withdrawing is allowed from every status.
     */
    fun allowed(
        from: ActivismStatus,
        to: ActivismStatus,
        role: ActivismViewerRole,
        reviewRequired: Boolean,
    ): Boolean {
        if (from == to) return false
        val manager = role == ActivismViewerRole.MANAGER
        val responsible = manager || role == ActivismViewerRole.RESPONSIBLE
        return when {
            to == ActivismStatus.WITHDRAWN -> manager
            from == ActivismStatus.WITHDRAWN -> manager && to == ActivismStatus.IDEA
            from == ActivismStatus.IDEA && to == ActivismStatus.PLANNED -> manager && !reviewRequired
            from == ActivismStatus.IDEA && to == ActivismStatus.AWAITING_REVIEW -> responsible && reviewRequired
            from == ActivismStatus.AWAITING_REVIEW && (to == ActivismStatus.PLANNED || to == ActivismStatus.IDEA) -> manager
            from == ActivismStatus.COMPLETED && to in CORE -> manager
            from in CORE && to in CORE -> responsible
            else -> false
        }
    }

    /** Whether a contribution or a signal may be given. */
    fun acceptsParticipation(status: ActivismStatus): Boolean = status == ActivismStatus.RUNNING
}

/** The list filter. [statuses] empty = the default "Geplant + Läuft". */
@Serializable
data class ActivismListFilter(
    val statuses: List<ActivismStatus> = emptyList(),
    val form: ActivismForm? = null,
)

@Serializable
data class ActivismActionSummaryDto(
    val id: String,
    val title: String,
    val kind: ActivismKind,
    val formAppointment: Boolean,
    val formProgress: Boolean,
    val formSignal: Boolean,
    val startsAt: LocalDateTime?,
    val endsAt: LocalDateTime?,
    val status: ActivismStatus,
    val unit: String?,
    val goalQuantity: Int?,
    val progressReported: Int,
    val progressConfirmed: Int?,
)

/** Create / update input. Only [title] is mandatory; the status is changed by its own methods. */
@Serializable
data class ActivismActionInput(
    val title: String,
    val description: String? = null,
    val goalText: String? = null,
    val location: String? = null,
    val startsAt: LocalDateTime? = null,
    val endsAt: LocalDateTime? = null,
    val kind: ActivismKind = ActivismKind.OTHER,
    val formAppointment: Boolean = false,
    val formProgress: Boolean = false,
    val formSignal: Boolean = false,
    val goalQuantity: Int? = null,
    val unit: String? = null,
    val confirmContributions: Boolean = false,
    val ownNotice: String? = null,
)

/** The aggregate of the signals: [countAtLeast5] is null below [ActivismLimits.SIGNAL_MIN_DISPLAY]. Never a list of persons. */
@Serializable
data class ActivismSignalStateDto(
    val countAtLeast5: Int? = null,
    val mine: Boolean = false,
)

@Serializable
data class ActivismTaskDto(
    val id: String,
    val position: Int,
    val text: String,
    val assigneeName: String? = null,
    val assigneeIsMe: Boolean = false,
    val dueOn: LocalDate? = null,
    val done: Boolean = false,
)

@Serializable
data class ActivismTaskInput(
    val text: String,
    val assigneeMemberId: String? = null,
    val dueOn: LocalDate? = null,
)

@Serializable
data class ActivismTextBlockDto(
    val id: String,
    val position: Int,
    val title: String,
    val body: String,
)

@Serializable
data class ActivismMaterialDto(
    val documentId: String,
    val position: Int,
    val title: String,
)

/** A shift of the linked event: the time window and the numbers, never a name. */
@Serializable
data class ActivismShiftSummaryDto(
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val needed: Int,
    val confirmed: Int,
)

@Serializable
data class ActivismResponsibleDto(
    val memberId: String,
    val name: String,
)

@Serializable
data class ActivismResultDto(
    val participants: Int?,
    val quantity: Int?,
    val text: String?,
    val recordedAt: LocalDateTime?,
)

@Serializable
data class ActivismResultInput(
    val participants: Int? = null,
    val quantity: Int? = null,
    val text: String? = null,
)

@Serializable
data class ActivismActionDetailDto(
    val id: String,
    val title: String,
    val description: String?,
    val goalText: String?,
    val location: String?,
    val startsAt: LocalDateTime?,
    val endsAt: LocalDateTime?,
    val kind: ActivismKind,
    val formAppointment: Boolean,
    val formProgress: Boolean,
    val formSignal: Boolean,
    val goalQuantity: Int?,
    val unit: String?,
    val confirmContributions: Boolean,
    val status: ActivismStatus,
    val viewerRole: ActivismViewerRole,
    val progressReported: Int,
    val progressConfirmed: Int?,
    val signal: ActivismSignalStateDto,
    /** The operator's notice text for this kind and country; null = the client shows its translated standard text. */
    val noticeText: String?,
    val ownNotice: String?,
    val tasks: List<ActivismTaskDto>,
    val textBlocks: List<ActivismTextBlockDto>,
    val material: List<ActivismMaterialDto>,
    /** Only when the viewer may see the linked event (the existing event gate); null otherwise. */
    val eventShifts: List<ActivismShiftSummaryDto>?,
    val result: ActivismResultDto?,
    /** RESPONSIBLE / MANAGER only. */
    val responsibles: List<ActivismResponsibleDto>?,
    val withdrawnReason: String?,
    val reviewRejectReason: String?,
    /** RESPONSIBLE / MANAGER only. */
    val costCenterId: String?,
    val costCenterName: String?,
    val eventId: String?,
    val revision: Int,
    /** The operator's switch: whether an idea needs a second person's approval (decides which buttons the page offers). */
    val reviewRequired: Boolean = false,
)

@Serializable
data class ActivismContributionInput(
    val quantity: Int,
    val area: String? = null,
    val note: String? = null,
)

@Serializable
data class ActivismContributionDto(
    val id: String,
    /** Null for foreign contributions seen by a plain member (never delivered), set for RESPONSIBLE / MANAGER and for the own ones. */
    val memberId: String? = null,
    val memberName: String? = null,
    val quantity: Int,
    val area: String?,
    val note: String?,
    val status: ActivismContributionStatus,
    val rejectReason: String? = null,
    val reportedAt: LocalDateTime,
    val editable: Boolean = false,
)

@Serializable
data class ActivismAreaSumDto(
    val area: String?,
    val sum: Int,
)

@Serializable
data class ActivismContributionsViewDto(
    val own: List<ActivismContributionDto>,
    val areaSums: List<ActivismAreaSumDto>,
    /** RESPONSIBLE / MANAGER only (with names); null for a plain member. */
    val all: List<ActivismContributionDto>?,
)

/** A report for ADMIN / BOARD. NO name of the reporting person (open question F4). */
@Serializable
data class ActivismReportDto(
    val id: String,
    val actionId: String,
    val actionTitle: String,
    val reason: String,
    val status: ActivismReportStatus,
    val createdAt: LocalDateTime,
)

@Serializable
data class ActivismNoticeSettingsDto(
    val country: String,
    val reviewRequired: Boolean,
    /** Per kind the stored text; a kind without an entry shows the standard text. */
    val texts: Map<ActivismKind, String>,
)

@Serializable
data class ActivismCandidateDto(
    val memberId: String,
    val name: String,
)
