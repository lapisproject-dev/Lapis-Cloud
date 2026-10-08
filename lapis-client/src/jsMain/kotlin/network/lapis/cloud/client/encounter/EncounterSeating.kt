package network.lapis.cloud.client.encounter

import network.lapis.cloud.shared.domain.ENCOUNTER_SEATS_PER_ROW
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.encounterSeatCapacity
import network.lapis.cloud.shared.domain.encounterSeatGridSize

/**
 * V1.9.79 Begegnungsraum Stufe 2a -- the seat plan as the SERVER reports it: a pure, DOM-free mirror of the `seat` field of
 * `listPresent`. Before 2a (B2) the plan was assigned on each device; that is gone: a seat exists only because its occupant CHOSE it
 * (`selectSeat`), nobody is seated automatically, and the order of arrival can not be read from the pews.
 *
 * - [applyServer] replaces the whole picture with the latest list. Only people of the congregation can sit; a seat claimed by two people
 *   (a transient inconsistency) belongs to the first by name, the other counts as unseated.
 * - [gridSize] is what the room draws: the server's capacity for this many people, but never less than needed to show an occupied seat
 *   (the capacity shrinks when people leave), rounded up to whole rows.
 * - [unseated] are the people without a seat (the row "Noch ohne Platz"), sorted by name.
 *
 * Nothing is stored: not in the browser, not across visits.
 */
internal class EncounterSeating {
    private var congregation: List<EncounterPresentDto> = emptyList()
    private val seatByIdentity = HashMap<String, Int>()
    private val identityBySeat = HashMap<Int, String>()

    fun applyServer(people: List<EncounterPresentDto>) {
        congregation = people.filter { it.role == EncounterPresenceRole.CONGREGATION }.sortedBy { it.displayName }
        seatByIdentity.clear()
        identityBySeat.clear()
        congregation.forEach { person ->
            val seat = person.seat ?: return@forEach
            if (seat < 0 || identityBySeat.containsKey(seat)) return@forEach
            seatByIdentity[person.memberId] = seat
            identityBySeat[seat] = person.memberId
        }
    }

    /** The person left (a LiveKit event): drops the seat locally until the next `listPresent` answers. */
    fun forget(identity: String) {
        congregation = congregation.filter { it.memberId != identity }
        seatByIdentity.remove(identity)?.let { identityBySeat.remove(it) }
    }

    fun isCongregation(identity: String): Boolean = congregation.any { it.memberId == identity }

    fun seatOf(identity: String): Int? = seatByIdentity[identity]

    /** The identity at [seat], or `null` for a free seat. */
    fun occupantOf(seat: Int): String? = identityBySeat[seat]

    /** Seats the room draws: the server's capacity or the highest occupied seat, whichever is larger, in whole rows. */
    val gridSize: Int
        get() {
            val raw = encounterSeatGridSize(congregation.size, seatByIdentity.values)
            return (raw + ENCOUNTER_SEATS_PER_ROW - 1) / ENCOUNTER_SEATS_PER_ROW * ENCOUNTER_SEATS_PER_ROW
        }

    /** The congregation people without a seat, sorted by name. */
    fun unseated(): List<EncounterPresentDto> = congregation.filter { it.memberId !in seatByIdentity }

    /**
     * Whether the server accepts a choice of [seat] right now: only seats below the capacity for this many people. The drawn grid can be
     * larger (it never cuts off an occupied seat while the capacity has shrunk); those extra seats are shown but must not be offered.
     */
    fun isChoosable(seat: Int): Boolean = seat in 0 until encounterSeatCapacity(congregation.size)

    /** Every free seat that can be chosen, ascending (the same limit the server enforces). */
    fun freeSeats(): List<Int> = (0 until gridSize).filter { it !in identityBySeat && isChoosable(it) }
}

/**
 * The 1-2 character initials shown on a seat: the first letter of the first two words, or the first two letters of a single word,
 * upper case, letters only (digits, symbols and markup characters never appear). `"?"` when the name holds no letter.
 */
internal fun encounterInitials(name: String): String {
    val words =
        name
            .trim()
            .split(Regex("\\s+"))
            .map { word -> word.filter { it.isLetter() } }
            .filter { it.isNotEmpty() }
    val letters =
        when {
            words.isEmpty() -> ""
            words.size == 1 -> words[0].take(2)
            else -> "${words[0].first()}${words[1].first()}"
        }
    return letters.uppercase().ifEmpty { "?" }
}

/**
 * V1.9.79: client-side throttle of the seat choice -- at most one change per [minGapMs] (1 s, the server's own limit). A press inside the
 * gap does nothing at all (no request, no message); the clock is injected so a test can drive it.
 */
internal class EncounterSeatChoiceThrottle(
    private val now: () -> Double,
    private val minGapMs: Double = 1_000.0,
) {
    private var last = Double.NEGATIVE_INFINITY

    /** `true` = go ahead now (and the gap starts). */
    fun tryChoose(): Boolean {
        val t = now()
        if (t - last < minGapMs) return false
        last = t
        return true
    }
}
