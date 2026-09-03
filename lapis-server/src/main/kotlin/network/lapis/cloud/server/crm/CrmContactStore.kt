package network.lapis.cloud.server.crm

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CrmContactTable
import network.lapis.cloud.server.db.generated.CrmInteractionTable
import network.lapis.cloud.server.db.generated.ExternalDonorTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.CrmContactDto
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmContactPageDto
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionDto
import network.lapis.cloud.shared.domain.CrmInteractionInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Reine Exposed-Datenzugriffsschicht für `crm_contact`/`crm_interaction` -- öffnet, wie jede
 * `*Store`/`*PersonalData` in diesem Codebase, NIE eine eigene `transaction {}` (die Aufrufer
 * -- `CrmService`, `CrmPersonalData` -- tun das). Ausschließlich typisierte Exposed-Query-Builder,
 * nie dynamisches SQL über Tabellen-/Spaltennamen.
 */
object CrmContactStore {
    private const val MAX_PAGE_SIZE = 200

    fun getOrNull(id: Uuid): CrmContactDto? =
        CrmContactTable
            .selectAll()
            .where { CrmContactTable.id eq id }
            .singleOrNull()
            ?.toDto()

    fun getOrThrow(id: Uuid): CrmContactDto = getOrNull(id) ?: throw NotFoundException("CRM contact $id not found")

    /**
     * Same lookup as [getOrThrow], but locks the row (`FOR UPDATE`) -- see [update]'s own KDoc
     * ("Concurrency") for why this exists and what it closes. Private: every OTHER read in this
     * object is intentionally unlocked (a plain read does not need to block on a concurrent
     * writer), only [update]'s read-decide-write sequence does.
     */
    private fun getForUpdateOrThrow(id: Uuid): CrmContactDto =
        CrmContactTable
            .selectAll()
            .where { CrmContactTable.id eq id }
            .forUpdate()
            .singleOrNull()
            ?.toDto()
            ?: throw NotFoundException("CRM contact $id not found")

    fun list(
        filterType: CrmContactType?,
        onlyRetentionOverdue: Boolean,
        includeArchived: Boolean,
        limit: Int,
        offset: Int,
    ): CrmContactPageDto {
        val effectiveLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val effectiveOffset = offset.coerceAtLeast(0)
        val now = DbClock.nowLocalDateTime()

        // Condition built up-front as a nullable Op<Boolean>, not a `.where {}.andWhere {}` chain
        // -- same idiom FederationService.listFederationRelationships/DsgvoService.listAuditLog
        // establish, this codebase does not use Exposed's separate `andWhere` extension anywhere.
        var condition: Op<Boolean>? = null
        if (filterType != null) condition = (CrmContactTable.contactType eq filterType).andWith(condition)
        if (!includeArchived) condition = CrmContactTable.archivedAt.isNull().andWith(condition)
        if (onlyRetentionOverdue) condition = (CrmContactTable.retentionReviewDueAt lessEq now).andWith(condition)
        val fixedCondition = condition

        fun query() = if (fixedCondition != null) CrmContactTable.selectAll().where { fixedCondition } else CrmContactTable.selectAll()

        val total = query().count().toInt()
        val items =
            query()
                // `id` as the tie-breaker (not just `displayName`) gives every offset-paged request
                // a deterministic, non-overlapping ordering once two contacts share a display name --
                // `displayName` alone is not unique (no such constraint on `crm_contact`).
                .orderBy(CrmContactTable.displayName to SortOrder.ASC, CrmContactTable.id to SortOrder.ASC)
                .limit(effectiveLimit)
                .offset(effectiveOffset.toLong())
                .map { it.toDto() }
        return CrmContactPageDto(items = items, total = total)
    }

