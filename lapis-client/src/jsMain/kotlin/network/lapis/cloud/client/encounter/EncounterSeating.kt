package network.lapis.cloud.client.encounter

/**
 * V1.9.62 Begegnungsraum (B2) -- the stable seat plan of the congregation: the pews of the church scene. Pure, DOM-free.
 *
 * - A seat is an index into a grid of [blocks] blocks of [perBlock] seats per row (default 2 x 3 = 6 seats per row, a centre aisle
 *   between the blocks).
 * - [assign] gives the next person the first free seat in the fixed fill order: row 0 left block, row 0 right block, row 0 left ...
 *   alternating between the blocks, within a block left to right, then the next row. When all seats are taken the plan grows by ONE row
 *   (never shrinks).
 * - A seat NEVER moves: [release] leaves it empty and nobody sits down into the gap, so the picture of the room does not jump while
 *   people come and go. A person who comes back gets the first free seat again (not necessarily the same one).
 *
 * The plan is per DEVICE and not synchronised (no server state, nothing that could record who sat where). The caller seats the people
 * who are already present in NAME order before the first live arrival, so the order of arrival is not readable from the seats.
 */
internal class EncounterSeating(
    private val rows: Int = 4,
    private val blocks: Int = 2,
    private val perBlock: Int = 3,
) {
    private val perRow = blocks * perBlock
    private val seats = HashMap<String, Int>()
    private var rowCount = rows

    /** All seats of the plan, taken or not (grows by [perRow] when the plan runs full). */
    val seatCount: Int get() = rowCount * perRow

    fun seatOf(identity: String): Int? = seats[identity]

    /** The identity at [seat], or `null` for an empty seat. */
    fun occupantOf(seat: Int): String? = seats.entries.firstOrNull { it.value == seat }?.key

    fun assign(identity: String): Int {
        seats[identity]?.let { return it }
        var seat = firstFree()
        while (seat == null) {
            rowCount++
            seat = firstFree()
        }
        seats[identity] = seat
        return seat
    }

    fun release(identity: String) {
        seats.remove(identity)
    }

    private fun firstFree(): Int? {
        val taken = seats.values.toSet()
        for (step in 0 until seatCount) {
            val seat = seatForStep(step)
            if (seat !in taken) return seat
        }
        return null
    }

    /** The seat index of the [step]-th place of the fill order (see the class KDoc). */
    private fun seatForStep(step: Int): Int {
        val row = step / perRow
        val k = step % perRow
        val block = k % blocks
        val position = k / blocks
        return row * perRow + block * perBlock + position
    }
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
