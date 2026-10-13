package network.lapis.cloud.server.carpool

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toKotlinLocalTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.CarpoolParticipationTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CarpoolMyParticipationDto
import network.lapis.cloud.shared.domain.CarpoolParticipantDto
import network.lapis.cloud.shared.domain.CarpoolParticipationInitiator
import network.lapis.cloud.shared.domain.CarpoolParticipationSnapshot
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPassengerHintDto
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.CarpoolPrivacy
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * V1.9.125 "Mitfahrten" -- the database logic of the participation state machine. Every function runs INSIDE the caller's open
 * `transaction {}` and returns the notifications it wants sent as data ([CarpoolNotice]); the caller sends them only AFTER the commit
 * (Exposed repeats a block on `SQLException`, so a mail inside would be sent twice).
 *
 * **Lock order, everywhere: the POSTING row first (`FOR UPDATE`), then the participation row.** The posting lock serialises every write on one
 * trip: two drivers' confirmations cannot both take the last seat, a parallel `updatePosting(seats = ...)` cannot undercut the confirmed
 * count, and two requests of the same person cannot both insert (no unique-violation race, so no savepoint is needed). The audit write is the
 * LAST lock-taking statement (see [AuditLogRecorder]).
 *
 * **Constant not-found answer**: an unknown id, a malformed id, a REQUEST posting, an expired trip and somebody else's participation are all
 * answered with the SAME [NOT_FOUND_MESSAGE] (never the id) -- a guest must not be able to tell them apart.
 *
 * States: `OPEN` waits for the side that did NOT start it; `CONFIRMED` holds a seat; `DECLINED` (by the counter-party) and `WITHDRAWN` (by either
 * side) are final -- a new request revives the SAME row (one row per posting and person). After a DECLINE the same direction is locked for 24
 * hours.
 */
internal object CarpoolParticipations {
    const val NOT_FOUND_MESSAGE = "Nicht gefunden."
    const val MAX_OPEN_PER_PERSON = 10
    const val MAX_OPEN_PER_POSTING = 20
    const val SHOW_DECIDED_DAYS = 7
    const val MAX_HINT_RANGE_DAYS = 62
    const val MAX_HINT_PASSENGERS = 8
    private val REREQUEST_LOCK = 24.hours

    class Result(
        val participationId: Uuid,
        val postingId: Uuid,
        val status: CarpoolParticipationStatus,
        val initiator: CarpoolParticipationInitiator,
        val notices: List<CarpoolNotice>,
    )

    fun notFound(): Nothing = throw NotFoundException(NOT_FOUND_MESSAGE)

    /** A malformed id is the same "not found" as an unknown one. */
    fun parseId(raw: String): Uuid = runCatching { Uuid.parse(raw) }.getOrElse { notFound() }

    /** `SELECT ... FOR UPDATE` on the bare posting row (never on a join: Postgres would lock the member row too). */
    fun lockPosting(postingId: Uuid): ResultRow? =
        CarpoolPostingTable
            .selectAll()
            .where { CarpoolPostingTable.id eq postingId }
            .forUpdate()
            .singleOrNull()

    fun confirmedCount(postingId: Uuid): Int =
        CarpoolParticipationTable
            .selectAll()
            .where {
                (CarpoolParticipationTable.postingId eq postingId) and
                    (CarpoolParticipationTable.status eq CarpoolParticipationStatus.CONFIRMED)
            }.count()
            .toInt()

    /** Confirmed seats per posting, one grouped query for a whole feed (no N+1). */
    fun confirmedCounts(postingIds: Collection<Uuid>): Map<Uuid, Int> =
        countsByStatus(postingIds = postingIds, status = CarpoolParticipationStatus.CONFIRMED, initiator = null)

    /** Participants waiting for the DRIVER's decision (OPEN, asked by the person), per posting. */
    fun openRequestCounts(postingIds: Collection<Uuid>): Map<Uuid, Int> =
        countsByStatus(
            postingIds = postingIds,
            status = CarpoolParticipationStatus.OPEN,
            initiator = CarpoolParticipationInitiator.PASSENGER,
        )