    fun create(
        input: CrmContactInput,
        createdBy: Uuid,
    ): CrmContactDto {
        val normalizedEmail = CrmContactPolicy.normalizeEmail(input.email)
        val externalDonorUuid = input.externalDonorId?.let { raw -> raw.toCrmUuid() }
        val memberUuid = input.memberId?.let { raw -> raw.toCrmUuid() }
        if (externalDonorUuid != null) requireExistingExternalDonor(externalDonorUuid)
        if (memberUuid != null) requireExistingMember(memberUuid)
        requireUniqueEmail(normalizedEmail = normalizedEmail, excludingId = null)
        requireUniqueExternalDonor(externalDonorId = externalDonorUuid, excludingId = null)
        requireUniqueMember(memberId = memberUuid, excludingId = null)

        val id = Uuid.random()
        val now = DbClock.nowLocalDateTime()
        val retentionDue = CrmContactPolicy.retentionReviewDueAt(lastInteractionAt = null, createdAt = now)
        try {
            CrmContactTable.insert {
                it[CrmContactTable.id] = id
                it[displayName] = input.displayName.trim()
                it[email] = normalizedEmail
                it[phone] = input.phone?.trim()?.takeIf { p -> p.isNotBlank() }
                it[street] = input.street?.trim()?.takeIf { s -> s.isNotBlank() }
                it[postalCode] = input.postalCode?.trim()?.takeIf { p -> p.isNotBlank() }
                it[city] = input.city?.trim()?.takeIf { c -> c.isNotBlank() }
                it[country] = input.country?.trim()?.takeIf { c -> c.isNotBlank() }
                it[contactType] = input.contactType
                it[lawfulBasis] = input.lawfulBasis
                it[consentSource] = input.consentSource?.trim()?.takeIf { s -> s.isNotBlank() }
                it[consentGivenAt] = input.consentGivenAt
                it[consentWithdrawnAt] = null
                it[externalDonorId] = externalDonorUuid
                it[memberId] = memberUuid
                it[createdAt] = now
                it[CrmContactTable.createdBy] = createdBy
                it[lastInteractionAt] = null
                it[retentionReviewDueAt] = retentionDue
                it[archivedAt] = null
            }
        } catch (e: ExposedSQLException) {
            // Application-level pre-checks above (email/externalDonorId/memberId) are racy under
            // concurrency on their own -- the DB-level UNIQUE indexes (`uq_crm_contact_email`/
            // `uq_crm_contact_external_donor`/`uq_crm_contact_member`) are the real backstop, same
            // "pre-check + ExposedSQLException backstop" idiom as
            // `AccountingService.createLedgerAccount`.
            throw ConflictException("Ein Kontakt mit dieser E-Mail-Adresse, Mitglieds- oder Spender-Verknüpfung existiert bereits.")
        }
        return getOrThrow(id)
    }

