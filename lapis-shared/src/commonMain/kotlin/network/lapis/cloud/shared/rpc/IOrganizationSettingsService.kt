package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.OrganizationSettingsInput

/**
 * The issuing association's own letterhead/bank/Gemeinnuetzigkeit settings (V0.4.1 Serienbrief/
 * PDF engine) -- see [OrganizationSettingsDto] KDoc for the full field rationale and the
 * exactly-one-row convention. Read is available to the treasury/board tier that also reads
 * [IAccountingService] ([network.lapis.cloud.shared.domain.AccountRole.TREASURER]/
 * [network.lapis.cloud.shared.domain.AccountRole.BOARD]/
 * [network.lapis.cloud.shared.domain.AccountRole.ADMIN]); write is ADMIN-only, since letterhead/
 * bank/tax-exemption data is org-wide configuration, not routine treasury business.
 */
@RpcService
interface IOrganizationSettingsService {
    /** Role: TREASURER/BOARD/ADMIN. Always returns the single seeded row. */
    suspend fun getOrganizationSettings(): OrganizationSettingsDto

    /** Role: ADMIN. Replaces every field of the single seeded row wholesale. */
    suspend fun updateOrganizationSettings(input: OrganizationSettingsInput): OrganizationSettingsDto

    /**
     * V1.9.124. Role: ADMIN only. Sets the instance default of the optional noise suppression in video conferences (a person's own
     * remembered choice still wins). A change writes one audit entry; setting the value it already has writes nothing.
     */
    suspend fun updateNoiseSuppressionDefault(enabled: Boolean): OrganizationSettingsDto

    /**
     * V1.9.129. Role: ADMIN only. Switches on or off whether members may lay logos, their name and a frame over their own camera image
     * ("Video-Overlays"). A change writes one audit entry; setting the value it already has writes nothing.
     */
    suspend fun updateVideoOverlaysOffered(enabled: Boolean): OrganizationSettingsDto

    /**
     * V1.9.143. Role: ADMIN only. Switches the optional recording of the pulpit in the encounter space on or off. Switching ON requires
     * [acknowledgedNoticeVersion] to equal [network.lapis.cloud.shared.domain.PULPIT_RECORDING_NOTICE_VERSION] (the admin has read the
     * operator notice and checked the legal admissibility for the country of the instance), else
     * [PulpitRecordingNoticeOutdatedException]. Switching OFF stops every running pulpit recording. A change writes one audit entry.
     */
    suspend fun updatePulpitRecordingEnabled(
        enabled: Boolean,
        acknowledgedNoticeVersion: String?,
    ): OrganizationSettingsDto

    /** V1.9.143. Role: ADMIN only. Whether the person at the pulpit has to confirm a recording start. Only while the instance switch is on. */
    suspend fun updatePulpitRecordingRequireConfirmation(required: Boolean): OrganizationSettingsDto

    /**
     * V1.9.144. Role: ADMIN only. Switches the optional member finder on or off. Switching ON requires [acknowledgedNoticeVersion] to equal
     * [network.lapis.cloud.shared.domain.MEMBER_FINDER_NOTICE_VERSION] (the admin has read the operator notice and checked the legal
     * admissibility -- party membership is a special category of data), else [MemberFinderNoticeOutdatedException]. Switching OFF deletes every
     * request, block and band and ends every consent. A change writes one audit entry (no count, no member).
     */
    suspend fun updateMemberFinderEnabled(
        enabled: Boolean,
        acknowledgedNoticeVersion: String?,
    ): OrganizationSettingsDto

    /**
     * V1.9.156. Role: ADMIN only. Switches "recording and live streaming in event rooms" on or off. Switching ON requires
     * [acknowledgedNoticeVersion] to equal [network.lapis.cloud.shared.domain.EVENT_CAPTURE_NOTICE_VERSION]
     * ([network.lapis.cloud.shared.rpc.EventCaptureOperatorNoticeOutdatedException]), a PUBLISHED `EVENT_REC` text
     * ([network.lapis.cloud.shared.rpc.EventCaptureTextMissingException]) and no active released event room
     * ([network.lapis.cloud.shared.rpc.EventCaptureChangeLockedException]). Switching OFF is refused while any event room records or streams
     * ([network.lapis.cloud.shared.rpc.EventCaptureBusyException]); it never stops anything remotely.
     */
    suspend fun updateEventCaptureEnabled(
        enabled: Boolean,
        acknowledgedNoticeVersion: String?,
    ): OrganizationSettingsDto
}
