package network.lapis.cloud.client

/**
 * Pure, DOM-free predicate deciding whether [linkRoute] should be highlighted as "active" for
 * [currentRoute]. Slash-suffixed prefix match (not plain `startsWith`) so `/cost-centers` does
 * NOT falsely match against a link route of `/cost`, while `/social-network/post/:id` DOES match
 * against the `/social-network` group link. See [NavRouteMatchTest] for the exact boundary cases.
 */
object NavRouteMatch {
    fun isActive(
        currentRoute: String?,
        linkRoute: String,
    ): Boolean {
        if (currentRoute == null) return false
        return currentRoute == linkRoute || currentRoute.startsWith("$linkRoute/")
    }
}
