package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.agreementtexts.AgreementMarkdown
import network.lapis.cloud.server.agreementtexts.AgreementTextResolver
import network.lapis.cloud.server.agreementtexts.CurrentAgreementText
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.memberfinder.MemberFinderAction
import network.lapis.cloud.server.memberfinder.MemberFinderBands
import network.lapis.cloud.server.memberfinder.MemberFinderBlockStore
import network.lapis.cloud.server.memberfinder.MemberFinderConsentStore
import network.lapis.cloud.server.memberfinder.MemberFinderEligibility
import network.lapis.cloud.server.memberfinder.MemberFinderLifecycle
import network.lapis.cloud.server.memberfinder.MemberFinderMatcher
import network.lapis.cloud.server.memberfinder.MemberFinderMessages
import network.lapis.cloud.server.memberfinder.MemberFinderRandom
import network.lapis.cloud.server.memberfinder.MemberFinderRateLimiter
import network.lapis.cloud.server.memberfinder.MemberFinderRegion
import network.lapis.cloud.server.memberfinder.MemberFinderRequestRow
import network.lapis.cloud.server.memberfinder.MemberFinderRequestStatus
import network.lapis.cloud.server.memberfinder.MemberFinderRequestStore
import network.lapis.cloud.server.memberfinder.MemberFinderRevokeReason
import network.lapis.cloud.server.memberfinder.SecureMemberFinderRandom
import network.lapis.cloud.server.memberfinder.plusDays
import network.lapis.cloud.server.memberfinder.plusHours
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.MemberFinderBlockDto
import network.lapis.cloud.shared.domain.MemberFinderBoardOverviewDto
import network.lapis.cloud.shared.domain.MemberFinderConnectionDto
import network.lapis.cloud.shared.domain.MemberFinderConsentTextDto
import network.lapis.cloud.shared.domain.MemberFinderIncomingRequestDto
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderOutgoingRequestDto
import network.lapis.cloud.shared.domain.MemberFinderOutgoingState
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.domain.MemberFinderRegionBandDto
import network.lapis.cloud.shared.domain.MemberFinderResponseDto
import network.lapis.cloud.shared.domain.MemberFinderRules
import network.lapis.cloud.shared.domain.MemberFinderStateDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IMemberFinderService
import network.lapis.cloud.shared.rpc.MemberFinderAdultConfirmationRequiredException
import network.lapis.cloud.shared.rpc.MemberFinderConsentOutdatedException
import network.lapis.cloud.shared.rpc.MemberFinderConsentRequiredException
import network.lapis.cloud.shared.rpc.MemberFinderLimitReachedException
import network.lapis.cloud.shared.rpc.MemberFinderNotEligibleException
import network.lapis.cloud.shared.rpc.MemberFinderRegionNotVisibleException
import network.lapis.cloud.shared.rpc.MemberFinderRegrantCooldownException
import network.lapis.cloud.shared.rpc.MemberFinderUnavailableException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

// Privacy rule of this file (Art. 9 GDPR): the logs name no member, no region, no note and no number. Not one line in this file is written.

/**
 * V1.9.144 -- see [IMemberFinderService] and `docs/features/member-finder.adoc`.
 *
 * **One gate** ([requireFinderMember]) at the top of every method: an ACTIVE member (GUEST, FRIEND, APPLICATION, WITHDRAWN, DONOR and DECEASED are
 * refused with the bare [ForbiddenException]); the instance switch is then checked inside the transaction ([MemberFinderUnavailableException]), except for
 * [getState] (answers `offered = false`) and [optOut] (ALWAYS possible -- Art. 7(3) GDPR). **Every write runs under the global finder lock**
 * (`MemberFinderSettingsStore.lockAndLoad`, decision E9), so a switch-off, the inbox limit and the duplicate open pair cannot race.
 *
 * **Neutrality for the sender.** [sendRequest] stores a PHANTOM request (no addressee) when nobody qualifies and answers EVERY case with the same
 * [MemberFinderOutgoingState.PENDING] shape; the sender's list, limit and export only ever show the derived state of
 * [MemberFinderRequestStore.outgoingStateOf]. Every refusal about somebody else's request is the same [NotFoundException] with the same text.
 *
 * Constructed per RPC request, so the rate limiter is a constructor parameter WITHOUT a default (a module singleton).
 */
