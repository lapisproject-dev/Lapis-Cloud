package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.CrmContactDto
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmContactPageDto
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionDto
import network.lapis.cloud.shared.domain.CrmInteractionInput
import network.lapis.cloud.shared.domain.TableErasureOutcomeDto

/**
 * Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- BOARD/ADMIN read/write management surface for
 * `crm_contact`/`crm_interaction`, backed by `network.lapis.cloud.server.crm.CrmContactStore` and,
 * for the DSGVO subject-erasure path, `network.lapis.cloud.server.dsgvo.CrmPersonalData`.
 *
 * **Rollen-Asymmetrie, bewusst**: every read/write method here is BOARD/ADMIN; [eraseContact] alone
 * is ADMIN-only, same posture `IDsgvoService.decideErasure`/`.executeErasure` already establish for
 * the member-erasure workflow. A BOARD caller can create/edit/archive a contact but not erase one.
 *
 * The Auskunftsbuendel (full Art. 15 export payload for a contact) travels over the dedicated HTTP
 * route `GET /api/dsgvo/crm-contacts/{id}/export` (see `network.lapis.cloud.server.routes
 * .registerCrmRoutes` KDoc), NOT this RPC surface -- same "can grow large, Kilua RPC is tuned for
 * small typed payloads" reasoning `IDsgvoService`'s own KDoc documents for the member-export route.
 */
@RpcService
interface ICrmService {
    /** Role: BOARD/ADMIN. [limit] is server-capped at 200 regardless of the requested value. */
    suspend fun listContacts(
        filterType: CrmContactType? = null,
        onlyRetentionOverdue: Boolean = false,
        includeArchived: Boolean = false,
        limit: Int = 50,
        offset: Int = 0,
    ): CrmContactPageDto

    /** Role: BOARD/ADMIN. */
    suspend fun getContact(id: String): CrmContactDto

    /** Role: BOARD/ADMIN. Rate-limited (`Application.kt` wiring). Server validates [input] regardless of any client-side pre-check. */
    suspend fun createContact(input: CrmContactInput): CrmContactDto

    /** Role: BOARD/ADMIN. Rate-limited. */
    suspend fun updateContact(
        id: String,
        input: CrmContactInput,
    ): CrmContactDto

    /** Role: BOARD/ADMIN. "Out of sight", never a deletion -- see `CrmContactsScreen.kt` KDoc. */
    suspend fun archiveContact(id: String): CrmContactDto

    /** Role: BOARD/ADMIN. */
    suspend fun unarchiveContact(id: String): CrmContactDto

    /**
     * Role: BOARD/ADMIN. Art. 7(3) DSGVO -- records that a previously given consent has been
     * withdrawn (`consentWithdrawnAt = now`), the ONLY codepath that can ever set it (see
     * `CrmContactPolicy.mayReceiveEmail` KDoc "the ONE gate a future mailing/newsletter feature MUST
     * call"). Requires a documented `consentGivenAt` on the contact -- throws a bad-request error if
     * none exists (cannot withdraw a consent that was never recorded as given).
     */
    suspend fun withdrawConsent(id: String): CrmContactDto

    /** Role: BOARD/ADMIN. Newest-first, [limit] server-capped at 200. */
    suspend fun listInteractions(
        contactId: String,
        limit: Int = 50,
        offset: Int = 0,
    ): List<CrmInteractionDto>

    /**
     * Role: BOARD/ADMIN. Rate-limited. Updates the owning contact's `last_interaction_at`/
     * `retention_review_due_at` in the same transaction (see `CrmContactStore.recordInteraction`
     * KDoc "Concurrency" for the row-locking this requires).
     */
    suspend fun recordInteraction(input: CrmInteractionInput): CrmInteractionDto

    /**
     * Role: **ADMIN**. Art. 17 DSGVO -- a real, irreversible DELETE of the contact and every one of
     * its interactions (see `CrmPersonalData.erase` KDoc for why this is a hard delete, not
     * anonymize-with-retained-row). Writes one `dsgvo_audit_log` row (`subjectKind = CRM_CONTACT`).
     */
    suspend fun eraseContact(id: String): List<TableErasureOutcomeDto>
}
