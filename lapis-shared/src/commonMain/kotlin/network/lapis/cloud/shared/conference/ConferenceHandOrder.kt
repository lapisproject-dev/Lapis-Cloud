package network.lapis.cloud.shared.conference

/*
 * V1.9.253 -- raised hands first. Pure and without any DOM: the order in which the OTHER people's tiles are laid out when hands are up.
 *
 * The people with a raised hand (and a tile) go to the front, in the order they raised the hand; everybody else keeps the base order.
 * The own tile never moves: it is taken out for the sorting and put back at its old index. A hand without a tile is ignored.
 * Without any (foreign) hand the very same [base] instance is returned, so "no hands" provably changes nothing.
 */
fun orderTilesByRaisedHands(
    base: List<String>,
    raisedInOrder: List<String>,
    selfId: String?,
): List<String> {
    val others = base.filter { it != selfId }
    val onBase = others.toHashSet()
    val front = raisedInOrder.filter { it in onBase }.distinct()
    if (front.isEmpty()) return base
    val frontSet = front.toHashSet()
    val ordered = front + others.filter { it !in frontSet }
    val selfIndex = base.indexOf(selfId)
    if (selfId == null || selfIndex < 0) return ordered
    return ordered.toMutableList().also { it.add(selfIndex.coerceIn(0, it.size), selfId) }
}
