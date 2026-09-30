package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.db.generated.MemberPublicBioTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberbio.MemberPublicBioStore
import network.lapis.cloud.server.memberphoto.MemberPhotoStore
import network.lapis.cloud.server.routes.PublicChrome
import network.lapis.cloud.server.routes.PublicLanguage
import network.lapis.cloud.server.routes.PublicProfilesReader
import network.lapis.cloud.server.routes.publicLabel
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.MemberPublicBioAuditAction
import network.lapis.cloud.shared.domain.MemberPublicBioRules
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.OwnPublicProfileDto
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PublicListingPlace
import network.lapis.cloud.shared.domain.PublicRankingKind
import network.lapis.cloud.shared.domain.PublicTextNormalization
import network.lapis.cloud.shared.domain.rank
import network.lapis.cloud.shared.rpc.IMemberPublicProfileService
import network.lapis.cloud.shared.rpc.MemberPublicBioConsentOutdatedException
import network.lapis.cloud.shared.rpc.MemberPublicBioMissingException
import network.lapis.cloud.shared.rpc.MemberPublicBioNotEligibleException
import network.lapis.cloud.shared.rpc.MemberPublicBioRateLimitedException
import network.lapis.cloud.shared.rpc.MemberPublicBioValidationException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the member's own short introduction plus the status line of
 * where they are publicly listed. Every method except [moderationRemoveBio] acts EXCLUSIVELY on the
 * caller's own profile; none takes a member id.
 *
 * - **Eligibility** (computed here, never trusted from the client): a current `EXECUTIVE_BOARD`
 *   mandate (the same [PublicProfilesReader.boardSelectionCondition] `/vorstand` uses) or an ACTIVE,
 *   non-revoked politician profile. Saving text needs eligibility OR an already stored row -- the
 *   latter keeps an ex-officer able to edit and, above all, DELETE their text ("löschbar bleiben").
 * - **Publication** needs ACTIVE status, stored text and the CURRENT consent wording; it is stored as
 *   a consent (timestamp + version) and is effective only under the current version. A text change
 *   KEEPS an existing consent: the author writes and previews the text themselves. Withdrawal
 *   (`visible = false`) and deletion are always available.
 * - **Politician listing** is NOT handled here -- `IDsgvoService` grants/revokes it under
 *   `PublicRankingKind.POLITICIAN_LISTING`; this service only REPORTS its state.
 * - **Audit** entries are written in the SAME transaction as the change and never carry the text.
 */
