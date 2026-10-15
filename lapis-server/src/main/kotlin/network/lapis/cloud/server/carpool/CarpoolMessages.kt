package network.lapis.cloud.server.carpool

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.CarpoolMessageTable
import network.lapis.cloud.server.db.generated.CarpoolMessageThreadTable
import network.lapis.cloud.server.db.generated.CarpoolParticipationTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.CarpoolMessageDto
import network.lapis.cloud.shared.domain.CarpoolMessageLimits
import network.lapis.cloud.shared.domain.CarpoolMessagePageDto
import network.lapis.cloud.shared.domain.CarpoolMessageSide
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** V1.9.139 -- who the caller is within one ride history. [counterpartLabel] is the display name of the other party. */
internal class ThreadAccess(
    val participationId: Uuid,
    val postingId: Uuid,
    val side: CarpoolMessageSide,
    val counterpartId: Uuid,
    val counterpartLabel: String,
    val departureDate: LocalDate,
    val fromPlace: String,
    val toPlace: String,
)

/**
 * V1.9.139 "Mitfahr-Nachrichten" -- the short message history of ONE CONFIRMED participation, visible only to the driver and that passenger.
 * A narrow, deliberate exception to the rule "guests and friends cannot write messages": it is NOT the member-to-member messaging (nothing here
 * touches that service, its insert helper or its tables; `CarpoolMessageNoContentLeakTest` and `CarpoolMessageDmBoundaryTripwireTest` pin this).
 *
 * Every function runs INSIDE the caller's open `transaction {}`. **Privacy by construction**: no logging, no audit entry, no message text in an
 * exception -- the text exists in `carpool_message` and nowhere else (the mail only says that there is something to read).
 *
 * **One gate**: [requireAccess]. Every failure -- a malformed id, an unknown id, a participation that is not CONFIRMED, a third person, a
 * driver who is no longer an active member, an anonymised or no longer eligible counter-party -- is the SAME not-found answer
 * ([CarpoolParticipations.notFound]); the checks that can leak something (length, ended, count, rate) run only AFTER it passed.
 */
internal object CarpoolMessages {
    /** The single gate; see the class KDoc. With [lock] the POSTING row is locked first, then the participation row (the carpool lock order). */
    fun requireAccess(
        callerId: Uuid,
        callerStatus: MemberStatus,
        participationId: Uuid,
        lock: Boolean,
    ): ThreadAccess {
        val first =
            CarpoolParticipationTable.selectAll().where { CarpoolParticipationTable.id eq participationId }.singleOrNull()
                ?: CarpoolParticipations.notFound()
        val postingId = first[CarpoolParticipationTable.postingId]
        val posting =
            (
                if (lock) {
                    CarpoolParticipations.lockPosting(postingId)
                } else {
                    CarpoolPostingTable.selectAll().where { CarpoolPostingTable.id eq postingId }.singleOrNull()
                }
            ) ?: CarpoolParticipations.notFound()
        val part =
            if (lock) {
                CarpoolParticipationTable
                    .selectAll()
                    .where { CarpoolParticipationTable.id eq participationId }
                    .forUpdate()
                    .singleOrNull() ?: CarpoolParticipations.notFound()
            } else {
                first
            }
        if (part[CarpoolParticipationTable.status] != CarpoolParticipationStatus.CONFIRMED ||
            posting[CarpoolPostingTable.type] != CarpoolPostingType.OFFER
        ) {
            CarpoolParticipations.notFound()
        }
        val driverId = posting[CarpoolPostingTable.authorMemberId]
        val passengerId = part[CarpoolParticipationTable.personId]
        val side: CarpoolMessageSide
        val counterpartId: Uuid
        val counterpartAllowed: Set<MemberStatus>
        when (callerId) {
            driverId -> {
                if (callerStatus !in MemberStatusSets.CARPOOL_DRIVER) CarpoolParticipations.notFound()
                side = CarpoolMessageSide.DRIVER
                counterpartId = passengerId
                counterpartAllowed = MemberStatusSets.CARPOOL_PASSENGER
            }
            passengerId -> {
                if (callerStatus !in MemberStatusSets.CARPOOL_PASSENGER) CarpoolParticipations.notFound()
                side = CarpoolMessageSide.PASSENGER
                counterpartId = driverId
                counterpartAllowed = MemberStatusSets.CARPOOL_DRIVER
            }
            else -> CarpoolParticipations.notFound()
        }
        // The access ends for BOTH parties as soon as one of them is blocked, has left or was anonymised.
        val label =
            CarpoolParticipations.displayNameIfEligible(memberId = counterpartId, allowed = counterpartAllowed)
                ?: CarpoolParticipations.notFound()
        return ThreadAccess(
            participationId = participationId,
            postingId = postingId,
            side = side,
            counterpartId = counterpartId,
            counterpartLabel = label,
            departureDate = posting[CarpoolPostingTable.departureDate],
            fromPlace = posting[CarpoolPostingTable.fromPlace],
            toPlace = posting[CarpoolPostingTable.toPlace],
        )
    }

