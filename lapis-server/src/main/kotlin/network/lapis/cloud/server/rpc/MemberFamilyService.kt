package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberFamilyLinkTable
import network.lapis.cloud.server.db.generated.MemberFamilyTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnniversaryCalendar
import network.lapis.cloud.shared.domain.FamilyMemberRole
import network.lapis.cloud.shared.domain.FamilyMembershipRules
import network.lapis.cloud.shared.domain.MemberFamilyDetailDto
import network.lapis.cloud.shared.domain.MemberFamilyLimits
import network.lapis.cloud.shared.domain.MemberFamilyLinkDto
import network.lapis.cloud.shared.domain.MemberFamilyPageDto
import network.lapis.cloud.shared.domain.MemberFamilySummaryDto
import network.lapis.cloud.shared.domain.UpcomingMajorityEntryDto
import network.lapis.cloud.shared.domain.UpcomingMajorityOverviewDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IMemberFamilyService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}
private val FAMILY_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Machine-fixed reason string [MembershipTierAssignment] records whenever a family operation
 * (never the manual `IMemberService.updateMemberMembershipTier` path) nulls a member's tier --
 * see [AuditLog][network.lapis.cloud.shared.domain.MemberMembershipTierSnapshot] KDoc "no PII
 * beyond ids": this must NEVER become a person's or family's name.
 */
private const val FAMILY_DEPENDENT_REASON = "family-dependent"

/**
 * Welle V1.4.4.4 "Mitgliederlebenszyklus: Familienmitgliedschaften" -- see [IMemberFamilyService]
 * KDoc for the overall shape (BOARD/ADMIN for every method, ADMIN-only for [deleteFamily]).
 *
 * **Deliberately no separate Store/Policy pair** -- same "single, simple table, no concurrency
 * problem" reasoning [MemberHonorService] KDoc already gives for that entity's shape.
 *
 * **Sorting/aggregation in [listFamilies] happens in Kotlin, not SQL** -- same "hundreds, not
 * millions, of rows" scale reasoning `MemberService.listMembersForAdministration`'s own
 * `statusCounts` KDoc already establishes for a comparable in-memory tally.
 */
