package network.lapis.cloud.server.member

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.FoundingMemberAuditEvent
import network.lapis.cloud.shared.domain.FoundingMemberAuditFacts
import network.lapis.cloud.shared.domain.FoundingMemberRules
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.305 "Gruendungsmitglied" -- the **only** writer of the four founding-member columns of `member` (marker, since date, consent timestamp, consent version) (`FoundingMemberWriterTripwireTest`). The only exception, by design:
 * the DSGVO erasure (`FoundationPersonalData`), which resets all four in the same `update` that clears the personal columns.
 *
 * The `*Locked` functions require that the caller holds the member row lock (`forMemberUpdate()`) in THIS transaction on the row it passes in.
 * They return the audit facts to record (or an empty result for a no-op). Audit entries carry fixed keys and, for the "since" date, the date
 * only -- never a name, never the consent text.
 *
 * Public effect: the label is shown publicly only while [publicEffectiveCondition] holds (flag, consent for the CURRENT wording, ACTIVE,
 * not anonymised). Every anonymous reader uses that condition (or its Kotlin twin [isPublicEffective]) live on every request.
 */
internal object FoundingMemberStore {
    /**
     * Sets or clears the marker. Clearing resets since and consent in the same UPDATE; an existing consent then additionally yields
     * [FoundingMemberAuditEvent.PUBLICATION_REVOKED]. Setting an already-set marker only changes the date (`SINCE_CHANGED`). Returns the events
     * to audit in order; empty for a no-op.
     */
    fun setLocked(
        targetRow: ResultRow,
        value: Boolean,
        since: LocalDate?,
    ): List<FoundingMemberAuditFacts> {
        val memberId = targetRow[MemberTable.id]
        val wasSet = targetRow[MemberTable.foundingMember]
        val oldSince = targetRow[MemberTable.foundingMemberSince]
        if (!value) {
            if (!wasSet) return emptyList()
            val hadConsent = targetRow[MemberTable.foundingMemberConsentGrantedAt] != null
            MemberTable.update({ MemberTable.id eq memberId }) {
                it[foundingMember] = false
                it[foundingMemberSince] = null
                it[foundingMemberConsentGrantedAt] = null
                it[foundingMemberConsentVersion] = null
            }
            val events = mutableListOf(FoundingMemberAuditFacts(event = FoundingMemberAuditEvent.CLEARED, sinceBefore = oldSince))
            if (hadConsent) {
                events +=
                    FoundingMemberAuditFacts(
                        event = FoundingMemberAuditEvent.PUBLICATION_REVOKED,
                        consentVersion = targetRow[MemberTable.foundingMemberConsentVersion],
                    )
            }
            return events
        }
        if (wasSet) {
            if (oldSince == since) return emptyList()
            MemberTable.update({ MemberTable.id eq memberId }) { it[foundingMemberSince] = since }
            return listOf(
                FoundingMemberAuditFacts(event = FoundingMemberAuditEvent.SINCE_CHANGED, sinceBefore = oldSince, sinceAfter = since),
            )
        }
        MemberTable.update({ MemberTable.id eq memberId }) {
            it[foundingMember] = true
            it[foundingMemberSince] = since
        }
        return listOf(FoundingMemberAuditFacts(event = FoundingMemberAuditEvent.SET, sinceAfter = since))
    }

    /** The member consents to the public label (current wording). `null` if it already is effective-consented with this version. */
    fun publishLocked(
        targetRow: ResultRow,
        now: LocalDateTime,
    ): FoundingMemberAuditFacts? {
        if (targetRow[MemberTable.foundingMemberConsentGrantedAt] != null &&
            targetRow[MemberTable.foundingMemberConsentVersion] == FoundingMemberRules.CONSENT_TEXT_VERSION
        ) {
            return null
        }
        MemberTable.update({ MemberTable.id eq targetRow[MemberTable.id] }) {
            it[foundingMemberConsentGrantedAt] = now
            it[foundingMemberConsentVersion] = FoundingMemberRules.CONSENT_TEXT_VERSION
        }
        return FoundingMemberAuditFacts(
            event = FoundingMemberAuditEvent.PUBLISHED,
            consentVersion = FoundingMemberRules.CONSENT_TEXT_VERSION,
        )
    }

