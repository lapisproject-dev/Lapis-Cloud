package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaMandateStatus

/**
 * V1.2.2 SEPA-Client-UI wave -- pure, DOM-free client-side mirror of the three role tiers
 * `network.lapis.cloud.server.rpc.SepaService`/`SepaRoutes` actually enforce (see the approved
 * plan §1's verified backend-contract matrix). As with every other client-side role gate in this
 * app ([GovernanceAuthzUi], `AppState.hasRole`), this is a UX nicety on top of the server's real
 * authority, not the actual security boundary -- `guarded()`/`sepaGuarded()` gracefully surface the
 * server's own exception if a stale client-side computation lets a now-unauthorized action through
 * to an RPC call.
 *
 * The three role sets are deliberately three SEPARATE constants, not one reused across all three
 * gates -- [FILE_DOWNLOAD_ROLES] mirrors `SepaRoutes.SEPA_FILE_DOWNLOAD_ROLES` exactly (TREASURER,
 * ADMIN -- **never** BOARD, Security Round 1, MAJOR-1: a BOARD member must never reach the raw
 * pain.008 file, which carries every collected member's full IBAN in clear text) while
 * [READ_ROLES] additionally admits BOARD (mirrors `requireSepaReadable`'s TREASURER/BOARD/ADMIN
 * tier for `listMandates`/`listBatches`/`getBatch`/`listReturns`). Reusing [TREASURY_ROLES] for
 * the download gate would have been a silent BOARD-can-download-IBANs regression the moment
 * someone "simplified" the two identical-looking sets together -- see [SepaAuthzUiTest] for the
 * test that exists specifically to catch that drift.
 */
