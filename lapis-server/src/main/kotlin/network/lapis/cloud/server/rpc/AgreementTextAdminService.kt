package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.agreementtexts.AgreementMarkdown
import network.lapis.cloud.server.agreementtexts.AgreementTextDraftRecord
import network.lapis.cloud.server.agreementtexts.AgreementTextHash
import network.lapis.cloud.server.agreementtexts.AgreementTextStore
import network.lapis.cloud.server.agreementtexts.BuiltinAgreementTexts
import network.lapis.cloud.server.agreementtexts.EncounterConsentLabels
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextDraftInput
import network.lapis.cloud.shared.domain.AgreementTextDraftLookupDto
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextKindOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextRules
import network.lapis.cloud.shared.domain.AgreementTextVersionDto
import network.lapis.cloud.shared.domain.AgreementTextVersionSummaryDto
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.isEncounterConsent
import network.lapis.cloud.shared.domain.isEventCapture
import network.lapis.cloud.shared.rpc.AgreementTextDraftChangedException
import network.lapis.cloud.shared.rpc.AgreementTextInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextLayoutInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextVersionLabelTakenException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IAgreementTextAdminService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

// Privacy rule of this file: the logs name counts and exception classes only -- never the text, a label together with a member, or a hash.
private val logger = KotlinLogging.logger {}

/**
 * V1.9.134 -- see [IAgreementTextAdminService]. Every method is ADMIN-only (the same [network.lapis.cloud.shared.rpc.ForbiddenException] as every
 * other admin service). A published version is immutable: [publishDraft] only appends. The draft is the one editable thing.
 *
 * Audit ([network.lapis.cloud.shared.domain.AuditEntityType.AGREEMENT_TEXT]): one entry per draft save (`CREATE` the first time, `UPDATE`
 * afterwards, `entityId` = the stable draft id) and one `POST` per publication (`entityId` = the new version id); the snapshot never contains
 * the text.
 *
 * **Race between check and insert at registration:** a registration compares against the current version and then writes its acknowledgment;
 * a publication in between is not locked against. The acknowledgment records what the registrant was shown, which is exactly what it must prove.
 */
