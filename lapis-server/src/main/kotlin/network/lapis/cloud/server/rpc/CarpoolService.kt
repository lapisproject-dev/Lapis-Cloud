package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalTime
import network.lapis.cloud.server.carpool.CarpoolAction
import network.lapis.cloud.server.carpool.CarpoolDeps
import network.lapis.cloud.server.carpool.CarpoolInviteeSearch
import network.lapis.cloud.server.carpool.CarpoolNotice
import network.lapis.cloud.server.carpool.CarpoolNoticeKind
import network.lapis.cloud.server.carpool.CarpoolParticipations
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CarpoolParticipationTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.CarpoolInviteeCandidateDto
import network.lapis.cloud.shared.domain.CarpoolMyParticipationDto
import network.lapis.cloud.shared.domain.CarpoolMyParticipationRef
import network.lapis.cloud.shared.domain.CarpoolParticipantDto
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPassengerHintDto
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.CarpoolPrivacy
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.ICarpoolService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val MAX_FEED_SIZE = 200
private const val MAX_MEETING_NOTE_LENGTH = 200

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- Fahrten anbieten/suchen, bearbeiten, löschen und die Kontaktaufnahme sind ORGANIZATION_MEMBER-
 * exklusiv ([requireActiveMembership]). Kein eigenes `NavVisibility`-Prädikat für ACTIVE (Repo-Konvention): innerhalb der Sidebar-Gruppe
 * `MEMBERSHIP` fällt die Mitfahrerzentrale unter das gruppenweite `showsMembershipSection`.
 *
 * **V1.9.125 "Mitfahrten" -- ÄNDERUNG DER ZUGRIFFSREGEL.** Eine namentliche Allowlist von sechs Methoden steht zusätzlich ACTIVE, GUEST und
 * FRIEND offen ([requireCarpoolPassengerMembership], [MemberStatusSets.CARPOOL_PASSENGER]): [listPostings] (für Nicht-Mitglieder nur
 * zukünftige Angebote, ohne Freitext), [requestParticipation], [confirmParticipation], [declineParticipation], [withdrawParticipation],
 * [listMyParticipations]. Alles andere bleibt [requireActiveMembership]. `CarpoolAccessTripwireTest` pinnt die Methodenliste und verbietet jede
 * weitere Verwendung des Passagier-Guards.
 *
 * **Feed-Sichtbarkeit vs. endgültige Löschung**: [listPostings] zeigt nur `departure_date >= heute` -- ein Eintrag verschwindet aus dem Feed
 * am Tag NACH der Abfahrt, wird aber erst [network.lapis.cloud.server.carpool.CarpoolRetention.RETENTION_DAYS_AFTER_DEPARTURE] Tage später
 * endgültig gelöscht (samt Mitfahrten), damit ein Mitglied eine knapp abgelaufene eigene Fahrt noch kurz duplizieren kann
 * ([listMyPostings] zeigt eigene abgelaufene Einträge weiterhin, markiert als `isPast`).
 *
 * **Kontaktaufnahme** läuft über eine ganz normale Direktnachricht ([contactAuthor] ruft [insertDirectMessage]) -- kein eigenes
 * Carpool-Nachrichtensystem, kein `authorMemberId`-Feld im DTO (Larry Teslers Auflage). Gäste und Freunde können keine Direktnachricht
 * senden oder lesen; für sie gibt es das Treffpunkt-Feld ([setMeetingNote]), sichtbar nur für den Fahrer und bestätigte Mitfahrer.
 *
 * Die Zustandslogik der Mitfahrten liegt in [CarpoolParticipations] (Sperrreihenfolge: erst die Fahrt, dann die Mitfahrt). Hier wird sie nur
 * mit Zugriffsprüfung, Ratenbegrenzung (VOR der Transaktion) und dem Versand der Benachrichtigungen NACH dem Commit verdrahtet.
 */
