package network.lapis.cloud.client

import dev.kilua.rpc.getService

/**
 * Foundation stub (see CLAUDE.md "Vorab-Befund" / [network.lapis.cloud.shared.domain.MemberStatus]
 * KDoc): there is no real login yet, so the UI offers a plain "act as this member" switcher and
 * every RPC call carries the chosen id in the `X-Member-Id` header via [rpcService]'s
 * `requestFilter`. Swapping this out for real session-based auth later only touches this file.
 */
object AppState {
    var currentMemberId: String = "00000000-0000-0000-0000-000000000001" // Amara Admin (seeded)
}

/** [getService] pre-wired to send the current "acting as" member id on every call. */
inline fun <reified T : Any> rpcService(): T =
    getService(requestFilter = {
        headers.set("X-Member-Id", AppState.currentMemberId)
    })
