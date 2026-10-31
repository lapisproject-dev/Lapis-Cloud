package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partner.BlockRow
import network.lapis.cloud.server.partner.ForeignText
import network.lapis.cloud.server.partner.PartnerAddressParse
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerFeedFetcher
import network.lapis.cloud.server.partner.PartnerFetchOutcome
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.server.partner.PartnerInsertResult
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartnerAuditKind
import network.lapis.cloud.shared.domain.PartnerAuditSnapshot
import network.lapis.cloud.shared.domain.PartnerBlockDto
import network.lapis.cloud.shared.domain.PartnerBlockResultDto
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import network.lapis.cloud.shared.domain.PartnerProbeResultDto
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
        return transaction {
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
        return transaction {
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
    }

    override suspend fun setPartnerListHidden(hidden: Boolean) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val now = clock()
        transaction {
            if (PartnerServerStore.isPartnerListHidden() != hidden) {
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
        }
    }

    private fun overviewInTx(): PartnerServerOverviewDto {
        val blocks = PartnerServerStore.listBlocklist()
        val patterns = blocks.map { it.pattern }
        return PartnerServerOverviewDto(
            partners =
                PartnerServerStore.listPartners().map {
                    it.toDto(blocked = PartnerBlocklistPattern.isBlocked(host = it.host, patterns = patterns))
                },
            blocklist = blocks.map { it.toDto() },
            partnerListHidden = PartnerServerStore.isPartnerListHidden(),
        )
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
    )