    /**
     * **Consent evidence (`consentSource`/`consentGivenAt`) is preserved, never silently erased by
     * a lawful-basis change.** [CrmContactInput] carries these fields because `lawfulBasis ==
     * CONSENT` requires them (see [CrmContactPolicy.validate]), NOT because switching to a
     * different basis is a request to forget a previously documented consent -- the UI's edit form
     * (`CrmContactsScreen.kt`) hides/blanks these fields once the basis is no longer `CONSENT` and
     * therefore submits `null` for both, which is indistinguishable at this layer from "no consent
     * was ever given". Falling back to the EXISTING row's values whenever the input carries `null`
     * closes two review findings in one fix:
     * 1. Without this, switching a WITHDRAWN contact's basis away from `CONSENT` nulled
     *    `consent_given_at` while `consent_withdrawn_at` stayed set, violating
     *    `chk_crm_contact_withdrawal_requires_consent` (`V17__crm_contacts.sql`) -- the
     *    [ExposedSQLException] backstop below then surfaced as a misleading
     *    [ConflictException] about a duplicate email/member/donor link, and the contact was
     *    permanently stuck on `CONSENT` (the only basis whose `validate()` path re-supplies a
     *    non-null `consentGivenAt`).
     * 2. Even WITHOUT a prior withdrawal, the same nulling silently destroyed the Art. 7(1) DSGVO
     *    proof-of-consent (`consent_source`/`consent_given_at`) the instant an operator corrected
     *    the lawful basis for an unrelated reason.
     *
     * This is authoritative regardless of what the client sends (same "server-side validation is
     * the authority" posture as [CrmContactPolicy.validate]'s own KDoc) -- a caller that legitimately
     * has a NEW consent to record for [CrmLawfulBasis.CONSENT] always supplies a non-null
     * `consentGivenAt` (enforced by `validate()`), so the fallback below never masks a real update,
     * only a `null` that would otherwise erase history.
     *
     * **Withdrawal reversibility (Art. 7(3) Satz 2 DSGVO -- a withdrawal does not bar a later, new
     * consent).** [CrmContactStore] previously had exactly ONE codepath writing `consent_withdrawn_at`
     * ([withdrawConsent]) and NONE that ever cleared it again, so a re-consenting contact stayed
     * permanently locked out of [CrmContactPolicy.mayReceiveEmail] and could end up with a
     * `consent_withdrawn_at` that chronologically PRECEDES the `consent_given_at` it supposedly
     * withdraws, once an operator recorded a fresh consent via this same edit form
     * (`CrmContactsScreen.kt`'s "Bearbeiten" dialog, which always re-submits `consentGivenAt` when
     * `lawfulBasis == CONSENT`). Fix: whenever the caller supplies a `consentGivenAt` that actually
     * DIFFERS from what is on file, that is new evidence of a (re-)consent event, so any existing
     * withdrawal is cleared -- a re-submission of the SAME unchanged `consentGivenAt` (or a `null`
     * that falls back to it, per the KDoc above) leaves a standing withdrawal untouched.
     *
     * **A changed `consentGivenAt` only counts as fresh if it is chronologically AFTER any
     * standing withdrawal.** The check above ("differs from what is on file") is not enough on its
     * own: a mere Art. 16 CORRECTION of the original consent evidence (e.g. a mistyped time-of-day
     * discovered against the paper form) or a backdated entry of an OLDER, previously-unrecorded
     * consent form ALSO produces a `consentGivenAt` that differs from what is on file, without
     * being a new consent EVENT at all -- and if that corrected/backdated value still precedes the
     * withdrawal, Art. 7(3) Satz 2 DSGVO does not consider it a basis to reactivate anything (only
     * a genuinely LATER new consent is). Without this, editing an unrelated field on a WITHDRAWN
     * contact while merely retyping the original `consentGivenAt` (e.g. fixing an AM/PM slip) --
     * or entering an older consent form that surfaces after the withdrawal -- silently cleared
     * `consent_withdrawn_at` and flipped [CrmContactPolicy.mayReceiveEmail] back to `true`, with no
     * record anywhere that the contact had ever withdrawn (this table's own `consent_withdrawn_at`
     * is the ONLY trace of a withdrawal -- [network.lapis.cloud.server.rpc.CrmService] deliberately
     * does not mirror CRM mutations into `audit_log_entry`/`dsgvo_audit_log`, see that class' own
     * KDoc). Requiring `input.consentGivenAt > existing.consentWithdrawnAt` closes both variants at
     * once: a correction or a rückdatiertes older consent can only ever land AT OR BEFORE the
     * withdrawal it is correcting/supplementing, never after.
     *
     * **Explicit consent-evidence erasure ([CrmContactInput.clearConsentEvidence]).** The
     * fallback-to-existing-value behaviour documented above deliberately has no way to DELETE a
     * wrongly-recorded consent (`consentSource`/`consentGivenAt` both `null`/blank in the input
     * always means "keep the existing evidence", never "erase it") -- so an operator who
     * accidentally created a contact with `lawfulBasis = CONSENT` and then corrected the lawful
     * basis away from `CONSENT` was left with a permanently unremovable, incorrect
     * `consentSource`/`consentGivenAt` pair (Art. 5(1)(d)/Art. 16 DSGVO: unrichtige personenbezogene
     * Daten, die über den eigens für Art. 16 eingeführten Bearbeiten-Pfad nicht korrigierbar sind).
     * `clearConsentEvidence = true` is the one explicit, opt-in signal that means "erase", bypassing
     * the fallback-to-existing entirely -- it also clears any `consentWithdrawnAt`, since a
     * withdrawal without a documented consent it withdraws would violate
     * `chk_crm_contact_withdrawal_requires_consent` (`V17__crm_contacts.sql`). Rejected by
     * [CrmContactPolicy.validate] whenever `lawfulBasis == CONSENT` (that basis requires exactly
     * the evidence this flag would erase), so it can never race with the fresh-consent path above.
     */
    fun update(
        id: Uuid,
        input: CrmContactInput,
    ): CrmContactDto {
        // Locks the contact row (`FOR UPDATE`) for the whole read-decide-write sequence below --
        // same `recordInteraction`/`DsgvoService.lockMemberRow` idiom this codebase already
        // establishes for exactly this shape of bug. Without the lock, this function's decisions
        // (the consent-evidence fallback, the fresh-consent/withdrawal-reversal check) are made
        // against a snapshot read that stays valid only until the NEXT write to this row commits --
        // a concurrent `withdrawConsent(id)` committing in that window is silently overwritten by
        // this transaction's own `consentWithdrawnAt` write below (a classic lost update), because
        // pre-fix this function issued only an unlocked `getOrThrow(id)` and this UPDATE never used
        // to touch `consent_withdrawn_at` at all -- the column only entered this function's write
        // set with the withdrawal-reversibility fix above.
        val existing = getForUpdateOrThrow(id)
        val normalizedEmail = CrmContactPolicy.normalizeEmail(input.email)
        val externalDonorUuid = input.externalDonorId?.let { raw -> raw.toCrmUuid() }
        val memberUuid = input.memberId?.let { raw -> raw.toCrmUuid() }
        if (externalDonorUuid != null) requireExistingExternalDonor(externalDonorUuid)
        if (memberUuid != null) requireExistingMember(memberUuid)
        requireUniqueEmail(normalizedEmail = normalizedEmail, excludingId = id)
        requireUniqueExternalDonor(externalDonorId = externalDonorUuid, excludingId = id)
        requireUniqueMember(memberId = memberUuid, excludingId = id)

        val resolvedConsentSource: String?
        val resolvedConsentGivenAt: LocalDateTime?
        val resolvedConsentWithdrawnAt: LocalDateTime?
        if (input.clearConsentEvidence) {
            resolvedConsentSource = null
            resolvedConsentGivenAt = null
            resolvedConsentWithdrawnAt = null
        } else {
            resolvedConsentSource = input.consentSource?.trim()?.takeIf { s -> s.isNotBlank() } ?: existing.consentSource
            resolvedConsentGivenAt = input.consentGivenAt ?: existing.consentGivenAt
            // Local `val`s -- both properties are nullable and declared in a DIFFERENT module
            // (`lapis-shared`: CrmContactInput/CrmContactDto), so Kotlin cannot smart-cast the
            // property accesses themselves across the module boundary even after a null check.
            val inputConsentGivenAt = input.consentGivenAt
            val existingConsentWithdrawnAt = existing.consentWithdrawnAt
            val isFreshConsent =
                inputConsentGivenAt != null &&
                    inputConsentGivenAt != existing.consentGivenAt &&
                    (existingConsentWithdrawnAt == null || inputConsentGivenAt > existingConsentWithdrawnAt)
            resolvedConsentWithdrawnAt = if (isFreshConsent) null else existing.consentWithdrawnAt
        }

        try {
            CrmContactTable.update({ CrmContactTable.id eq id }) {
                it[displayName] = input.displayName.trim()
                it[email] = normalizedEmail
                it[phone] = input.phone?.trim()?.takeIf { p -> p.isNotBlank() }
                it[street] = input.street?.trim()?.takeIf { s -> s.isNotBlank() }
                it[postalCode] = input.postalCode?.trim()?.takeIf { p -> p.isNotBlank() }
                it[city] = input.city?.trim()?.takeIf { c -> c.isNotBlank() }
                it[country] = input.country?.trim()?.takeIf { c -> c.isNotBlank() }
                it[contactType] = input.contactType
                it[lawfulBasis] = input.lawfulBasis
                it[consentSource] = resolvedConsentSource
                it[consentGivenAt] = resolvedConsentGivenAt
                it[consentWithdrawnAt] = resolvedConsentWithdrawnAt
                it[externalDonorId] = externalDonorUuid
                it[memberId] = memberUuid
            }
        } catch (e: ExposedSQLException) {
            // Same "pre-check + ExposedSQLException backstop" idiom as create() above.
            throw ConflictException("Ein Kontakt mit dieser E-Mail-Adresse, Mitglieds- oder Spender-Verknüpfung existiert bereits.")
        }
        return getOrThrow(id)
    }

