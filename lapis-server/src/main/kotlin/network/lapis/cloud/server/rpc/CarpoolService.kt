package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.DirectMessageDto
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

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- jede Methode ORGANIZATION_MEMBER-exklusiv
 * ([requireActiveMembership]). Kein eigenes `NavVisibility.showsCarpool`-Prädikat auf Client-Seite
 * (Repo-Konvention hat Vorrang, siehe Plan Abschnitt 1a): innerhalb der Sidebar-Gruppe
 * `MEMBERSHIP` haben Routen ohne eine ABWEICHENDE Sichtbarkeitsregel kein eigenes Prädikat,
 * sondern hängen am gruppenweiten `showsMembershipSection` -- die Mitfahrerzentrale (jedes ACTIVE
 * Mitglied, ohne Ausnahme) fällt exakt darunter, wie `COMMUNICATION`.
 *
 * **Feed-Sichtbarkeit vs. endgültige Löschung**: [listPostings] zeigt nur `departure_date >=
 * heute` -- ein Eintrag verschwindet aus dem Feed am Tag NACH der Abfahrt, wird aber erst
 * [network.lapis.cloud.server.carpool.CarpoolRetention.RETENTION_DAYS_AFTER_DEPARTURE] Tage
 * später endgültig gelöscht, damit ein Mitglied eine knapp abgelaufene eigene Fahrt noch kurz
 * duplizieren kann ([listMyPostings] zeigt eigene abgelaufene Einträge weiterhin, markiert als
 * `isPast`).
 *
 * **Kontaktaufnahme** läuft über eine ganz normale Direktnachricht ([contactAuthor] ruft
 * [insertDirectMessage]) -- kein eigenes Carpool-Nachrichtensystem, kein
 * `authorMemberId`-Feld im DTO (Larry Teslers Auflage): der Client kann den Empfänger einer
 * Kontaktnachricht nicht selbst adressieren, nur über eine `postingId`, die der Server auflöst.
 */