class MemberPublicProfileService internal constructor(
    private val call: ApplicationCall,
    private val writeRateLimiter: FederationInboxRateLimiter,
    private val moderationRateLimiter: FederationInboxRateLimiter,
) : IMemberPublicProfileService {
    override suspend fun getOwnPublicProfile(): OwnPublicProfileDto {
        val current = resolveCurrentMember(call)
        return transaction { buildDto(current.memberId) ?: throw NotFoundException("Member not found") }
    }

    override suspend fun saveOwnBio(text: String): OwnPublicProfileDto {
        val current = resolveCurrentMember(call)
        checkRate(current)
        val normalized = MemberPublicBioRules.normalize(text)
        if (normalized is PublicTextNormalization.TooLong ||
            normalized is PublicTextNormalization.TooManyLineBreaks ||
            normalized is PublicTextNormalization.ControlChars
        ) {
            throw MemberPublicBioValidationException()
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val member = MemberPublicBioStore.lockMember(current.memberId) ?: throw NotFoundException("Member not found")
            val existing = MemberPublicBioStore.findByMember(current.memberId)
            when (normalized) {
                PublicTextNormalization.Empty -> {
                    val deleted = MemberPublicBioStore.deleteRow(current.memberId)
                    if (deleted != null) {
                        MemberPublicBioStore.recordAudit(
                            actorMemberId = current.memberId,
                            actorRole = current.role,
                            targetMemberId = current.memberId,
                            targetStatus = member[MemberTable.status],
                            action = MemberPublicBioAuditAction.DELETED,
                            beforePublic = deleted.wasPublic,
                            beforeVersion = deleted.consentTextVersion,
                            afterPublic = false,
                            afterVersion = null,
                            now = now,
                        )
                    }
                }
                is PublicTextNormalization.Ok -> {
                    if (existing == null && !isEligible(current.memberId)) throw MemberPublicBioNotEligibleException()
                    MemberPublicBioStore.upsertText(memberId = current.memberId, text = normalized.text, now = now)
                    val after = MemberPublicBioStore.findByMember(current.memberId)
                    MemberPublicBioStore.recordAudit(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        targetMemberId = current.memberId,
                        targetStatus = member[MemberTable.status],
                        action = MemberPublicBioAuditAction.SAVED,
                        beforePublic = MemberPublicBioStore.isEffectivelyPublic(existing),
                        beforeVersion = existing?.get(MemberPublicBioTable.consentTextVersion),
                        afterPublic = MemberPublicBioStore.isEffectivelyPublic(after),
                        afterVersion = after?.get(MemberPublicBioTable.consentTextVersion),
                        now = now,
                    )
                }
                else -> error("unreachable: rejected above")
            }
            buildDto(current.memberId) ?: throw NotFoundException("Member not found")
        }
    }

    override suspend fun setOwnBioPublic(
        visible: Boolean,
        consentTextVersion: String?,
    ): OwnPublicProfileDto {
        val current = resolveCurrentMember(call)
        checkRate(current)
        if (visible) {
            if (current.status !in MemberStatusSets.ORGANIZATION_MEMBER) throw MemberPublicBioNotEligibleException()
            if (consentTextVersion != MemberPublicBioRules.CONSENT_TEXT_VERSION) throw MemberPublicBioConsentOutdatedException()
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val member = MemberPublicBioStore.lockMember(current.memberId) ?: throw NotFoundException("Member not found")
            val row = MemberPublicBioStore.findByMember(current.memberId)
            if (visible) {
                if (member[MemberTable.status] !in MemberStatusSets.ORGANIZATION_MEMBER) throw MemberPublicBioNotEligibleException()
                if (row == null) throw MemberPublicBioMissingException()
                if (!MemberPublicBioStore.isEffectivelyPublic(row)) {
                    val beforeVersion = row[MemberPublicBioTable.consentTextVersion]
                    val beforePublic = row[MemberPublicBioTable.consentGrantedAt] != null
                    MemberPublicBioStore.publish(
                        memberId = current.memberId,
                        version = MemberPublicBioRules.CONSENT_TEXT_VERSION,
                        now = now,
                    )
                    audit(
                        current = current,
                        targetStatus = member[MemberTable.status],
                        action = MemberPublicBioAuditAction.PUBLISHED,
                        beforePublic = beforePublic,
                        beforeVersion = beforeVersion,
                        afterPublic = true,
                        now = now,
                    )
                }
            } else if (row != null && row[MemberPublicBioTable.consentGrantedAt] != null) {
                val beforeVersion = row[MemberPublicBioTable.consentTextVersion]
                MemberPublicBioStore.unpublish(current.memberId)
                audit(
                    current = current,
                    targetStatus = member[MemberTable.status],
                    action = MemberPublicBioAuditAction.UNPUBLISHED,
                    beforePublic = true,
                    beforeVersion = beforeVersion,
                    afterPublic = false,
                    now = now,
                )
            }
            buildDto(current.memberId) ?: throw NotFoundException("Member not found")
        }
    }

    override suspend fun moderationRemoveBio(memberId: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        if (!moderationRateLimiter.checkAndRecord("actor:${current.memberId}")) throw MemberPublicBioRateLimitedException()
        val targetId = runCatching { Uuid.parse(memberId) }.getOrElse { throw NotFoundException("Member $memberId not found") }
        val now = DbClock.nowLocalDateTime()
        val removed =
            transaction {
                val member = MemberPublicBioStore.lockMember(targetId) ?: return@transaction false
                val deleted = MemberPublicBioStore.deleteRow(targetId) ?: return@transaction false
                MemberPublicBioStore.recordAudit(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    targetMemberId = targetId,
                    targetStatus = member[MemberTable.status],
                    action = MemberPublicBioAuditAction.REMOVED_BY_MODERATION,
                    beforePublic = deleted.wasPublic,
                    beforeVersion = deleted.consentTextVersion,
                    afterPublic = false,
                    afterVersion = null,
                    now = now,
                )
                true
            }
        logger.info { "Member public bio moderation removal: actor=${current.memberId} removed=$removed" }
        // Always Unit -- also when nothing existed: no existence oracle, no content, no metadata.
    }

    private fun checkRate(current: CurrentMember) {
        if (!writeRateLimiter.checkAndRecord("member:${current.memberId}")) throw MemberPublicBioRateLimitedException()
    }

    private fun audit(
        current: CurrentMember,
        targetStatus: MemberStatus,
        action: MemberPublicBioAuditAction,
        beforePublic: Boolean,
        beforeVersion: String?,
        afterPublic: Boolean,
        now: LocalDateTime,
    ) = MemberPublicBioStore.recordAudit(
        actorMemberId = current.memberId,
        actorRole = current.role,
        targetMemberId = current.memberId,
        targetStatus = targetStatus,
        action = action,
        beforePublic = beforePublic,
        beforeVersion = beforeVersion,
        afterPublic = afterPublic,
        afterVersion = if (afterPublic) MemberPublicBioRules.CONSENT_TEXT_VERSION else null,
        now = now,
    )

    /** The caller's CURRENT board roles, best rank first -- same selection `/vorstand` renders. */
    private fun boardRoles(memberId: Uuid): List<CommitteeRole> =
        (CommitteeMembershipTable innerJoin CommitteeTable innerJoin MemberTable)
            .selectAll()
            .where { PublicProfilesReader.boardSelectionCondition() and (MemberTable.id eq memberId) }
            .map { it[CommitteeMembershipTable.role] }
            .sortedBy { it.rank }

    private fun activePoliticianMandate(memberId: Uuid): Pair<Boolean, String?> {
        val row =
            PoliticianProfileTable
                .selectAll()
                .where {
                    (PoliticianProfileTable.memberId eq memberId) and
                        (PoliticianProfileTable.status eq PoliticianProfileStatus.ACTIVE) and
                        PoliticianProfileTable.revokedAt.isNull()
                }.singleOrNull() ?: return false to null
        return true to row[PoliticianProfileTable.mandateText]
    }

    private fun isEligible(memberId: Uuid): Boolean = boardRoles(memberId).isNotEmpty() || activePoliticianMandate(memberId).first

    private fun buildDto(memberId: Uuid): OwnPublicProfileDto? {
        val member =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq memberId }
                .singleOrNull() ?: return null
        val roles = boardRoles(memberId)
        val (isPolitician, mandateText) = activePoliticianMandate(memberId)
        val bio = MemberPublicBioStore.findByMember(memberId)
        val photo = MemberPhotoStore.findByMember(memberId)
        val grantedVersion = bio?.get(MemberPublicBioTable.consentTextVersion)
        val bioPublic = MemberPublicBioStore.isEffectivelyPublic(bio)
        val listingEffective =
            isPolitician &&
                member[MemberTable.status] in MemberStatusSets.ORGANIZATION_MEMBER &&
                PublicRankingConsentStore
                    .currentState(memberId)
                    .single { it.kind == PublicRankingKind.POLITICIAN_LISTING }
                    .effective
        val places =
            buildList {
                if (roles.isNotEmpty()) add(PublicListingPlace.BOARD)
                if (listingEffective) add(PublicListingPlace.POLITICIANS)
            }
        return OwnPublicProfileDto(
            eligible = roles.isNotEmpty() || isPolitician,
            isBoardMember = roles.isNotEmpty(),
            isPolitician = isPolitician,
            displayName = member[MemberTable.displayName],
            roleLabel = roles.firstOrNull()?.publicLabel(PublicChrome.stringsFor(PublicLanguage.DE)),
            office = if (isPolitician) PublicProfilesReader.publicOfficeText(mandateText) else null,
            bioText = bio?.get(MemberPublicBioTable.bioText),
            bioPublic = bioPublic,
            bioConsentOutdated = grantedVersion != null && grantedVersion != MemberPublicBioRules.CONSENT_TEXT_VERSION,
            photoPublic = photo != null && photo[MemberPhotoTable.visibility] == MemberPhotoVisibility.PUBLIC,
            politicianListingEffective = listingEffective,
            publicOn = places,
            requiredConsentTextVersion = MemberPublicBioRules.CONSENT_TEXT_VERSION,
        )
    }
}
