package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.server.db.generated.LupaBbSyncRunTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.lupabb.ApiKeyTransport
import network.lapis.cloud.server.lupabb.LupaBbApplier
import network.lapis.cloud.server.lupabb.LupaBbClient
import network.lapis.cloud.server.lupabb.LupaBbCredentialStore
import network.lapis.cloud.server.lupabb.LupaBbCredentials
import network.lapis.cloud.server.lupabb.LupaBbErrorCode
import network.lapis.cloud.server.lupabb.LupaBbFetchException
import network.lapis.cloud.server.lupabb.LupaBbFetcher
import network.lapis.cloud.server.lupabb.LupaBbLinker
import network.lapis.cloud.server.lupabb.LupaBbPlan
import network.lapis.cloud.server.lupabb.LupaBbPlanner
import network.lapis.cloud.server.lupabb.LupaBbPreviewStore
import network.lapis.cloud.server.lupabb.LupaBbRecordParser
import network.lapis.cloud.server.lupabb.LupaBbRunGuard
import network.lapis.cloud.server.lupabb.LupaBbRunKind
import network.lapis.cloud.server.lupabb.LupaBbSyncRuntime
import network.lapis.cloud.server.lupabb.PlanReason
import network.lapis.cloud.server.lupabb.PlanRow
import network.lapis.cloud.server.lupabb.StoredLupaBbCredentials
import network.lapis.cloud.server.lupabb.filtered
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LupaBbApplyResultDto
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbConfigEvent
import network.lapis.cloud.shared.domain.LupaBbConfigSnapshot
import network.lapis.cloud.shared.domain.LupaBbCredentialsInput
import network.lapis.cloud.shared.domain.LupaBbLastRunDto
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbPreviewPageDto
import network.lapis.cloud.shared.domain.LupaBbPreviewSummaryDto
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.LupaBbSyncStatusDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ILupaBbSyncService
import network.lapis.cloud.shared.rpc.LupaBbAuthRejectedException
import network.lapis.cloud.shared.rpc.LupaBbCredentialsMissingException
import network.lapis.cloud.shared.rpc.LupaBbEncryptionKeyMissingException
import network.lapis.cloud.shared.rpc.LupaBbLimitExceededException
import network.lapis.cloud.shared.rpc.LupaBbPreviewExpiredException
import network.lapis.cloud.shared.rpc.LupaBbSyncBusyException
import network.lapis.cloud.shared.rpc.LupaBbUnavailableException
import network.lapis.cloud.shared.rpc.LupaBbUnexpectedResponseException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.226 -- see [ILupaBbSyncService] and `docs/features/lupabb-sync.adoc`. EVERY method starts with
 * `resolveCurrentMember(call).requireRole(AccountRole.ADMIN)` (pinned by `LupaBbSyncAccessTripwireTest`): the sync can end memberships.
 *
 * The HTTP calls to LupaBB run OUTSIDE every transaction (`Dispatchers.IO`), under the run guard, and every failure of the client is translated
 * into one of the typed exceptions of the interface WITHOUT a cause. Nothing personal and no credential is ever logged.
 */