    /** The member withdraws the consent. `null` if there was none. */
    fun unpublishLocked(targetRow: ResultRow): FoundingMemberAuditFacts? {
        if (targetRow[MemberTable.foundingMemberConsentGrantedAt] == null) return null
        val version = targetRow[MemberTable.foundingMemberConsentVersion]
        MemberTable.update({ MemberTable.id eq targetRow[MemberTable.id] }) {
            it[foundingMemberConsentGrantedAt] = null
            it[foundingMemberConsentVersion] = null
        }
        return FoundingMemberAuditFacts(event = FoundingMemberAuditEvent.UNPUBLISHED, consentVersion = version)
    }

    /**
     * Second defense layer behind the live condition: when a member's status moves OUT of
     * [MemberStatusSets.FOUNDING_MEMBER_PUBLICATION_ELIGIBLE], the consent is deleted so it does not silently come back after a later
     * reactivation. No-op when the new status is eligible or there is no consent. Call in the SAME transaction as the status write.
     */
    fun revokePublicationOnStatusLoss(
        memberId: Uuid,
        newStatus: MemberStatus,
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
        now: LocalDateTime,
    ) {
        if (newStatus in MemberStatusSets.FOUNDING_MEMBER_PUBLICATION_ELIGIBLE) return
        val row = MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull() ?: return
        if (row[MemberTable.foundingMemberConsentGrantedAt] == null) return
        val version = row[MemberTable.foundingMemberConsentVersion]
        MemberTable.update({ MemberTable.id eq memberId }) {
            it[foundingMemberConsentGrantedAt] = null
            it[foundingMemberConsentVersion] = null
        }
        recordAudit(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            targetMemberId = memberId,
            targetStatus = newStatus,
            facts =
                FoundingMemberAuditFacts(
                    event = FoundingMemberAuditEvent.PUBLICATION_REVOKED_BY_STATUS_CHANGE,
                    consentVersion = version,
                ),
            now = now,
        )
        logger.info { "Founding-member publication revoked on status loss: memberId=$memberId" }
    }

    /** One hash-chained audit entry, `MEMBER`/`UPDATE`. Call it LAST in the transaction. */
    fun recordAudit(
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
        targetMemberId: Uuid,
        targetStatus: MemberStatus,
        facts: FoundingMemberAuditFacts,
        now: LocalDateTime,
    ) {
        val before = MemberChangeSnapshot(displayNameChanged = false, emailChanged = false, status = targetStatus, role = null)
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.MEMBER,
            entityId = targetMemberId,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(MemberChangeSnapshot.serializer(), before),
            after = Json.encodeToString(MemberChangeSnapshot.serializer(), before.copy(foundingMember = facts)),
            occurredAt = now,
        )
    }

    /**
     * True only while the label may be shown publicly: marker set, consent given for the CURRENT wording, status ACTIVE, not anonymised.
     * Requires `MemberTable` in the query's column set.
     */
    fun publicEffectiveCondition(): Op<Boolean> =
        (MemberTable.foundingMember eq true) and
            MemberTable.foundingMemberConsentGrantedAt.isNotNull() and
            (MemberTable.foundingMemberConsentVersion eq FoundingMemberRules.CONSENT_TEXT_VERSION) and
            (MemberTable.status inList MemberStatusSets.FOUNDING_MEMBER_PUBLICATION_ELIGIBLE) and
            MemberTable.anonymizedAt.isNull()

    /** Kotlin twin of [publicEffectiveCondition] for a row that already carries the member columns (`FoundingMemberEffectiveConditionTest`). */
    fun isPublicEffective(row: ResultRow): Boolean =
        row[MemberTable.foundingMember] &&
            row[MemberTable.foundingMemberConsentGrantedAt] != null &&
            row[MemberTable.foundingMemberConsentVersion] == FoundingMemberRules.CONSENT_TEXT_VERSION &&
            row[MemberTable.status] in MemberStatusSets.FOUNDING_MEMBER_PUBLICATION_ELIGIBLE &&
            row[MemberTable.anonymizedAt] == null
}
