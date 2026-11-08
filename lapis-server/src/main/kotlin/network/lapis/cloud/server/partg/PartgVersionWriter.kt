package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.partg.report.PartgReportDocument
import network.lapis.cloud.server.partg.report.PartgReportDocuments
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgDeadlineAuditSnapshot
import network.lapis.cloud.shared.domain.PartgDeadlineRules
import network.lapis.cloud.shared.domain.PartgDeclarationAuditSnapshot
import network.lapis.cloud.shared.domain.PartgDeclarationTemplate
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgVersionAuditSnapshot
import network.lapis.cloud.shared.domain.PartgVersionDeletionAuditSnapshot
import network.lapis.cloud.shared.domain.PartgVersionRetention
import network.lapis.cloud.shared.domain.PartgVersionRules
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportVersionDeletionStaleException
import network.lapis.cloud.shared.rpc.PartyReportVersionIntegrityException
import network.lapis.cloud.shared.rpc.PartyReportVersionStateException
import network.lapis.cloud.shared.rpc.PartyReportVersionUnchangedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.291 (R6) -- the ONE write path of kept versions, declarations, the internal "final" mark, deadlines and the deletion routine;
 * `PartyReportService` calls nothing else.
 *
 * One transaction per write; the audit entry is written LAST; no external effect inside the transaction. NO booking is read for writing and none
 * is changed (GoBD). The audit snapshots carry ids, numbers, the checksum and counters: NEVER a comment, a name or an amount.
 *
 * LOCK ORDER (documented, pinned by the scenario tests): the counter rows (sorted by year, scope key), then the version row. The writer never takes a
 * sheet, annex, notes or consolidation lock, so there is no cycle with the writers of R1..R5. The report is READ (and its document built) OUTSIDE
 * the write transaction through the revision fence of [PartgReportSnapshotReader]; only the finished, serialised text is stored inside it.
 */
internal object PartgVersionWriter {
    /** Test seam: the maximum JSON size, so a test can use a small budget instead of building 4 MiB. */
    @Volatile
    internal var maxJsonBytes: Int = PartgVersionFormat.MAX_JSON_BYTES

    /** Test seam: called after the document is built and before the write transaction (to interleave a concurrent keep). */
    @Volatile
    internal var beforeWriteHook: () -> Unit = {}

    // ---------------------------------------------------------------- keep a version

    fun freeze(
        current: CurrentMember,
        year: Int,
        scope: PartgReportScope,
        comment: String?,
        now: LocalDateTime,
    ): Uuid {
        val built =
            PartgReportSnapshotReader.read(listOf(year, year - 1)).let { snapshot ->
                PartgReportDocuments.build(snapshot = snapshot, year = year, scope = scope)
            }
        val json = PartgVersionFormat.encode(built)
        val size = PartgVersionFormat.requireWithinBudget(text = json, maxBytes = maxJsonBytes)
        val hash = PartgVersionFormat.hash(json)
        val thirdParty = holdsThirdPartyData(built)
        beforeWriteHook()
        return transaction {
            val counter = PartgVersionStore.lockCounter(year = year, scopeKey = scope.key(), actor = current.memberId, now = now)
            if (PartgVersionStore.countVersions(counter.id) >= PartgVersionRules.MAX_PER_SCOPE) {
                throw PartyReportInvalidInputException("Too many kept versions")
            }
            val newest = PartgVersionStore.latest(year = year, scopeKey = scope.key())
            if (newest != null && newest.contentHash == hash && newest.formatVersion == PartgVersionFormat.CURRENT) {
                throw PartyReportVersionUnchangedException()
            }
            val number = counter.lastNumber + 1
            val id =
                PartgVersionStore.insertVersion(
                    counter = counter,
                    number = number,
                    formatVersion = PartgVersionFormat.CURRENT,
                    contentJson = json,
                    contentHash = hash,
                    byteSize = size,
                    hasThirdPartyData = thirdParty,
                    comment = comment,
                    actor = current.memberId,
                    now = now,
                )
            PartgVersionStore.bumpCounter(counter = counter, number = number, actor = current.memberId, now = now)
            audit(
                current = current,
                id = id,
                action = AuditAction.CREATE,
                now = now,
                snapshot =
                    PartgVersionAuditSnapshot(
                        year = year,
                        scope = scope.key(),
                        versionNumber = number,
                        contentHash = hash,
                        byteSize = size,
                        hasThirdPartyData = thirdParty,
                        change = "VERSION_KEPT",
                    ),
            )
            id
        }
    }

