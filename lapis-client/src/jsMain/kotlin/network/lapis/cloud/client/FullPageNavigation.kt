package network.lapis.cloud.client

import kotlinx.browser.window
import network.lapis.cloud.shared.auth.safeReturnTo

/**
 * V1.9.89 -- test seam for the one full-page navigation the login return-to round trip needs. `location.replace`
 * (not `assign`) on purpose: the login page must not stay in the history, otherwise "Back" from the consent page
 * lands on a login screen that immediately jumps forward again (a back-button trap). The ONLY place in `jsMain`
 * for return-to navigation; `ReturnToNavigationTripwireTest` pins that.
 */
internal var fullPageNavigate: (String) -> Unit = { url -> window.location.replace(url) }

/**
 * The only caller of [fullPageNavigate]. Re-validates [target] at the point of the jump (defence in depth: the
 * value may have travelled through several hands). `true` = the page is being left; `false` = rejected, nothing happened.
 */
internal fun navigateToReturnTo(target: String): Boolean {
    val safe = safeReturnTo(target) ?: return false
    fullPageNavigate(safe)
    return true
}