    /**
     * Art. 7(3) DSGVO: sets `consent_withdrawn_at` to now, see [CrmContactPolicy.mayReceiveEmail]'s
     * "future mailing gate" KDoc and the review finding this closes ("Einwilligungswiderruf ist
     * technisch unmoeglich"). Requires a documented `consent_given_at` (mirrors
     * `chk_crm_contact_withdrawal_requires_consent`, `V17__crm_contacts.sql`) -- cannot withdraw a
     * consent that was never recorded as given. Idempotent-by-overwrite like [setArchived]: calling
     * it again on an already-withdrawn contact simply refreshes the timestamp, no guard against
     * "already withdrawn" is needed.
     *
     * This is the only codepath that WITHDRAWS consent. [update] is the one that can REVERSE a
     * withdrawal (clearing `consent_withdrawn_at`) when the caller records a genuinely new
     * `consentGivenAt` -- see that function's "Withdrawal reversibility" KDoc section; Art. 7(3)
     * Satz 2 DSGVO does not let a withdrawal bar a later new consent.
     */
    fun withdrawConsent(id: Uuid): CrmContactDto {
        val contact = getOrThrow(id)
        if (contact.consentGivenAt == null) {
            throw BadRequestException("Kontakt hat keine dokumentierte Einwilligung, die widerrufen werden könnte.")
        }
        CrmContactTable.update({ CrmContactTable.id eq id }) {
            it[consentWithdrawnAt] = DbClock.nowLocalDateTime()
        }
        return getOrThrow(id)
    }

