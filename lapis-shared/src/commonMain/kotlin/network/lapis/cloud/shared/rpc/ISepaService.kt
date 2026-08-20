package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.SepaComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.SepaComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.SepaCreditorSettingsDto
import network.lapis.cloud.shared.domain.SepaCreditorSettingsInput
import network.lapis.cloud.shared.domain.SepaDebitBatchDetailDto
import network.lapis.cloud.shared.domain.SepaDebitBatchDto
import network.lapis.cloud.shared.domain.SepaDebitBatchInput
import network.lapis.cloud.shared.domain.SepaDebitBatchPreviewDto
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaMandateDto
import network.lapis.cloud.shared.domain.SepaMandateInput
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaPrenotificationDto
import network.lapis.cloud.shared.domain.SepaReturnDto
import network.lapis.cloud.shared.domain.SepaReturnInput
import network.lapis.cloud.shared.domain.SepaSettingsDto

/**
 * SEPA-Lastschriftmandate -- Welle V1.2.1 "Zahlungs-Fundament" ships ONLY the disclaimer-
 * acknowledgment opt-in gate below (`OrganizationSettings.sepaDebitEnabled`), NO mandate/batch/
 * return functionality yet (mandate management, pain.008 generation, batch runs are V1.2.2 --
 * `grantMandate`/`revokeMandate`/`createDebitBatch`/etc. are added to THIS SAME interface then,
 * not a new one). See `network.lapis.cloud.server.rpc.SepaComplianceDisclaimer` KDoc for the full
 * mechanism and `11-organization-settings.kuml.kts` file header "Welle V1.2.1" for why the gate
 * exists now, ahead of any real functionality behind it.
 *
 * ## The `sepaDebitEnabled` gate
 *
 * Exact mirror of `IAuctionService`'s "The `auctionEnabled` gate" -- `sepaDebitEnabled` is
 * deliberately NOT part of `IOrganizationSettingsService.updateOrganizationSettings`'s writable
 * field set; it can only be flipped on via [enableSepaDebit] (requires the disclaimer
 * acknowledgment below) or off via [disableSepaDebit].
 *
 * ## The disclaimer-acknowledgment mechanism (auditable, not a bare boolean flip)
 *
 * Exact mirror of `IAuctionService`'s own mechanism -- [enableSepaDebit] requires the calling ADMIN
 * to first [getSepaComplianceDisclaimer] (the current, versioned+hashed legal-risk text) and echo
 * BOTH its `version` and `sha256` back unmodified. On success the acknowledgment is persisted as
 * its own append-only row (who/when/which version+hash). [disableSepaDebit] requires no such
 * acknowledgment and does not erase the acknowledgment history.
 */
@RpcService
interface ISepaService {
    /** Role: ADMIN. Not gated by `sepaDebitEnabled` (must be readable BEFORE the feature can be switched on). */
    suspend fun getSepaComplianceDisclaimer(): SepaComplianceDisclaimerDto

    /** Role: ADMIN. See class KDoc "The disclaimer-acknowledgment mechanism". */
    suspend fun enableSepaDebit(input: SepaComplianceAcknowledgmentInput): SepaSettingsDto

    /** Role: ADMIN. No acknowledgment required to turn the feature off. */
    suspend fun disableSepaDebit(): SepaSettingsDto

    /** Role: ADMIN. */
    suspend fun getSepaSettings(): SepaSettingsDto

    // ── V1.2.2 "SEPA-Lastschriftmandate" -- see network.lapis.cloud.server.rpc.SepaService KDoc ──

    // ── Configuration (ADMIN) ────────────────────────────────────────────

    /** Role: ADMIN. Creditor id/name and the pre-notification period. See D-4/E-11. */
    suspend fun getSepaCreditorSettings(): SepaCreditorSettingsDto

    /** Role: ADMIN. Writes an ORGANIZATION_SETTINGS audit entry. Deliberately NOT via updateOrganizationSettings. */
    suspend fun updateSepaCreditorSettings(input: SepaCreditorSettingsInput): SepaCreditorSettingsDto

    // ── Mandates ─────────────────────────────────────────────────────────