    /** Statements B and C (and the consolidated B) hold names and addresses of third parties. */
    internal fun holdsThirdPartyData(document: PartgReportDocument): Boolean =
        document.annex.donors.isNotEmpty() ||
            document.annex.sponsors.isNotEmpty() ||
            document.consolidation?.donors?.isNotEmpty() == true

    // ---------------------------------------------------------------- declaration and finalisation

    /** Records the assurance on a whole-party version. All checks run under the locks; the stored text is re-hashed first. */
    fun declare(
        current: CurrentMember,
        versionId: Uuid,
        expectedHash: String,
        reviewed: Boolean,
        now: LocalDateTime,
    ) {
        transaction {
            val version = lockForStep(versionId)
            if (!reviewed) throw PartyReportVersionStateException()
            requireWholeParty(version)
            if (PartgVersionStore.declaration(versionId) != null) throw PartyReportVersionStateException()
            requireHash(version = version, expectedHash = expectedHash)
            val declarationId =
                PartgVersionStore.insertDeclaration(
                    versionId = versionId,
                    templateCode = PartgDeclarationTemplate.CODE,
                    contentHash = version.contentHash,
                    actor = current.memberId,
                    now = now,
                )
            audit(
                current = current,
                id = declarationId,
                entityType = AuditEntityType.PARTG_REPORT_DECLARATION,
                action = AuditAction.CREATE,
                now = now,
                snapshot =
                    PartgDeclarationAuditSnapshot(
                        year = version.year,
                        versionId = versionId.toString(),
                        versionNumber = version.versionNumber,
                        templateCode = PartgDeclarationTemplate.CODE,
                        contentHash = version.contentHash,
                    ),
            )
        }
    }

    /** Marks a declared whole-party version as internally final. Irreversible: there is no function that clears the mark. */
    fun finalize(
        current: CurrentMember,
        versionId: Uuid,
        typedNumber: Int,
        expectedHash: String,
        now: LocalDateTime,
    ) {
        transaction {
            val version = lockForStep(versionId)
            if (!PartgVersionRules.matchesTypedNumber(typed = typedNumber, versionNumber = version.versionNumber)) {
                throw PartyReportInvalidInputException("The typed version number does not match")
            }
            requireWholeParty(version)
            if (version.finalizedAt != null) throw PartyReportVersionStateException()
            if (PartgVersionStore.declaration(versionId) == null) throw PartyReportVersionStateException()
            requireHash(version = version, expectedHash = expectedHash)
            if (!PartgVersionStore.markFinal(id = versionId, actor = current.memberId, now = now)) throw PartyReportVersionStateException()
            audit(
                current = current,
                id = versionId,
                action = AuditAction.UPDATE,
                now = now,
                snapshot =
                    PartgVersionAuditSnapshot(
                        year = version.year,
                        scope = version.scopeKey,
                        versionNumber = version.versionNumber,
                        contentHash = version.contentHash,
                        byteSize = version.byteSize,
                        hasThirdPartyData = version.hasThirdPartyData,
                        change = "VERSION_FINALISED",
                    ),
            )
        }
    }

    /** Counter row first, then the version row. A vanished version is a 404. */
    private fun lockForStep(versionId: Uuid): PartgVersionStore.VersionMeta {
        val unlocked = PartgVersionStore.versionMeta(versionId) ?: throw NotFoundException("Version not found")
        PartgVersionStore.lockExistingCounter(year = unlocked.year, scopeKey = unlocked.scopeKey)
        return PartgVersionStore.lockVersion(versionId) ?: throw NotFoundException("Version not found")
    }

    private fun requireWholeParty(version: PartgVersionStore.VersionMeta) {
        if (version.scopeKey != PartgReportScope.ALL_KEY) throw PartyReportVersionStateException()
    }

