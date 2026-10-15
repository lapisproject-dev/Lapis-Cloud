package network.lapis.cloud.server.agreementtexts

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.AgreementTextDraftTable
import network.lapis.cloud.server.db.generated.AgreementTextVersionTable
import network.lapis.cloud.server.db.generated.FriendTermsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextVersionSummaryDto
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** One published version, as the rest of the code sees it (the Exposed table object never leaves [AgreementTextStore]). */
internal data class AgreementTextVersionRecord(
    val id: Uuid,
    val kind: AgreementTextKind,
    val sequenceNo: Int,
    val versionLabel: String,
    val validFrom: LocalDate,
    val body: String,
    val sha256: String,
    val charCount: Int,
    val authorMemberId: Uuid,
    val publishedAt: LocalDateTime,
)

/** The working copy of one kind. */
internal data class AgreementTextDraftRecord(
    val kind: AgreementTextKind,
    val id: Uuid,
    val versionLabel: String,
    val validFrom: LocalDate,
    val body: String,
    val sha256: String,
    val charCount: Int,
    val updatedBy: Uuid,
    val updatedAt: LocalDateTime,
)

/**
 * Welle V1.9.134 -- THE ONLY READER AND WRITER of `agreement_text_version` and `agreement_text_draft` (tripwire
 * `AgreementTextStoreTripwireTest`; backup and DSGVO read the tables from their own, listed places). All functions run inside the caller's
 * `transaction {}`.
 *
 * `agreement_text_version` is APPEND-ONLY: this object only ever INSERTs into it ([insertVersion]) -- there is no update, no delete, no
 * upsert for a published version. The one allowed DELETE of the whole feature is [deleteDraft], on the working-copy table.
 *
 * The audit snapshot ([snapshotJson]) carries the version label, hash, character count and validity date -- NEVER the text itself.
 */