    /** One page, newest first. [beforeId] is a cursor: only messages older than that message of THIS history (a foreign cursor is not-found). */
    fun page(
        access: ThreadAccess,
        beforeId: Uuid?,
    ): CarpoolMessagePageDto {
        val pid = access.participationId
        var condition = CarpoolMessageTable.participationId eq pid
        if (beforeId != null) {
            val cursor =
                CarpoolMessageTable
                    .selectAll()
                    .where { (CarpoolMessageTable.id eq beforeId) and (CarpoolMessageTable.participationId eq pid) }
                    .singleOrNull() ?: CarpoolParticipations.notFound()
            val cursorAt = cursor[CarpoolMessageTable.sentAt]
            condition =
                condition and
                (
                    (CarpoolMessageTable.sentAt less cursorAt) or
                        ((CarpoolMessageTable.sentAt eq cursorAt) and (CarpoolMessageTable.id less beforeId))
                )
        }
        val rows =
            CarpoolMessageTable
                .selectAll()
                .where { condition }
                .orderBy(CarpoolMessageTable.sentAt to SortOrder.DESC, CarpoolMessageTable.id to SortOrder.DESC)
                .limit(CarpoolMessageLimits.PAGE_SIZE + 1)
                .toList()
        val hasMore = rows.size > CarpoolMessageLimits.PAGE_SIZE
        val total = count(pid)
        val thread = threadRow(pid)
        val ended = thread?.get(CarpoolMessageThreadTable.endedAt) != null
        return CarpoolMessagePageDto(
            items = rows.take(CarpoolMessageLimits.PAGE_SIZE).map { it.toDto(access) },
            hasMore = hasMore,
            ended = ended,
            endedByMe = ended && thread?.get(CarpoolMessageThreadTable.endedBySide) == access.side,
            writable = !ended && total < CarpoolMessageLimits.MAX_MESSAGES,
            totalCount = total,
        )
    }

    /**
     * Read-only checks of a send, in the order of the design: ended, empty, too long, full. Returns the normalised text. Run it AFTER
     * [requireAccess], so none of these answers can be elicited by an outsider.
     */
    fun precheckSend(
        access: ThreadAccess,
        body: String,
    ): String {
        if (isEnded(access.participationId)) throw ConflictException("Dieser Verlauf wurde beendet.")
        val normalized = normalize(body)
        if (normalized.isBlank()) throw ConflictException("Bitte geben Sie eine Nachricht ein.")
        if (normalized.length > CarpoolMessageLimits.MAX_BODY_LENGTH) {
            throw ConflictException("Die Nachricht darf höchstens ${CarpoolMessageLimits.MAX_BODY_LENGTH} Zeichen lang sein.")
        }
        if (count(access.participationId) >= CarpoolMessageLimits.MAX_MESSAGES) {
            throw ConflictException("Dieser Verlauf hat die Höchstzahl von ${CarpoolMessageLimits.MAX_MESSAGES} Nachrichten erreicht.")
        }
        return normalized
    }

