package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.auction.federation.AuctionFederationStore
import network.lapis.cloud.server.auction.federation.AuctionWithdrawals
import network.lapis.cloud.server.auction.federation.feed.AuctionTeaserFeedCounters
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.CarpoolPullCounters
import network.lapis.cloud.server.carpool.CarpoolReceivedStore
import network.lapis.cloud.server.carpool.CarpoolWithdrawalKey
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedBuilder
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedCounters
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.SigningKeyFingerprint
import network.lapis.cloud.server.partner.BlockRow
import network.lapis.cloud.server.partner.ForeignText
import network.lapis.cloud.server.partner.PartnerAddressParse
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerDiscoveryStore
import network.lapis.cloud.server.partner.PartnerDisplayEpoch
import network.lapis.cloud.server.partner.PartnerFeedFetcher
import network.lapis.cloud.server.partner.PartnerFetchOutcome
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.server.partner.PartnerInsertResult
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionFederationLimits
import network.lapis.cloud.shared.domain.AuctionFederationSettingsDto
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CarpoolFederationLimits
import network.lapis.cloud.shared.domain.CarpoolFederationSettingsDto
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import network.lapis.cloud.shared.domain.CarpoolPullStates
import network.lapis.cloud.shared.domain.CarpoolPullStatus
import network.lapis.cloud.shared.domain.DiscoveredPartnerServerDto
import network.lapis.cloud.shared.domain.PartnerAuditKind
import network.lapis.cloud.shared.domain.PartnerAuditSnapshot
import network.lapis.cloud.shared.domain.PartnerBlockDto
import network.lapis.cloud.shared.domain.PartnerBlockResultDto
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import network.lapis.cloud.shared.domain.PartnerProbeResultDto
import network.lapis.cloud.shared.domain.PartnerRecursionSettingsDto
import network.lapis.cloud.shared.domain.PartnerRecursionSummaryDto
import network.lapis.cloud.shared.domain.PartnerServerDto
import network.lapis.cloud.shared.domain.PartnerServerOverviewDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IPartnerServerService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.244 -- see [IPartnerServerService] and `docs/features/partner-servers.adoc`. EVERY method starts with
 * `resolveCurrentMember(call).requireRole(AccountRole.ADMIN)` (pinned by `PartnerServerAccessTripwireTest`): the list steers outgoing requests.
 *
 * The fetch of `probeAndSave` runs OUTSIDE every transaction, and so does the rate-limit check (a transaction block may be repeated by Exposed,
 * which would count twice). Failures are returned as enum CODES in [PartnerProbeResultDto] (Kilua transmits only the type of an exception, never
 * its message); nothing here returns an address, a body or a resolver detail. Every change is audited as the LAST step of its transaction
 * (hosts and patterns only -- never a name from a foreign feed).
 */