class MemberFamilyService(
    private val call: ApplicationCall,
    private val clock: () -> LocalDate = { DbClock.nowLocalDateTime().date },
) : IMemberFamilyService {
    override suspend fun listFamilies(
        search: String?,
        limit: Int,
        offset: Int,
    ): MemberFamilyPageDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*FAMILY_ROLES)
        val effectiveLimit = limit.coerceIn(1, MemberFamilyLimits.MAX_LIMIT)
        val effectiveOffset = offset.coerceAtLeast(0)
        val searchTerm =
            search
                ?.take(MemberFamilyLimits.MAX_SEARCH_LENGTH)
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }

        return transaction {
            val nameCondition: Op<Boolean>? = searchTerm?.let { MemberFamilyTable.name.lowerCase() like containsPattern(it) }

            val total =
                (if (nameCondition != null) MemberFamilyTable.selectAll().where { nameCondition } else MemberFamilyTable.selectAll())
                    .count()
                    .toInt()

            // Security fix (LOW DoS/Skalierung) -- sorting AND paging now happen on the database, not
            // in Kotlin: the previous version unconditionally loaded EVERY family row plus EVERY link
            // row for EVERY family into the JVM heap regardless of limit/offset, same
            // "hundreds-not-millions" scale assumption `MemberService.listMembersForAdministration`'s
            // own KDoc states but never actually enforced at the SQL level the way
            // `MemberHonorService.listHonors` already does for a comparable page. The `payer_family_id`
            // shadow column (see V23__member_family.sql) matches AT MOST ONE `member_family_link` row
            // per family by construction (`uq_member_family_link_payer`), so this LEFT JOIN can never
            // multiply a family's row -- "hasPayer" becomes a plain "is the joined row present" test
            // pushed into ORDER BY instead of a Kotlin-side `.sortedWith` over the whole table.
            val payerJoin =
                MemberFamilyTable
                    .join(MemberFamilyLinkTable, JoinType.LEFT, MemberFamilyTable.id, MemberFamilyLinkTable.payerFamilyId)
                    .join(MemberTable, JoinType.LEFT, MemberFamilyLinkTable.memberId, MemberTable.id)
            val pageRows =
                (if (nameCondition != null) payerJoin.selectAll().where { nameCondition } else payerJoin.selectAll())
                    .orderBy(
                        // Zahlerlose Familien zuerst: `payerFamilyId IS NULL` (true = kein Zahler)
                        // DESC bringt die NULL-Treffer an den Anfang, dann Name, dann Id.
                        MemberFamilyLinkTable.payerFamilyId.isNull() to SortOrder.DESC,
                        MemberFamilyTable.name to SortOrder.ASC,
                        MemberFamilyTable.id to SortOrder.ASC,
                    ).limit(effectiveLimit)
                    .offset(effectiveOffset.toLong())
                    .toList()

            // Bounded to THIS page's families (at most MemberFamilyLimits.MAX_LIMIT), never the whole
            // table -- the previous `familyId inList familyIds` collected every family id in the
            // system into one IN-list, which both pulled an unbounded member-count join into the JVM
            // heap on every request and, at scale, risked exceeding PostgreSQL's JDBC bind-parameter
            // limit outright.
            val pageFamilyIds = pageRows.map { it[MemberFamilyTable.id] }
            val memberCountByFamily =
                if (pageFamilyIds.isEmpty()) {
                    emptyMap()
                } else {
                    MemberFamilyLinkTable
                        .selectAll()
                        .where { MemberFamilyLinkTable.familyId inList pageFamilyIds }
                        .map { it[MemberFamilyLinkTable.familyId] }
                        .groupingBy { it }
                        .eachCount()
                }

            val page =
                pageRows.map { row ->
                    val familyId = row[MemberFamilyTable.id]
                    MemberFamilySummaryDto(
                        id = familyId.toString(),
                        name = row[MemberFamilyTable.name],
                        memberCount = memberCountByFamily[familyId] ?: 0,
                        payerMemberId = row.getOrNull(MemberFamilyLinkTable.memberId)?.toString(),
                        payerDisplayName = row.getOrNull(MemberTable.displayName),
                    )
                }
            MemberFamilyPageDto(entries = page, totalCount = total, limit = effectiveLimit, offset = effectiveOffset)
        }
    }

    override suspend fun getFamily(id: String): MemberFamilyDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*FAMILY_ROLES)
        val familyUuid = id.toUuidOrNotFound("MemberFamily")
        return transaction { loadFamilyDetailOrThrow(familyUuid) }
    }

    override suspend fun createFamily(
        name: String,
        payerMemberId: String,
    ): MemberFamilyDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*FAMILY_ROLES)
        val trimmedName = validateName(name)
        val payerUuid = payerMemberId.toUuidOrBadRequest("Member")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            requireExistingEligibleMember(payerUuid)
            requireNotAlreadyInFamily(payerUuid)
            val familyId = Uuid.random()
            MemberFamilyTable.insert {
                it[id] = familyId
                it[MemberFamilyTable.name] = trimmedName
                it[createdBy] = current.memberId
                it[createdAt] = now
            }
            try {
                MemberFamilyLinkTable.insert {
                    it[id] = Uuid.random()
                    it[MemberFamilyLinkTable.familyId] = familyId
                    it[memberId] = payerUuid
                    it[role] = FamilyMemberRole.PAYER
                    it[payerFamilyId] = familyId
                    it[linkedAt] = now
                    it[linkedBy] = current.memberId
                }
            } catch (e: ExposedSQLException) {
                // Race backstop -- same two-layer uniqueness idiom MemberService
                // .updateMemberCoreData/grantMemberAccount already establish for their own
                // UNIQUE constraints: requireNotAlreadyInFamily above is a racy pre-check on its
                // own under concurrency, uq_member_family_link_member (V23__member_family.sql) is
                // the real backstop. Class name only -- no message/stacktrace -- carries no PII.
                logger.warn { "MemberFamilyLinkTable.insert failed in createFamily: ${e::class.simpleName}" }
                throw ConflictException("Member $payerMemberId already belongs to a family")
            }
            logger.info {
                "member family created: actor=${current.memberId} actorRole=${current.role} familyId=$familyId payerMemberId=$payerUuid"
            }
            loadFamilyDetailOrThrow(familyId)
        }
    }

    override suspend fun renameFamily(
        id: String,
        name: String,
    ): MemberFamilyDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*FAMILY_ROLES)
        val familyUuid = id.toUuidOrNotFound("MemberFamily")
        val trimmedName = validateName(name)
        return transaction {
            val updated = MemberFamilyTable.update({ MemberFamilyTable.id eq familyUuid }) { it[MemberFamilyTable.name] = trimmedName }
            if (updated == 0) throw NotFoundException("MemberFamily $id not found")
            logger.info { "member family renamed: actor=${current.memberId} actorRole=${current.role} familyId=$familyUuid" }
            loadFamilyDetailOrThrow(familyUuid)
        }
    }

    override suspend fun addFamilyMember(
        familyId: String,
        memberId: String,
    ): MemberFamilyDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*FAMILY_ROLES)
        val familyUuid = familyId.toUuidOrNotFound("MemberFamily")
        val memberUuid = memberId.toUuidOrBadRequest("Member")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            requireFamilyExists(familyUuid)
            requireExistingEligibleMember(memberUuid)
            // Security fix (MAJOR, self-target/Peer-Schutz) -- this method nulls the new member's
            // membership_tier_id further down (see MembershipTierAssignment.apply call below), the
            // SAME production write path IMemberService.updateMemberMembershipTier gates behind a
            // self-target block and an ESCALATED_ROLES peer check -- without them here, a BOARD
            // caller could add THEMSELVES as a DEPENDENT (nulling their own tier, no TREASURER/ADMIN
            // involved at all) or strip a fellow ADMIN/BOARD/TREASURER peer's tier the same way.
            if (memberUuid == current.memberId) throw ForbiddenException()
            val existingRole = currentAccountRole(memberUuid)
            if (existingRole != null && existingRole in ESCALATED_ROLES) current.requireRole(AccountRole.ADMIN)
            requireNotAlreadyInFamily(memberUuid)
            try {
                MemberFamilyLinkTable.insert {
                    it[id] = Uuid.random()
                    it[MemberFamilyLinkTable.familyId] = familyUuid
                    it[MemberFamilyLinkTable.memberId] = memberUuid
                    it[role] = FamilyMemberRole.DEPENDENT
                    it[payerFamilyId] = null
                    it[linkedAt] = now
                    it[linkedBy] = current.memberId
                }
            } catch (e: ExposedSQLException) {
                // Race backstop -- same idiom as createFamily's own uq_member_family_link_member
                // backstop above: requireNotAlreadyInFamily is racy under concurrency on its own.
                logger.warn { "MemberFamilyLinkTable.insert failed in addFamilyMember: ${e::class.simpleName}" }
                throw ConflictException("Member $memberId already belongs to a family")
            }
            // Ein Angehöriger wird ueber den Zahler der Familie abgerechnet, nicht direkt -- siehe
            // IMemberFamilyService.addFamilyMember KDoc. Innerhalb DERSELBEN Transaktion.
            MembershipTierAssignment.apply(
                targetMemberId = memberUuid,
                newTierId = null,
                actor = current,
                reason = FAMILY_DEPENDENT_REASON,
                familyId = familyUuid,
                now = now,
            )
            logger.info {
                "member family member added: actor=${current.memberId} actorRole=${current.role} familyId=$familyUuid memberId=$memberUuid"
            }
            loadFamilyDetailOrThrow(familyUuid)
        }
    }

    override suspend fun removeFamilyMember(linkId: String): MemberFamilyDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*FAMILY_ROLES)
        val linkUuid = linkId.toUuidOrNotFound("MemberFamilyLink")
        return transaction {
            val row =
                MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.id eq linkUuid }.singleOrNull()
                    ?: throw NotFoundException("MemberFamilyLink $linkId not found")
            val familyUuid = row[MemberFamilyLinkTable.familyId]
            // Setzt bewusst KEINEN Tarif -- siehe IMemberFamilyService.removeFamilyMember KDoc. Die
            // Familie bleibt bestehen, auch wenn sie danach leer oder zahlerlos ist.
            MemberFamilyLinkTable.deleteWhere { MemberFamilyLinkTable.id eq linkUuid }
            logger.info {
                "member family member removed: actor=${current.memberId} actorRole=${current.role} familyId=$familyUuid linkId=$linkUuid"
            }
            loadFamilyDetailOrThrow(familyUuid)
        }
    }

    override suspend fun changePayer(
        familyId: String,
        newPayerMemberId: String,
    ): MemberFamilyDetailDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*FAMILY_ROLES)
        val familyUuid = familyId.toUuidOrNotFound("MemberFamily")
        val newPayerUuid = newPayerMemberId.toUuidOrBadRequest("Member")
        val now = DbClock.nowLocalDateTime()
        return transaction {
            requireFamilyExists(familyUuid)
            // Security fix (INFO) -- same eligibility guard createFamily/addFamilyMember already
            // apply to the member they are about to link: existence + not-anonymized. Currently not
            // exploitable (a DSGVO-erased member has their family link hard-removed before/while
            // anonymizedAt is set, see MemberFamilyPersonalData/FoundationPersonalData ordering), but
            // that safety today depends entirely on PersonalDataContributor execution ORDER, not on
            // anything this method itself checks -- restated here structurally, consistent with its
            // two sibling methods.
            requireExistingEligibleMember(newPayerUuid)
            val newPayerLink =
                MemberFamilyLinkTable
                    .selectAll()
                    .where { (MemberFamilyLinkTable.familyId eq familyUuid) and (MemberFamilyLinkTable.memberId eq newPayerUuid) }
                    .singleOrNull()
                    ?: throw BadRequestException("Member $newPayerMemberId is not a link of family $familyId")
            val oldPayerLink =
                MemberFamilyLinkTable
                    .selectAll()
                    .where { (MemberFamilyLinkTable.familyId eq familyUuid) and (MemberFamilyLinkTable.role eq FamilyMemberRole.PAYER) }
                    .singleOrNull()

            // Reihenfolge zwingend (S2 im Plan): erst den ALTEN Zahler demoten, dann den NEUEN
            // befoerdern -- uq_member_family_link_payer ist ein gewoehnlicher, NICHT deferrable
            // Unique-Index und wuerde die umgekehrte Reihenfolge sofort verletzen.
            if (oldPayerLink != null && oldPayerLink[MemberFamilyLinkTable.id] != newPayerLink[MemberFamilyLinkTable.id]) {
                val oldPayerLinkId = oldPayerLink[MemberFamilyLinkTable.id]
                val oldPayerMemberId = oldPayerLink[MemberFamilyLinkTable.memberId]
                // Security fix (MAJOR, self-target/Peer-Schutz) -- demoting the OLD payer nulls
                // THEIR membership_tier_id further down (MembershipTierAssignment.apply), the same
                // write path IMemberService.updateMemberMembershipTier gates behind a self-target
                // block and an ESCALATED_ROLES peer check -- without them here, a BOARD caller who is
                // ALSO the current payer could promote anyone else and thereby null their OWN tier as
                // a side effect, or do the same to a fellow ADMIN/BOARD/TREASURER peer.
                if (oldPayerMemberId == current.memberId) throw ForbiddenException()
                val oldPayerRole = currentAccountRole(oldPayerMemberId)
                if (oldPayerRole != null && oldPayerRole in ESCALATED_ROLES) current.requireRole(AccountRole.ADMIN)
                MemberFamilyLinkTable.update({ MemberFamilyLinkTable.id eq oldPayerLinkId }) {
                    it[role] = FamilyMemberRole.DEPENDENT
                    it[payerFamilyId] = null
                }
                MembershipTierAssignment.apply(
                    targetMemberId = oldPayerMemberId,
                    newTierId = null,
                    actor = current,
                    reason = FAMILY_DEPENDENT_REASON,
                    familyId = familyUuid,
                    now = now,
                )
            }
            if (newPayerLink[MemberFamilyLinkTable.role] != FamilyMemberRole.PAYER) {
                val newPayerLinkId = newPayerLink[MemberFamilyLinkTable.id]
                try {
                    MemberFamilyLinkTable.update({ MemberFamilyLinkTable.id eq newPayerLinkId }) {
                        it[role] = FamilyMemberRole.PAYER
                        it[payerFamilyId] = familyUuid
                    }
                } catch (e: ExposedSQLException) {
                    // Race backstop -- same idiom as createFamily/addFamilyMember above, this time
                    // against uq_member_family_link_payer: a concurrent changePayer on the SAME
                    // family (read-committed does not see the other transaction's not-yet-committed
                    // demotion of the old payer) can still collide here even though the demotion
                    // above already ran in THIS transaction.
                    logger.warn { "MemberFamilyLinkTable.update failed in changePayer: ${e::class.simpleName}" }
                    throw ConflictException("Family $familyId already has a payer being assigned concurrently")
                }
            }
            logger.info {
                "member family payer changed: actor=${current.memberId} actorRole=${current.role} familyId=$familyUuid " +
                    "newPayerMemberId=$newPayerUuid"
            }
            loadFamilyDetailOrThrow(familyUuid)
        }
    }

    /** Role: ADMIN. Never touches any member's `membership_tier_id` -- see interface KDoc. */
    override suspend fun deleteFamily(id: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val familyUuid = id.toUuidOrNotFound("MemberFamily")
        transaction {
            requireFamilyExists(familyUuid)
            MemberFamilyLinkTable.deleteWhere { MemberFamilyLinkTable.familyId eq familyUuid }
            MemberFamilyTable.deleteWhere { MemberFamilyTable.id eq familyUuid }
            logger.info { "member family deleted: actor=${current.memberId} actorRole=${current.role} familyId=$familyUuid" }
        }
    }

    override suspend fun listUpcomingMajorities(windowDays: Int): UpcomingMajorityOverviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*FAMILY_ROLES)
        if (windowDays !in 1..AnniversaryCalendar.MAX_WINDOW_DAYS) {
            throw BadRequestException("windowDays must be in 1..${AnniversaryCalendar.MAX_WINDOW_DAYS}, got $windowDays")
        }
        val today = clock()
        val through = AnniversaryCalendar.windowEnd(today = today, windowDays = windowDays)

        val (dependentCount, withoutDob, rows) =
            transaction {
                val base =
                    MemberFamilyLinkTable
                        .join(MemberTable, JoinType.INNER, MemberFamilyLinkTable.memberId, MemberTable.id)
                        .join(MemberFamilyTable, JoinType.INNER, MemberFamilyLinkTable.familyId, MemberFamilyTable.id)
                val dependentCondition = (MemberFamilyLinkTable.role eq FamilyMemberRole.DEPENDENT) and MemberTable.anonymizedAt.isNull()

                // Security fix (LOW DoS/Skalierung) -- dependentCount/withoutDob computed as SQL
                // COUNTs, not by materializing every DEPENDENT row into the JVM heap first just to
                // call `.size`/`.count { }` on it in Kotlin (same fix shape `listFamilies` above
                // applies). Only rows with a KNOWN date of birth are then actually fetched -- a
                // missing `dateOfBirth` is `continue`d past in the loop below anyway, so there is
                // nothing to gain from loading those rows at all.
                val dependentCount =
                    base
                        .selectAll()
                        .where { dependentCondition }
                        .count()
                        .toInt()
                val withoutDob =
                    base
                        .selectAll()
                        .where { dependentCondition and MemberTable.dateOfBirth.isNull() }
                        .count()
                        .toInt()
                val rows =
                    base
                        .selectAll()
                        .where { dependentCondition and MemberTable.dateOfBirth.isNotNull() }
                        .map { row ->
                            RawDependent(
                                linkId = row[MemberFamilyLinkTable.id],
                                familyId = row[MemberFamilyLinkTable.familyId],
                                familyName = row[MemberFamilyTable.name],
                                memberId = row[MemberFamilyLinkTable.memberId],
                                displayName = row[MemberTable.displayName],
                                dateOfBirth = row[MemberTable.dateOfBirth],
                            )
                        }
                Triple(dependentCount, withoutDob, rows)
            }

        val entries = mutableListOf<UpcomingMajorityEntryDto>()
        for (row in rows) {
            val dob = row.dateOfBirth ?: continue
            val turnsMajorOn = AnniversaryCalendar.nthAnniversary(anniversary = dob, years = FamilyMembershipRules.MAJORITY_AGE_YEARS)
            val alreadyMajor = turnsMajorOn < today
            if (alreadyMajor || turnsMajorOn <= through) {
                entries +=
                    UpcomingMajorityEntryDto(
                        linkId = row.linkId.toString(),
                        familyId = row.familyId.toString(),
                        familyName = row.familyName,
                        memberId = row.memberId.toString(),
                        memberDisplayName = row.displayName,
                        turnsMajorOn = turnsMajorOn,
                        alreadyMajor = alreadyMajor,
                        shiftedFromLeapDay = AnniversaryCalendar.isLeapDayAnniversary(dob),
                    )
            }
        }
        // Ueberfaellige zuerst (aeltestes Datum oben -- alreadyMajor DESC gruppiert sie an den
        // Anfang, turnsMajorOn ASC innerhalb jeder Gruppe sortiert das aelteste Datum zuerst),
        // dann bevorstehende chronologisch, dann Name, dann Id.
        entries.sortWith(
            compareByDescending<UpcomingMajorityEntryDto> { it.alreadyMajor }
                .thenBy { it.turnsMajorOn }
                .thenBy { it.memberDisplayName }
                .thenBy { it.memberId },
        )

        logger.info {
            "member family upcoming majorities read: actor=${current.memberId} actorRole=${current.role} windowDays=$windowDays " +
                "dependentCount=$dependentCount hits=${entries.size}"
        }

        return UpcomingMajorityOverviewDto(
            windowDays = windowDays,
            from = today,
            through = through,
            entries = entries,
            dependentCount = dependentCount,
            dependentsWithoutDateOfBirth = withoutDob,
        )
    }

    private fun loadFamilyDetailOrThrow(familyId: Uuid): MemberFamilyDetailDto {
        val familyRow =
            MemberFamilyTable.selectAll().where { MemberFamilyTable.id eq familyId }.singleOrNull()
                ?: throw NotFoundException("MemberFamily $familyId not found")
        val links =
            linkMemberJoin()
                .selectAll()
                .where { MemberFamilyLinkTable.familyId eq familyId }
                .orderBy(MemberFamilyLinkTable.role to SortOrder.DESC, MemberTable.displayName to SortOrder.ASC)
                .map { it.toLinkDto() }
        return MemberFamilyDetailDto(
            id = familyId.toString(),
            name = familyRow[MemberFamilyTable.name],
            createdById = familyRow[MemberFamilyTable.createdBy].toString(),
            createdAt = familyRow[MemberFamilyTable.createdAt],
            links = links,
        )
    }

    /**
     * Explicit joins throughout -- [MemberFamilyLinkTable] has TWO FKs to [MemberTable]
     * (`member_id`, `linked_by`), same shape [MemberHonorService.honorMemberJoin]'s own KDoc
     * documents as fatal for Exposed's implicit-join inference. Always joins on `member_id` (the
     * roster member, for `memberDisplayName`/`memberStatus`/tier).
     */
    private fun linkMemberJoin() =
        MemberFamilyLinkTable
            .join(MemberTable, JoinType.INNER, MemberFamilyLinkTable.memberId, MemberTable.id)
            .join(MembershipTierTable, JoinType.LEFT, MemberTable.membershipTierId, MembershipTierTable.id)

    private fun requireFamilyExists(familyId: Uuid) {
        val exists = MemberFamilyTable.selectAll().where { MemberFamilyTable.id eq familyId }.count() > 0
        if (!exists) throw NotFoundException("MemberFamily $familyId not found")
    }

    /**
     * Must exist AND not be anonymized -- same posture [MemberHonorService
     * .requireExistingEligibleMember] already establishes, restated here as [BadRequestException]
     * for the same reason: an anonymized member row still exists, it is simply no longer a valid
     * target for a new family link.
     */
    private fun requireExistingEligibleMember(memberId: Uuid) {
        val row =
            MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull()
                ?: throw BadRequestException("Member $memberId not found")
        if (row[MemberTable.anonymizedAt] != null) {
            throw BadRequestException("Member $memberId is anonymized and can no longer join a family")
        }
    }

    /**
     * Security fix (MAJOR, Peer-Schutz) -- same `.forUpdate()`-locked helper
     * [MemberService.currentAccountRole] already establishes for the identical peer-protection
     * purpose: without the lock, this read could race a concurrent `updateMemberRole` transaction
     * under READ COMMITTED and observe a stale (pre-escalation) role. `internal`, not `private` --
     * mirrors [MemberService]'s own copy rather than sharing one file for a five-line helper, same
     * "no shared file for a three-line helper" precedent [containsPattern] already sets in this file.
     */
    private fun currentAccountRole(memberId: Uuid): AccountRole? =
        AccountTable
            .selectAll()
            .where { AccountTable.memberId eq memberId }
            .forUpdate()
            .singleOrNull()
            ?.get(AccountTable.role)

    /** `uq_member_family_link_member` backstop -- a member belongs to at most one family at a time. */
    private fun requireNotAlreadyInFamily(memberId: Uuid) {
        val exists = MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.memberId eq memberId }.count() > 0
        if (exists) throw ConflictException("Member $memberId already belongs to a family")
    }

    private fun validateName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MemberFamilyLimits.NAME_MAX_LENGTH) {
            throw BadRequestException("name must be non-blank and at most ${MemberFamilyLimits.NAME_MAX_LENGTH} characters")
        }
        return trimmed
    }

    private fun String.toUuidOrNotFound(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }

    private fun String.toUuidOrBadRequest(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw BadRequestException("Invalid $kind id: $this") }
}