    /** The caller's checksum must equal the stored one AND the stored text must still hash to it; else nothing changes. */
    private fun requireHash(
        version: PartgVersionStore.VersionMeta,
        expectedHash: String,
    ) {
        if (!PartgVersionRules.isHash(expectedHash) || expectedHash != version.contentHash) throw PartyReportVersionStateException()
        val stored = PartgVersionStore.content(version.id) ?: throw NotFoundException("Version not found")
        if (PartgVersionFormat.hash(stored.first) != version.contentHash || stored.second != version.contentHash) {
            throw PartyReportVersionIntegrityException()
        }
    }

    // ---------------------------------------------------------------- deadline

    /** Confirms or changes the deadline; returns the previous date, if any. */
    fun confirmDeadline(
        current: CurrentMember,
        year: Int,
        deadline: LocalDate,
        now: LocalDateTime,
    ) {
        if (!PartgDeadlineRules.valid(year = year, deadline = deadline)) throw PartyReportInvalidInputException("Deadline out of range")
        transaction {
            PartgVersionStore.upsertDeadline(year = year, deadline = deadline, actor = current.memberId, now = now)
            val row = PartgVersionStore.deadline(year) ?: error("deadline vanished after upsert")
            audit(
                current = current,
                id = row.id,
                entityType = AuditEntityType.PARTG_REPORT_DEADLINE,
                action = AuditAction.UPDATE,
                now = now,
                snapshot = PartgDeadlineAuditSnapshot(year = year, deadline = deadline.toString()),
            )
        }
    }

    // ---------------------------------------------------------------- deletion routine

    /** The versions the routine would remove (at most [PartgVersionRules.MAX_DELETE_IDS], in order), and whether there are more. Inside a transaction. */
    fun deletionCandidates(
        olderThanYears: Int,
        today: LocalDate,
    ): Pair<List<PartgVersionStore.VersionMeta>, Boolean> {
        val all =
            PartgVersionStore
                .versionsBefore(today.year - olderThanYears)
                .filter {
                    PartgVersionRetention.eligible(
                        fiscalYear = it.year,
                        frozenAtYear = it.frozenAt.year,
                        today = today,
                        n = olderThanYears,
                    )
                }
        return all.take(PartgVersionRules.MAX_DELETE_IDS) to (all.size > PartgVersionRules.MAX_DELETE_IDS)
    }

    /**
     * Removes exactly [expectedIds]. EVERY counter row is locked first (sorted), then the candidates are recomputed under the lock; a different set is
     * [PartyReportVersionDeletionStaleException] and nothing is deleted. The counter rows stay, so a number is never reused. ONE audit entry.
     */
    fun deleteOld(
        current: CurrentMember,
        olderThanYears: Int,
        expectedIds: Set<Uuid>,
        today: LocalDate,
        now: LocalDateTime,
    ): Int {
        if (!PartgVersionRetention.validYears(olderThanYears)) throw PartyReportInvalidInputException("Retention out of range")
        return transaction {
            PartgVersionStore.lockAllCountersSorted()
            val (candidates, _) = deletionCandidates(olderThanYears = olderThanYears, today = today)
            val ids = candidates.map { it.id }
            if (ids.toSet() != expectedIds) throw PartyReportVersionDeletionStaleException()
            if (ids.isEmpty()) return@transaction 0
            PartgVersionStore.deleteVersions(ids)
            audit(
                current = current,
                id = ids.first(),
                action = AuditAction.VOID,
                now = now,
                snapshot =
                    PartgVersionDeletionAuditSnapshot(
                        olderThanYears = olderThanYears,
                        count = ids.size,
                        versionIds = ids.map { it.toString() },
                    ),
            )
            ids.size
        }
    }

    private inline fun <reified T> audit(
        current: CurrentMember,
        id: Uuid,
        action: AuditAction,
        now: LocalDateTime,
        snapshot: T,
        entityType: AuditEntityType = AuditEntityType.PARTG_REPORT_VERSION,
    ) {
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = entityType,
            entityId = id,
            action = action,
            after = Json.encodeToString(kotlinx.serialization.serializer<T>(), snapshot),
            occurredAt = now,
        )
    }
}
