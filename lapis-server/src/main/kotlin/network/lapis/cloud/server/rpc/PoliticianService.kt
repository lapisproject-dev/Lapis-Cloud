package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PoliticianProfileTable
import network.lapis.cloud.server.db.generated.PoliticianReactionTable
import network.lapis.cloud.server.db.generated.PoliticianWeightSnapshotTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PoliticianProfileDto
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PoliticianReactionDto
import network.lapis.cloud.shared.domain.PoliticianReactionValue
import network.lapis.cloud.shared.domain.PoliticianWeightSnapshotDto
import network.lapis.cloud.shared.rpc.IPoliticianService
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val POLITICIAN_BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

private val ZERO_2DP: BigDecimal = BigDecimal.ZERO.setScale(2)

private val ZERO_WEIGHT_RESULT = PoliticianTrustWeightCalculator.TrustWeightResult(0, 0, ZERO_2DP)

/**
 * Politiker-Profile und Politiker-Ranking (V0.6.4) -- see [IPoliticianService] KDoc and
 * `20-politician.kuml.kts` file header for the full fachlich model. [ltrBalanceProvider] defaults
 * to [LedgerBackedLtrBalanceProvider], same seam [CrowdfundingService]/[PeerTransferService] use.
 *
 * ## The `politicianRankingEnabled` gate
 *
 * Every method calls [requirePoliticianRankingEnabled] first -- if
 * `OrganizationSettingsDto.politicianRankingEnabled` is `false` (the default), the call is
 * rejected with [ConflictException] and has zero side effects. Same
 * `requirePostalMailEnabled`-style gate [PostalMailService] already establishes for its own
 * opt-in flag -- applies even to [grantPoliticianStatus], so a BOARD member cannot silently
 * activate the feature by granting status while it is toggled off.
 *
 * ## Revoke-vs-rate concurrency
 *
 * [revokePoliticianStatus] and [castRating]/[retractRating] all take a `SELECT ... FOR UPDATE` row
 * lock on the relevant [PoliticianProfileTable] row BEFORE reading/writing its status or child
 * rows (same per-row-mutex pattern [LtrBalanceProvider.lockForDebit]/
 * `CrowdfundingService.requireProjectRow(forUpdate=true)` already use) -- this serializes a
 * concurrent revoke against a concurrent rating on the SAME politician, so a vote can never land
 * on `politician_reaction` after `revokePoliticianStatus`'s delete-scan already ran while the
 * profile is left `status=FORMER`: whichever transaction wins the lock commits fully (delete-then-
 * flip, or insert-then-commit) before the other proceeds, and if revoke wins second it re-reads and
 * sweeps up whatever the rating transaction just committed.
 *
 * ## First-grant race
 *
 * `SELECT ... FOR UPDATE` locks an existing [PoliticianProfileTable] row, but locks nothing when
 * no row exists yet for that member -- two concurrent [grantPoliticianStatus] calls for the same
 * never-before-politician member can both observe `existing == null` and both attempt the insert,
 * racing on `uq_politician_profile_member`. [grantPoliticianStatus] catches the resulting
 * [ExposedSQLException] (same "pre-check + backstop" idiom [AccountingService.createLedgerAccount]
 * already uses) and retries once in a fresh transaction rather than surfacing a raw 500 -- unlike
 * that idiom, the retry does not turn into a [ConflictException]: [grantPoliticianStatus]'s own
 * contract is an idempotent upsert (see its KDoc), so the race loser must converge to the same
 * `ACTIVE` profile the winner just committed, which the retry's `existing != null` branch does.
 */