class CarpoolService(
    private val call: ApplicationCall,
) : ICarpoolService {
    override suspend fun listPostings(type: CarpoolPostingType?): List<CarpoolPostingDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val today = DbClock.nowLocalDateTime().date
            baseQuery()
                .where {
                    (CarpoolPostingTable.departureDate greaterEq today) and
                        (type?.let { CarpoolPostingTable.type eq it } ?: Op.TRUE)
                }.orderBy(
                    CarpoolPostingTable.departureDate to SortOrder.ASC,
                    CarpoolPostingTable.departureTime to SortOrder.ASC_NULLS_LAST,
                    CarpoolPostingTable.createdAt to SortOrder.ASC,
                ).limit(MAX_FEED_SIZE)
                .map { it.toDto(currentMemberId = current.memberId, today = today) }
        }
    }

    override suspend fun listMyPostings(): List<CarpoolPostingDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val today = DbClock.nowLocalDateTime().date
            baseQuery()
                .where { CarpoolPostingTable.authorMemberId eq current.memberId }
                .orderBy(
                    CarpoolPostingTable.departureDate to SortOrder.DESC,
                    CarpoolPostingTable.departureTime to SortOrder.DESC_NULLS_LAST,
                ).map { it.toDto(currentMemberId = current.memberId, today = today) }
        }
    }

    override suspend fun createPosting(input: CarpoolPostingInput): CarpoolPostingDto {
        val current = resolveCurrentMember(call)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val today = DbClock.nowLocalDateTime().date
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
            requirePostingRow(id).toDto(currentMemberId = current.memberId, today = today)
        }
    }

    override suspend fun updatePosting(
        id: String,
        input: CarpoolPostingInput,
    ): CarpoolPostingDto {
        val current = resolveCurrentMember(call)
        val postingId = Uuid.parse(id)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val today = DbClock.nowLocalDateTime().date
            val existing = requirePostingRow(postingId)
            if (existing[CarpoolPostingTable.authorMemberId] != current.memberId) {
                throw ForbiddenException("Only the author may edit this posting")
            }
            val validated = CarpoolValidation.validate(input = input, today = today)
            CarpoolValidation.requireUnderQuota(authorMemberId = current.memberId, today = today, excludingPostingId = postingId)
            CarpoolPostingTable.update({ CarpoolPostingTable.id eq postingId }) {
                // Stolperfalle 7 (Plan): seatsOffered aktiv auf NULL setzen bei Typwechsel
                // OFFER -> REQUEST, sonst verletzt ein reines Feld-Update den DB-CHECK
                // (chk_carpool_posting_seats) -- CarpoolValidation.validate hat bereits sichergestellt,
                // dass validated.seatsOffered zu validated.type passt (siehe dort).
                it[type] = validated.type
                it[fromPlace] = validated.fromPlace
                it[toPlace] = validated.toPlace
                it[departureDate] = validated.departureDate
                it[departureTime] = validated.departureTime?.toJavaLocalTime()
                it[seatsOffered] = validated.seatsOffered
                it[notes] = validated.notes
                it[updatedAt] = DbClock.nowLocalDateTime()
            }
            requirePostingRow(postingId).toDto(currentMemberId = current.memberId, today = today)
        }
    }

    override suspend fun deletePosting(id: String) {
        val current = resolveCurrentMember(call)
        val postingId = Uuid.parse(id)
        transaction {
            requireActiveMembership(memberId = current.memberId)
            val existing = requirePostingRow(postingId)
            if (existing[CarpoolPostingTable.authorMemberId] != current.memberId) {
                throw ForbiddenException("Only the author may delete this posting")
            }
            CarpoolPostingTable.deleteWhere { CarpoolPostingTable.id eq postingId }
        }
    }

    override suspend fun contactAuthor(
        postingId: String,
        message: String,
    ): DirectMessageDto {
        val current = resolveCurrentMember(call)
        val id = Uuid.parse(postingId)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val validatedMessage = CarpoolValidation.validateContactMessage(message)
            val today = DbClock.nowLocalDateTime().date
            // Eigener Blick auf "abgelaufen": der Feed-Filter in listPostings greift zwar schon
            // vorher, aber ein Client könnte theoretisch eine gecachte Posting-ID senden.
            val row =
                CarpoolPostingTable
                    .selectAll()
                    .where { (CarpoolPostingTable.id eq id) and (CarpoolPostingTable.departureDate greaterEq today) }
                    .singleOrNull() ?: throw NotFoundException("Carpool posting $id not found or expired")
            val authorId = row[CarpoolPostingTable.authorMemberId]
            if (authorId == current.memberId) throw ConflictException("Cannot contact yourself")
            insertDirectMessage(senderId = current.memberId, recipientId = authorId, body = validatedMessage)
        }
    }

    private fun requirePostingRow(id: Uuid) =
        baseQuery().where { CarpoolPostingTable.id eq id }.singleOrNull()
            ?: throw NotFoundException("Carpool posting $id not found")

    private fun baseQuery() =
        CarpoolPostingTable
            .join(MemberTable, JoinType.INNER, CarpoolPostingTable.authorMemberId, MemberTable.id)
            .selectAll()

    private fun ResultRow.toDto(
        currentMemberId: Uuid,
        today: LocalDate,
    ): CarpoolPostingDto {
        val authorId = this[CarpoolPostingTable.authorMemberId]
        return CarpoolPostingDto(
            id = this[CarpoolPostingTable.id].toString(),
            type = this[CarpoolPostingTable.type],
            fromPlace = this[CarpoolPostingTable.fromPlace],
            toPlace = this[CarpoolPostingTable.toPlace],
            departureDate = this[CarpoolPostingTable.departureDate],
            departureTime = this[CarpoolPostingTable.departureTime]?.toKotlinLocalTime(),
            seatsOffered = this[CarpoolPostingTable.seatsOffered],
            notes = this[CarpoolPostingTable.notes],
            authorDisplayName = this[MemberTable.displayName],
            isOwn = authorId == currentMemberId,
            isPast = this[CarpoolPostingTable.departureDate] < today,
            createdAt = this[CarpoolPostingTable.createdAt],
        )
    }
}