class MemberFinderService(
    private val call: ApplicationCall,
    private val rateLimiter: MemberFinderRateLimiter,
    private val random: MemberFinderRandom = SecureMemberFinderRandom,
) : IMemberFinderService {
    override suspend fun getState(): MemberFinderStateDto {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.READ)
        return transaction {
            if (!MemberFinderSettingsStore.load().active()) return@transaction notOfferedState()
            stateOf(memberId = current.memberId, now = ServerClock.now())
        }
    }

    override suspend fun getConsentText(): MemberFinderConsentTextDto {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.READ)
        return transaction {
            requireSwitchOn()
            val text = AgreementTextResolver.current(kind = AgreementTextKind.FINDER)
            MemberFinderConsentTextDto(
                versionLabel = text.versionLabel,
                body = text.body,
                sha256 = text.sha256,
                format = text.format,
                renderedHtml = AgreementMarkdown.htmlFor(format = text.format, body = text.body),
            )
        }
    }

    override suspend fun optIn(
        consentVersion: String,
        consentSha256: String,
        interest: MemberFinderInterest,
        adultConfirmed: Boolean,
    ): MemberFinderStateDto {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.CONSENT)
        if (!adultConfirmed) throw MemberFinderAdultConfirmationRequiredException()
        return transaction {
            requireSwitchOnLocked()
            val now = ServerClock.now()
            val basis = MemberFinderEligibility.basisOf(memberId = current.memberId, today = OrganizationTimeZone.dateOf(now))
            if (basis == null || !basis.eligible) throw MemberFinderNotEligibleException()
            if (!AgreementTextResolver.matchesCurrent(kind = AgreementTextKind.FINDER, version = consentVersion, sha256 = consentSha256)) {
                throw MemberFinderConsentOutdatedException()
            }
            val text = AgreementTextResolver.current(kind = AgreementTextKind.FINDER)
            val open = MemberFinderConsentStore.openGrant(current.memberId)
            val effective = open != null && open.version == text.versionLabel && open.sha256 == text.sha256
            if (effective) {
                // Already consenting to this text: a changed interest is recorded, the same interest is a no-op. No cooldown -- the group size
                // does not move.
                if (open!!.interest != interest) {
                    MemberFinderConsentStore.grant(
                        memberId = current.memberId,
                        version = text.versionLabel,
                        sha256 = text.sha256,
                        interest = interest,
                        now = now,
                    )
                }
            } else {
                // A re-consent after the text changed (an open GRANT of an older text) is never blocked; a fresh consent after a revoke waits 24 hours
                // after the member's last event.
                if (open == null) {
                    val last = MemberFinderConsentStore.lastEventAt(current.memberId)
                    if (last != null &&
                        now < last.plusHours(MemberFinderRules.REGRANT_COOLDOWN_HOURS)
                    ) {
                        throw MemberFinderRegrantCooldownException()
                    }
                }
                MemberFinderConsentStore.grant(
                    memberId = current.memberId,
                    version = text.versionLabel,
                    sha256 = text.sha256,
                    interest = interest,
                    now = now,
                )
            }
            stateOf(memberId = current.memberId, now = now)
        }
    }

    override suspend fun optOut(): MemberFinderStateDto {
        // Deliberately NOT rate limited and NOT behind the instance switch: a withdrawal of consent is always possible.
        val current = requireFinderMember()
        return transaction {
            MemberFinderSettingsStore.lockAndLoad()
            val now = ServerClock.now()
            MemberFinderLifecycle.onOptOut(memberId = current.memberId, reason = MemberFinderRevokeReason.MEMBER, now = now)
            if (MemberFinderSettingsStore.load().active()) stateOf(memberId = current.memberId, now = now) else notOfferedState()
        }
    }

    override suspend fun sendRequest(
        purpose: MemberFinderPurpose,
        note: String?,
    ): MemberFinderOutgoingRequestDto {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.SEND_HOUR, MemberFinderAction.SEND_DAY)
        val cleanNote = MemberFinderRules.normalizeNote(note)
        return transaction {
            requireSwitchOnLocked()
            val now = ServerClock.now()
            val today = OrganizationTimeZone.dateOf(now)
            val text = AgreementTextResolver.current(kind = AgreementTextKind.FINDER)
            val grant = requireEffectiveGrant(memberId = current.memberId, text = text)
            if (!MemberFinderMatcher.interestMatches(interest = grant.interest, purpose = purpose)) {
                throw ConflictException("The purpose is not part of your interest")
            }
            val basis = MemberFinderEligibility.basisOf(memberId = current.memberId, today = today)
            val region = basis?.takeIf { it.eligible }?.regionKey ?: throw MemberFinderNotEligibleException()
            // The caller's OWN region: what the member sees about their own group anyway.
            if (MemberFinderBands.bandOf(region) == null) throw MemberFinderRegionNotVisibleException()
            if (MemberFinderRequestStore.countPending(fromMemberId = current.memberId, now = now) >= MemberFinderRules.MAX_OPEN_OUTGOING) {
                throw MemberFinderLimitReachedException()
            }
            val participants = MemberFinderEligibility.participants(today = today, current = text)
            val sender = participants.firstOrNull { it.memberId == current.memberId } ?: throw MemberFinderNotEligibleException()
            val target =
                MemberFinderMatcher.pick(
                    sender = sender,
                    purpose = purpose,
                    participants = participants,
                    now = now,
                    random = random,
                )
            // No counterpart found = a phantom (to_member_id NULL): the sender must not learn that nobody was found.
            val row =
                MemberFinderRequestStore.insert(
                    fromMemberId = current.memberId,
                    toMemberId = target,
                    purpose = purpose,
                    note = cleanNote,
                    now = now,
                )
            outgoingDto(row = row, now = now)
        }
    }

    override suspend fun listOutgoingRequests(): List<MemberFinderOutgoingRequestDto> {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.READ)
        return transaction {
            requireSwitchOn()
            val now = ServerClock.now()
            MemberFinderRequestStore
                .outgoing(
                    fromMemberId = current.memberId,
                    limit = MAX_OUTGOING_LISTED,
                ).map { outgoingDto(row = it, now = now) }
        }
    }

    override suspend fun withdrawRequest(requestId: String) {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.RESPOND)
        val id = parseId(requestId)
        val found =
            transaction {
                requireSwitchOnLocked()
                val now = ServerClock.now()
                val row = id?.let { MemberFinderRequestStore.find(it) }
                // The same answer for an unknown id, somebody else's request and one that is not pending any more.
                if (row == null ||
                    row.fromMemberId != current.memberId ||
                    MemberFinderRequestStore.outgoingStateOf(row = row, now = now) != MemberFinderOutgoingState.PENDING
                ) {
                    false
                } else {
                    // A stored DECLINED / EXPIRED keeps the addressee's decision (Art. 15 accuracy); the sender flag makes the sender's view identical.
                    MemberFinderRequestStore.withdraw(row = row, now = now)
                    true
                }
            }
        if (!found) throw notFound()
    }

    override suspend fun listIncomingRequests(): List<MemberFinderIncomingRequestDto> {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.READ)
        return transaction {
            requireSwitchOn()
            val now = ServerClock.now()
            val today = OrganizationTimeZone.dateOf(now)
            val text = AgreementTextResolver.current(kind = AgreementTextKind.FINDER)
            val participants = MemberFinderEligibility.participants(today = today, current = text).associateBy { it.memberId }
            val me = participants[current.memberId] ?: return@transaction emptyList()
            MemberFinderRequestStore
                .incomingOpen(toMemberId = current.memberId, now = now, limit = MAX_INCOMING_LISTED)
                // Live filter: the sender must still be eligible and in the same region. The sender is never shown.
                .filter { participants[it.fromMemberId]?.regionKey == me.regionKey }
                .map {
                    MemberFinderIncomingRequestDto(
                        requestId = it.id.toString(),
                        purpose = it.purpose,
                        receivedOn = OrganizationTimeZone.dateOf(it.createdAt),
                        expiresOn = OrganizationTimeZone.dateOf(it.expiresAt),
                        note = it.note,
                    )
                }
        }
    }

    override suspend fun respondToRequest(
        requestId: String,
        accept: Boolean,
        block: Boolean,
    ): MemberFinderResponseDto {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.RESPOND)
        val id = parseId(requestId)
        // The outcome is decided inside the transaction but a refusal is thrown AFTER it: marking a stale request EXPIRED must commit.
        val outcome =
            transaction {
                requireSwitchOnLocked()
                val now = ServerClock.now()
                val today = OrganizationTimeZone.dateOf(now)
                val text = AgreementTextResolver.current(kind = AgreementTextKind.FINDER)
                val row = id?.let { MemberFinderRequestStore.find(it) }
                if (row == null || row.toMemberId != current.memberId || row.status != MemberFinderRequestStatus.OPEN) {
                    return@transaction RespondOutcome.Refused
                }
                if (now >= row.expiresAt) {
                    MemberFinderRequestStore.setStatus(id = row.id, status = MemberFinderRequestStatus.EXPIRED, decidedAt = row.expiresAt)
                    return@transaction RespondOutcome.Refused
                }
                val participants = MemberFinderEligibility.participants(today = today, current = text).associateBy { it.memberId }
                val sender = participants[row.fromMemberId]
                val me = participants[current.memberId]
                if (sender == null || me == null || sender.regionKey != me.regionKey) {
                    MemberFinderRequestStore.setStatus(id = row.id, status = MemberFinderRequestStatus.EXPIRED, decidedAt = now)
                    return@transaction RespondOutcome.Refused
                }
                if (accept) {
                    // The lifecycle hooks run without the finder lock: if the sender vanished meanwhile, nothing is updated and nothing is revealed.
                    val updated =
                        MemberFinderRequestStore.acceptOpen(id = row.id, toMemberId = current.memberId, decidedAt = now)
                    if (updated == 0) return@transaction RespondOutcome.Refused
                    RespondOutcome.Done(
                        connectionOf(
                            row = row.copy(status = MemberFinderRequestStatus.ACCEPTED, decidedAt = now),
                            memberId = current.memberId,
                        ),
                    )
                } else {
                    MemberFinderRequestStore.setStatus(id = row.id, status = MemberFinderRequestStatus.DECLINED, decidedAt = now)
                    if (block) {
                        MemberFinderBlockStore.block(
                            blockerMemberId = current.memberId,
                            blockedMemberId = row.fromMemberId,
                            now = now,
                        )
                    }
                    RespondOutcome.Done(null)
                }
            }
        return when (outcome) {
            RespondOutcome.Refused -> throw notFound()
            is RespondOutcome.Done -> MemberFinderResponseDto(connection = outcome.connection)
        }
    }

    override suspend fun listConnections(): List<MemberFinderConnectionDto> {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.READ)
        return transaction {
            requireSwitchOn()
            MemberFinderRequestStore
                .connections(memberId = current.memberId, limit = MAX_CONNECTIONS_LISTED)
                .mapNotNull { connectionOf(row = it, memberId = current.memberId) }
        }
    }

    override suspend fun contactConnection(
        connectionId: String,
        message: String,
    ): DirectMessageDto {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.RESPOND)
        val id = parseId(connectionId)
        return transaction {
            requireSwitchOnLocked()
            val row = id?.let { connectionRow(id = it, memberId = current.memberId) } ?: throw notFound()
            insertDirectMessage(
                senderId = current.memberId,
                recipientId = otherSide(row = row, memberId = current.memberId),
                body = message,
            )
        }
    }

    override suspend fun blockConnection(connectionId: String) {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.BLOCK)
        val id = parseId(connectionId)
        val found =
            transaction {
                requireSwitchOnLocked()
                val row = id?.let { connectionRow(id = it, memberId = current.memberId) }
                if (row == null) {
                    false
                } else {
                    MemberFinderBlockStore.block(
                        blockerMemberId = current.memberId,
                        blockedMemberId = otherSide(row = row, memberId = current.memberId),
                        now = ServerClock.now(),
                    )
                    MemberFinderRequestStore.delete(row.id)
                    true
                }
            }
        if (!found) throw notFound()
    }

    override suspend fun listBlocks(): List<MemberFinderBlockDto> {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.READ)
        return transaction {
            requireSwitchOn()
            MemberFinderBlockStore.ownBlocks(blockerMemberId = current.memberId, limit = MAX_BLOCKS_LISTED).map {
                MemberFinderBlockDto(blockId = it.id.toString(), createdOn = OrganizationTimeZone.dateOf(it.createdAt))
            }
        }
    }

    override suspend fun unblock(blockId: String) {
        val current = requireFinderMember()
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.BLOCK)
        val id = parseId(blockId)
        val found =
            transaction {
                requireSwitchOnLocked()
                val row = id?.let { MemberFinderBlockStore.find(it) }
                if (row == null || row.blockerMemberId != current.memberId) {
                    false
                } else {
                    MemberFinderBlockStore.unblock(row.id)
                    true
                }
            }
        if (!found) throw notFound()
    }

    override suspend fun getBoardOverview(): MemberFinderBoardOverviewDto {
        val current = requireFinderMember()
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        rateLimiter.checkAndRecord(memberId = current.memberId, MemberFinderAction.READ)
        return transaction {
            requireSwitchOn()
            MemberFinderBoardOverviewDto(
                computedOn = MemberFinderBands.computedOn(),
                regions =
                    MemberFinderBands.all().map { (region, band) ->
                        MemberFinderRegionBandDto(regionLabel = MemberFinderRegion.labelOf(region), band = band)
                    },
            )
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------

    /** THE gate of every method: an ACTIVE member. Everything else is the bare [ForbiddenException]. */
    private fun requireFinderMember(): CurrentMember {
        val current = resolveCurrentMember(call)
        if (current.status !in MemberStatusSets.ORGANIZATION_MEMBER) throw ForbiddenException()
        return current
    }

    private fun requireSwitchOn() {
        if (!MemberFinderSettingsStore.load().active()) throw MemberFinderUnavailableException()
    }

    /** For writes: takes the global finder lock FIRST, then checks the switch under it. */
    private fun requireSwitchOnLocked() {
        if (!MemberFinderSettingsStore.lockAndLoad().active()) throw MemberFinderUnavailableException()
    }

    private fun requireEffectiveGrant(
        memberId: Uuid,
        text: CurrentAgreementText,
    ) = MemberFinderConsentStore.openGrant(memberId)?.takeIf { it.version == text.versionLabel && it.sha256 == text.sha256 }
        ?: throw MemberFinderConsentRequiredException()

    private fun notOfferedState() =
        MemberFinderStateDto(
            offered = false,
            eligible = false,
            optedIn = false,
            consentOutdated = false,
            interest = null,
            regionLabel = null,
            band = null,
            bandComputedOn = null,
            nextOptInAllowedAt = null,
            pendingOutgoing = 0,
        )

    private fun stateOf(
        memberId: Uuid,
        now: LocalDateTime,
    ): MemberFinderStateDto {
        val today = OrganizationTimeZone.dateOf(now)
        val basis = MemberFinderEligibility.basisOf(memberId = memberId, today = today)
        val text = AgreementTextResolver.current(kind = AgreementTextKind.FINDER)
        val open = MemberFinderConsentStore.openGrant(memberId)
        val current = open != null && open.version == text.versionLabel && open.sha256 == text.sha256
        val eligible = basis?.eligible == true
        val optedIn = current && eligible
        val region = basis?.regionKey
        val nextAllowed =
            if (open == null) {
                MemberFinderConsentStore
                    .lastEventAt(memberId)
                    ?.plusHours(MemberFinderRules.REGRANT_COOLDOWN_HOURS)
                    ?.takeIf { it > now }
            } else {
                null
            }
        return MemberFinderStateDto(
            offered = true,
            eligible = eligible,
            optedIn = optedIn,
            consentOutdated = open != null && !current,
            interest = if (optedIn) open?.interest else null,
            regionLabel = if (optedIn && region != null) MemberFinderRegion.labelOf(region) else null,
            band = if (optedIn && region != null) MemberFinderBands.bandOf(region) else null,
            bandComputedOn = if (optedIn) MemberFinderBands.computedOn() else null,
            nextOptInAllowedAt = nextAllowed,
            pendingOutgoing = MemberFinderRequestStore.countPending(fromMemberId = memberId, now = now),
        )
    }

    private fun outgoingDto(
        row: MemberFinderRequestRow,
        now: LocalDateTime,
    ) = MemberFinderOutgoingRequestDto(
        requestId = row.id.toString(),
        purpose = row.purpose,
        sentOn = OrganizationTimeZone.dateOf(row.createdAt),
        expiresOn = OrganizationTimeZone.dateOf(row.expiresAt),
        state = MemberFinderRequestStore.outgoingStateOf(row = row, now = now),
    )

    private fun otherSide(
        row: MemberFinderRequestRow,
        memberId: Uuid,
    ): Uuid = checkNotNull(if (row.fromMemberId == memberId) row.toMemberId else row.fromMemberId)

    /** An ACCEPTED request the caller takes part in, else `null` (unknown, foreign, not accepted: all the same). */
    private fun connectionRow(
        id: Uuid,
        memberId: Uuid,
    ): MemberFinderRequestRow? =
        MemberFinderRequestStore.find(id)?.takeIf {
            it.status == MemberFinderRequestStatus.ACCEPTED &&
                it.toMemberId != null &&
                (it.fromMemberId == memberId || it.toMemberId == memberId)
        }

    /** The connection as [memberId] sees it; `null` if the other side is no longer an ACTIVE, non-anonymized member. */
    private fun connectionOf(
        row: MemberFinderRequestRow,
        memberId: Uuid,
    ): MemberFinderConnectionDto? {
        val other =
            MemberTable.selectAll().where { MemberTable.id eq otherSide(row = row, memberId = memberId) }.singleOrNull() ?: return null
        if (other[MemberTable.anonymizedAt] != null || other[MemberTable.status] != MemberStatus.ACTIVE) return null
        val decided = checkNotNull(row.decidedAt)
        return MemberFinderConnectionDto(
            connectionId = row.id.toString(),
            otherDisplayName = other[MemberTable.displayName],
            purpose = row.purpose,
            connectedOn = OrganizationTimeZone.dateOf(decided),
            endsOn = OrganizationTimeZone.dateOf(decided.plusDays(MemberFinderRules.ACCEPTED_RETENTION_DAYS)),
        )
    }

    private fun parseId(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()

    private fun notFound() = NotFoundException(MemberFinderMessages.NOT_FOUND)

    private sealed interface RespondOutcome {
        data object Refused : RespondOutcome

        data class Done(
            val connection: MemberFinderConnectionDto?,
        ) : RespondOutcome
    }

    private companion object {
        const val MAX_OUTGOING_LISTED = 50
        const val MAX_INCOMING_LISTED = 50
        const val MAX_CONNECTIONS_LISTED = 200
        const val MAX_BLOCKS_LISTED = 500
    }
}
