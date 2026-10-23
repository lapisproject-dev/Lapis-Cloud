package network.lapis.cloud.client

/**
 * V1.9.187 -- the help topics that really exist on the public website (`cloud.lapisproject.dev/help/<topic>`).
 *
 * A page intro or the help menu links to a topic ONLY if it is in [available]; a view whose topic has no page yet shows no help link at
 * all (a dead link is worse than none). Today the website has exactly one topic page. Add a topic here in the same change that adds its
 * page to the website, never earlier.
 */
internal object HelpTopics {
    val available: Set<String> = setOf("travel-expenses")

    /** `true` when [topic] is non-null and has a published help page. */
    fun exists(topic: String?): Boolean = topic != null && topic in available
}