    private fun countsByStatus(
        postingIds: Collection<Uuid>,
        status: CarpoolParticipationStatus,
        initiator: CarpoolParticipationInitiator?,
    ): Map<Uuid, Int> {
        if (postingIds.isEmpty()) return emptyMap()
        val counter = CarpoolParticipationTable.id.count()
        return CarpoolParticipationTable
            .select(CarpoolParticipationTable.postingId, counter)
            .where {
                (CarpoolParticipationTable.postingId inList postingIds.toSet()) and
                    (CarpoolParticipationTable.status eq status) and
                    (initiator?.let { CarpoolParticipationTable.initiator eq it } ?: org.jetbrains.exposed.v1.core.Op.TRUE)
            }.groupBy(CarpoolParticipationTable.postingId)
            .associate { it[CarpoolParticipationTable.postingId] to it[counter].toInt() }
    }

    /** The caller's own participation per posting (never a foreign one). */
    fun ownParticipations(
        postingIds: Collection<Uuid>,
        personId: Uuid,
    ): Map<Uuid, ResultRow> {
        if (postingIds.isEmpty()) return emptyMap()
        return CarpoolParticipationTable
            .selectAll()
            .where { (CarpoolParticipationTable.personId eq personId) and (CarpoolParticipationTable.postingId inList postingIds.toSet()) }
            .associateBy { it[CarpoolParticipationTable.postingId] }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // Transitions
    // ---------------------------------------------------------------------------------------------------------------------------------

    /** A person asks to ride along on an OFFER. Idempotent while OPEN/CONFIRMED. */
    fun request(
        actor: CurrentMember,
        postingId: Uuid,
        today: LocalDate,
        now: LocalDateTime,
    ): Result {
        val posting = requireOpenOffer(postingId = postingId, today = today)
        val authorId = posting[CarpoolPostingTable.authorMemberId]
        if (authorId == actor.memberId) throw ConflictException("Auf Ihrer eigenen Fahrt können Sie nicht mitfahren.")
        val existing = findRow(postingId = postingId, personId = actor.memberId)
        if (existing != null) {
            when (existing[CarpoolParticipationTable.status]) {
                CarpoolParticipationStatus.OPEN, CarpoolParticipationStatus.CONFIRMED -> return resultOf(
                    row = existing,
                    notices = emptyList(),
                )
                CarpoolParticipationStatus.DECLINED ->
                    // Declined by the DRIVER = the person had asked. A decline the person made themselves (the driver had invited) is no lock for them.
                    if (existing[CarpoolParticipationTable.initiator] == CarpoolParticipationInitiator.PASSENGER &&
                        isRecent(decidedAt = existing[CarpoolParticipationTable.decidedAt], now = now)
                    ) {
                        throw ConflictException("Sie können diese Fahrt frühestens 24 Stunden nach der Ablehnung erneut anfragen.")
                    }
                CarpoolParticipationStatus.WITHDRAWN -> Unit
            }
        }
        if (openCountOfPerson(actor.memberId) >=
            MAX_OPEN_PER_PERSON
        ) {
            throw ConflictException("Sie haben bereits $MAX_OPEN_PER_PERSON offene Anfragen.")
        }
        if (openCountOfPosting(postingId) >= MAX_OPEN_PER_POSTING) {
            throw ConflictException("Für diese Fahrt liegen bereits zu viele offene Anfragen vor.")
        }
        val id =
            openRow(
                existing = existing,
                postingId = postingId,
                personId = actor.memberId,
                initiatorOfNew = CarpoolParticipationInitiator.PASSENGER,
                now = now,
            )
        audit(
            actor = actor,
            participationId = id,
            postingId = postingId,
            created = existing == null,
            initiator = CarpoolParticipationInitiator.PASSENGER,
            status = CarpoolParticipationStatus.OPEN,
            now = now,
        )
        return Result(
            participationId = id,
            postingId = postingId,
            status = CarpoolParticipationStatus.OPEN,
            initiator = CarpoolParticipationInitiator.PASSENGER,
            notices = listOf(notice(recipientId = authorId, kind = CarpoolNoticeKind.REQUESTED, posting = posting)),
        )
    }

    /** The driver (author) invites [personId]. Every refusal that could reveal something about the target is the same generic conflict. */
    fun invite(
        actor: CurrentMember,
        postingId: Uuid,
        personId: Uuid,
        today: LocalDate,
        now: LocalDateTime,
    ): Result {
        val posting = requireOpenOffer(postingId = postingId, today = today)
        if (posting[CarpoolPostingTable.authorMemberId] != actor.memberId) notFound()
        if (personId == actor.memberId) throw ConflictException("Auf Ihrer eigenen Fahrt können Sie nicht mitfahren.")
        if (!isEligible(memberId = personId, allowed = MemberStatusSets.CARPOOL_PASSENGER)) throw ConflictException(INVITE_NOT_POSSIBLE)
        val existing = findRow(postingId = postingId, personId = personId)
        if (existing != null) {
            when (existing[CarpoolParticipationTable.status]) {
                CarpoolParticipationStatus.OPEN, CarpoolParticipationStatus.CONFIRMED -> return resultOf(
                    row = existing,
                    notices = emptyList(),
                )
                CarpoolParticipationStatus.DECLINED ->
                    // Declined by the PERSON = the driver had invited. Symmetric 24-hour lock.
                    if (existing[CarpoolParticipationTable.initiator] == CarpoolParticipationInitiator.DRIVER &&
                        isRecent(decidedAt = existing[CarpoolParticipationTable.decidedAt], now = now)
                    ) {
                        throw ConflictException("Sie können diese Person frühestens 24 Stunden nach der Ablehnung erneut einladen.")
                    }
                CarpoolParticipationStatus.WITHDRAWN -> Unit
            }
        }
        if (openCountOfPerson(personId) >= MAX_OPEN_PER_PERSON || openCountOfPosting(postingId) >= MAX_OPEN_PER_POSTING) {
            throw ConflictException(INVITE_NOT_POSSIBLE)
        }
        val id =
            openRow(
                existing = existing,
                postingId = postingId,
                personId = personId,
                initiatorOfNew = CarpoolParticipationInitiator.DRIVER,
                now = now,
            )
        audit(
            actor = actor,
            participationId = id,
            postingId = postingId,
            created = existing == null,
            initiator = CarpoolParticipationInitiator.DRIVER,
            status = CarpoolParticipationStatus.OPEN,
            now = now,
        )
        return Result(
            participationId = id,
            postingId = postingId,
            status = CarpoolParticipationStatus.OPEN,
            initiator = CarpoolParticipationInitiator.DRIVER,
            notices = listOf(notice(recipientId = personId, kind = CarpoolNoticeKind.INVITED, posting = posting)),
        )
    }

    /** The counter-party of the initiator confirms an OPEN participation; the last seat is taken under the posting lock. */
    fun confirm(
        actor: CurrentMember,
        participationId: Uuid,
        today: LocalDate,
        now: LocalDateTime,
    ): Result {
        val loaded = load(participationId = participationId, today = today, actorId = actor.memberId)
        requireCounterParty(loaded = loaded, actorId = actor.memberId)
        requireOpen(loaded.part)
        val seats = loaded.posting[CarpoolPostingTable.seatsOffered] ?: 0
        if (confirmedCount(loaded.postingId) >= seats) throw ConflictException("Für diese Fahrt ist kein Platz mehr frei.")
        // Both sides must still be eligible NOW (the status of a person can change between the request and the answer).
        if (!isEligible(memberId = loaded.authorId, allowed = MemberStatusSets.CARPOOL_DRIVER) ||
            !isEligible(memberId = loaded.personId, allowed = MemberStatusSets.CARPOOL_PASSENGER)
        ) {
            notFound()
        }
        return decide(
            actor = actor,
            loaded = loaded,
            newStatus = CarpoolParticipationStatus.CONFIRMED,
            kind = CarpoolNoticeKind.CONFIRMED,
            now = now,
            notify = loaded.initiatorId,
        )
    }

    fun decline(
        actor: CurrentMember,
        participationId: Uuid,
        today: LocalDate,
        now: LocalDateTime,
    ): Result {
        val loaded = load(participationId = participationId, today = today, actorId = actor.memberId)
        requireCounterParty(loaded = loaded, actorId = actor.memberId)
        requireOpen(loaded.part)
        return decide(
            actor = actor,
            loaded = loaded,
            newStatus = CarpoolParticipationStatus.DECLINED,
            kind = CarpoolNoticeKind.DECLINED,
            now = now,
            notify = loaded.initiatorId,
        )
    }

    /** The person or the driver steps back, from OPEN or CONFIRMED (a confirmed seat is released). */
    fun withdraw(
        actor: CurrentMember,
        participationId: Uuid,
        today: LocalDate,
        now: LocalDateTime,
    ): Result {
        val loaded = load(participationId = participationId, today = today, actorId = actor.memberId)
        val status = loaded.part[CarpoolParticipationTable.status]
        if (status != CarpoolParticipationStatus.OPEN &&
            status != CarpoolParticipationStatus.CONFIRMED
        ) {
            throw ConflictException(WRONG_STATE)
        }
        val other = if (actor.memberId == loaded.personId) loaded.authorId else loaded.personId
        return decide(
            actor = actor,
            loaded = loaded,
            newStatus = CarpoolParticipationStatus.WITHDRAWN,
            kind = CarpoolNoticeKind.WITHDRAWN,
            now = now,
            notify = other,
        )
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------------------------------------------------

    fun myParticipation(
        participationId: Uuid,
        today: LocalDate,
    ): CarpoolMyParticipationDto =
        joinedForPerson()
            .where { CarpoolParticipationTable.id eq participationId }
            .single()
            .toMyDto(today)

    /** "Meine Mitfahrten": waiting items first; a declined/withdrawn entry disappears [SHOW_DECIDED_DAYS] days after the decision. */
    fun listMine(
        personId: Uuid,
        today: LocalDate,
        now: LocalDateTime,
    ): List<CarpoolMyParticipationDto> {
        val cutoff = decidedCutoff(now)
        return joinedForPerson()
            .where {
                (CarpoolParticipationTable.personId eq personId) and
                    (CarpoolPostingTable.type eq CarpoolPostingType.OFFER) and
                    visibleByDecision(cutoff)
            }.map { it.toMyDto(today) }
            .sortedWith(
                compareByDescending<CarpoolMyParticipationDto> { it.awaitingMe }
                    .thenBy { it.isPast }
                    .thenBy { it.departureDate }
                    .thenBy { it.departureTime },
            )
    }

    /** The driver's own participant list; anybody but the author gets the constant not-found answer. */
    fun listParticipants(
        driverId: Uuid,
        postingId: Uuid,
        now: LocalDateTime,
    ): List<CarpoolParticipantDto> {
        val posting = CarpoolPostingTable.selectAll().where { CarpoolPostingTable.id eq postingId }.singleOrNull() ?: notFound()
        if (posting[CarpoolPostingTable.authorMemberId] != driverId ||
            posting[CarpoolPostingTable.type] != CarpoolPostingType.OFFER
        ) {
            notFound()
        }
        val cutoff = decidedCutoff(now)
        return CarpoolParticipationTable
            .join(MemberTable, JoinType.INNER, CarpoolParticipationTable.personId, MemberTable.id)
            .selectAll()
            .where { (CarpoolParticipationTable.postingId eq postingId) and visibleByDecision(cutoff) }
            .map { row ->
                val status = row[CarpoolParticipationTable.status]
                val initiator = row[CarpoolParticipationTable.initiator]
                CarpoolParticipantDto(
                    id = row[CarpoolParticipationTable.id].toString(),
                    displayName = row[MemberTable.displayName],
                    memberStatus = row[MemberTable.status],
                    status = status,
                    initiator = initiator,
                    awaitingMe = status == CarpoolParticipationStatus.OPEN && initiator == CarpoolParticipationInitiator.PASSENGER,
                    decidedAt = row[CarpoolParticipationTable.decidedAt],
                    createdAt = row[CarpoolParticipationTable.createdAt],
                )
            }.sortedWith(compareByDescending<CarpoolParticipantDto> { it.awaitingMe }.thenBy { it.createdAt })
    }

    /** Numbers only: the confirmed passengers on the caller's OWN offers between [from] and [to], or `0` when there are none. */
    fun passengerHint(
        driverId: Uuid,
        from: LocalDate,
        to: LocalDate,
    ): CarpoolPassengerHintDto {
        if (to < from || from.daysUntil(to) > MAX_HINT_RANGE_DAYS) throw ConflictException("Der Zeitraum ist ungültig oder zu lang.")
        val postingIds =
            CarpoolPostingTable
                .select(CarpoolPostingTable.id)
                .where {
                    (CarpoolPostingTable.authorMemberId eq driverId) and
                        (CarpoolPostingTable.type eq CarpoolPostingType.OFFER) and
                        (CarpoolPostingTable.departureDate greaterEq from) and
                        (CarpoolPostingTable.departureDate lessEq to)
                }.map { it[CarpoolPostingTable.id] }
        val counts = confirmedCounts(postingIds).values.filter { it > 0 }
        if (counts.isEmpty()) return CarpoolPassengerHintDto(confirmedPassengers = 0, multipleTrips = false)
        return CarpoolPassengerHintDto(confirmedPassengers = minOf(MAX_HINT_PASSENGERS, counts.max()), multipleTrips = counts.size > 1)
    }

    /** Everybody with an OPEN or CONFIRMED participation on [postingId], to tell them about a change or deletion of the trip. */
    fun activeParticipants(postingId: Uuid): List<Uuid> =
        CarpoolParticipationTable
            .selectAll()
            .where {
                (CarpoolParticipationTable.postingId eq postingId) and
                    (CarpoolParticipationTable.status inList listOf(CarpoolParticipationStatus.OPEN, CarpoolParticipationStatus.CONFIRMED))
            }.map { it[CarpoolParticipationTable.personId] }

    fun deleteAllOf(postingId: Uuid) {
        CarpoolParticipationTable.deleteWhere { CarpoolParticipationTable.postingId eq postingId }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------------------------------------------

    private const val WRONG_STATE = "Diese Aktion ist im aktuellen Zustand nicht möglich."
    private const val INVITE_NOT_POSSIBLE = "Einladung nicht möglich."

    private class Loaded(
        val part: ResultRow,
        val posting: ResultRow,
    ) {
        val postingId: Uuid get() = posting[CarpoolPostingTable.id]
        val authorId: Uuid get() = posting[CarpoolPostingTable.authorMemberId]
        val personId: Uuid get() = part[CarpoolParticipationTable.personId]
        val initiatorId: Uuid
            get() = if (part[CarpoolParticipationTable.initiator] == CarpoolParticipationInitiator.DRIVER) authorId else personId
        val counterPartyId: Uuid get() = if (initiatorId == authorId) personId else authorId
    }

    private fun requireOpenOffer(
        postingId: Uuid,
        today: LocalDate,
    ): ResultRow {
        val posting = lockPosting(postingId) ?: notFound()
        if (posting[CarpoolPostingTable.type] != CarpoolPostingType.OFFER || posting[CarpoolPostingTable.departureDate] < today) notFound()
        return posting
    }

    /** Reads the participation without a lock to learn the posting, locks the POSTING, re-reads the participation under its own lock. */
    private fun load(
        participationId: Uuid,
        today: LocalDate,
        actorId: Uuid,
    ): Loaded {
        val first =
            CarpoolParticipationTable.selectAll().where { CarpoolParticipationTable.id eq participationId }.singleOrNull() ?: notFound()
        val posting = requireOpenOffer(postingId = first[CarpoolParticipationTable.postingId], today = today)
        val part =
            CarpoolParticipationTable
                .selectAll()
                .where { CarpoolParticipationTable.id eq participationId }
                .forUpdate()
                .singleOrNull() ?: notFound()
        val loaded = Loaded(part = part, posting = posting)
        if (actorId != loaded.personId && actorId != loaded.authorId) notFound()
        return loaded
    }

    private fun requireCounterParty(
        loaded: Loaded,
        actorId: Uuid,
    ) {
        if (actorId == loaded.initiatorId) throw ConflictException("Sie können Ihre eigene Anfrage nicht bestätigen.")
        if (actorId != loaded.counterPartyId) notFound()
    }

    private fun requireOpen(part: ResultRow) {
        if (part[CarpoolParticipationTable.status] != CarpoolParticipationStatus.OPEN) throw ConflictException(WRONG_STATE)
    }

    private fun decide(
        actor: CurrentMember,
        loaded: Loaded,
        newStatus: CarpoolParticipationStatus,
        kind: CarpoolNoticeKind,
        now: LocalDateTime,
        notify: Uuid,
    ): Result {
        val id = loaded.part[CarpoolParticipationTable.id]
        CarpoolParticipationTable.update({ CarpoolParticipationTable.id eq id }) {
            it[status] = newStatus
            it[decidedAt] = now
            it[updatedAt] = now
        }
        val initiator = loaded.part[CarpoolParticipationTable.initiator]
        audit(
            actor = actor,
            participationId = id,
            postingId = loaded.postingId,
            created = false,
            initiator = initiator,
            status = newStatus,
            now = now,
        )
        return Result(
            participationId = id,
            postingId = loaded.postingId,
            status = newStatus,
            initiator = initiator,
            notices = listOf(notice(recipientId = notify, kind = kind, posting = loaded.posting)),
        )
    }

    private fun findRow(
        postingId: Uuid,
        personId: Uuid,
    ): ResultRow? =
        CarpoolParticipationTable
            .selectAll()
            .where { (CarpoolParticipationTable.postingId eq postingId) and (CarpoolParticipationTable.personId eq personId) }
            .forUpdate()
            .singleOrNull()

    /** Inserts a new OPEN row, or revives the existing final one (never a second row per posting and person). */
    private fun openRow(
        existing: ResultRow?,
        postingId: Uuid,
        personId: Uuid,
        initiatorOfNew: CarpoolParticipationInitiator,
        now: LocalDateTime,
    ): Uuid {
        if (existing == null) {
            val id = Uuid.random()
            CarpoolParticipationTable.insert {
                it[CarpoolParticipationTable.id] = id
                it[CarpoolParticipationTable.postingId] = postingId
                it[CarpoolParticipationTable.personId] = personId
                it[initiator] = initiatorOfNew
                it[status] = CarpoolParticipationStatus.OPEN
                it[createdAt] = now
                it[updatedAt] = now
                it[decidedAt] = null
            }
            return id
        }
        val id = existing[CarpoolParticipationTable.id]
        CarpoolParticipationTable.update({ CarpoolParticipationTable.id eq id }) {
            it[initiator] = initiatorOfNew
            it[status] = CarpoolParticipationStatus.OPEN
            it[updatedAt] = now
            it[decidedAt] = null
        }
        return id
    }

    private fun openCountOfPerson(personId: Uuid): Int =
        CarpoolParticipationTable
            .selectAll()
            .where {
                (CarpoolParticipationTable.personId eq personId) and
                    (CarpoolParticipationTable.status eq CarpoolParticipationStatus.OPEN)
            }.count()
            .toInt()

    private fun openCountOfPosting(postingId: Uuid): Int =
        CarpoolParticipationTable
            .selectAll()
            .where {
                (CarpoolParticipationTable.postingId eq postingId) and
                    (CarpoolParticipationTable.status eq CarpoolParticipationStatus.OPEN)
            }.count()
            .toInt()

    /** The member exists, is not anonymised and has a status in [allowed] (plain read, no lock). */
    private fun isEligible(
        memberId: Uuid,
        allowed: Set<network.lapis.cloud.shared.domain.MemberStatus>,
    ): Boolean {
        val row = MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull() ?: return false
        val status = row[MemberTable.status]
        if (status !in allowed || row[MemberTable.anonymizedAt] != null) return false
        // Same rule as the passenger guard of the service: an unverified FRIEND cannot act, so it must not hold a seat either.
        val unverifiedFriend =
            status == MemberStatus.FRIEND && CarpoolFriendConfig.requireEmailVerification() && row[MemberTable.emailVerifiedAt] == null
        return !unverifiedFriend
    }

    /** `true` when [decidedAt] is less than 24 hours before [now] (class A against class A). */
    private fun isRecent(
        decidedAt: LocalDateTime?,
        now: LocalDateTime,
    ): Boolean {
        if (decidedAt == null) return false
        return ServerClock.systemToInstant(now) - ServerClock.systemToInstant(decidedAt) < REREQUEST_LOCK
    }

    private fun decidedCutoff(now: LocalDateTime): LocalDateTime =
        (ServerClock.systemToInstant(now) - (SHOW_DECIDED_DAYS * 24).hours).toLocalDateTime(ServerClock.zone)

    private fun visibleByDecision(cutoff: LocalDateTime) =
        (CarpoolParticipationTable.status inList listOf(CarpoolParticipationStatus.OPEN, CarpoolParticipationStatus.CONFIRMED)) or
            (CarpoolParticipationTable.decidedAt greater cutoff)

    private fun resultOf(
        row: ResultRow,
        notices: List<CarpoolNotice>,
    ) = Result(
        participationId = row[CarpoolParticipationTable.id],
        postingId = row[CarpoolParticipationTable.postingId],
        status = row[CarpoolParticipationTable.status],
        initiator = row[CarpoolParticipationTable.initiator],
        notices = notices,
    )

    private fun notice(
        recipientId: Uuid,
        kind: CarpoolNoticeKind,
        posting: ResultRow,
    ) = CarpoolNotice(
        recipientId = recipientId,
        kind = kind,
        date = posting[CarpoolPostingTable.departureDate],
        fromPlace = posting[CarpoolPostingTable.fromPlace],
        toPlace = posting[CarpoolPostingTable.toPlace],
    )

    private fun audit(
        actor: CurrentMember,
        participationId: Uuid,
        postingId: Uuid,
        created: Boolean,
        initiator: CarpoolParticipationInitiator,
        status: CarpoolParticipationStatus,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.CARPOOL_PARTICIPATION,
            entityId = participationId,
            action = if (created) AuditAction.CREATE else AuditAction.UPDATE,
            after =
                Json.encodeToString(
                    CarpoolParticipationSnapshot.serializer(),
                    CarpoolParticipationSnapshot(postingId = postingId.toString(), initiator = initiator.name, status = status.name),
                ),
            occurredAt = now,
        )
    }

    private fun joinedForPerson() =
        CarpoolParticipationTable
            .join(CarpoolPostingTable, JoinType.INNER, CarpoolParticipationTable.postingId, CarpoolPostingTable.id)
            .join(MemberTable, JoinType.INNER, CarpoolPostingTable.authorMemberId, MemberTable.id)
            .selectAll()

    private fun ResultRow.toMyDto(today: LocalDate): CarpoolMyParticipationDto {
        val status = this[CarpoolParticipationTable.status]
        val initiator = this[CarpoolParticipationTable.initiator]
        return CarpoolMyParticipationDto(
            id = this[CarpoolParticipationTable.id].toString(),
            postingId = this[CarpoolParticipationTable.postingId].toString(),
            fromPlace = this[CarpoolPostingTable.fromPlace],
            toPlace = this[CarpoolPostingTable.toPlace],
            departureDate = this[CarpoolPostingTable.departureDate],
            departureTime = this[CarpoolPostingTable.departureTime]?.toKotlinLocalTime(),
            // Only once the person is part of the ride (confirmed, or the driver invited them) -- see CarpoolPrivacy.
            driverDisplayName =
                if (status == CarpoolParticipationStatus.CONFIRMED || initiator == CarpoolParticipationInitiator.DRIVER) {
                    this[MemberTable.displayName]
                } else {
                    CarpoolPrivacy.HIDDEN_NAME
                },
            status = status,
            initiator = initiator,
            awaitingMe = status == CarpoolParticipationStatus.OPEN && initiator == CarpoolParticipationInitiator.DRIVER,
            meetingNote = if (status == CarpoolParticipationStatus.CONFIRMED) this[CarpoolPostingTable.meetingNote] else null,
            isPast = this[CarpoolPostingTable.departureDate] < today,
            decidedAt = this[CarpoolParticipationTable.decidedAt],
            createdAt = this[CarpoolParticipationTable.createdAt],
        )
    }
}