internal class PartnerServerService(
    private val call: ApplicationCall,
    private val probeRateLimiter: FederationInboxRateLimiter,
    private val fetcher: PartnerFeedFetcher = PartnerFeedFetcher(),
    private val publicBaseUrl: String = FederationConfig.publicBaseUrl,
    private val clock: () -> LocalDateTime = { ServerClock.now() },
    /** V1.9.312: the in-memory counters of the carpool teaser feed and whether its key is configured (both for the admin view only). */
    private val carpoolFeedCounters: CarpoolTeaserFeedCounters? = null,
    private val carpoolFeedKeyAvailable: Boolean = false,
    /** V1.9.322 (step c2): the same for the auction hint feed. */
    private val auctionFeedCounters: AuctionTeaserFeedCounters? = null,
    private val auctionFeedKeyAvailable: Boolean = false,
) : IPartnerServerService {
    override suspend fun list(): PartnerServerOverviewDto {
        resolveCurrentMember(call).requireRole(AccountRole.ADMIN)
        return transaction { overviewInTx() }
    }

    override suspend fun probeAndSave(
        address: String,
        displayName: String?,
    ): PartnerProbeResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val host =
            when (val parsed = PartnerHost.normalize(raw = address, ownHost = PartnerHost.ownHostOf(publicBaseUrl))) {
                is PartnerAddressParse.Rejected -> return PartnerProbeResultDto(
                    saved = false,
                    host = null,
                    eventCount = 0,
                    error = null,
                    addressRejection = parsed.reason,
                )
                is PartnerAddressParse.Ok -> parsed.host
            }
        val name = ForeignText.clean(raw = displayName, maxChars = 100)
        val early =
            transaction {
                when {
                    PartnerBlocklistPattern.isBlocked(
                        host = host,
                        patterns = PartnerServerStore.blockPatterns(),
                    ) -> PartnerFetchErrorCode.BLOCKED
                    PartnerServerStore.findPartnerByHost(host) != null -> PartnerFetchErrorCode.DUPLICATE
                    else -> null
                }
            }
        if (early != null) return PartnerProbeResultDto(saved = false, host = host, eventCount = 0, error = early, addressRejection = null)
        if (!probeRateLimiter.checkAndRecord("partner:probe:${current.memberId}")) throw RateLimitedException()
        val now = clock()
        val ownServerId = transaction { PartnerServerStore.ensureServerId() }
        val outcome =
            fetcher.fetch(host = host, etag = null, ownServerId = ownServerId, knownRemoteId = null, now = ServerClock.systemToInstant(now))
        val fetched =
            when (outcome) {
                is PartnerFetchOutcome.Failed -> return PartnerProbeResultDto(
                    saved = false,
                    host = host,
                    eventCount = 0,
                    error = outcome.error,
                    addressRejection = null,
                )
                is PartnerFetchOutcome.NotModified -> return PartnerProbeResultDto(
                    saved = false,
                    host = host,
                    eventCount = 0,
                    error = PartnerFetchErrorCode.INVALID_FEED,
                    addressRejection = null,
                )
                is PartnerFetchOutcome.Fetched -> outcome
            }
        val result =
            transaction {
                when (
                    val inserted =
                        PartnerServerStore.insertProbedPartner(
                            host = host,
                            displayName = name,
                            parsed = fetched.parsed,
                            etag = fetched.etag,
                            now = now,
                        )
                ) {
                    is PartnerInsertResult.Failed ->
                        PartnerProbeResultDto(
                            saved = false,
                            host = host,
                            eventCount = 0,
                            error = inserted.error,
                            addressRejection = null,
                        )
                    is PartnerInsertResult.Inserted -> {
                        audit(
                            actor = current,
                            entityType = AuditEntityType.PARTNER_SERVER,
                            entityId = inserted.id,
                            action = AuditAction.CREATE,
                            kind = PartnerAuditKind.ADDED,
                            subject = host,
                            now = now,
                        )
                        PartnerProbeResultDto(
                            saved = true,
                            host = host,
                            eventCount = fetched.parsed.events.size,
                            error = null,
                            addressRejection = null,
                        )
                    }
                }
            }
        // V1.9.258 -- a new partner appears on the guest sign-in page at once.
        if (result.saved) PartnerDisplayEpoch.bump()
        return result
    }

    override suspend fun setActive(
        id: String,
        active: Boolean,
    ) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = parseId(id)
        val now = clock()
        transaction {
            val row = PartnerServerStore.setActive(id = uuid, active = active, now = now) ?: throw NotFoundException("Partner not found")
            if (row.active && !active) {
                // V1.9.318: a deactivated partner is no source any more: its copies go NOW (reported as withdrawn when they could have been handed
                // on), not at the next stale sweep, and its ETag is forgotten.
                CarpoolReceivedStore.dropFromSource(sourceId = uuid, now = now)
                PartnerServerStore.clearCarpoolPullEtag(uuid)
            } else if (!row.active && active && row.carpoolSharing) {
                PartnerServerStore.resetCarpoolPull(id = uuid, now = now)
            }
            if (row.active != active) {
                val kind = if (active) PartnerAuditKind.ACTIVATED else PartnerAuditKind.DEACTIVATED
                audit(
                    actor = current,
                    entityType = AuditEntityType.PARTNER_SERVER,
                    entityId = uuid,
                    action = AuditAction.UPDATE,
                    kind = kind,
                    subject = row.host,
                    now = now,
                )
            }
        }
        PartnerDisplayEpoch.bump()
    }

    override suspend fun remove(id: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = parseId(id)
        val now = clock()
        transaction {
            val row = PartnerServerStore.remove(uuid) ?: throw NotFoundException("Partner not found")
            audit(
                actor = current,
                entityType = AuditEntityType.PARTNER_SERVER,
                entityId = uuid,
                action = AuditAction.VOID,
                kind = PartnerAuditKind.REMOVED,
                subject = row.host,
                now = now,
            )
        }
        PartnerDisplayEpoch.bump()
    }

    override suspend fun listBlocklist(): List<PartnerBlockDto> {
        resolveCurrentMember(call).requireRole(AccountRole.ADMIN)
        return transaction { PartnerServerStore.listBlocklist().map { it.toDto() } }
    }

    override suspend fun addBlock(pattern: String): PartnerBlockResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val canonical = PartnerBlocklistPattern.parse(pattern) ?: return PartnerBlockResultDto(block = null, invalid = true)
        val now = clock()
        val result =
            transaction {
                val (block, created) = PartnerServerStore.addBlock(pattern = canonical, now = now)
                if (created) {
                    audit(
                        actor = current,
                        entityType = AuditEntityType.PARTNER_BLOCKLIST,
                        entityId = block.id,
                        action = AuditAction.CREATE,
                        kind = PartnerAuditKind.BLOCK_ADDED,
                        subject = canonical,
                        now = now,
                    )
                }
                PartnerBlockResultDto(block = block.toDto(), invalid = false)
            }
        // After the commit: the memoised public page is rebuilt from the state that includes the block.
        PartnerDisplayEpoch.bump()
        return result
    }

    override suspend fun removeBlock(id: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = parseId(id)
        val now = clock()
        transaction {
            val row = PartnerServerStore.removeBlock(uuid) ?: throw NotFoundException("Block not found")
            audit(
                actor = current,
                entityType = AuditEntityType.PARTNER_BLOCKLIST,
                entityId = uuid,
                action = AuditAction.VOID,
                kind = PartnerAuditKind.BLOCK_REMOVED,
                subject = row.pattern,
                now = now,
            )
        }
        PartnerDisplayEpoch.bump()
    }

    override suspend fun setPartnerListHidden(hidden: Boolean) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val now = clock()
        val changed =
            transaction {
                val differs = PartnerServerStore.isPartnerListHidden() != hidden
                if (differs) {
                    PartnerServerStore.setPartnerListHidden(hidden)
                    val kind = if (hidden) PartnerAuditKind.LIST_HIDDEN else PartnerAuditKind.LIST_SHOWN
                    audit(
                        actor = current,
                        entityType = AuditEntityType.PARTNER_SERVER,
                        entityId = ORGANIZATION_SETTINGS_ID,
                        action = AuditAction.UPDATE,
                        kind = kind,
                        subject = null,
                        now = now,
                    )
                }
                differs
            }
        // V1.9.258 -- after the commit: the memoised guest sign-in partner list is rebuilt from the new state.
        if (changed) PartnerDisplayEpoch.bump()
    }

    override suspend fun getPartnerRecursionSettings(): PartnerRecursionSettingsDto {
        resolveCurrentMember(call).requireRole(AccountRole.ADMIN)
        return transaction {
            val settings = PartnerDiscoveryStore.recursionSettings()
            PartnerRecursionSettingsDto(
                enabled = settings.enabled,
                depth = settings.depth,
                lastRunAt = settings.lastRunAt,
                summary =
                    settings.summaryJson?.let {
                        runCatching { SUMMARY_JSON.decodeFromString(PartnerRecursionSummaryDto.serializer(), it) }.getOrNull()
                    },
            )
        }
    }

    override suspend fun setPartnerRecursionSettings(
        enabled: Boolean,
        depth: Int,
    ) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (depth !in PARTNER_RECURSION_MIN_DEPTH..PARTNER_RECURSION_MAX_DEPTH) throw BadRequestException("Invalid depth")
        val now = clock()
        transaction {
            val before = PartnerDiscoveryStore.recursionSettings()
            PartnerDiscoveryStore.setRecursionSettings(enabled = enabled, depth = depth)
            if (before.enabled != enabled) {
                val kind = if (enabled) PartnerAuditKind.RECURSION_ENABLED else PartnerAuditKind.RECURSION_DISABLED
                audit(
                    actor = current,
                    entityType = AuditEntityType.PARTNER_SERVER,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    kind = kind,
                    subject = depth.toString(),
                    now = now,
                )
            }
            if (before.depth != depth) {
                audit(
                    actor = current,
                    entityType = AuditEntityType.PARTNER_SERVER,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    kind = PartnerAuditKind.RECURSION_DEPTH_CHANGED,
                    subject = depth.toString(),
                    now = now,
                )
            }
        }
        PartnerDisplayEpoch.bump()
    }

    override suspend fun listDiscoveredPartnerServers(): List<DiscoveredPartnerServerDto> {
        resolveCurrentMember(call).requireRole(AccountRole.ADMIN)
        return transaction {
            val roots = PartnerServerStore.listPartners().associateBy { it.id }
            val held = PartnerDiscoveryStore.heldHosts()
            PartnerDiscoveryStore.listForAdmin().mapNotNull { row ->
                val root = roots[row.rootPartnerId] ?: return@mapNotNull null
                DiscoveredPartnerServerDto(
                    host = row.host,
                    name = row.remoteName,
                    hops = row.hops,
                    viaChain = row.via,
                    rootPartnerId = root.id.toString(),
                    rootName = root.displayName ?: root.remoteName ?: root.host,
                    eventCount = row.eventCount,
                    lastStatus = row.lastStatus,
                    lastError = row.lastError?.let { code -> PartnerFetchErrorCode.entries.firstOrNull { it.name == code } },
                    lastFetchAt = row.lastFetchAt,
                    signingKeyHeld = row.host in held,
                )
            }
        }
    }

    // ---- V1.9.311 "Mitfahrzentrale-Foederation" W1: switches only, nothing is transferred. ----

    override suspend fun getCarpoolFederationSettings(): CarpoolFederationSettingsDto {
        resolveCurrentMember(call).requireRole(AccountRole.ADMIN)
        return transaction {
            val s = CarpoolFederationStore.settings()
            val c = carpoolFeedCounters?.snapshot()
            CarpoolFederationSettingsDto(
                enabled = s.enabled,
                hopLimit = s.hopLimit,
                feedKeyAvailable = carpoolFeedKeyAvailable,
                feedRefusedSinceStart = c?.refusedTotal ?: 0,
                feedRateLimitedSinceStart = c?.rateLimited ?: 0,
                carpoolWithdrawalsActive = CarpoolFederationStore.countLiveWithdrawals(clock()),
                carpoolReceivedHintsTotal = CarpoolReceivedStore.countTotal(clock()),
                carpoolReceivedRejectedSinceStart = CarpoolPullCounters.rejected.get(),
                carpoolReceivedUnknownOriginKeySinceStart = CarpoolPullCounters.unknownOriginKey.get(),
                serverStartedAt = CarpoolPullCounters.startedAt,
                feedServedSinceStart = c?.served ?: 0,
                feedNotModifiedSinceStart = c?.notModified ?: 0,
                carpoolReceivedStoredRows = CarpoolReceivedStore.countStoredRows(),
                carpoolReceivedBlocksActive = CarpoolReceivedStore.countBlocksActive(clock()),
                carpoolReceivedAcceptedSinceStart = CarpoolPullCounters.accepted.get(),
                carpoolReceivedExpiredDeletedSinceStart = CarpoolPullCounters.expiredDeleted.get(),
                // Keys are the enum names, values are plain counts: no host, no id, no content.
                carpoolReceivedRejectedByReason = CarpoolPullCounters.rejectedByReason().mapKeys { it.key.name },
                carpoolPullBudgetDeferredSinceStart = CarpoolPullCounters.budgetDeferred.get(),
                carpoolRelayTombstonesSkippedSinceStart = CarpoolPullCounters.relayTombstonesSkipped.get(),
            )
        }
    }

    override suspend fun setCarpoolFederationSettings(
        enabled: Boolean,
        hopLimit: Int,
    ) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (hopLimit !in CarpoolFederationLimits.MIN_HOPS..CarpoolFederationLimits.MAX_HOPS) throw BadRequestException("Invalid hop limit")
        val now = clock()
        transaction {
            val before = CarpoolFederationStore.setSettings(enabled = enabled, hopLimit = hopLimit)
            if (before.enabled && !enabled) {
                // V1.9.313: switched off: every hint that was shareable is reported as withdrawn for every partner. The consents stay.
                val targets =
                    CarpoolTeaserFeedBuilder.shareableTargets(
                        today = OrganizationTimeZone.today(),
                        scanLimit = CarpoolTeaserFeedBuilder.SCAN_LIMIT,
                    )
                CarpoolFederationStore.withdraw(
                    targets = targets,
                    partnerId = null,
                    ids = CarpoolWithdrawalKey.current(),
                    now = now,
                    clearOptIn = false,
                    requireServed = false,
                )
                // V1.9.314: and every hint received from a partner goes too (the forwardable ones reported as withdrawn), the ETags are forgotten.
                CarpoolReceivedStore.withdrawAllForSwitchOff(now)
            }
            if (before.enabled != enabled) {
                val kind = if (enabled) PartnerAuditKind.CARPOOL_EXCHANGE_ENABLED else PartnerAuditKind.CARPOOL_EXCHANGE_DISABLED
                audit(
                    actor = current,
                    entityType = AuditEntityType.PARTNER_SERVER,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    kind = kind,
                    subject = null,
                    now = now,
                )
            }
            if (before.hopLimit != hopLimit) {
                audit(
                    actor = current,
                    entityType = AuditEntityType.PARTNER_SERVER,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    kind = PartnerAuditKind.CARPOOL_HOP_LIMIT_CHANGED,
                    subject = hopLimit.toString(),
                    now = now,
                )
            }
        }
    }

    override suspend fun setPartnerCarpoolSharing(
        partnerId: String,
        enabled: Boolean,
    ) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = runCatching { Uuid.parse(partnerId) }.getOrElse { throw NotFoundException("Partner not found") }
        val now = clock()
        transaction {
            val row = PartnerServerStore.setCarpoolSharing(id = uuid, enabled = enabled) ?: throw NotFoundException("Partner not found")
            if (row.carpoolSharing && !enabled) {
                // V1.9.313: switched off for this partner: the shareable hints are reported as withdrawn for THIS partner only.
                val targets =
                    CarpoolTeaserFeedBuilder.shareableTargets(
                        today = OrganizationTimeZone.today(),
                        scanLimit = CarpoolTeaserFeedBuilder.SCAN_LIMIT,
                    )
                CarpoolFederationStore.withdraw(
                    targets = targets,
                    partnerId = uuid,
                    ids = CarpoolWithdrawalKey.current(),
                    now = now,
                    clearOptIn = false,
                    requireServed = false,
                )
                // V1.9.314: (a) what this partner may have received from the copies we hold is reported as withdrawn for THIS partner; (b) the copies
                // we hold FROM this partner go (the forwardable ones reported as withdrawn for everybody) and its ETag is forgotten.
                CarpoolFederationStore.withdrawRelayed(
                    targets = CarpoolReceivedStore.scopedTombstoneTargetsFor(row),
                    partnerId = uuid,
                    now = now,
                    requireServedSince = null,
                    maxRelayRows = CarpoolReceivedStore.MAX_RELAY_TOMBSTONES_TOTAL,
                )
                CarpoolReceivedStore.dropFromSource(sourceId = uuid, now = now)
                PartnerServerStore.clearCarpoolPullEtag(uuid)
            } else if (!row.carpoolSharing && enabled) {
                CarpoolFederationStore.forgetPartnerTombstones(uuid)
                // V1.9.318: the way to "try again now": sharing off and on again starts with a clean slate (no back-off, no ETag).
                PartnerServerStore.resetCarpoolPull(id = uuid, now = now)
            }
            if (row.carpoolSharing != enabled) {
                val kind = if (enabled) PartnerAuditKind.PARTNER_CARPOOL_SHARING_ON else PartnerAuditKind.PARTNER_CARPOOL_SHARING_OFF
                audit(
                    actor = current,
                    entityType = AuditEntityType.PARTNER_SERVER,
                    entityId = uuid,
                    action = AuditAction.UPDATE,
                    kind = kind,
                    subject = row.host,
                    now = now,
                )
            }
        }
    }

    override suspend fun acceptPartnerSigningKey(
        partnerId: String,
        pendingFingerprint: String,
    ) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = runCatching { Uuid.parse(partnerId) }.getOrElse { throw NotFoundException("Partner not found") }
        if (pendingFingerprint.length != FINGERPRINT_HEX_LENGTH) throw BadRequestException("Invalid fingerprint")
        val now = clock()
        transaction {
            when (val result = PartnerServerStore.acceptPendingSigningKey(id = uuid, expectedFingerprint = pendingFingerprint, now = now)) {
                PartnerServerStore.AcceptKeyResult.NoSuchPartner -> throw NotFoundException("Partner not found")
                PartnerServerStore.AcceptKeyResult.Mismatch ->
                    throw BadRequestException("No pending signing key with this fingerprint")
                is PartnerServerStore.AcceptKeyResult.Accepted ->
                    // Fingerprints only (never the key), so the entry shows WHO accepted WHICH key over WHICH one.
                    audit(
                        actor = current,
                        entityType = AuditEntityType.PARTNER_SERVER,
                        entityId = uuid,
                        action = AuditAction.UPDATE,
                        kind = PartnerAuditKind.PARTNER_SIGNING_KEY_ACCEPTED,
                        subject = "${result.host} ${result.oldFingerprint ?: "-"} -> ${result.newFingerprint}",
                        now = now,
                    )
            }
        }
    }

    override suspend fun releaseDiscoveredSigningKeyHold(host: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val normalized = host.trim().lowercase().trimEnd('.')
        if (normalized.isEmpty() || normalized.length > MAX_HOST_LENGTH) throw BadRequestException("Invalid host")
        val now = clock()
        transaction {
            if (!PartnerDiscoveryStore.releaseSigningKeyHold(normalized)) throw NotFoundException("No signing key hold for this host")
            audit(
                actor = current,
                entityType = AuditEntityType.PARTNER_SERVER,
                entityId = ORGANIZATION_SETTINGS_ID,
                action = AuditAction.UPDATE,
                kind = PartnerAuditKind.DISCOVERED_SIGNING_KEY_HOLD_RELEASED,
                subject = normalized,
                now = now,
            )
        }
    }

    private fun overviewInTx(): PartnerServerOverviewDto {
        val blocks = PartnerServerStore.listBlocklist()
        val patterns = blocks.map { it.pattern }
        val counts = PartnerDiscoveryStore.countsByRoot()
        val withdrawals = CarpoolFederationStore.countLiveWithdrawalsByPartner(clock())
        val received = CarpoolReceivedStore.countBySource()
        val blocksByReporter = CarpoolReceivedStore.countBlocksByReporter(clock())
        return PartnerServerOverviewDto(
            partners =
                PartnerServerStore.listPartners().map {
                    val (servers, events) = counts[it.id] ?: (0 to 0)
                    it
                        .toDto(blocked = PartnerBlocklistPattern.isBlocked(host = it.host, patterns = patterns))
                        .copy(
                            discoveredServerCount = servers,
                            discoveredEventCount = events,
                            carpoolWithdrawalsScoped = withdrawals[it.id] ?: 0,
                            carpoolReceivedHints = received[it.id] ?: 0,
                            carpoolReceivedBlocks = blocksByReporter[it.id] ?: 0,
                        )
                },
            blocklist = blocks.map { it.toDto() },
            partnerListHidden = PartnerServerStore.isPartnerListHidden(),
        )
    }

    // ---- V1.9.322 (Fiat wave c, step c2): auction hints. Only the ORIGIN side exists so far (the pull and the received hints come with c3). ----

    override suspend fun getAuctionFederationSettings(): AuctionFederationSettingsDto {
        resolveCurrentMember(call).requireRole(AccountRole.ADMIN)
        return transaction {
            val s = AuctionFederationStore.settings()
            val c = auctionFeedCounters?.snapshot()
            val now = clock()
            AuctionFederationSettingsDto(
                enabled = s.enabled,
                hopLimit = s.hopLimit,
                feedKeyAvailable = auctionFeedKeyAvailable,
                feedServedSinceStart = c?.served ?: 0,
                feedNotModifiedSinceStart = c?.notModified ?: 0,
                feedRefusedSinceStart = c?.refusedTotal ?: 0,
                feedRateLimitedSinceStart = c?.rateLimited ?: 0,
                withdrawalsActive = AuctionFederationStore.countLiveWithdrawals(now),
                sharedActive = AuctionFederationStore.countSharedActive(now),
            )
        }
    }

    override suspend fun setAuctionFederationSettings(
        enabled: Boolean,
        hopLimit: Int,
    ) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (hopLimit !in AuctionFederationLimits.MIN_HOPS..AuctionFederationLimits.MAX_HOPS) throw BadRequestException("Invalid hop limit")
        val now = clock()
        transaction {
            val before = AuctionFederationStore.setSettings(enabled = enabled, hopLimit = hopLimit)
            // Switched off: every shared hint is reported as withdrawn for every partner. The consents stay.
            if (before.enabled && !enabled) AuctionWithdrawals.onInstanceOff(now)
            if (before.enabled != enabled) {
                val kind = if (enabled) PartnerAuditKind.AUCTION_EXCHANGE_ENABLED else PartnerAuditKind.AUCTION_EXCHANGE_DISABLED
                audit(
                    actor = current,
                    entityType = AuditEntityType.PARTNER_SERVER,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    kind = kind,
                    subject = null,
                    now = now,
                )
            }
            if (before.hopLimit != hopLimit) {
                audit(
                    actor = current,
                    entityType = AuditEntityType.PARTNER_SERVER,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    kind = PartnerAuditKind.AUCTION_HOP_LIMIT_CHANGED,
                    subject = hopLimit.toString(),
                    now = now,
                )
            }
        }
    }

    override suspend fun setPartnerAuctionSharing(
        partnerId: String,
        enabled: Boolean,
    ) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = runCatching { Uuid.parse(partnerId) }.getOrElse { throw NotFoundException("Partner not found") }
        val now = clock()
        transaction {
            val row = PartnerServerStore.setAuctionSharing(id = uuid, enabled = enabled) ?: throw NotFoundException("Partner not found")
            if (row.auctionSharing && !enabled) {
                // Switched off for this partner: the shared hints are reported as withdrawn for THIS partner only.
                AuctionWithdrawals.onPartnerOff(partnerId = uuid, now = now)
            } else if (!row.auctionSharing && enabled) {
                AuctionFederationStore.forgetPartnerTombstones(uuid)
            }
            if (row.auctionSharing != enabled) {
                val kind = if (enabled) PartnerAuditKind.PARTNER_AUCTION_SHARING_ON else PartnerAuditKind.PARTNER_AUCTION_SHARING_OFF
                audit(
                    actor = current,
                    entityType = AuditEntityType.PARTNER_SERVER,
                    entityId = uuid,
                    action = AuditAction.UPDATE,
                    kind = kind,
                    subject = row.host,
                    now = now,
                )
            }
        }
    }

    private fun audit(
        actor: CurrentMember,
        entityType: AuditEntityType,
        entityId: Uuid,
        action: AuditAction,
        kind: PartnerAuditKind,
        subject: String?,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = entityType,
            entityId = entityId,
            action = action,
            after = Json.encodeToString(PartnerAuditSnapshot.serializer(), PartnerAuditSnapshot(kind = kind, subject = subject)),
            occurredAt = now,
        )
    }

    private fun parseId(raw: String): Uuid = runCatching { Uuid.parse(raw) }.getOrElse { throw BadRequestException("Invalid id") }
}

