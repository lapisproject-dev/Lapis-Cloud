package network.lapis.cloud.server.rpc

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- serverseitige Validierungsgrenzen, immer VOR jedem
 * DB-Constraint geprüft (App-seitige Validierung muss der DB-Grenze zuvorkommen, siehe
 * `CarpoolService.updatePosting`'s eigener Kommentar zum Typwechsel-Fall). Nie nur Client-seitig.
 */
internal object CarpoolValidation {
    const val MAX_FUTURE_DAYS = 180
    const val MAX_FUTURE_POSTINGS_PER_MEMBER = 10
    const val MAX_NOTES_LENGTH = 500
    const val MIN_PLACE_LENGTH = 2
    const val MAX_PLACE_LENGTH = 60
    const val MIN_SEATS_OFFERED = 1
    const val MAX_SEATS_OFFERED = 8
    const val CONTACT_MESSAGE_MAX_LENGTH = 2000

    /**
     * Normalizes (trims) and validates [input]; throws [ConflictException] with a speaking reason
     * on the first violation found. [today] is passed in (not read here) so callers use a single,
     * consistent clock read for both this check and [requireUnderQuota].
     */
    fun validate(
        input: CarpoolPostingInput,
        today: LocalDate,
    ): CarpoolPostingInput {
        val fromPlace = input.fromPlace.trim()
        val toPlace = input.toPlace.trim()
        val notes = input.notes?.trim()?.takeIf { it.isNotEmpty() }

        if (fromPlace.length !in MIN_PLACE_LENGTH..MAX_PLACE_LENGTH) {
            throw ConflictException("fromPlace must be $MIN_PLACE_LENGTH-$MAX_PLACE_LENGTH characters")
        }
        if (toPlace.length !in MIN_PLACE_LENGTH..MAX_PLACE_LENGTH) {
            throw ConflictException("toPlace must be $MIN_PLACE_LENGTH-$MAX_PLACE_LENGTH characters")
        }
        if (notes != null && notes.length > MAX_NOTES_LENGTH) {
            throw ConflictException("notes must be at most $MAX_NOTES_LENGTH characters")
        }
        val latest = today.plus(DatePeriod(days = MAX_FUTURE_DAYS))
        if (input.departureDate < today || input.departureDate > latest) {
            throw ConflictException("departureDate must be between $today and $latest")
        }
        when (input.type) {
            CarpoolPostingType.OFFER ->
                if (input.seatsOffered == null || input.seatsOffered !in MIN_SEATS_OFFERED..MAX_SEATS_OFFERED) {
                    throw ConflictException("seatsOffered must be $MIN_SEATS_OFFERED-$MAX_SEATS_OFFERED for an OFFER")
                }
            CarpoolPostingType.REQUEST ->
                if (input.seatsOffered != null) {
                    throw ConflictException("seatsOffered must not be set for a REQUEST")
                }
        }
        return input.copy(fromPlace = fromPlace, toPlace = toPlace, notes = notes)
    }

    /**
     * At most [MAX_FUTURE_POSTINGS_PER_MEMBER] FUTURE postings per author -- counts
     * `departure_date >= today` for `authorMemberId`. [excludingPostingId] lets `updatePosting`
     * exclude the row being edited from its own count.
     */
    fun requireUnderQuota(
        authorMemberId: Uuid,
        today: LocalDate,
        excludingPostingId: Uuid? = null,
    ) {
        val count =
            CarpoolPostingTable
                .selectAll()
                .where {
                    (CarpoolPostingTable.authorMemberId eq authorMemberId) and
                        (CarpoolPostingTable.departureDate greaterEq today) and
                        (excludingPostingId?.let { CarpoolPostingTable.id neq it } ?: Op.TRUE)
                }.count()
        if (count >= MAX_FUTURE_POSTINGS_PER_MEMBER) {
            throw ConflictException("At most $MAX_FUTURE_POSTINGS_PER_MEMBER future postings per member are allowed")
        }
    }

    /** Trims and validates a `contactAuthor` message body. */
    fun validateContactMessage(message: String): String {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || trimmed.length > CONTACT_MESSAGE_MAX_LENGTH) {
            throw ConflictException("message must be 1-$CONTACT_MESSAGE_MAX_LENGTH characters")
        }
        return trimmed
    }
}