class CarpoolService internal constructor(
    private val call: ApplicationCall,
    private val deps: CarpoolDeps,
) : ICarpoolService {
    constructor(call: ApplicationCall) : this(call = call, deps = CarpoolDeps())

    override suspend fun listPostings(type: CarpoolPostingType?): List<CarpoolPostingDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            val status = requireCarpoolPassengerMembership(memberId = current.memberId)
            val isMember = status in MemberStatusSets.CARPOOL_DRIVER
            val today = OrganizationTimeZone.today()
            // A guest or friend only ever sees offers (a request is a member's own search) -- the type filter is ignored for them.
            val effectiveType = if (isMember) type else CarpoolPostingType.OFFER
            val rows =
                baseQuery()
                    .where {
                        (CarpoolPostingTable.departureDate greaterEq today) and
                            (effectiveType?.let { CarpoolPostingTable.type eq it } ?: Op.TRUE)
                    }.orderBy(
                        CarpoolPostingTable.departureDate to SortOrder.ASC,
                        CarpoolPostingTable.departureTime to SortOrder.ASC_NULLS_LAST,
                        CarpoolPostingTable.createdAt to SortOrder.ASC,
                    ).limit(MAX_FEED_SIZE)
                    .toList()
            toDtos(rows = rows, currentMemberId = current.memberId, today = today, isMember = isMember)
        }
    }

    override suspend fun listMyPostings(): List<CarpoolPostingDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val today = OrganizationTimeZone.today()
            val rows =
                baseQuery()
                    .where { CarpoolPostingTable.authorMemberId eq current.memberId }
                    .orderBy(
                        CarpoolPostingTable.departureDate to SortOrder.DESC,
                        CarpoolPostingTable.departureTime to SortOrder.DESC_NULLS_LAST,
                    ).toList()
            toDtos(rows = rows, currentMemberId = current.memberId, today = today, isMember = true)
        }
    }

    override suspend fun createPosting(input: CarpoolPostingInput): CarpoolPostingDto {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val today = OrganizationTimeZone.today()
            val validated = CarpoolValidation.validate(input = input, today = today)
            CarpoolValidation.requireUnderQuota(authorMemberId = current.memberId, today = today)
            val now = DbClock.nowLocalDateTime()
            val id = Uuid.random()
            CarpoolPostingTable.insert {
                it[CarpoolPostingTable.id] = id
                it[authorMemberId] = current.memberId
                it[type] = validated.type
                it[fromPlace] = validated.fromPlace
                it[toPlace] = validated.toPlace
                it[departureDate] = validated.departureDate
                it[departureTime] = validated.departureTime?.toJavaLocalTime()
                it[seatsOffered] = validated.seatsOffered
                it[notes] = validated.notes
                it[createdAt] = now
                it[updatedAt] = now
            }
            singleDto(id = id, currentMemberId = current.memberId, today = today)
        }
    }

    override suspend fun updatePosting(
        id: String,
        input: CarpoolPostingInput,
    ): CarpoolPostingDto {
        val current = resolveCurrentMember(call)
        val (dto, notices) =
            transaction {
                requireActiveMembership(memberId = current.memberId)
                val postingId = CarpoolParticipations.parseId(id)
                val today = OrganizationTimeZone.today()
                // The posting lock comes first, before anything else touches this trip's participations.
                val existing = CarpoolParticipations.lockPosting(postingId) ?: CarpoolParticipations.notFound()
                if (existing[CarpoolPostingTable.authorMemberId] != current.memberId) {
                    throw ForbiddenException("Only the author may edit this posting")
                }
                val validated = CarpoolValidation.validate(input = input, today = today)
                CarpoolValidation.requireUnderQuota(authorMemberId = current.memberId, today = today, excludingPostingId = postingId)
                val active = CarpoolParticipations.activeParticipants(postingId)
                val confirmed = CarpoolParticipations.confirmedCount(postingId)
                if (validated.type == CarpoolPostingType.REQUEST && active.isNotEmpty()) {
                    throw ConflictException("Diese Fahrt hat offene oder bestätigte Mitfahrten und kann kein Gesuch werden.")
                }
                if (validated.type == CarpoolPostingType.OFFER && (validated.seatsOffered ?: 0) < confirmed) {
                    throw ConflictException("Es sind bereits $confirmed Mitfahrten bestätigt.")
                }
                val changedForPassengers =
                    existing[CarpoolPostingTable.fromPlace] != validated.fromPlace ||
                        existing[CarpoolPostingTable.toPlace] != validated.toPlace ||
                        existing[CarpoolPostingTable.departureDate] != validated.departureDate ||
                        existing[CarpoolPostingTable.departureTime]?.toKotlinLocalTime() != validated.departureTime
                CarpoolPostingTable.update({ CarpoolPostingTable.id eq postingId }) {
                    // Stolperfalle 7 (Plan V1.9.12): seatsOffered aktiv auf NULL setzen bei Typwechsel OFFER -> REQUEST, sonst verletzt ein
                    // reines Feld-Update den DB-CHECK (chk_carpool_posting_seats) -- CarpoolValidation.validate hat bereits sichergestellt,
                    // dass validated.seatsOffered zu validated.type passt.
                    it[type] = validated.type
                    it[fromPlace] = validated.fromPlace
                    it[toPlace] = validated.toPlace
                    it[departureDate] = validated.departureDate
                    it[departureTime] = validated.departureTime?.toJavaLocalTime()
                    it[seatsOffered] = validated.seatsOffered
                    it[notes] = validated.notes
                    if (validated.type == CarpoolPostingType.REQUEST) it[meetingNote] = null
                    it[updatedAt] = DbClock.nowLocalDateTime()
                }
                val notices =
                    if (changedForPassengers) {
                        active.map {
                            CarpoolNotice(
                                recipientId = it,
                                kind = CarpoolNoticeKind.POSTING_CHANGED,
                                date = validated.departureDate,
                                fromPlace = validated.fromPlace,
                                toPlace = validated.toPlace,
                            )
                        }
                    } else {
                        emptyList()
                    }
                singleDto(id = postingId, currentMemberId = current.memberId, today = today) to notices
            }
        deps.notifier.dispatch(notices)
        return dto
    }

    override suspend fun deletePosting(id: String) {
        val current = resolveCurrentMember(call)
        val notices =
            transaction {
                requireActiveMembership(memberId = current.memberId)
                val postingId = CarpoolParticipations.parseId(id)
                val existing = CarpoolParticipations.lockPosting(postingId) ?: CarpoolParticipations.notFound()
                if (existing[CarpoolPostingTable.authorMemberId] != current.memberId) {
                    throw ForbiddenException("Only the author may delete this posting")
                }
                val affected = CarpoolParticipations.activeParticipants(postingId)
                // Explicit delete first: correct whatever the foreign key's ON DELETE rule is.
                CarpoolParticipations.deleteAllOf(postingId)
                CarpoolPostingTable.deleteWhere { CarpoolPostingTable.id eq postingId }
                affected.map {
                    CarpoolNotice(
                        recipientId = it,
                        kind = CarpoolNoticeKind.POSTING_DELETED,
                        date = existing[CarpoolPostingTable.departureDate],
                        fromPlace = existing[CarpoolPostingTable.fromPlace],
                        toPlace = existing[CarpoolPostingTable.toPlace],
                    )
                }
            }
        deps.notifier.dispatch(notices)
    }

    override suspend fun contactAuthor(
        postingId: String,
        message: String,
    ): DirectMessageDto {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val id = CarpoolParticipations.parseId(postingId)
            val validatedMessage = CarpoolValidation.validateContactMessage(message)
            val today = OrganizationTimeZone.today()
            // Eigener Blick auf "abgelaufen": der Feed-Filter in listPostings greift zwar schon vorher, aber ein Client könnte theoretisch
            // eine gecachte Posting-ID senden.
            val row =
                CarpoolPostingTable
                    .selectAll()
                    .where { (CarpoolPostingTable.id eq id) and (CarpoolPostingTable.departureDate greaterEq today) }
                    .singleOrNull() ?: throw NotFoundException("Carpool posting not found or expired")
            val authorId = row[CarpoolPostingTable.authorMemberId]
            if (authorId == current.memberId) throw ConflictException("Cannot contact yourself")
            insertDirectMessage(senderId = current.memberId, recipientId = authorId, body = validatedMessage)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // V1.9.125 "Mitfahrten"
    // ---------------------------------------------------------------------------------------------------------------------------------

    override suspend fun requestParticipation(postingId: String): CarpoolMyParticipationDto {
        val current = resolveCurrentMember(call)
        deps.rateLimiter.checkAndRecord(memberId = current.memberId, action = CarpoolAction.REQUEST)
        val (dto, notices) =
            transaction {
                requireCarpoolPassengerMembership(memberId = current.memberId)
                val id = CarpoolParticipations.parseId(postingId)
                val today = OrganizationTimeZone.today()
                val result = CarpoolParticipations.request(actor = current, postingId = id, today = today, now = DbClock.nowLocalDateTime())
                CarpoolParticipations.myParticipation(participationId = result.participationId, today = today) to result.notices
            }
        deps.notifier.dispatch(notices)
        return dto
    }

    override suspend fun findInvitee(
        firstName: String,
        lastName: String,
    ): List<CarpoolInviteeCandidateDto> {
        val current = resolveCurrentMember(call)
        // Over the limit the answer has the SAME shape as "nobody found" -- the limit itself must not become a signal.
        if (!deps.rateLimiter.tryAcquire(memberId = current.memberId, action = CarpoolAction.FIND)) return emptyList()
        val hits =
            transaction {
                requireActiveMembership(memberId = current.memberId)
                CarpoolInviteeSearch.find(driverId = current.memberId, firstName = firstName, lastName = lastName)
            }
        return hits.map { hit ->
            CarpoolInviteeCandidateDto(
                personRef = deps.refStore.issue(driverId = current.memberId, personId = hit.memberId),
                displayName = hit.displayName,
                memberStatus = hit.status,
            )
        }
    }

    override suspend fun inviteParticipant(
        postingId: String,
        personRef: String,
    ): CarpoolParticipantDto {
        val current = resolveCurrentMember(call)
        deps.rateLimiter.checkAndRecord(memberId = current.memberId, action = CarpoolAction.INVITE)
        val (dto, notices) =
            transaction {
                requireActiveMembership(memberId = current.memberId)
                val id = CarpoolParticipations.parseId(postingId)
                val personId = deps.refStore.resolve(driverId = current.memberId, token = personRef) ?: CarpoolParticipations.notFound()
                val now = DbClock.nowLocalDateTime()
                val result =
                    CarpoolParticipations.invite(
                        actor = current,
                        postingId = id,
                        personId = personId,
                        today = OrganizationTimeZone.today(),
                        now = now,
                    )
                val participant =
                    CarpoolParticipations
                        .listParticipants(driverId = current.memberId, postingId = id, now = now)
                        .first { it.id == result.participationId.toString() }
                participant to result.notices
            }
        deps.notifier.dispatch(notices)
        return dto
    }

    override suspend fun confirmParticipation(participationId: String): CarpoolParticipationStatus =
        decide(
            participationId = participationId,
        ) { actor, id, today, now -> CarpoolParticipations.confirm(actor = actor, participationId = id, today = today, now = now) }

    override suspend fun declineParticipation(participationId: String): CarpoolParticipationStatus =
        decide(
            participationId = participationId,
        ) { actor, id, today, now -> CarpoolParticipations.decline(actor = actor, participationId = id, today = today, now = now) }

    override suspend fun withdrawParticipation(participationId: String): CarpoolParticipationStatus =
        decide(
            participationId = participationId,
        ) { actor, id, today, now -> CarpoolParticipations.withdraw(actor = actor, participationId = id, today = today, now = now) }

    private fun decide(
        participationId: String,
        action: (CurrentMember, Uuid, LocalDate, LocalDateTime) -> CarpoolParticipations.Result,
    ): CarpoolParticipationStatus {
        val current = resolveCurrentMember(call)
        deps.rateLimiter.checkAndRecord(memberId = current.memberId, action = CarpoolAction.DECIDE)
        val result =
            transaction {
                requireCarpoolPassengerMembership(memberId = current.memberId)
                val id = CarpoolParticipations.parseId(participationId)
                action(current, id, OrganizationTimeZone.today(), DbClock.nowLocalDateTime())
            }
        deps.notifier.dispatch(result.notices)
        return result.status
    }

    override suspend fun listMyParticipations(): List<CarpoolMyParticipationDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireCarpoolPassengerMembership(memberId = current.memberId)
            CarpoolParticipations.listMine(
                personId = current.memberId,
                today = OrganizationTimeZone.today(),
                now = DbClock.nowLocalDateTime(),
            )
        }
    }

    override suspend fun listParticipants(postingId: String): List<CarpoolParticipantDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val id = CarpoolParticipations.parseId(postingId)
            CarpoolParticipations.listParticipants(driverId = current.memberId, postingId = id, now = DbClock.nowLocalDateTime())
        }
    }

    override suspend fun setMeetingNote(
        postingId: String,
        note: String?,
    ): CarpoolPostingDto {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val id = CarpoolParticipations.parseId(postingId)
            val trimmed = note?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmed != null && trimmed.length > MAX_MEETING_NOTE_LENGTH) {
                throw ConflictException("Der Treffpunkt darf höchstens $MAX_MEETING_NOTE_LENGTH Zeichen lang sein.")
            }
            val posting = CarpoolParticipations.lockPosting(id) ?: CarpoolParticipations.notFound()
            if (posting[CarpoolPostingTable.authorMemberId] != current.memberId ||
                posting[CarpoolPostingTable.type] != CarpoolPostingType.OFFER
            ) {
                CarpoolParticipations.notFound()
            }
            CarpoolPostingTable.update({ CarpoolPostingTable.id eq id }) {
                it[meetingNote] = trimmed
                it[updatedAt] = DbClock.nowLocalDateTime()
            }
            singleDto(id = id, currentMemberId = current.memberId, today = OrganizationTimeZone.today())
        }
    }

    override suspend fun getPassengerHint(
        from: LocalDate,
        to: LocalDate,
    ): CarpoolPassengerHintDto {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            CarpoolParticipations.passengerHint(driverId = current.memberId, from = from, to = to)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun baseQuery() =
        CarpoolPostingTable
            .join(MemberTable, JoinType.INNER, CarpoolPostingTable.authorMemberId, MemberTable.id)
            .selectAll()

    private fun singleDto(
        id: Uuid,
        currentMemberId: Uuid,
        today: LocalDate,
    ): CarpoolPostingDto {
        val row = baseQuery().where { CarpoolPostingTable.id eq id }.singleOrNull() ?: CarpoolParticipations.notFound()
        return toDtos(rows = listOf(row), currentMemberId = currentMemberId, today = today, isMember = true).single()
    }

    /** Builds the DTOs of a whole feed with three grouped queries (no per-row query). */
    private fun toDtos(
        rows: List<ResultRow>,
        currentMemberId: Uuid,
        today: LocalDate,
        isMember: Boolean,
    ): List<CarpoolPostingDto> {
        val ids = rows.map { it[CarpoolPostingTable.id] }
        val confirmed = CarpoolParticipations.confirmedCounts(ids)
        val own = CarpoolParticipations.ownParticipations(postingIds = ids, personId = currentMemberId)
        val ownPostingIds = rows.filter { it[CarpoolPostingTable.authorMemberId] == currentMemberId }.map { it[CarpoolPostingTable.id] }
        val openRequests = CarpoolParticipations.openRequestCounts(ownPostingIds)
        return rows.map { row ->
            val postingId = row[CarpoolPostingTable.id]
            val isOwn = row[CarpoolPostingTable.authorMemberId] == currentMemberId
            val mine = own[postingId]
            val myStatus = mine?.get(CarpoolParticipationTable.status)
            val seats = row[CarpoolPostingTable.seatsOffered]
            CarpoolPostingDto(
                id = postingId.toString(),
                type = row[CarpoolPostingTable.type],
                fromPlace = row[CarpoolPostingTable.fromPlace],
                toPlace = row[CarpoolPostingTable.toPlace],
                departureDate = row[CarpoolPostingTable.departureDate],
                departureTime = row[CarpoolPostingTable.departureTime]?.toKotlinLocalTime(),
                seatsOffered = seats,
                // The free text may hold a phone number meant for members: a guest or friend never gets it.
                notes = if (isMember) row[CarpoolPostingTable.notes] else null,
                // A guest or friend sees the name only for their own CONFIRMED ride (anti-enumeration of members, Art. 9 GDPR).
                authorDisplayName =
                    if (isMember || isOwn || myStatus == CarpoolParticipationStatus.CONFIRMED) {
                        row[MemberTable.displayName]
                    } else {
                        CarpoolPrivacy.HIDDEN_NAME
                    },
                isOwn = isOwn,
                isPast = row[CarpoolPostingTable.departureDate] < today,
                createdAt = row[CarpoolPostingTable.createdAt],
                seatsFree = seats?.let { maxOf(0, it - (confirmed[postingId] ?: 0)) },
                myParticipation =
                    mine?.let {
                        CarpoolMyParticipationRef(
                            id = it[CarpoolParticipationTable.id].toString(),
                            status = it[CarpoolParticipationTable.status],
                            initiator = it[CarpoolParticipationTable.initiator],
                        )
                    },
                meetingNote =
                    if (isOwn || myStatus == CarpoolParticipationStatus.CONFIRMED) row[CarpoolPostingTable.meetingNote] else null,
                openRequests = if (isOwn) openRequests[postingId] ?: 0 else 0,
            )
        }
    }
}