    /**
     * The member themselves (input.memberId == null) OR TREASURER/ADMIN on their behalf (E-12).
     * NIT-1 (Security Round 1, 2026-08-20): this KDoc used to say "TREASURER/BOARD/ADMIN" -- the
     * actual implementation (`SepaService.SEPA_TREASURY_ROLES`) has always excluded BOARD (fail-closed,
     * not a live bug), but the wrong KDoc could have misled a future maintainer into "fixing" it in
     * the wrong direction.
     */
    suspend fun grantMandate(input: SepaMandateInput): SepaMandateDto

    /**
     * The member themselves OR TREASURER/ADMIN. A foreign mandate -> NotFoundException. See
     * [grantMandate] KDoc "NIT-1" -- same KDoc-vs-implementation correction applies here.
     */
    suspend fun revokeMandate(
        mandateId: String,
        reason: String?,
    ): SepaMandateDto

    /**
     * Self-service. Empty if no ACTIVE mandate exists, else exactly one element -- `List` rather than
     * a nullable return, same convention `IConferenceRecordingService.getActiveRecording`/
     * `IConferenceStreamingService.getActiveStream` already establish (kilua-rpc's KSP code
     * generator does not support a nullable DTO return type for a generated RPC binding).
     */
    suspend fun getMyMandate(): List<SepaMandateDto>

    /** Role: TREASURER/BOARD/ADMIN. Only last4, never the full IBAN. Keyset-paginated. */
    suspend fun listMandates(
        status: SepaMandateStatus? = null,
        limit: Int = 50,
        beforeGrantedAt: LocalDateTime? = null,
    ): List<SepaMandateDto>

    // ── Direct-debit runs ────────────────────────────────────────────────

    /** Role: TREASURER/ADMIN. Purely read-only preview -- changes NOTHING. */
    suspend fun previewDebitBatch(input: SepaDebitBatchInput): SepaDebitBatchPreviewDto

    /** Role: TREASURER/ADMIN. Creates the run, sets contributions to DEBIT_SCHEDULED. */
    suspend fun createDebitBatch(input: SepaDebitBatchInput): SepaDebitBatchDto

    /** Role: TREASURER/ADMIN. DRAFT -> NOTIFIED. Fixes required_notice_days (E-7). */
    suspend fun notifyBatch(batchId: String): SepaDebitBatchDto

    /** Role: TREASURER/ADMIN. NOTIFIED -> GENERATED. Throws if the notice period has not yet elapsed. */
    suspend fun generateBatchFile(batchId: String): SepaDebitBatchDto

    /** Role: TREASURER/ADMIN. GENERATED -> SUBMITTED. Manual "submitted to the bank" confirmation (E-3). */
    suspend fun markBatchSubmitted(
        batchId: String,
        note: String?,
    ): SepaDebitBatchDto

    /** Role: TREASURER/ADMIN. Only DRAFT/NOTIFIED/GENERATED. */
    suspend fun cancelBatch(
        batchId: String,
        reason: String,
    ): SepaDebitBatchDto

    /**
     * Role: TREASURER/ADMIN. Posts every SETTLEABLE item of this run via the EXISTING, unchanged
     * `ContributionPostingBridge` -- with the calling treasurer's own identity. See plan D-5/D-6: the
     * poller only marks readiness, a human posts.
     */
    suspend fun settleBatch(batchId: String): SepaDebitBatchDetailDto

    /** Role: TREASURER/BOARD/ADMIN. */
    suspend fun listBatches(
        status: SepaDebitBatchStatus? = null,
        limit: Int = 50,
        beforeCreatedAt: LocalDateTime? = null,
    ): List<SepaDebitBatchDto>

    /** Role: TREASURER/BOARD/ADMIN. */
    suspend fun getBatch(batchId: String): SepaDebitBatchDetailDto

    // ── Returns ──────────────────────────────────────────────────────────

    /** Role: TREASURER/ADMIN. MD01/MD06/MD07 additionally revoke the mandate. */
    suspend fun recordReturn(input: SepaReturnInput): SepaReturnDto

    /** Role: TREASURER/BOARD/ADMIN. */
    suspend fun listReturns(
        from: LocalDate? = null,
        to: LocalDate? = null,
        limit: Int = 100,
    ): List<SepaReturnDto>

    // ── Member self-service ──────────────────────────────────────────────

    /** Self-service: upcoming direct debits (pre-notification). */
    suspend fun listMyPrenotifications(): List<SepaPrenotificationDto>
}