    private fun requireUniqueEmail(
        normalizedEmail: String?,
        excludingId: Uuid?,
    ) {
        if (normalizedEmail == null) return
        var condition: Op<Boolean> = CrmContactTable.email eq normalizedEmail
        if (excludingId != null) condition = condition and (CrmContactTable.id neq excludingId)
        if (CrmContactTable.selectAll().where { condition }.any()) {
            throw ConflictException("Ein Kontakt mit dieser E-Mail-Adresse existiert bereits.")
        }
    }

    private fun requireUniqueExternalDonor(
        externalDonorId: Uuid?,
        excludingId: Uuid?,
    ) {
        if (externalDonorId == null) return
        var condition: Op<Boolean> = CrmContactTable.externalDonorId eq externalDonorId
        if (excludingId != null) condition = condition and (CrmContactTable.id neq excludingId)
        if (CrmContactTable.selectAll().where { condition }.any()) {
            throw ConflictException("Dieser Spender ist bereits mit einem anderen Kontakt verknüpft.")
        }
    }

    private fun requireUniqueMember(
        memberId: Uuid?,
        excludingId: Uuid?,
    ) {
        if (memberId == null) return
        var condition: Op<Boolean> = CrmContactTable.memberId eq memberId
        if (excludingId != null) condition = condition and (CrmContactTable.id neq excludingId)
        if (CrmContactTable.selectAll().where { condition }.any()) {
            throw ConflictException("Dieses Mitglied ist bereits mit einem anderen Kontakt verknüpft.")
        }
    }