private val SUMMARY_JSON = Json { ignoreUnknownKeys = true }
private const val FINGERPRINT_HEX_LENGTH = 64
private const val MAX_HOST_LENGTH = 253
private const val PARTNER_RECURSION_MIN_DEPTH = 1
private const val PARTNER_RECURSION_MAX_DEPTH = 3

private fun BlockRow.toDto() = PartnerBlockDto(id = id.toString(), pattern = pattern)

private fun PartnerRow.toDto(blocked: Boolean) =
    PartnerServerDto(
        id = id.toString(),
        host = host,
        displayName = displayName,
        remoteName = remoteName,
        active = active,
        blocked = blocked,
        status = lastStatus,
        lastFetchAt = lastFetchAt,
        nextFetchAt = if (active && !blocked) nextFetchAt else null,
        consecutiveFailures = consecutiveFailures,
        eventCount = if (blocked) 0 else eventCount,
        lastError = lastError?.let { code -> PartnerFetchErrorCode.entries.firstOrNull { it.name == code } },
        carpoolSharing = carpoolSharing,
        carpoolKeyPinned = signingPublicKeyPem != null,
        auctionSharing = auctionSharing,
        auctionFeedServedAt = auctionFeedServedAt,
        auctionFeedServedCount = auctionFeedServedCount,
        auctionFeedLastItemCount = auctionFeedLastItems,
        carpoolFeedServedAt = carpoolFeedServedAt,
        carpoolFeedServedCount = carpoolFeedServedCount,
        carpoolFeedLastItemCount = carpoolFeedLastItems,
        carpoolPullAt = carpoolPullAt,
        carpoolPullStatus = carpoolPullStatus?.let { s -> CarpoolPullStatus.entries.firstOrNull { it.name == s } },
        carpoolPullError = carpoolPullError?.let { c -> CarpoolPullErrorCode.entries.firstOrNull { it.name == c } },
        carpoolPullFailures = carpoolPullFailures,
        carpoolPullLastRejected = carpoolPullLastRejected,
        carpoolPullLastAccepted = carpoolPullLastAccepted,
        carpoolPullOkAt = carpoolPullOkAt,
        carpoolPullNextAt = if (carpoolSharing && active) carpoolPullNextAt else null,
        carpoolPullState = if (carpoolSharing && active) CarpoolPullStates.of(carpoolPullFailures) else null,
        signingKeyFingerprint = signingPublicKeyPem?.let { SigningKeyFingerprint.of(it) },
        signingKeyPendingFingerprint = signingKeyPendingPem?.let { SigningKeyFingerprint.of(it) },
        signingKeyChangedAt = signingKeyChangedAt,
    )
