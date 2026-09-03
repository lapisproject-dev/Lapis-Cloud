package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.CrmContactTable
import network.lapis.cloud.server.db.generated.CrmInteractionTable
import network.lapis.cloud.shared.domain.DsgvoSubjectKind
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [CrmContactTable]/[CrmInteractionTable] (Welle V1.4.2 "Interessenten-/Sympathisanten-CRM").
 * The **only** place either table is ever mutated for erasure purposes -- see
 * `CrmRegressionScanTest`'s structural guard against a second delete path.
 *
 * Unlike every other [PersonalDataContributor] in this codebase, this one implements the raw
 * interface directly (not [MemberPersonalDataContributor]) -- it is the one contributor that
 * genuinely handles BOTH [DsgvoSubjectKind.CRM_CONTACT] and [DsgvoSubjectKind.MEMBER] subjects,
 * with two structurally different [export]/[erase] behaviours (see each branch below).
 *
 * **CRM_CONTACT subject (the contact themselves is the DSGVO subject)**: a real DELETE, not
 * anonymize-with-retained-row, regardless of [mode] -- a non-member CRM contact carries no
 * retention-obliged financial/legal record the way a member's contributions/postings do (contrast
 * `ContributionPersonalData`), so Art. 17 is taken literally here. [mode] is deliberately ignored on
 * this branch, documented rather than silently no-op'd.
 *
 * **MEMBER subject (a member is being erased, and happens to also be a CRM contact and/or have
 * authored CRM data)**: three sub-cases --
 * 1. `crm_contact.member_id = subject` -- the SAME person as a CRM contact too: deleted exactly
 *    like the CRM_CONTACT branch (contact row + its interactions).
 * 2. `crm_contact.created_by = subject` (for a contact NOT linked to this member) -- retained with
 *    reason: the row describes a DIFFERENT, third person; only who-created-it is this member's
 *    trace, same "organisatorische Nachvollziehbarkeit" posture [ApiKeyPersonalData]/
 *    [WebhookPersonalData] already establish.
 * 3. `crm_interaction.recorded_by = subject` (for an interaction on a contact NOT case-1-deleted)
 *    -- retained with reason, same posture as case 2.
 *
 * FK integrity for cases 2/3 holds because `member` itself always survives erasure as an FK anchor
 * (`ANONIMIZE`/`HARD_DELETE_WHERE_UNCONSTRAINED` both keep the row, see [ErasureMode] KDoc) -- see
 * `FoundationPersonalData` KDoc for that invariant.
 */
object CrmPersonalData : PersonalDataContributor {
    override val sectionKey = "crm"
    override val displayName = "CRM-Kontakte"
    override val coveredTables = setOf(CrmContactTable, CrmInteractionTable)
    override val handledSubjects = setOf(DsgvoSubjectKind.MEMBER, DsgvoSubjectKind.CRM_CONTACT)

    /** Export bundle cap -- mirrors [PublicRankingConsentPersonalData]'s own `MAX_EXPORTED_EVENTS` posture. */
    internal const val MAX_EXPORTED_INTERACTIONS = 2_000

    override fun export(subject: DataSubject): JsonElement =
        when (subject) {
            is DataSubject.CrmContact -> exportContact(subject.id)
            is DataSubject.Member -> exportForMember(subject.id)
        }

    override fun erase(
        subject: DataSubject,
        mode: ErasureMode,
    ): List<TableErasureOutcome> =
        when (subject) {
            is DataSubject.CrmContact -> eraseContact(subject.id)
            is DataSubject.Member -> eraseForMember(subject.id)
        }