    /**
     * Existing-entity check, same tier as `AccountingService.requireExistingMember` --
     * [NotFoundException], not [ConflictException]/an uncaught FK-violation 500. Closes the review
     * finding "Roher 500 statt Conflict bei doppeltem memberId/externalDonorId" for the
     * well-formed-but-nonexistent-id half of that finding.
     */
    private fun requireExistingMember(memberId: Uuid) {
        val exists = MemberTable.selectAll().where { MemberTable.id eq memberId }.count() > 0
        if (!exists) throw NotFoundException("Member $memberId not found")
    }

    /** Existing-entity check for [ExternalDonorTable] -- see [requireExistingMember] KDoc. */
    private fun requireExistingExternalDonor(externalDonorId: Uuid) {
        val exists = ExternalDonorTable.selectAll().where { ExternalDonorTable.id eq externalDonorId }.count() > 0
        if (!exists) throw NotFoundException("ExternalDonor $externalDonorId not found")
    }

    fun setArchived(
        id: Uuid,
        archived: Boolean,
    ): CrmContactDto {
        getOrThrow(id)
        CrmContactTable.update({ CrmContactTable.id eq id }) {
            it[archivedAt] = if (archived) DbClock.nowLocalDateTime() else null
        }
        return getOrThrow(id)
    }

    fun listInteractions(
        contactId: Uuid,
        limit: Int,
        offset: Int,
    ): List<CrmInteractionDto> {
        val effectiveLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val effectiveOffset = offset.coerceAtLeast(0)
        return interactionJoin()
            .selectAll()
            .where { CrmInteractionTable.contactId eq contactId }
            // `id` as the tie-breaker -- same reasoning as [list]'s own `id`-ordered tie-breaker
            // KDoc: without it, two `occurredAt`-equal rows (e.g. a bulk backfill of Infostand notes
            // all stamped with the same minute-precision timestamp) have no stable order between two
            // separate offset-paged queries, so "Mehr laden" can duplicate or skip rows across pages.
            .orderBy(CrmInteractionTable.occurredAt to SortOrder.DESC, CrmInteractionTable.id to SortOrder.ASC)
            .limit(effectiveLimit)
            .offset(effectiveOffset.toLong())
            .map { it.toInteractionDto() }
    }

    /**
     * **Concurrency**: locks the contact row (`FOR UPDATE`) BEFORE inserting the interaction and
     * updating `last_interaction_at`/`retention_review_due_at` -- same
     * `DsgvoService.lockMemberRow`/`LtrBalanceProvider.lockForDebit` idiom. Without the lock, two
     * concurrent `recordInteraction` calls for the same contact could interleave such that the
     * later-committing transaction's denormalized `last_interaction_at` is overwritten by the
     * earlier one's, silently losing the newer timestamp.
     *
     * **Monotonic `last_interaction_at`**: `CrmContactsScreen.kt`'s capture form deliberately
     * allows an operator to backdate `occurredAt` (nachtragen a past interaction). Because
     * [listInteractions] already sorts by `occurredAt DESC`, the newest entry in the timeline is
     * NOT necessarily the one just inserted -- so `last_interaction_at`/`retention_review_due_at`
     * must become `maxOf(the row's current last_interaction_at, this occurredAt)`, never this
     * `occurredAt` unconditionally. Fixes the regression where a backdated nachgetragene
     * interaction silently REGRESSED `last_interaction_at` (and pulled the Art. 5(1)(e) DSGVO
     * retention review forward by however far the backdate reached).
     */
    fun recordInteraction(
        input: CrmInteractionInput,
        recordedBy: Uuid,
    ): CrmInteractionDto {
        val contactId = input.contactId.toCrmUuid()
        val lockedContact =
            CrmContactTable
                .selectAll()
                .where { CrmContactTable.id eq contactId }
                .forUpdate()
                .singleOrNull()
                ?: throw NotFoundException("CRM contact ${input.contactId} not found")

        val now = DbClock.nowLocalDateTime()
        val occurredAt = input.occurredAt ?: now
        val existingLastInteractionAt = lockedContact[CrmContactTable.lastInteractionAt]
        val newLastInteractionAt =
            if (existingLastInteractionAt == null || occurredAt > existingLastInteractionAt) occurredAt else existingLastInteractionAt
        val id = Uuid.random()
        CrmInteractionTable.insert {
            it[CrmInteractionTable.id] = id
            it[CrmInteractionTable.contactId] = contactId
            it[CrmInteractionTable.occurredAt] = occurredAt
            it[kind] = input.kind
            it[summary] = input.summary.trim()
            it[CrmInteractionTable.recordedBy] = recordedBy
            it[recordedAt] = now
        }
        CrmContactTable.update({ CrmContactTable.id eq contactId }) {
            it[lastInteractionAt] = newLastInteractionAt
            it[retentionReviewDueAt] =
                CrmContactPolicy.retentionReviewDueAt(lastInteractionAt = newLastInteractionAt, createdAt = newLastInteractionAt)
        }
        return interactionJoin()
            .selectAll()
            .where { CrmInteractionTable.id eq id }
            .single()
            .toInteractionDto()
    }

