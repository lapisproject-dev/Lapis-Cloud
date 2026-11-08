package network.lapis.cloud.server.partg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partg.report.PartgReportDocument
import network.lapis.cloud.server.partg.report.PartgReportDocuments
import network.lapis.cloud.server.partg.report.PartgVersionDiff
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PartgDeadlineDto
import network.lapis.cloud.shared.domain.PartgDeadlineRules
import network.lapis.cloud.shared.domain.PartgDraftDeviation
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgVersionDeletionCandidateDto
import network.lapis.cloud.shared.domain.PartgVersionDeletionPreviewDto
import network.lapis.cloud.shared.domain.PartgVersionDiffDto
import network.lapis.cloud.shared.domain.PartgVersionDto
import network.lapis.cloud.shared.domain.PartgVersionOverviewDto
import network.lapis.cloud.shared.domain.PartgVersionRetention
import network.lapis.cloud.shared.domain.PartgVersionRules
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import network.lapis.cloud.shared.rpc.PartyReportVersionIntegrityException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.291 (R6) -- the work behind the eight version methods of `PartyReportService`. The service calls the federal gate FIRST and then only
 * delegates here (the gate must be the first statement of each method; `PartyReportAccessTripwireTest` pins that). The limiters are the shared
 * instances of `Application.module`.
 */