object SepaAuthzUi {
    val TREASURY_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.ADMIN)
    val READ_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

    /**
     * Bewusst eigene Konstante, NICHT [TREASURY_ROLES] wiederverwendet -- spiegelt
     * `SepaRoutes.SEPA_FILE_DOWNLOAD_ROLES` (Security Round 1, MAJOR-1). Zufällig aktuell
     * wertgleich mit [TREASURY_ROLES], aber absichtlich getrennt gehalten, falls sich einer der
     * beiden Server-Tiers künftig unabhängig ändert.
     */
    val FILE_DOWNLOAD_ROLES: Set<AccountRole> = setOf(AccountRole.TREASURER, AccountRole.ADMIN)

    fun canReadSepa(role: AccountRole?): Boolean = role in READ_ROLES

    fun canTreasuryAct(role: AccountRole?): Boolean = role in TREASURY_ROLES

    /** == [canTreasuryAct] -- eigene Funktion für Lesbarkeit an den Fremderfassungs-Aufrufstellen
     * und für einen eigenen, benannten Test (spiegelt `grantMandate`'s eigene Rollenprüfung für
     * `input.memberId != null`, siehe Plan §1). */
    fun canGrantOnBehalf(role: AccountRole?): Boolean = canTreasuryAct(role)

    fun canRecordReturn(role: AccountRole?): Boolean = canTreasuryAct(role)

    /**
     * Mirrors `SepaService.revokeMandate`'s own check: the mandate's owner may always revoke their
     * own mandate (any status is rejected server-side except ACTIVE, but the server -- not this
     * function -- is the authority on that; this predicate governs whether the BUTTON is even
     * rendered), OR TREASURER/ADMIN may revoke anyone's. Unlike every other SEPA write action,
     * `revokeMandate` has **no** `requireSepaUsable()` gate server-side (Plan §1 matrix) -- this
     * function does not attempt to model that; it is purely the role/ownership half.
     */
    fun canRevokeMandateOf(
        role: AccountRole?,
        ownMandate: Boolean,
        status: SepaMandateStatus,
    ): Boolean {
        if (status != SepaMandateStatus.ACTIVE) return false
        return ownMandate || canTreasuryAct(role)
    }

    /**
     * `false` für jede Rolle außer TREASURER/ADMIN (**BOARD niemals**, s. o.), für
     * `status == CANCELLED` (die Route antwortet 409) und ohne `generatedDocumentId` (die Route
     * antwortet 404) -- die drei serverseitigen Ablehnungsgründe der Download-Route, gespiegelt
     * als reine Sichtbarkeits-Vorprüfung (S-7).
     */
    fun canDownloadBatchFile(
        role: AccountRole?,
        status: SepaDebitBatchStatus,
        generatedDocumentId: String?,
    ): Boolean {
        if (role !in FILE_DOWNLOAD_ROLES) return false
        if (status == SepaDebitBatchStatus.CANCELLED) return false
        if (generatedDocumentId == null) return false
        return true
    }

    /**
     * K12: die einzige Stelle in dieser Welle, an der eine sonst unzulässige Aktion NICHT
     * verborgen, sondern als eigener, deaktivierter Zustand modelliert wird
     * ([SepaBatchAction.GENERATE_FILE_TOO_EARLY], S-13) -- ein Schatzmeister soll sehen, DASS eine
     * Datei kommt, nicht nur, dass gerade keine da ist.
     *
     * Review Round 2 (2026-08-20, MAJOR): the NOTIFIED branch compares [requestedCollectionDate]
     * against [fileGenerationAllowedFrom] -- mirroring `SepaService.prepareBatchFileGeneration`'s
     * OWN check (`requestedCollectionDate < allowedFrom`) -- and takes **no** `today` parameter at
     * all. An earlier version compared `fileGenerationAllowedFrom <= today` instead, following the
     * (itself imprecise) `SepaDebitBatchDto.fileGenerationAllowedFrom` KDoc rather than the actual
     * server implementation. That produced two symmetric bugs: once `today` passed
     * [fileGenerationAllowedFrom], the button would flip to enabled even for a batch whose
     * `requestedCollectionDate` still falls short of the notice period (the server would then reject
     * every single click, permanently, since both dates are already fixed) -- and, conversely, a
     * batch whose `requestedCollectionDate` already satisfies the notice period stayed disabled for
     * the entire remaining wait, because `today` had not yet caught up. Since both
     * `requestedCollectionDate` and `fileGenerationAllowedFrom` are fixed once a batch is NOTIFIED,
     * the comparison is timeless -- there was never a need for `today` here in the first place.
     */
    fun nextBatchAction(
        role: AccountRole?,
        status: SepaDebitBatchStatus,
        requestedCollectionDate: LocalDate,
        fileGenerationAllowedFrom: LocalDate?,
        hasSettleableItems: Boolean,
    ): SepaBatchAction? {
        if (!canTreasuryAct(role)) return null
        return when (status) {
            SepaDebitBatchStatus.DRAFT -> SepaBatchAction.NOTIFY
            SepaDebitBatchStatus.NOTIFIED ->
                if (fileGenerationAllowedFrom != null && requestedCollectionDate >= fileGenerationAllowedFrom) {
                    SepaBatchAction.GENERATE_FILE
                } else {
                    SepaBatchAction.GENERATE_FILE_TOO_EARLY
                }
            SepaDebitBatchStatus.GENERATED -> SepaBatchAction.MARK_SUBMITTED
            SepaDebitBatchStatus.SUBMITTED -> if (hasSettleableItems) SepaBatchAction.SETTLE else null
            SepaDebitBatchStatus.SETTLED -> null
            SepaDebitBatchStatus.CANCELLED -> null
        }
    }
}

/** Die genau fünf möglichen "nächsten Schritte" eines Lastschriftlaufs aus Schatzmeister-Sicht --
 * siehe [SepaAuthzUi.nextBatchAction] KDoc "K12" für [GENERATE_FILE_TOO_EARLY]s Sonderrolle. */
enum class SepaBatchAction { NOTIFY, GENERATE_FILE, GENERATE_FILE_TOO_EARLY, MARK_SUBMITTED, SETTLE }