    /**
     * Stores the message (caller holds the posting lock: [requireAccess] with `lock = true`) after re-checking ended and the count -- the
     * limit of 200 wins a race. Returns the mail notice when one is due: [mailEnabled] and no mail for this history within the last hour.
     */
    fun insert(
        access: ThreadAccess,
        body: String,
        now: LocalDateTime,
        mailEnabled: Boolean,
    ): CarpoolNotice? {
        precheckSend(access = access, body = body)
        val pid = access.participationId
        CarpoolMessageTable.insert {
            it[id] = Uuid.random()
            it[participationId] = pid
            it[senderSide] = access.side
            it[CarpoolMessageTable.body] = normalize(body)
            it[sentAt] = now
        }
        val thread = ensureThread(pid)
        val lastMail = thread[CarpoolMessageThreadTable.lastMailAt]
        val due =
            mailEnabled &&
                (lastMail == null || ServerClock.systemToInstant(now) - ServerClock.systemToInstant(lastMail) >= MAIL_THROTTLE)
        if (!due) return null
        CarpoolMessageThreadTable.update({ CarpoolMessageThreadTable.participationId eq pid }) { it[lastMailAt] = now }
        return CarpoolNotice(
            recipientId = access.counterpartId,
            kind = CarpoolNoticeKind.MESSAGE,
            date = access.departureDate,
            fromPlace = access.fromPlace,
            toPlace = access.toPlace,
        )
    }

    /** One side closes the history for good. Idempotent: a second call changes nothing (the first closer stays). */
    fun end(
        access: ThreadAccess,
        now: LocalDateTime,
    ) {
        val pid = access.participationId
        val thread = ensureThread(pid)
        if (thread[CarpoolMessageThreadTable.endedAt] != null) return
        CarpoolMessageThreadTable.update({ CarpoolMessageThreadTable.participationId eq pid }) {
            it[endedAt] = now
            it[endedBySide] = access.side
        }
    }

    /** Deletes the messages, then the thread rows, of these participations (explicit: the generated tables cannot express ON DELETE CASCADE). */
    fun deleteForParticipations(ids: Collection<Uuid>) {
        ids.toSet().chunked(DELETE_CHUNK).forEach { chunk ->
            CarpoolMessageTable.deleteWhere { CarpoolMessageTable.participationId inList chunk }
            CarpoolMessageThreadTable.deleteWhere { CarpoolMessageThreadTable.participationId inList chunk }
        }
    }

    /** Same for every participation of these postings -- call BEFORE the participations themselves are deleted. */
    fun deleteForPostings(postingIds: Collection<Uuid>) {
        postingIds.toSet().chunked(DELETE_CHUNK).forEach { chunk ->
            val participationIds =
                CarpoolParticipationTable
                    .select(CarpoolParticipationTable.id)
                    .where { CarpoolParticipationTable.postingId inList chunk }
                    .map { it[CarpoolParticipationTable.id] }
            deleteForParticipations(participationIds)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------

    private const val DELETE_CHUNK = 500
    private val MAIL_THROTTLE = 1.hours

    /**
     * Trims the text at its ends only (the inside stays as written), unifies line breaks and removes control characters other than a line
     * break and a tab, plus the bidirectional override characters (they can make a text read differently from what it says). Nothing is
     * escaped: the client only ever creates text nodes.
     */
    internal fun normalize(raw: String): String =
        raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { c -> (c == '\n' || c == '\t' || !c.isISOControl()) && c !in BIDI_CONTROLS }
            .trim()

    private val BIDI_CONTROLS: Set<Char> = ('‪'..'‮').toSet() + ('⁦'..'⁩').toSet()

    private fun count(participationId: Uuid): Int =
        CarpoolMessageTable
            .selectAll()
            .where { CarpoolMessageTable.participationId eq participationId }
            .count()
            .toInt()

    private fun threadRow(participationId: Uuid): ResultRow? =
        CarpoolMessageThreadTable.selectAll().where { CarpoolMessageThreadTable.participationId eq participationId }.singleOrNull()

    private fun isEnded(participationId: Uuid): Boolean = threadRow(participationId)?.get(CarpoolMessageThreadTable.endedAt) != null

    /** Under the posting lock: read, insert when missing (no unique-violation race, so no savepoint is needed). */
    private fun ensureThread(participationId: Uuid): ResultRow {
        threadRow(participationId)?.let { return it }
        CarpoolMessageThreadTable.insert { it[CarpoolMessageThreadTable.participationId] = participationId }
        return threadRow(participationId)!!
    }

    private fun ResultRow.toDto(access: ThreadAccess): CarpoolMessageDto {
        val mine = this[CarpoolMessageTable.senderSide] == access.side
        return CarpoolMessageDto(
            id = this[CarpoolMessageTable.id].toString(),
            mine = mine,
            senderLabel = if (mine) "" else access.counterpartLabel,
            body = this[CarpoolMessageTable.body],
            sentAt = this[CarpoolMessageTable.sentAt],
        )
    }
}