internal class PartgVersionOperations(
    private val writeRateLimiter: FederationInboxRateLimiter,
    private val freezeRateLimiter: FederationInboxRateLimiter,
    private val readRateLimiter: FederationInboxRateLimiter,
    private val concurrency: Semaphore,
) {
    suspend fun overview(
        current: CurrentMember,
        year: Int,
    ): PartgVersionOverviewDto {
        PartgYearRules.requireValid(year)
        if (!readRateLimiter.checkAndRecord("partg:versionread:${current.memberId}")) throw RateLimitedException()
        val deviation = deviation(year)
        val today = OrganizationTimeZone.today()
        return transaction { PartgVersionView.overview(year = year, today = today, deviation = deviation) }
    }

    /**
     * Does the live state still equal the newest kept whole-party version? Bounded: only when a permit is free, else `NOT_CHECKED` (never an
     * exception, the overview must still load). An older format is `UNKNOWN_FORMAT`, never "differs".
     */
    private suspend fun deviation(year: Int): PartgDraftDeviation {
        val newest = transaction { PartgVersionView.newestWholeParty(year) } ?: return PartgDraftDeviation.NOT_CHECKED
        if (newest.formatVersion != PartgVersionFormat.CURRENT) return PartgDraftDeviation.UNKNOWN_FORMAT
        if (!concurrency.tryAcquire()) return PartgDraftDeviation.NOT_CHECKED
        try {
            val hash =
                try {
                    withContext(Dispatchers.Default) {
                        val snapshot = PartgReportSnapshotReader.read(listOf(year, year - 1))
                        PartgVersionFormat.hash(
                            PartgVersionFormat.encode(
                                PartgReportDocuments.build(snapshot = snapshot, year = year, scope = PartgReportScope.All),
                            ),
                        )
                    }
                } catch (e: PartyReportRevisionConflictException) {
                    return PartgDraftDeviation.NOT_CHECKED
                }
            return if (hash == newest.contentHash) PartgDraftDeviation.SAME else PartgDraftDeviation.DIFFERS
        } finally {
            concurrency.release()
        }
    }

    suspend fun freeze(
        current: CurrentMember,
        year: Int,
        scopeKey: String,
        comment: String?,
    ): PartgVersionDto {
        PartgYearRules.requireValid(year)
        val scope = PartgReportScope.parse(scopeKey) ?: throw PartyReportInvalidInputException("Invalid scope")
        val checked = PartgVersionRules.checkComment(comment)
        if (!checked.ok) throw PartyReportInvalidInputException("Invalid comment")
        if (!freezeRateLimiter.checkAndRecord("partg:version:${current.memberId}")) throw RateLimitedException()
        if (!concurrency.tryAcquire()) throw RateLimitedException()
        try {
            val id =
                withContext(Dispatchers.Default) {
                    PartgVersionWriter.freeze(
                        current = current,
                        year = year,
                        scope = scope,
                        comment = checked.value,
                        now = ServerClock.now(),
                    )
                }
            return transaction { PartgVersionView.dto(id) }
        } finally {
            concurrency.release()
        }
    }

    suspend fun compare(
        current: CurrentMember,
        versionIdA: String,
        versionIdB: String,
    ): PartgVersionDiffDto {
        val idA = parseId(versionIdA)
        val idB = parseId(versionIdB)
        if (!readRateLimiter.checkAndRecord("partg:versionread:${current.memberId}")) throw RateLimitedException()
        if (!concurrency.tryAcquire()) throw RateLimitedException()
        try {
            val a = loadVerified(idA)
            val b = loadVerified(idB)
            return withContext(Dispatchers.Default) {
                PartgVersionDiff.compute(
                    a = a.document,
                    b = b.document,
                    versionIdA = idA.toString(),
                    versionIdB = idB.toString(),
                    versionNumberA = a.versionNumber,
                    versionNumberB = b.versionNumber,
                )
            }
        } finally {
            concurrency.release()
        }
    }

    private class Loaded(
        val document: PartgReportDocument,
        val versionNumber: Int,
    )

    /** Reads a version, checks the checksum of the stored text and decodes it. 404 for an unknown id, integrity error for a damaged one. */
    private suspend fun loadVerified(id: Uuid): Loaded {
        val stored =
            transaction {
                val meta = PartgVersionStore.versionMeta(id)
                val content = meta?.let { PartgVersionStore.content(id) }
                if (meta == null || content == null) null else Triple(meta, content.first, content.second)
            } ?: throw NotFoundException("Version not found")
        val (meta, json, storedHash) = stored
        if (PartgVersionFormat.hash(json) != storedHash || storedHash != meta.contentHash) throw PartyReportVersionIntegrityException()
        return withContext(Dispatchers.Default) {
            Loaded(
                document = PartgVersionFormat.decode(text = json, formatVersion = meta.formatVersion),
                versionNumber = meta.versionNumber,
            )
        }
    }

    fun declare(
        current: CurrentMember,
        versionId: String,
        expectedHash: String,
        reviewed: Boolean,
    ): PartgVersionDto {
        val id = parseId(versionId)
        checkWriteRate(current)
        PartgVersionWriter.declare(
            current = current,
            versionId = id,
            expectedHash = expectedHash,
            reviewed = reviewed,
            now = ServerClock.now(),
        )
        return transaction { PartgVersionView.dto(id) }
    }

    fun finalize(
        current: CurrentMember,
        versionId: String,
        typedVersionNumber: Int,
        expectedHash: String,
    ): PartgVersionDto {
        val id = parseId(versionId)
        checkWriteRate(current)
        PartgVersionWriter.finalize(
            current = current,
            versionId = id,
            typedNumber = typedVersionNumber,
            expectedHash = expectedHash,
            now = ServerClock.now(),
        )
        return transaction { PartgVersionView.dto(id) }
    }

    fun confirmDeadline(
        current: CurrentMember,
        year: Int,
        deadline: String,
    ): PartgDeadlineDto {
        PartgYearRules.requireValid(year)
        val date = PartgDeadlineRules.parseIso(deadline) ?: throw PartyReportInvalidInputException("Invalid date")
        checkWriteRate(current)
        PartgVersionWriter.confirmDeadline(current = current, year = year, deadline = date, now = ServerClock.now())
        return transaction {
            PartgVersionView.deadline(year = year, today = OrganizationTimeZone.today(), status = PartgVersionView.yearStatus(year))
        }
    }

    fun previewDeletion(
        current: CurrentMember,
        olderThanYears: Int,
    ): PartgVersionDeletionPreviewDto {
        current.requireRole(AccountRole.ADMIN)
        if (!PartgVersionRetention.validYears(olderThanYears)) throw PartyReportInvalidInputException("Retention out of range")
        if (!readRateLimiter.checkAndRecord("partg:versionread:${current.memberId}")) throw RateLimitedException()
        val today = OrganizationTimeZone.today()
        val (candidates, truncated) = transaction { PartgVersionWriter.deletionCandidates(olderThanYears = olderThanYears, today = today) }
        return PartgVersionDeletionPreviewDto(
            olderThanYears = olderThanYears,
            candidates =
                candidates.map {
                    PartgVersionDeletionCandidateDto(
                        versionId = it.id.toString(),
                        year = it.year,
                        scopeKey = it.scopeKey,
                        versionNumber = it.versionNumber,
                        frozenAt = it.frozenAt,
                        finalized = it.finalizedAt != null,
                    )
                },
            truncated = truncated,
        )
    }

    fun deleteOld(
        current: CurrentMember,
        olderThanYears: Int,
        expectedVersionIds: List<String>,
    ): Int {
        current.requireRole(AccountRole.ADMIN)
        if (!PartgVersionRetention.validYears(olderThanYears)) throw PartyReportInvalidInputException("Retention out of range")
        if (expectedVersionIds.size > PartgVersionRules.MAX_DELETE_IDS) throw PartyReportInvalidInputException("Too many versions")
        val ids = expectedVersionIds.map { parseId(it) }.toSet()
        checkWriteRate(current)
        return PartgVersionWriter.deleteOld(
            current = current,
            olderThanYears = olderThanYears,
            expectedIds = ids,
            today = OrganizationTimeZone.today(),
            now = ServerClock.now(),
        )
    }

    private fun checkWriteRate(current: CurrentMember) {
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
    }

    private fun parseId(raw: String): Uuid =
        if (STRICT_UUID.matches(raw)) Uuid.parse(raw) else throw PartyReportInvalidInputException("Invalid id")

    private companion object {
        val STRICT_UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    }
}
