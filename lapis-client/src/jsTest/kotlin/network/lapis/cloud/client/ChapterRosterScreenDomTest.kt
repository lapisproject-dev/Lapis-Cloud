package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAdminPageDto
import network.lapis.cloud.shared.domain.MemberAdminSort
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAuthService
import network.lapis.cloud.shared.rpc.IMemberService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review fix (Welle V1.9.14, MAJOR test-coverage finding + MINOR logic finding): before this file,
 * `ChapterRosterScreen.kt`'s `load` -- the session re-check, the `NoLongerOfficer` state, and the
 * `ForbiddenException` race -- had no test at all. The MINOR logic fix this file also covers
 * (`refreshSessionFromServer()` falling back to `AppState.session?.chapterScope` on an ordinary
 * failure instead of being indistinguishable from "the grant is really gone") is exactly what
 * [firstLoad_sessionRefreshFails_fallsBackToTheLastKnownSession_stillShowsTheRoster] pins: without
 * that fallback, a transient network hiccup on the FIRST load would show "Ihr Landesvorstand-Zugang
 * ist nicht mehr aktiv." to an officer whose grant is perfectly fine.
 */
class ChapterRosterScreenDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            try {
                block()
            } finally {
                closeOpenModals()
                AppState.setSession(null)
            }
        }

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private val nordScope = RegionalChapterRefDto(id = "chapter-1", name = "Landesverband Nord")

    private fun sessionWithChapterScope(scope: RegionalChapterRefDto?): SessionInfoDto =
        SessionInfoDto(
            memberId = "member-officer",
            displayName = "Amara Okafor",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2027, 1, 1, 0, 0),
            chapterScope = scope,
        )

    private fun emptyPageJson(): String =
        jsonOf(
            MemberAdminPageDto.serializer(),
            MemberAdminPageDto(rows = emptyList(), totalCount = 0, statusCounts = emptyMap(), limit = 25, offset = 0),
        )

    @Test
    fun firstLoad_sessionRefreshSaysNoGrant_showsNoLongerOfficer_neverCallsListMembers(): Promise<Unit> =
        test {
            val sessionRoute = routeOf { rpcService<IAuthService>().getSessionInfo() }
            val respond: (RecordedRequest) -> StubResponse = { request ->
                if (!request.isRpc) {
                    StubResponse()
                } else if (request.rpcRoute == sessionRoute) {
                    rpcResult(request.json.id as Int, jsonOf(SessionInfoDto.serializer(), sessionWithChapterScope(null)))
                } else {
                    rpcResult(request.json.id as Int, "null")
                }
            }
            withFetchStub(respond = respond) { calls ->
                withMountedRoot("body-chapter-roster-no-grant") { root, element ->
                    renderChapterRosterScreen(root)
                    awaitUntil("the notice") { element().text().contains("Landesvorstand-Zugang") }
                    assertTrue(
                        element().text().contains("Ihr Landesvorstand-Zugang ist nicht mehr aktiv."),
                        "actual: ${element().text()}",
                    )
                    assertFalse(
                        calls.any { it.isRpc && it.rpcRoute.contains("Member") },
                        "listMembersForAdministration must never be called once the session says there is no grant",
                    )
                }
            }
        }

    @Test
    fun firstLoad_sessionRefreshFails_fallsBackToTheLastKnownSession_stillShowsTheRoster(): Promise<Unit> =
        test {
            AppState.setSession(sessionWithChapterScope(nordScope))
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    // getSessionInfo() fails like a dropped connection -- refreshSessionFromServer()
                    // must fall back to AppState.session?.chapterScope (the review fix), not treat
                    // this as "the grant is gone".
                    (request.json.params.length as Int) == 0 -> StubResponse(networkError = true)
                    else -> rpcResult(request.json.id as Int, emptyPageJson())
                }
            }
            withFetchStub(respond = respond) { calls ->
                withMountedRoot("body-chapter-roster-refresh-fails") { root, element ->
                    renderChapterRosterScreen(root)
                    awaitUntil("the roster notice") { element().text().contains("Landesverband Nord") }
                    assertFalse(
                        element().text().contains("nicht mehr aktiv"),
                        "a transient refresh failure must not show the 'grant is gone' notice: ${element().text()}",
                    )
                    assertTrue(
                        calls.any { it.isRpc && (it.json.params.length as Int) == 1 },
                        "listMembersForAdministration must still be called, using the LAST KNOWN chapterScope",
                    )
                }
            }
        }

    @Test
    fun forbiddenExceptionMidFlight_refreshesTheSession_andShowsNoLongerOfficer(): Promise<Unit> =
        test {
            AppState.setSession(sessionWithChapterScope(nordScope))
            val sessionRoute = routeOf { rpcService<IAuthService>().getSessionInfo() }
            val memberRoute =
                routeOf { rpcService<IMemberService>().listMembersForAdministration(chapterRosterQuery("", MemberAdminSort.NAME_ASC, 0)) }
            var sessionCalls = 0
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == sessionRoute -> {
                        sessionCalls++
                        // Every refresh, including the mid-flight one after the race, still reports the grant.
                        rpcResult(request.json.id as Int, jsonOf(SessionInfoDto.serializer(), sessionWithChapterScope(nordScope)))
                    }
                    request.rpcRoute == memberRoute ->
                        serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.ForbiddenException")
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }
            withFetchStub(respond = respond) {
                withMountedRoot("body-chapter-roster-forbidden-race") { root, element ->
                    renderChapterRosterScreen(root)
                    awaitUntil("the notice") { element().text().contains("Landesvorstand-Zugang") }
                    assertTrue(element().text().contains("Ihr Landesvorstand-Zugang ist nicht mehr aktiv."))
                    assertEquals(2, sessionCalls, "the initial session read, then the race-recovery refresh after ForbiddenException")
                }
            }
        }
}
