package network.lapis.cloud.shared.domain

/**
 * V1.9.42 -- the one minimum number of answers below which no figures of an anonymous decision are disclosed
 * (polls AND anonymous consensus). A single constant so the two features can never drift apart.
 */
object DisclosureRules {
    const val MIN_ANONYMOUS_RESPONSES = 5
}