    /** Full contact row + up to [MAX_EXPORTED_INTERACTIONS] interactions, `summary` included. */
    private fun exportContact(contactId: Uuid): JsonElement {
        val contactRow = CrmContactTable.selectAll().where { CrmContactTable.id eq contactId }.singleOrNull()
        return buildJsonObject {
            if (contactRow == null) return@buildJsonObject
            put("id", contactRow[CrmContactTable.id].toString())
            put("displayName", contactRow[CrmContactTable.displayName])
            put("email", contactRow[CrmContactTable.email])
            put("phone", contactRow[CrmContactTable.phone])
            put("street", contactRow[CrmContactTable.street])
            put("postalCode", contactRow[CrmContactTable.postalCode])
            put("city", contactRow[CrmContactTable.city])
            put("country", contactRow[CrmContactTable.country])
            put("contactType", contactRow[CrmContactTable.contactType].name)
            put("lawfulBasis", contactRow[CrmContactTable.lawfulBasis].name)
            put("consentSource", contactRow[CrmContactTable.consentSource])
            put("consentGivenAt", contactRow[CrmContactTable.consentGivenAt]?.toString())
            put("consentWithdrawnAt", contactRow[CrmContactTable.consentWithdrawnAt]?.toString())
            put("createdAt", contactRow[CrmContactTable.createdAt].toString())
            put(
                "interactions",
                buildJsonArray {
                    CrmInteractionTable
                        .selectAll()
                        .where { CrmInteractionTable.contactId eq contactId }
                        .limit(MAX_EXPORTED_INTERACTIONS)
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[CrmInteractionTable.id].toString())
                                    put("occurredAt", row[CrmInteractionTable.occurredAt].toString())
                                    put("kind", row[CrmInteractionTable.kind].name)
                                    put("summary", row[CrmInteractionTable.summary])
                                },
                            )
                        }
                },
            )
        }
    }

    /**
     * MEMBER-subject export -- their OWN contact row (if `member_id`-linked, case 1) plus the
     * interactions THEY recorded (case 3), deliberately WITHOUT `summary` -- see class KDoc, the
     * free text describes a third person, not an Art. 15 disclosure owed to the recorder.
     */
    private fun exportForMember(memberId: Uuid): JsonElement =
        buildJsonObject {
            val ownContact = CrmContactTable.selectAll().where { CrmContactTable.memberId eq memberId }.singleOrNull()
            put(
                "ownContact",
                ownContact?.let { row -> buildJsonObject { put("id", row[CrmContactTable.id].toString()) } } ?: JsonNull,
            )
            put(
                "recordedInteractions",
                buildJsonArray {
                    CrmInteractionTable
                        .selectAll()
                        .where { CrmInteractionTable.recordedBy eq memberId }
                        .limit(MAX_EXPORTED_INTERACTIONS)
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[CrmInteractionTable.id].toString())
                                    put("contactId", row[CrmInteractionTable.contactId].toString())
                                    put("occurredAt", row[CrmInteractionTable.occurredAt].toString())
                                    put("kind", row[CrmInteractionTable.kind].name)
                                },
                            )
                        }
                },
            )
        }

    /** Real DELETE (contact + interactions), regardless of [ErasureMode] -- see class KDoc "CRM_CONTACT subject". */
    private fun eraseContact(contactId: Uuid): List<TableErasureOutcome> {
        val deletedInteractions = CrmInteractionTable.deleteWhere { CrmInteractionTable.contactId eq contactId }
        val deletedContacts = CrmContactTable.deleteWhere { CrmContactTable.id eq contactId }
        return listOf(
            TableErasureOutcome(table = "crm_interaction", rowsDeleted = deletedInteractions),
            TableErasureOutcome(table = "crm_contact", rowsDeleted = deletedContacts),
        )
    }

    /** See class KDoc "MEMBER subject" for the three sub-cases this implements. */
    private fun eraseForMember(memberId: Uuid): List<TableErasureOutcome> {
        val ownContactId =
            CrmContactTable.selectAll().where { CrmContactTable.memberId eq memberId }.singleOrNull()?.get(
                CrmContactTable.id,
            )

        val deletedInteractions = ownContactId?.let { id -> CrmInteractionTable.deleteWhere { CrmInteractionTable.contactId eq id } } ?: 0
        val deletedContacts = ownContactId?.let { id -> CrmContactTable.deleteWhere { CrmContactTable.id eq id } } ?: 0

        val retainedCreatedContacts =
            if (ownContactId != null) {
                CrmContactTable
                    .selectAll()
                    .where { (CrmContactTable.createdBy eq memberId) and (CrmContactTable.id neq ownContactId) }
                    .count()
                    .toInt()
            } else {
                CrmContactTable
                    .selectAll()
                    .where { CrmContactTable.createdBy eq memberId }
                    .count()
                    .toInt()
            }

        val retainedInteractions =
            if (ownContactId != null) {
                CrmInteractionTable
                    .selectAll()
                    .where { (CrmInteractionTable.recordedBy eq memberId) and (CrmInteractionTable.contactId neq ownContactId) }
                    .count()
                    .toInt()
            } else {
                CrmInteractionTable
                    .selectAll()
                    .where { CrmInteractionTable.recordedBy eq memberId }
                    .count()
                    .toInt()
            }

        val outcomes = mutableListOf<TableErasureOutcome>()
        if (deletedInteractions > 0 || retainedInteractions > 0) {
            outcomes +=
                TableErasureOutcome(
                    table = "crm_interaction",
                    rowsDeleted = deletedInteractions,
                    rowsRetained = retainedInteractions,
                    retentionReason =
                        if (retainedInteractions > 0) {
                            "Organisatorische Nachvollziehbarkeit, wer eine Interaktion mit einem " +
                                "anderen CRM-Kontakt erfasst hat -- die Zeile beschreibt eine dritte " +
                                "Person, nicht das geloeschte Mitglied."
                        } else {
                            null
                        },
                )
        }
        if (deletedContacts > 0 || retainedCreatedContacts > 0) {
            outcomes +=
                TableErasureOutcome(
                    table = "crm_contact",
                    rowsDeleted = deletedContacts,
                    rowsRetained = retainedCreatedContacts,
                    retentionReason =
                        if (retainedCreatedContacts > 0) {
                            "Organisatorische Nachvollziehbarkeit, wer einen CRM-Kontakt angelegt hat " +
                                "-- die Zeile beschreibt eine dritte Person, nicht das geloeschte " +
                                "Mitglied."
                        } else {
                            null
                        },
                )
        }
        return outcomes
    }
}