private data class RawDependent(
    val linkId: Uuid,
    val familyId: Uuid,
    val familyName: String,
    val memberId: Uuid,
    val displayName: String,
    val dateOfBirth: LocalDate?,
)

private fun ResultRow.toLinkDto(): MemberFamilyLinkDto =
    MemberFamilyLinkDto(
        id = this[MemberFamilyLinkTable.id].toString(),
        familyId = this[MemberFamilyLinkTable.familyId].toString(),
        memberId = this[MemberFamilyLinkTable.memberId].toString(),
        memberDisplayName = this[MemberTable.displayName],
        memberStatus = this[MemberTable.status],
        role = this[MemberFamilyLinkTable.role],
        membershipTierId = this[MemberTable.membershipTierId]?.toString(),
        membershipTierName = this.getOrNull(MembershipTierTable.name),
        linkedAt = this[MemberFamilyLinkTable.linkedAt],
        linkedById = this[MemberFamilyLinkTable.linkedBy].toString(),
    )

/**
 * `%`/`_` in the raw search text are LIKE metacharacters -- same escaping idiom
 * `MemberService.containsPattern` already establishes (each `rpc` file keeps its own private
 * copy, no shared file for a three-line helper).
 */
private fun containsPattern(term: String): LikePattern {
    val escaped = LikePattern.ofLiteral(term)
    return LikePattern("%${escaped.pattern}%", escaped.escapeChar)
}