internal class LupaBbSyncService(
    private val call: ApplicationCall,
    private val runtime: LupaBbSyncRuntime,
) : ILupaBbSyncService {
    override suspend fun getStatus(): LupaBbSyncStatusDto {
        resolveCurrentMember(call).requireRole(AccountRole.ADMIN)
        return transaction { statusInTx() }
    }

    override suspend fun verifyAndSaveCredentials(input: LupaBbCredentialsInput): LupaBbSyncStatusDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val box = runtime.secretBox ?: throw LupaBbEncryptionKeyMissingException()
        if (!runtime.credentialRateLimiter.checkAndRecord("lupabb:credentials:${current.memberId}")) throw RateLimitedException()
        val credentials = validated(input)
        if (LupaBbRunGuard.isRunning(ServerClock.now())) throw LupaBbSyncBusyException()
        val transport = withContext(Dispatchers.IO) { translating { probeTransport(credentials) } }
        val now = ServerClock.now()
        return transaction {
            val event =
                LupaBbCredentialStore.save(
                    box = box,
                    credentials = credentials,
                    transport = transport,
                    actor = current.memberId,
                    now = now,
                )
            auditConfig(actor = current, event = event, transport = transport, now = now)
            statusInTx()
        }
    }

    override suspend fun removeCredentials(): LupaBbSyncStatusDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val now = ServerClock.now()
        return transaction {
            val info = LupaBbCredentialStore.info()
            if (info != null) {
                if (!LupaBbCredentialStore.delete(LupaBbRunGuard.staleBefore(now))) throw LupaBbSyncBusyException()
                auditConfig(actor = current, event = LupaBbConfigEvent.REMOVED, transport = null, now = now)
            }
            statusInTx()
        }
    }

    override suspend fun loadPreview(): LupaBbPreviewSummaryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val box = runtime.secretBox ?: throw LupaBbEncryptionKeyMissingException()
        val stored = loadCredentials(box) ?: throw LupaBbCredentialsMissingException()
        val claim = LupaBbRunGuard.claim(kind = LupaBbRunKind.FETCH, now = ServerClock.now())
        try {
            val startedAt = ServerClock.now()
            val plan =
                withContext(Dispatchers.IO) {
                    translating {
                        val dataset =
                            LupaBbClient(
                                endpoint = runtime.endpoint,
                                credentials = stored.credentials,
                                transport = stored.transport,
                                limits = runtime.limits,
                            ).use { client ->
                                LupaBbFetcher.fetchAll(client = client, limits = runtime.limits)
                            }
                        LupaBbPlanner.plan(
                            dataset = dataset,
                            actor = current.memberId,
                            secretBox = box,
                            chapterEnforced = runtime.chapterEnforced(),
                            today = OrganizationTimeZone.today(),
                        )
                    }
                }
            val expiresAt = ServerClock.now().plusMinutes(LupaBbPreviewStore.TTL.inWholeMinutes)
            val token = runtime.previewStore.put(adminId = current.memberId, plan = plan, startedAt = startedAt, expiresAt = expiresAt)
            return summaryOf(token = token, expiresAt = expiresAt, rows = plan.rows, plan = plan)
        } finally {
            LupaBbRunGuard.release(claim)
        }
    }

    override suspend fun getPreviewPage(
        token: String,
        filter: LupaBbPreviewFilter,
        offset: Int,
        limit: Int,
    ): LupaBbPreviewPageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return runtime.previewStore.peek(token = token, adminId = current.memberId) {
            pageOf(rows = it.rows, filter = filter, offset = offset, limit = limit)
        }
            ?: throw LupaBbPreviewExpiredException()
    }

    override suspend fun applyPreview(token: String): LupaBbApplyResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (runtime.previewStore.peek(token = token, adminId = current.memberId) { true } != true) throw LupaBbPreviewExpiredException()
        val claim = LupaBbRunGuard.claim(kind = LupaBbRunKind.APPLY, now = ServerClock.now())
        try {
            val entry = runtime.previewStore.take(token = token, adminId = current.memberId) ?: throw LupaBbPreviewExpiredException()
            return withContext(
                Dispatchers.IO,
            ) { LupaBbApplier(chapterEnforced = runtime.chapterEnforced()).apply(entry = entry, actor = current) }
        } finally {
            LupaBbRunGuard.release(claim)
        }
    }

    override suspend fun linkMemberToLupaBb(
        token: String,
        personNumber: Long,
        memberId: String,
    ): LupaBbPreviewPageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val target = runCatching { Uuid.parse(memberId) }.getOrElse { throw NotFoundException("Member not found") }
        val row =
            runtime.previewStore.peek(token = token, adminId = current.memberId) { entry ->
                entry.rows.firstOrNull {
                    it.personNumber ==
                        personNumber
                }
            }
                ?: throw LupaBbPreviewExpiredException()
        if (row.category != LupaBbCategory.AMBIGUOUS) throw BadRequestException("The record is not ambiguous")
        val candidate = row.candidates.firstOrNull { it.memberId == target } ?: throw BadRequestException("Not a candidate of this record")
        val now = ServerClock.now()
        transaction { LupaBbLinker.link(row = row, candidate = candidate, actor = current, now = now) }
        val linked =
            row.copy(
                category = LupaBbCategory.SKIPPED,
                reasons = listOf(PlanReason(code = LupaBbRowReasonCode.LINKED_WAITING)),
                candidates = emptyList(),
            )
        return runtime.previewStore.peek(token = token, adminId = current.memberId) { entry ->
            runtime.previewStore.replaceRow(token = token, adminId = current.memberId, personNumber = personNumber, row = linked)
            pageOf(rows = entry.rows, filter = LupaBbPreviewFilter.ACTION_NEEDED, offset = 0, limit = PAGE_AFTER_LINK)
        } ?: throw LupaBbPreviewExpiredException()
    }

    // ---- helpers ----

    /** The stored credentials, or `null` when none are stored or they cannot be opened (another key, tampering): the ADMIN enters them again. */
    private fun loadCredentials(box: SecretBox): StoredLupaBbCredentials? =
        try {
            transaction { LupaBbCredentialStore.load(box) }
        } catch (_: SecretBoxException) {
            null
        }

    private fun statusInTx(): LupaBbSyncStatusDto {
        val info = LupaBbCredentialStore.info()
        val setByName =
            info?.let { i ->
                MemberTable
                    .selectAll()
                    .where { MemberTable.id eq i.setBy }
                    .singleOrNull()
                    ?.get(MemberTable.displayName)
            }
        val last =
            LupaBbSyncRunTable
                .selectAll()
                .orderBy(LupaBbSyncRunTable.finishedAt to SortOrder.DESC, LupaBbSyncRunTable.id to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        return LupaBbSyncStatusDto(
            credentialsSet = info != null,
            setAt = info?.setAt,
            setByName = setByName,
            encryptionAvailable = runtime.secretBox != null,
            lastRun =
                last?.let {
                    LupaBbLastRunDto(
                        finishedAt = it[LupaBbSyncRunTable.finishedAt],
                        created = it[LupaBbSyncRunTable.createdCount],
                        updated = it[LupaBbSyncRunTable.updatedCount],
                        unchanged = it[LupaBbSyncRunTable.unchangedCount],
                        skipped = it[LupaBbSyncRunTable.skippedCount],
                        ambiguous = it[LupaBbSyncRunTable.ambiguousCount],
                        errors = it[LupaBbSyncRunTable.errorCount],
                        missing = it[LupaBbSyncRunTable.missingCount],
                    )
                },
        )
    }

    private fun auditConfig(
        actor: CurrentMember,
        event: LupaBbConfigEvent,
        transport: ApiKeyTransport?,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.LUPABB_SYNC_CONFIG,
            entityId = LupaBbCredentialStore.CONFIG_ENTITY_ID,
            action = if (event == LupaBbConfigEvent.REMOVED) AuditAction.UPDATE else AuditAction.CREATE,
            before = null,
            after =
                Json.encodeToString(
                    LupaBbConfigSnapshot.serializer(),
                    LupaBbConfigSnapshot(event = event, apiKeyTransport = transport?.name),
                ),
            occurredAt = now,
        )
    }

    private fun validated(input: LupaBbCredentialsInput): LupaBbCredentials {
        val username = input.username.trim()
        val apiKey = input.apiKey.trim()
        val password = input.password
        val fields = listOf(username, password, apiKey)
        if (fields.any { it.isBlank() || it.length > MAX_CREDENTIAL_LENGTH || LupaBbRecordParser.hasControlCharacter(it) }) {
            throw BadRequestException("Invalid credentials")
        }
        return LupaBbCredentials(username = username, password = password, apiKey = apiKey)
    }

    /** One request with the key as a header; when LupaBB refuses, one with the key as query parameter (the way the old PZB sent it). */
    private suspend fun probeTransport(credentials: LupaBbCredentials): ApiKeyTransport {
        for (transport in ApiKeyTransport.entries) {
            try {
                LupaBbClient(
                    endpoint = runtime.endpoint,
                    credentials = credentials,
                    transport = transport,
                    limits = runtime.limits,
                ).use { it.probe() }
                return transport
            } catch (e: LupaBbFetchException) {
                if (e.code != LupaBbErrorCode.AUTH_REJECTED || transport == ApiKeyTransport.entries.last()) throw e
            }
        }
        throw LupaBbFetchException(LupaBbErrorCode.AUTH_REJECTED)
    }

    private fun summaryOf(
        token: String,
        expiresAt: LocalDateTime,
        rows: List<PlanRow>,
        plan: LupaBbPlan,
    ): LupaBbPreviewSummaryDto {
        val counts = plan.counts()
        val statusChanges = rows.filter { it.category == LupaBbCategory.CHANGED && it.statusTo != null }
        val severe =
            statusChanges.count {
                val from = it.statusFrom
                val to = it.statusTo
                from == MemberStatus.DECEASED || to == MemberStatus.DECEASED || (from == MemberStatus.ACTIVE && to != MemberStatus.ACTIVE)
            }
        return LupaBbPreviewSummaryDto(
            token = token,
            expiresAt = expiresAt,
            counts = counts,
            newCount = counts.getValue(LupaBbCategory.NEW),
            changedCount = counts.getValue(LupaBbCategory.CHANGED),
            statusChangeCount = statusChanges.size,
            severeStatusChangeCount = severe,
            notices = plan.notices,
        )
    }

    private fun pageOf(
        rows: List<PlanRow>,
        filter: LupaBbPreviewFilter,
        offset: Int,
        limit: Int,
    ): LupaBbPreviewPageDto {
        val selected = rows.filtered(filter)
        val from = offset.coerceAtLeast(0)
        val size = limit.coerceIn(1, MAX_PAGE_SIZE)
        return LupaBbPreviewPageDto(
            total = selected.size,
            offset = from,
            rows = selected.drop(from).take(size).map { it.toDto() },
            counts = LupaBbCategory.entries.associateWith { c -> rows.count { it.category == c } },
        )
    }

    private inline fun <T> translating(block: () -> T): T =
        try {
            block()
        } catch (e: LupaBbFetchException) {
            // NO cause: see LupaBbFetchException
            throw when (e.code) {
                LupaBbErrorCode.AUTH_REJECTED -> LupaBbAuthRejectedException()
                LupaBbErrorCode.UNAVAILABLE, LupaBbErrorCode.TIMEOUT -> LupaBbUnavailableException()
                LupaBbErrorCode.UNEXPECTED_RESPONSE -> LupaBbUnexpectedResponseException()
                LupaBbErrorCode.LIMIT_EXCEEDED -> LupaBbLimitExceededException()
            }
        }

    private fun LocalDateTime.plusMinutes(minutes: Long): LocalDateTime = toJavaLocalDateTime().plusMinutes(minutes).toKotlinLocalDateTime()

    private companion object {
        const val MAX_CREDENTIAL_LENGTH = 200
        const val MAX_PAGE_SIZE = 200
        const val PAGE_AFTER_LINK = 50
    }
}