internal object AgreementTextStore {
    /** The published version with the highest `sequence_no` of [kind], or `null` while only the built-in placeholder exists. */
    fun currentVersion(kind: AgreementTextKind): AgreementTextVersionRecord? =
        AgreementTextVersionTable
            .selectAll()
            .where { AgreementTextVersionTable.kind eq kind.name }
            .orderBy(AgreementTextVersionTable.sequenceNo to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let { versionRecord(it) }

    fun listVersions(kind: AgreementTextKind): List<AgreementTextVersionRecord> =
        AgreementTextVersionTable
            .selectAll()
            .where { AgreementTextVersionTable.kind eq kind.name }
            .orderBy(AgreementTextVersionTable.sequenceNo to SortOrder.DESC)
            .map { versionRecord(it) }

    fun findVersion(
        kind: AgreementTextKind,
        versionLabel: String,
    ): AgreementTextVersionRecord? =
        AgreementTextVersionTable
            .selectAll()
            .where { (AgreementTextVersionTable.kind eq kind.name) and (AgreementTextVersionTable.versionLabel eq versionLabel) }
            .singleOrNull()
            ?.let { versionRecord(it) }

    fun versionLabelExists(
        kind: AgreementTextKind,
        versionLabel: String,
    ): Boolean = findVersion(kind = kind, versionLabel = versionLabel) != null

    fun findDraft(kind: AgreementTextKind): AgreementTextDraftRecord? =
        AgreementTextDraftTable
            .selectAll()
            .where { AgreementTextDraftTable.kind eq kind.name }
            .singleOrNull()
            ?.let { draftRecord(it) }

    /** The draft row locked (`FOR UPDATE`) for the rest of the transaction -- serializes two admins publishing or saving the same kind. */
    fun lockDraft(kind: AgreementTextKind): AgreementTextDraftRecord? =
        AgreementTextDraftTable
            .selectAll()
            .where { AgreementTextDraftTable.kind eq kind.name }
            .forUpdate()
            .singleOrNull()
            ?.let { draftRecord(it) }

    /** Creates or overwrites the draft of [kind]. [newId] is only used when no draft exists yet; an existing draft keeps its stable id. */
    fun upsertDraft(
        kind: AgreementTextKind,
        newId: Uuid,
        versionLabel: String,
        validFrom: LocalDate,
        body: String,
        sha256: String,
        updatedBy: Uuid,
        now: LocalDateTime,
    ) {
        val updated =
            AgreementTextDraftTable.update({ AgreementTextDraftTable.kind eq kind.name }) {
                it[AgreementTextDraftTable.versionLabel] = versionLabel
                it[AgreementTextDraftTable.validFrom] = validFrom
                it[AgreementTextDraftTable.body] = body
                it[AgreementTextDraftTable.sha256] = sha256
                it[AgreementTextDraftTable.charCount] = body.length
                it[AgreementTextDraftTable.updatedBy] = updatedBy
                it[AgreementTextDraftTable.updatedAt] = now
            }
        if (updated == 0) {
            AgreementTextDraftTable.insert {
                it[AgreementTextDraftTable.kind] = kind.name
                it[AgreementTextDraftTable.id] = newId
                it[AgreementTextDraftTable.versionLabel] = versionLabel
                it[AgreementTextDraftTable.validFrom] = validFrom
                it[AgreementTextDraftTable.body] = body
                it[AgreementTextDraftTable.sha256] = sha256
                it[AgreementTextDraftTable.charCount] = body.length
                it[AgreementTextDraftTable.updatedBy] = updatedBy
                it[AgreementTextDraftTable.updatedAt] = now
            }
        }
    }

    /** The ONLY write to `agreement_text_version`: appends a version with `sequence_no = max + 1` of its kind (caller holds the draft lock). */
    fun insertVersion(
        id: Uuid,
        kind: AgreementTextKind,
        versionLabel: String,
        validFrom: LocalDate,
        body: String,
        sha256: String,
        authorMemberId: Uuid,
        now: LocalDateTime,
    ): AgreementTextVersionRecord {
        val maxSeq = AgreementTextVersionTable.sequenceNo.max()
        val next =
            (
                AgreementTextVersionTable
                    .select(maxSeq)
                    .where { AgreementTextVersionTable.kind eq kind.name }
                    .singleOrNull()
                    ?.get(maxSeq) ?: 0
            ) + 1
        AgreementTextVersionTable.insert {
            it[AgreementTextVersionTable.id] = id
            it[AgreementTextVersionTable.kind] = kind.name
            it[AgreementTextVersionTable.sequenceNo] = next
            it[AgreementTextVersionTable.versionLabel] = versionLabel
            it[AgreementTextVersionTable.validFrom] = validFrom
            it[AgreementTextVersionTable.body] = body
            it[AgreementTextVersionTable.sha256] = sha256
            it[AgreementTextVersionTable.charCount] = body.length
            it[AgreementTextVersionTable.authorMemberId] = authorMemberId
            it[AgreementTextVersionTable.publishedAt] = now
        }
        return versionRecord(AgreementTextVersionTable.selectAll().where { AgreementTextVersionTable.id eq id }.single())
    }

    /** The only DELETE of the feature: removes the working copy of [kind] after its publication. */
    fun deleteDraft(kind: AgreementTextKind) {
        AgreementTextDraftTable.deleteWhere { AgreementTextDraftTable.kind eq kind.name }
    }

    fun authorName(memberId: Uuid): String =
        MemberTable
            .select(MemberTable.displayName)
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.get(MemberTable.displayName) ?: ""

    fun toSummary(row: AgreementTextVersionRecord): AgreementTextVersionSummaryDto =
        AgreementTextVersionSummaryDto(
            kind = row.kind,
            versionLabel = row.versionLabel,
            validFrom = row.validFrom,
            sha256 = row.sha256,
            charCount = row.charCount,
            authorDisplayName = authorName(row.authorMemberId),
            publishedAt = row.publishedAt,
            sequenceNo = row.sequenceNo,
        )

    fun toDraftDto(row: AgreementTextDraftRecord): AgreementTextDraftDto =
        AgreementTextDraftDto(
            kind = row.kind,
            versionLabel = row.versionLabel,
            validFrom = row.validFrom,
            body = row.body,
            sha256 = row.sha256,
            charCount = row.charCount,
            updatedByDisplayName = authorName(row.updatedBy),
            updatedAt = row.updatedAt,
        )

    private fun versionRecord(row: ResultRow) =
        AgreementTextVersionRecord(
            id = row[AgreementTextVersionTable.id],
            kind = AgreementTextKind.valueOf(row[AgreementTextVersionTable.kind]),
            sequenceNo = row[AgreementTextVersionTable.sequenceNo],
            versionLabel = row[AgreementTextVersionTable.versionLabel],
            validFrom = row[AgreementTextVersionTable.validFrom],
            body = row[AgreementTextVersionTable.body],
            sha256 = row[AgreementTextVersionTable.sha256],
            charCount = row[AgreementTextVersionTable.charCount],
            authorMemberId = row[AgreementTextVersionTable.authorMemberId],
            publishedAt = row[AgreementTextVersionTable.publishedAt],
        )

    private fun draftRecord(row: ResultRow) =
        AgreementTextDraftRecord(
            kind = AgreementTextKind.valueOf(row[AgreementTextDraftTable.kind]),
            id = row[AgreementTextDraftTable.id],
            versionLabel = row[AgreementTextDraftTable.versionLabel],
            validFrom = row[AgreementTextDraftTable.validFrom],
            body = row[AgreementTextDraftTable.body],
            sha256 = row[AgreementTextDraftTable.sha256],
            charCount = row[AgreementTextDraftTable.charCount],
            updatedBy = row[AgreementTextDraftTable.updatedBy],
            updatedAt = row[AgreementTextDraftTable.updatedAt],
        )

    /**
     * How many people whose registration text of this kind is "in force" have NOT acknowledged [currentLabel]: MEMBERSHIP counts `ACTIVE`
     * and `APPLICATION` members without an acknowledgment of that label, FRIEND counts `FRIEND` members without one. One `COUNT`.
     */
    fun countOlderAcknowledgments(
        kind: AgreementTextKind,
        currentLabel: String,
    ): Int =
        when (kind) {
            AgreementTextKind.MEMBERSHIP ->
                MemberTable
                    .selectAll()
                    .where {
                        (MemberTable.status inList listOf(MemberStatus.ACTIVE, MemberStatus.APPLICATION)) and
                            notExists(
                                MembershipAgreementAcknowledgmentTable.selectAll().where {
                                    (MembershipAgreementAcknowledgmentTable.memberId eq MemberTable.id) and
                                        (MembershipAgreementAcknowledgmentTable.agreementVersion eq currentLabel)
                                },
                            )
                    }.count()
            AgreementTextKind.FRIEND ->
                MemberTable
                    .selectAll()
                    .where {
                        (MemberTable.status eq MemberStatus.FRIEND) and
                            notExists(
                                FriendTermsAcknowledgmentTable.selectAll().where {
                                    (FriendTermsAcknowledgmentTable.memberId eq MemberTable.id) and
                                        (FriendTermsAcknowledgmentTable.termsVersion eq currentLabel)
                                },
                            )
                    }.count()
        }.toInt()

    /** `{"event","kind","versionLabel","sha256","charCount","validFrom"[,"sequenceNo"]}` -- nothing else may ever enter the audit chain. */
    fun snapshotJson(
        event: String,
        kind: AgreementTextKind,
        versionLabel: String,
        sha256: String,
        charCount: Int,
        validFrom: LocalDate,
        sequenceNo: Int? = null,
    ): String =
        Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                buildMap {
                    put("event", JsonPrimitive(event))
                    put("kind", JsonPrimitive(kind.name))
                    put("versionLabel", JsonPrimitive(versionLabel))
                    put("sha256", JsonPrimitive(sha256))
                    put("charCount", JsonPrimitive(charCount))
                    put("validFrom", JsonPrimitive(validFrom.toString()))
                    if (sequenceNo != null) put("sequenceNo", JsonPrimitive(sequenceNo))
                },
            ),
        )

    fun recordAudit(
        actorMemberId: Uuid,
        actorRole: AccountRole,
        entityId: Uuid,
        action: AuditAction,
        before: String?,
        after: String?,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.AGREEMENT_TEXT,
            entityId = entityId,
            action = action,
            before = before,
            after = after,
            occurredAt = now,
        )
    }
}