    private fun interactionJoin() = CrmInteractionTable.join(MemberTable, JoinType.INNER, CrmInteractionTable.recordedBy, MemberTable.id)

    private fun ResultRow.toDto(): CrmContactDto =
        CrmContactDto(
            id = this[CrmContactTable.id].toString(),
            displayName = this[CrmContactTable.displayName],
            email = this[CrmContactTable.email],
            phone = this[CrmContactTable.phone],
            street = this[CrmContactTable.street],
            postalCode = this[CrmContactTable.postalCode],
            city = this[CrmContactTable.city],
            country = this[CrmContactTable.country],
            contactType = this[CrmContactTable.contactType],
            lawfulBasis = this[CrmContactTable.lawfulBasis],
            consentSource = this[CrmContactTable.consentSource],
            consentGivenAt = this[CrmContactTable.consentGivenAt],
            consentWithdrawnAt = this[CrmContactTable.consentWithdrawnAt],
            externalDonorId = this[CrmContactTable.externalDonorId]?.toString(),
            memberId = this[CrmContactTable.memberId]?.toString(),
            createdAt = this[CrmContactTable.createdAt],
            createdBy = this[CrmContactTable.createdBy].toString(),
            lastInteractionAt = this[CrmContactTable.lastInteractionAt],
            retentionReviewDueAt = this[CrmContactTable.retentionReviewDueAt],
            archivedAt = this[CrmContactTable.archivedAt],
            mayReceiveEmail =
                CrmContactPolicy.mayReceiveEmail(
                    lawfulBasis = this[CrmContactTable.lawfulBasis],
                    consentGivenAt = this[CrmContactTable.consentGivenAt],
                    consentWithdrawnAt = this[CrmContactTable.consentWithdrawnAt],
                    email = this[CrmContactTable.email],
                ),
        )

    private fun ResultRow.toInteractionDto(): CrmInteractionDto =
        CrmInteractionDto(
            id = this[CrmInteractionTable.id].toString(),
            contactId = this[CrmInteractionTable.contactId].toString(),
            occurredAt = this[CrmInteractionTable.occurredAt],
            kind = this[CrmInteractionTable.kind],
            summary = this[CrmInteractionTable.summary],
            recordedBy = this[CrmInteractionTable.recordedBy].toString(),
            recordedByDisplayName = this[MemberTable.displayName],
            recordedAt = this[CrmInteractionTable.recordedAt],
        )
}

internal fun String.toCrmUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

/** ANDs [this] onto [existing] (or returns [this] alone if [existing] is `null`) -- the accumulator step [list]'s condition-building uses. */
private fun Op<Boolean>.andWith(existing: Op<Boolean>?): Op<Boolean> = existing?.and(this) ?: this