class PoliticianService(
    private val call: ApplicationCall,
    private val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
) : IPoliticianService {
    override suspend fun grantPoliticianStatus(
        memberId: String,
        mandateText: String?,
    ): PoliticianProfileDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*POLITICIAN_BOARD_ROLES)
        requirePoliticianRankingEnabled()
        val targetMemberId = memberId.toMemberUuidOrThrow()
        val now = nowLocalDateTime()
        return try {
            grantPoliticianStatusTx(targetMemberId, mandateText, now, current.memberId)
        } catch (e: ExposedSQLException) {
            // See class KDoc "First-grant race" -- the loser of a concurrent first-grant retries
            // in a brand-new transaction (the failed one is unusable/aborted on Postgres past this
            // point) and now takes the existing-row/update branch, converging on the same ACTIVE
            // profile the winner just committed instead of surfacing a raw 500.
            grantPoliticianStatusTx(targetMemberId, mandateText, now, current.memberId)
        }
    }

    private fun grantPoliticianStatusTx(
        targetMemberId: Uuid,
        mandateText: String?,
        now: LocalDateTime,
        grantedBy: Uuid,
    ): PoliticianProfileDto =
        transaction {
            requireMemberExists(targetMemberId)
            val existing =
                PoliticianProfileTable
                    .selectAll()
                    .where { PoliticianProfileTable.memberId eq targetMemberId }
                    .forUpdate()
                    .singleOrNull()
            val profileId =
                if (existing == null) {
                    val newId = Uuid.random()
                    PoliticianProfileTable.insert {
                        it[id] = newId
                        it[PoliticianProfileTable.memberId] = targetMemberId
                        it[status] = PoliticianProfileStatus.ACTIVE
                        it[PoliticianProfileTable.mandateText] = mandateText
                        it[grantedAt] = now
                        it[grantedByMemberId] = grantedBy
                        it[revokedAt] = null
                        it[revokedByMemberId] = null
                    }
                    newId
                } else {
                    val existingId = existing[PoliticianProfileTable.id]
                    // Idempotent upsert -- see IPoliticianService.grantPoliticianStatus KDoc:
                    // ACTIVE->ACTIVE refreshes grantedAt/grantedBy, FORMER->ACTIVE reactivates.
                    // Mandate text is only overwritten when a non-null value is supplied.
                    PoliticianProfileTable.update({ PoliticianProfileTable.id eq existingId }) {
                        it[status] = PoliticianProfileStatus.ACTIVE
                        if (mandateText != null) it[PoliticianProfileTable.mandateText] = mandateText
                        it[grantedAt] = now
                        it[grantedByMemberId] = grantedBy
                        it[revokedAt] = null
                        it[revokedByMemberId] = null
                    }
                    existingId
                }
            loadProfile(profileId)
        }

    override suspend fun revokePoliticianStatus(memberId: String): PoliticianProfileDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*POLITICIAN_BOARD_ROLES)
        requirePoliticianRankingEnabled()
        val targetMemberId = memberId.toMemberUuidOrThrow()
        val now = nowLocalDateTime()
        return transaction {
            val row = requireProfileRowByMember(targetMemberId, forUpdate = true)
            if (row[PoliticianProfileTable.status] != PoliticianProfileStatus.ACTIVE) {
                throw ConflictException("PoliticianProfile for member $targetMemberId is already FORMER")
            }
            val profileId = row[PoliticianProfileTable.id]
            PoliticianProfileTable.update({ PoliticianProfileTable.id eq profileId }) {
                it[status] = PoliticianProfileStatus.FORMER
                it[revokedAt] = now
                it[revokedByMemberId] = current.memberId
            }
            // "Bewertungsstatistik wird geloescht" -- see IPoliticianService.revokePoliticianStatus
            // KDoc. Both deletes run AFTER the status flip, still inside this same locked
            // transaction, so no reader can observe status=FORMER alongside surviving rows.
            PoliticianWeightSnapshotTable.deleteWhere { PoliticianWeightSnapshotTable.politicianProfileId eq profileId }
            PoliticianReactionTable.deleteWhere { PoliticianReactionTable.politicianProfileId eq profileId }
            loadProfile(profileId)
        }
    }

    override suspend fun updateMandateText(
        memberId: String,
        mandateText: String?,
    ): PoliticianProfileDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*POLITICIAN_BOARD_ROLES)
        requirePoliticianRankingEnabled()
        val targetMemberId = memberId.toMemberUuidOrThrow()
        return transaction {
            val row = requireProfileRowByMember(targetMemberId, forUpdate = false)
            val profileId = row[PoliticianProfileTable.id]
            PoliticianProfileTable.update({ PoliticianProfileTable.id eq profileId }) {
                it[PoliticianProfileTable.mandateText] = mandateText
            }
            loadProfile(profileId)
        }
    }

    /**
     * See class KDoc "Revoke-vs-rate concurrency" -- locks the profile row FOR UPDATE before
     * reading its status. [requireActiveMembership] gates the RATER's own membership status (a
     * plain read on [MemberTable], unrelated to the profile lock above).
     */
    override suspend fun castRating(
        politicianMemberId: String,
        value: PoliticianReactionValue,
    ): PoliticianReactionDto {
        val current = resolveCurrentMember(call)
        requirePoliticianRankingEnabled()
        val targetMemberId = politicianMemberId.toMemberUuidOrThrow()
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(current.memberId)
            val profileRow = requireProfileRowByMember(targetMemberId, forUpdate = true)
            if (profileRow[PoliticianProfileTable.status] != PoliticianProfileStatus.ACTIVE) {
                throw ConflictException("PoliticianProfile for member $targetMemberId is not ACTIVE -- ratings are not open")
            }
            val profileId = profileRow[PoliticianProfileTable.id]
            val existing =
                PoliticianReactionTable
                    .selectAll()
                    .where {
                        (PoliticianReactionTable.politicianProfileId eq profileId) and
                            (PoliticianReactionTable.raterMemberId eq current.memberId)
                    }.singleOrNull()
            val reactionId =
                if (existing == null) {
                    val newId = Uuid.random()
                    PoliticianReactionTable.insert {
                        it[id] = newId
                        it[PoliticianReactionTable.politicianProfileId] = profileId
                        it[raterMemberId] = current.memberId
                        it[reactionValue] = value
                        it[castAt] = now
                    }
                    newId
                } else {
                    val existingId = existing[PoliticianReactionTable.id]
                    PoliticianReactionTable.update({ PoliticianReactionTable.id eq existingId }) {
                        it[reactionValue] = value
                        it[castAt] = now
                    }
                    existingId
                }
            PoliticianReactionTable
                .selectAll()
                .where { PoliticianReactionTable.id eq reactionId }
                .single()
                .toReactionDto(targetMemberId)
        }
    }

    override suspend fun retractRating(politicianMemberId: String) {
        val current = resolveCurrentMember(call)
        requirePoliticianRankingEnabled()
        val targetMemberId = politicianMemberId.toMemberUuidOrThrow()
        transaction {
            requireActiveMembership(current.memberId)
            val profileRow = requireProfileRowByMember(targetMemberId, forUpdate = true)
            val profileId = profileRow[PoliticianProfileTable.id]
            PoliticianReactionTable.deleteWhere {
                (PoliticianReactionTable.politicianProfileId eq profileId) and (PoliticianReactionTable.raterMemberId eq current.memberId)
            }
        }
    }

    /**
     * Deliberately NOT gated on [requireActiveMembership] -- unlike [castRating]/[retractRating],
     * this is a read of the caller's own state, and a member whose status changed after casting a
     * rating (e.g. AKTIV -> AUSGETRETEN) must still be able to see what they previously voted,
     * same "no status check on a read of one's own state" precedent
     * [ICrowdfundingService.getMyReaction] already establishes.
     */
    override suspend fun getMyRating(politicianMemberId: String): List<PoliticianReactionDto> {
        val current = resolveCurrentMember(call)
        requirePoliticianRankingEnabled()
        val targetMemberId = politicianMemberId.toMemberUuidOrThrow()
        return transaction {
            val profileRow = requireProfileRowByMember(targetMemberId, forUpdate = false)
            val profileId = profileRow[PoliticianProfileTable.id]
            PoliticianReactionTable
                .selectAll()
                .where {
                    (PoliticianReactionTable.politicianProfileId eq profileId) and
                        (PoliticianReactionTable.raterMemberId eq current.memberId)
                }.singleOrNull()
                ?.toReactionDto(targetMemberId)
                .let(::listOfNotNull)
        }
    }

    override suspend fun listPoliticians(includeFormer: Boolean): List<PoliticianProfileDto> {
        resolveCurrentMember(call)
        requirePoliticianRankingEnabled()
        return transaction {
            val query = profileJoin().selectAll()
            val rows =
                (if (includeFormer) query else query.where { PoliticianProfileTable.status eq PoliticianProfileStatus.ACTIVE })
                    .toList()
            val activeIds =
                rows
                    .filter {
                        it[PoliticianProfileTable.status] == PoliticianProfileStatus.ACTIVE
                    }.map { it[PoliticianProfileTable.id] }
            val weights = computeWeights(activeIds)
            rows.map { it.toProfileDto(weights[it[PoliticianProfileTable.id]] ?: ZERO_WEIGHT_RESULT) }
        }
    }

    override suspend fun getPoliticianProfile(memberId: String): PoliticianProfileDto {
        resolveCurrentMember(call)
        requirePoliticianRankingEnabled()
        val targetMemberId = memberId.toMemberUuidOrThrow()
        return transaction {
            val profileId =
                PoliticianProfileTable
                    .selectAll()
                    .where { PoliticianProfileTable.memberId eq targetMemberId }
                    .singleOrNull()
                    ?.get(PoliticianProfileTable.id)
                    ?: throw NotFoundException("PoliticianProfile for member $targetMemberId not found")
            loadProfile(profileId)
        }
    }

    override suspend fun getTopPoliticians(limit: Int): List<PoliticianProfileDto> {
        resolveCurrentMember(call)
        requirePoliticianRankingEnabled()
        if (limit <= 0) throw ConflictException("limit must be positive")
        return transaction {
            val rows =
                profileJoin()
                    .selectAll()
                    .where { PoliticianProfileTable.status eq PoliticianProfileStatus.ACTIVE }
                    .toList()
            val activeIds = rows.map { it[PoliticianProfileTable.id] }
            val weights = computeWeights(activeIds)
            rows
                .map { it.toProfileDto(weights[it[PoliticianProfileTable.id]] ?: ZERO_WEIGHT_RESULT) }
                .sortedWith(compareByDescending<PoliticianProfileDto> { it.memberTrustWeight }.thenBy { it.memberId })
                .take(limit)
        }
    }

    /**
     * See [IPoliticianService.snapshotWeights] KDoc. [periodMonth] is normalized to the first day
     * of its calendar month before use -- "first-of-month sentinel", same idiom
     * `crowdfunding_distribution.period_start` documents for its own period columns.
     */
    override suspend fun snapshotWeights(periodMonth: LocalDate): List<PoliticianWeightSnapshotDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*POLITICIAN_BOARD_ROLES)
        requirePoliticianRankingEnabled()
        val normalizedMonth = LocalDate(periodMonth.year, periodMonth.month, 1)
        val now = nowLocalDateTime()
        return transaction {
            val activeProfileIds =
                PoliticianProfileTable
                    .selectAll()
                    .where { PoliticianProfileTable.status eq PoliticianProfileStatus.ACTIVE }
                    .map { it[PoliticianProfileTable.id] }
            if (activeProfileIds.isEmpty()) return@transaction emptyList()

            val weights = computeWeights(activeProfileIds)
            activeProfileIds.forEach { profileId ->
                val result = weights[profileId] ?: ZERO_WEIGHT_RESULT
                PoliticianWeightSnapshotTable.insertIgnore {
                    it[id] = Uuid.random()
                    it[PoliticianWeightSnapshotTable.politicianProfileId] = profileId
                    it[PoliticianWeightSnapshotTable.periodMonth] = normalizedMonth
                    it[memberTrustWeight] = result.memberTrustWeight
                    it[memberLikeCount] = result.memberLikeCount
                    it[memberDislikeCount] = result.memberDislikeCount
                    it[computedAt] = now
                    it[computedByMemberId] = current.memberId
                }
            }
            loadWeightHistory(activeProfileIds.toSet(), periodFilter = normalizedMonth)
        }
    }

    override suspend fun getWeightHistory(memberId: String): List<PoliticianWeightSnapshotDto> {
        resolveCurrentMember(call)
        requirePoliticianRankingEnabled()
        val targetMemberId = memberId.toMemberUuidOrThrow()
        return transaction {
            val profileId =
                PoliticianProfileTable
                    .selectAll()
                    .where { PoliticianProfileTable.memberId eq targetMemberId }
                    .singleOrNull()
                    ?.get(PoliticianProfileTable.id)
                    ?: throw NotFoundException("PoliticianProfile for member $targetMemberId not found")
            loadWeightHistory(setOf(profileId), periodFilter = null)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /** See class KDoc "The politicianRankingEnabled gate". */
    private fun requirePoliticianRankingEnabled() {
        val enabled =
            transaction {
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .singleOrNull()
                    ?.get(OrganizationSettingsTable.politicianRankingEnabled)
                    ?: false
            }
        if (!enabled) {
            throw ConflictException(
                "Politician ranking is disabled (OrganizationSettings.politicianRankingEnabled=false) -- " +
                    "an ADMIN must enable it via updateOrganizationSettings first",
            )
        }
    }

    /**
     * Must run BEFORE any row lock for any client-supplied (not-already-authenticated) member id
     * -- same reasoning [PeerTransferService.requireMemberExists] KDoc documents.
     */
    private fun requireMemberExists(memberId: Uuid) {
        MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull()
            ?: throw NotFoundException("Member $memberId not found")
    }

    private fun requireProfileRowByMember(
        memberId: Uuid,
        forUpdate: Boolean,
    ): ResultRow {
        val query = PoliticianProfileTable.selectAll().where { PoliticianProfileTable.memberId eq memberId }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
            ?: throw NotFoundException("PoliticianProfile for member $memberId not found")
    }

    /**
     * **Must compute over every ACTIVE profile, never just [profileId] alone** -- the shared pool
     * (see [PoliticianTrustWeightCalculator] KDoc) is apportioned across ALL active politicians at
     * once; calling [computeWeights] with a single-element list would silently give [profileId]
     * the ENTIRE pool of only ITS OWN raters, ignoring every other active politician and their
     * raters entirely -- a different (and wrong) number than what [listPoliticians]/
     * [getTopPoliticians] report for the exact same politician. This is the one place that
     * divergence would otherwise sneak in, since every single-profile read/mutation
     * (grant/revoke/updateMandateText/getPoliticianProfile) funnels through here.
     */
    private fun loadProfile(profileId: Uuid): PoliticianProfileDto {
        val row =
            profileJoin().selectAll().where { PoliticianProfileTable.id eq profileId }.singleOrNull()
                ?: throw NotFoundException("PoliticianProfile $profileId not found")
        val weight =
            if (row[PoliticianProfileTable.status] == PoliticianProfileStatus.ACTIVE) {
                val activeProfileIds =
                    PoliticianProfileTable
                        .selectAll()
                        .where { PoliticianProfileTable.status eq PoliticianProfileStatus.ACTIVE }
                        .map { it[PoliticianProfileTable.id] }
                computeWeights(activeProfileIds)[profileId] ?: ZERO_WEIGHT_RESULT
            } else {
                ZERO_WEIGHT_RESULT
            }
        return row.toProfileDto(weight)
    }

    /**
     * One query for every relevant profile's reactions (`politicianProfileId inList
     * activeProfileIds`), grouped in Kotlin -- NOT one query per profile. Bounded by the number of
     * active politicians passed in, same "closes the N+1/DoS gap" reasoning
     * `CrowdfundingService.reactionCountsByProject` KDoc documents. Delegates the actual weight
     * math to [PoliticianTrustWeightCalculator] -- see that object's KDoc for the shared-pool
     * algorithm.
     */
    private fun computeWeights(activeProfileIds: List<Uuid>): Map<Uuid, PoliticianTrustWeightCalculator.TrustWeightResult> {
        if (activeProfileIds.isEmpty()) return emptyMap()
        val reactionRows =
            PoliticianReactionTable
                .selectAll()
                .where { PoliticianReactionTable.politicianProfileId inList activeProfileIds }
                .toList()
        val groupedByProfile = reactionRows.groupBy { it[PoliticianReactionTable.politicianProfileId] }
        val reactionsByProfile: Map<Uuid, List<Pair<Uuid, PoliticianReactionValue>>> =
            activeProfileIds.associateWith { profileId ->
                groupedByProfile[profileId]?.map { row ->
                    row[PoliticianReactionTable.raterMemberId] to
                        row[PoliticianReactionTable.reactionValue]
                }
                    ?: emptyList()
            }
        val distinctRaters = reactionRows.map { it[PoliticianReactionTable.raterMemberId] }.toSet()
        val raterBalances = ltrBalanceProvider.freeBalances(distinctRaters)
        return PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, raterBalances)
    }

    private fun loadWeightHistory(
        profileIds: Set<Uuid>,
        periodFilter: LocalDate?,
    ): List<PoliticianWeightSnapshotDto> {
        // Condition built up-front (not a `.where {}.andWhere {}` chain) so the whole filter is one
        // Op<Boolean> value handed to a single `.where {}` call -- avoids depending on Exposed's
        // separate `andWhere` extension, which this codebase does not use anywhere else.
        val condition =
            if (periodFilter != null) {
                (PoliticianWeightSnapshotTable.politicianProfileId inList profileIds.toList()) and
                    (PoliticianWeightSnapshotTable.periodMonth eq periodFilter)
            } else {
                PoliticianWeightSnapshotTable.politicianProfileId inList profileIds.toList()
            }
        val memberIdByProfile = memberIdsByProfile(profileIds)
        return PoliticianWeightSnapshotTable
            .selectAll()
            .where { condition }
            .orderBy(PoliticianWeightSnapshotTable.periodMonth, SortOrder.ASC)
            .map { row ->
                PoliticianWeightSnapshotDto(
                    id = row[PoliticianWeightSnapshotTable.id].toString(),
                    politicianMemberId = memberIdByProfile.getValue(row[PoliticianWeightSnapshotTable.politicianProfileId]).toString(),
                    periodMonth = row[PoliticianWeightSnapshotTable.periodMonth],
                    memberTrustWeight = row[PoliticianWeightSnapshotTable.memberTrustWeight],
                    memberLikeCount = row[PoliticianWeightSnapshotTable.memberLikeCount],
                    memberDislikeCount = row[PoliticianWeightSnapshotTable.memberDislikeCount],
                    computedAt = row[PoliticianWeightSnapshotTable.computedAt],
                )
            }
    }

    private fun memberIdsByProfile(profileIds: Set<Uuid>): Map<Uuid, Uuid> =
        PoliticianProfileTable
            .selectAll()
            .where { PoliticianProfileTable.id inList profileIds.toList() }
            .associate { it[PoliticianProfileTable.id] to it[PoliticianProfileTable.memberId] }

    /**
     * Explicit join, not `PoliticianProfileTable innerJoin MemberTable`: [PoliticianProfileTable]
     * has THREE FKs to [MemberTable] (`memberId`/`grantedByMemberId`/`revokedByMemberId`), so
     * Exposed's implicit FK-based join resolution can't tell which path to use -- same
     * disambiguation `CrowdfundingService.projectJoin` KDoc documents.
     */
    private fun profileJoin() = PoliticianProfileTable.join(MemberTable, JoinType.INNER, PoliticianProfileTable.memberId, MemberTable.id)

    private fun memberDisplayName(memberId: Uuid?): String? =
        memberId?.let { id ->
            MemberTable
                .selectAll()
                .where { MemberTable.id eq id }
                .singleOrNull()
                ?.get(MemberTable.displayName)
        }

    private fun ResultRow.toProfileDto(weight: PoliticianTrustWeightCalculator.TrustWeightResult): PoliticianProfileDto =
        PoliticianProfileDto(
            id = this[PoliticianProfileTable.id].toString(),
            memberId = this[PoliticianProfileTable.memberId].toString(),
            displayName = this[MemberTable.displayName],
            status = this[PoliticianProfileTable.status],
            mandateText = this[PoliticianProfileTable.mandateText],
            grantedAt = this[PoliticianProfileTable.grantedAt],
            grantedByDisplayName = memberDisplayName(this[PoliticianProfileTable.grantedByMemberId]) ?: "",
            revokedAt = this[PoliticianProfileTable.revokedAt],
            revokedByDisplayName = memberDisplayName(this[PoliticianProfileTable.revokedByMemberId]),
            memberTrustWeight = weight.memberTrustWeight,
            memberLikeCount = weight.memberLikeCount,
            memberDislikeCount = weight.memberDislikeCount,
        )

    private fun ResultRow.toReactionDto(politicianMemberId: Uuid): PoliticianReactionDto =
        PoliticianReactionDto(
            id = this[PoliticianReactionTable.id].toString(),
            politicianMemberId = politicianMemberId.toString(),
            value = this[PoliticianReactionTable.reactionValue],
            castAt = this[PoliticianReactionTable.castAt],
        )

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun String.toMemberUuidOrThrow(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