class AgreementTextAdminService(
    private val call: ApplicationCall,
    /** V1.9.196: budget of [previewAgreementText]; Application passes ONE shared instance (the service itself is created per call). */
    private val previewRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
) : IAgreementTextAdminService {
    override suspend fun getOverview(): AgreementTextOverviewDto {
        requireAdmin()
        return transaction {
            AgreementTextOverviewDto(
                kinds =
                    AgreementTextKind.entries.map { kind ->
                        val row = AgreementTextStore.currentVersion(kind)
                        AgreementTextKindOverviewDto(
                            kind = kind,
                            placeholderActive = row == null,
                            current = row?.let { AgreementTextStore.toSummary(it) },
                            builtinVersionLabel = BuiltinAgreementTexts.of(kind = kind).versionLabel,
                            hasDraft = AgreementTextStore.findDraft(kind) != null,
                            olderAcknowledgmentCount =
                                row?.let { AgreementTextStore.countOlderAcknowledgments(kind = kind, currentLabel = it.versionLabel) },
                        )
                    },
            )
        }
    }

    override suspend fun getDraft(kind: AgreementTextKind): AgreementTextDraftLookupDto {
        requireAdmin()
        return transaction {
            AgreementTextDraftLookupDto(
                draft = AgreementTextStore.findDraft(kind)?.let { AgreementTextStore.toDraftDto(it) },
            )
        }
    }

    override suspend fun saveDraft(input: AgreementTextDraftInput): AgreementTextDraftDto {
        val current = requireAdmin()
        val body = AgreementTextRules.normalizeBody(input.body)
        val today = OrganizationTimeZone.today()
        val result =
            try {
                transaction {
                    validate(
                        kind = input.kind,
                        versionLabel = input.versionLabel,
                        validFrom = input.validFrom,
                        body = body,
                        today = today,
                        format = AgreementTextFormat.MARKDOWN,
                    )
                    val sha = AgreementTextHash.of(versionLabel = input.versionLabel, body = body)
                    val now = DbClock.nowLocalDateTime()
                    val before = AgreementTextStore.lockDraft(input.kind)
                    val beforeJson =
                        before?.let {
                            AgreementTextStore.snapshotJson(
                                event = EVENT_DRAFT_SAVED,
                                kind = input.kind,
                                versionLabel = it.versionLabel,
                                sha256 = it.sha256,
                                charCount = it.charCount,
                                validFrom = it.validFrom,
                                format = it.format,
                            )
                        }
                    AgreementTextStore.upsertDraft(
                        kind = input.kind,
                        newId = Uuid.random(),
                        versionLabel = input.versionLabel,
                        validFrom = input.validFrom,
                        body = body,
                        sha256 = sha,
                        updatedBy = current.memberId,
                        now = now,
                        format = AgreementTextFormat.MARKDOWN,
                    )
                    val after = AgreementTextStore.findDraft(input.kind)!!
                    AgreementTextStore.recordAudit(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityId = after.id,
                        action = if (before == null) AuditAction.CREATE else AuditAction.UPDATE,
                        before = beforeJson,
                        after =
                            AgreementTextStore.snapshotJson(
                                event = EVENT_DRAFT_SAVED,
                                kind = input.kind,
                                versionLabel = input.versionLabel,
                                sha256 = sha,
                                charCount = body.length,
                                validFrom = input.validFrom,
                                format = AgreementTextFormat.MARKDOWN,
                            ),
                        now = now,
                    )
                    AgreementTextStore.toDraftDto(after)
                }
            } catch (e: ExposedSQLException) {
                // Two admins creating the very first draft of a kind at the same moment: the loser sees the primary key.
                if (e.isUniqueViolation()) throw AgreementTextDraftChangedException()
                throw e
            }
        logger.info { "agreement draft saved (kind=${input.kind.name}, chars=${result.charCount})" }
        return result
    }

    override suspend fun publishDraft(
        kind: AgreementTextKind,
        expectedDraftSha256: String,
    ): AgreementTextVersionSummaryDto {
        val current = requireAdmin()
        val today = OrganizationTimeZone.today()
        val summary =
            try {
                transaction {
                    val draft = lockDraftForPublish(kind) ?: throw AgreementTextDraftChangedException()
                    // A draft stored before V1.9.196 is PLAIN, but the preview always shows Markdown: refuse it, so saving it again (which makes
                    // it MARKDOWN) is the only way to publish, and what the admin previewed is exactly what members get.
                    if (draft.format != AgreementTextFormat.MARKDOWN) throw AgreementTextDraftChangedException()
                    val label = draft.versionLabel
                    val body = draft.body
                    val validFrom = draft.validFrom
                    val sha = AgreementTextHash.of(versionLabel = label, body = body)
                    if (!AgreementTextHash.matches(expectedSha256 = draft.sha256, providedSha256 = expectedDraftSha256) ||
                        !AgreementTextHash.matches(expectedSha256 = sha, providedSha256 = expectedDraftSha256)
                    ) {
                        throw AgreementTextDraftChangedException()
                    }
                    // Re-check everything: the clock moved, another admin may have published in between.
                    validate(kind = kind, versionLabel = label, validFrom = validFrom, body = body, today = today, format = draft.format)
                    val now = DbClock.nowLocalDateTime()
                    val versionId = Uuid.random()
                    val row =
                        AgreementTextStore.insertVersion(
                            id = versionId,
                            kind = kind,
                            versionLabel = label,
                            validFrom = validFrom,
                            body = body,
                            sha256 = sha,
                            authorMemberId = current.memberId,
                            now = now,
                            format = draft.format,
                        )
                    AgreementTextStore.deleteDraft(kind)
                    AgreementTextStore.recordAudit(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityId = versionId,
                        action = AuditAction.POST,
                        before = null,
                        after =
                            AgreementTextStore.snapshotJson(
                                event = EVENT_PUBLISHED,
                                kind = kind,
                                versionLabel = label,
                                sha256 = sha,
                                charCount = body.length,
                                validFrom = validFrom,
                                sequenceNo = row.sequenceNo,
                                format = draft.format,
                            ),
                        now = now,
                    )
                    AgreementTextStore.toSummary(row)
                }
            } catch (e: ExposedSQLException) {
                // Backstop of the two UNIQUE constraints; nothing else is swallowed.
                if (e.isUniqueViolation()) throw AgreementTextVersionLabelTakenException()
                throw e
            }
        logger.info { "agreement version published (kind=${kind.name}, sequenceNo=${summary.sequenceNo})" }
        return summary
    }

    override suspend fun listVersions(kind: AgreementTextKind): List<AgreementTextVersionSummaryDto> {
        requireAdmin()
        return transaction { AgreementTextStore.listVersions(kind).map { AgreementTextStore.toSummary(it) } }
    }

    override suspend fun getVersion(
        kind: AgreementTextKind,
        versionLabel: String,
    ): AgreementTextVersionDto {
        requireAdmin()
        return transaction {
            val row = AgreementTextStore.findVersion(kind = kind, versionLabel = versionLabel)
            if (row != null) {
                AgreementTextVersionDto(
                    summary = AgreementTextStore.toSummary(row),
                    body = row.body,
                    renderedHtml = AgreementMarkdown.htmlFor(format = row.format, body = row.body),
                )
            } else {
                // V1.9.143: a REC kind has two placeholders (one per room profile); the label picks the one that is shown.
                val builtin =
                    BuiltinAgreementTexts.allOf(kind).firstOrNull { it.versionLabel == versionLabel }
                        ?: BuiltinAgreementTexts.of(kind = kind)
                if (builtin.versionLabel != versionLabel) throw NotFoundException("Version not found")
                // The built-in placeholder has no author and no publication; it is shown with an empty author and its own hash.
                AgreementTextVersionDto(
                    summary =
                        AgreementTextVersionSummaryDto(
                            kind = kind,
                            versionLabel = builtin.versionLabel,
                            validFrom = BUILTIN_VALID_FROM.getValue(kind),
                            sha256 = builtin.sha256,
                            charCount = builtin.body.length,
                            authorDisplayName = "",
                            publishedAt = BUILTIN_PUBLISHED_AT.getValue(kind),
                            sequenceNo = 0,
                        ),
                    body = builtin.body,
                )
            }
        }
    }

    override suspend fun previewAgreementText(
        kind: AgreementTextKind,
        body: String,
    ): String {
        val current = requireAdmin()
        if (!previewRateLimiter.checkAndRecord("member:${current.memberId}")) throw RateLimitedException()
        val normalized = AgreementTextRules.normalizeBody(body)
        if (normalized.length >
            AgreementTextRules.MAX_CHARS
        ) {
            throw BadRequestException("text exceeds ${AgreementTextRules.MAX_CHARS} characters")
        }
        if (AgreementTextRules.invalidCharacter(normalized)) throw BadRequestException("text contains a character that is not allowed")
        // Privacy rule of this file: nothing about the text is logged, not even its length.
        return AgreementMarkdown.renderOrNull(body = normalized) ?: throw BadRequestException("text is too deeply nested")
    }

    /**
     * Locks the draft of [kind]. The two encounter notices share ONE label namespace (the acknowledgment table has no kind column), so
     * their publications are serialized against each other: both drafts are locked, always ENC_CHURCH before ENC_ASSEM (no deadlock).
     */
    private fun lockDraftForPublish(kind: AgreementTextKind): AgreementTextDraftRecord? {
        if (!kind.isEncounterConsent()) return AgreementTextStore.lockDraft(kind)
        val church = AgreementTextStore.lockDraft(AgreementTextKind.ENC_CHURCH)
        val assembly = AgreementTextStore.lockDraft(AgreementTextKind.ENC_ASSEM)
        return if (kind == AgreementTextKind.ENC_CHURCH) church else assembly
    }

    private suspend fun requireAdmin(): CurrentMember {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return current
    }

    /** Throws the specific exception for the first rule [body] / [versionLabel] / [validFrom] violates. Needs an open transaction. */
    private fun validate(
        kind: AgreementTextKind,
        versionLabel: String,
        validFrom: LocalDate,
        body: String,
        today: LocalDate,
        format: AgreementTextFormat,
    ) {
        if (!AgreementTextRules.isValidLabel(versionLabel)) throw AgreementTextInvalidException()
        if (body.isEmpty() || body.length > AgreementTextRules.MAX_CHARS) throw AgreementTextInvalidException()
        if (AgreementTextRules.invalidCharacter(body)) throw AgreementTextInvalidException()
        // V1.9.196: a MARKDOWN text must be renderable (an absurdly nested one is refused here, so serving a stored text can never fail).
        if (format == AgreementTextFormat.MARKDOWN &&
            AgreementMarkdown.renderOrNull(body = body) == null
        ) {
            throw AgreementTextInvalidException()
        }
        if (validFrom > today) throw AgreementTextInvalidException()
        val currentRow = AgreementTextStore.currentVersion(kind)
        if (currentRow != null && validFrom < currentRow.validFrom) throw AgreementTextInvalidException()
        if (kind.isEncounterConsent()) {
            // V1.9.148: the paragraph rule and the label length (room for the system's "+rec" in VARCHAR(50)).
            if (EncounterConsentLayout.violation(label = versionLabel, body = body, format = format) !=
                null
            ) {
                throw AgreementTextLayoutInvalidException()
            }
            // The acknowledgment table has no kind column: a label must identify ONE text across both ENC kinds and every label code ever wrote.
            if (EncounterConsentLabels.isReserved(versionLabel) ||
                AgreementTextStore.versionLabelExists(kind = EncounterConsentLabels.siblingOf(kind), versionLabel = versionLabel)
            ) {
                throw AgreementTextVersionLabelTakenException()
            }
        }
        if (kind.isEventCapture()) {
            // V1.9.156: the same paragraph rule (headline + three key points + full text); no "+rec" suffix and no code-owned paragraph, so the label
            // limit is the general VERSION_LABEL_MAX that isValidLabel already enforced above.
            if (EncounterConsentLayout.violation(label = "", body = body, format = format) !=
                null
            ) {
                throw AgreementTextLayoutInvalidException()
            }
        }
        if (kind == AgreementTextKind.REC_PULPIT || kind == AgreementTextKind.REC_ROOM) {
            // V1.9.196: both recording texts are short dialog texts; for a MARKDOWN text no line may start with block markup.
            if (format == AgreementTextFormat.MARKDOWN &&
                (EncounterConsentLayout.hasBlockMarkup(body) || AgreementMarkdown.hasBlockNodes(body))
            ) {
                throw AgreementTextLayoutInvalidException()
            }
        }
        // V1.9.143: the REC kinds have one placeholder per room profile; a text equal to ANY of them, or a label equal to ANY of them, is refused.
        val builtins = BuiltinAgreementTexts.allOf(kind)
        // A text equal to the placeholder would silence the placeholder warning while the placeholder stays what people see.
        if (builtins.any { AgreementTextRules.normalizeBody(it.body) == body }) throw AgreementTextInvalidException()
        if (builtins.any { it.versionLabel == versionLabel } ||
            AgreementTextStore.versionLabelExists(kind = kind, versionLabel = versionLabel)
        ) {
            throw AgreementTextVersionLabelTakenException()
        }
    }

    private companion object {
        const val EVENT_DRAFT_SAVED = "DRAFT_SAVED"
        const val EVENT_PUBLISHED = "PUBLISHED"
        val BUILTIN_VALID_FROM =
            mapOf(
                AgreementTextKind.MEMBERSHIP to LocalDate(2026, 7, 23),
                AgreementTextKind.FRIEND to LocalDate(2026, 8, 15),
                AgreementTextKind.REC_ROOM to LocalDate(2026, 10, 16),
                AgreementTextKind.REC_PULPIT to LocalDate(2026, 10, 16),
                AgreementTextKind.FINDER to LocalDate(2026, 10, 16),
                AgreementTextKind.ENC_CHURCH to LocalDate(2026, 10, 17),
                AgreementTextKind.ENC_ASSEM to LocalDate(2026, 10, 17),
                AgreementTextKind.EVENT_REC to LocalDate(2026, 10, 18),
            )
        val BUILTIN_PUBLISHED_AT =
            BUILTIN_VALID_FROM.mapValues { (_, date) -> LocalDateTime(date.year, date.month, date.day, 0, 0) }
    }
}
