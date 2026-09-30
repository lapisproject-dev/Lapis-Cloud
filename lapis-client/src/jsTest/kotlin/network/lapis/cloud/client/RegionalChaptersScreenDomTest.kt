package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.RegionalChapterDto
import network.lapis.cloud.shared.domain.RegionalChapterOverviewDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review fix (Welle V1.9.14, MINOR edge-case finding), `RegionalChaptersScreen.kt`: with the
 * previous `isEmpty = { it.chapters.isEmpty() && it.unassignedCount == 0 }` predicate, an instance
 * with no chapters AND no ACTIVE/APPLICATION member (only DONOR/WITHDRAWN, or simply none yet) hit
 * `DataViewState.Empty`, which shows ONLY `emptyText` and never calls `render` -- the "Landesverband
 * anlegen" form, the only way to create the FIRST chapter, was unreachable. This test pins the fix
 * (`isEmpty = { false }`): the creation form must always render, the notice alongside it.
 */
class RegionalChaptersScreenDomTest {
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

    private fun sessionOf(role: AccountRole) =
        SessionInfoDto(memberId = "m-1", displayName = "Testperson", role = role, expiresAt = LocalDateTime(2099, 1, 1, 0, 0))

    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    private fun HTMLElement.button(text: String): HTMLElement = all("button").first { it.textContent?.trim() == text }

    private fun overviewJson(unassignedCount: Int): String {
        val overview = RegionalChapterOverviewDto(chapters = emptyList(), unassignedCount = unassignedCount)
        return jsonOf(RegionalChapterOverviewDto.serializer(), overview)
    }

    @Test
    fun noChaptersAndNoUnassignedMember_stillShowsTheCreationForm(): Promise<Unit> =
        test {
            AppState.setSession(sessionOf(AccountRole.ADMIN))
            val respond: (RecordedRequest) -> StubResponse = { request ->
                if (!request.isRpc) StubResponse() else rpcResult(request.json.id as Int, overviewJson(unassignedCount = 0))
            }
            withFetchStub(respond = respond) {
                withMountedRoot("body-regional-chapters-empty-edge-case") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil("the creation form") { element().querySelector("input[type=text]") != null }
                    assertTrue(
                        element().all("button").any { it.textContent?.trim() == "Landesverband anlegen" },
                        "the 'Landesverband anlegen' creation form must be reachable even with zero chapters and zero unassigned members",
                    )
                    assertTrue(
                        element().textContent.orEmpty().contains("Noch keine Landesverbände angelegt"),
                        "the informational notice must still be shown alongside the form",
                    )
                }
            }
        }

    @Test
    fun unassignedMembersButNoChapters_stillShowsBothTheHintAndTheCreationForm(): Promise<Unit> =
        test {
            AppState.setSession(sessionOf(AccountRole.ADMIN))
            val respond: (RecordedRequest) -> StubResponse = { request ->
                if (!request.isRpc) StubResponse() else rpcResult(request.json.id as Int, overviewJson(unassignedCount = 3))
            }
            withFetchStub(respond = respond) {
                withMountedRoot("body-regional-chapters-unassigned-only") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil("the creation form") { element().querySelector("input[type=text]") != null }
                    // Not empty (unassignedCount > 0) even before this fix -- verifies the fix did not
                    // regress the ALREADY-working "unassigned members exist" branch.
                    assertTrue(element().textContent.orEmpty().contains("3 Mitglieder ohne Landesverband"))
                    assertTrue(element().all("button").any { it.textContent?.trim() == "Landesverband anlegen" })
                }
            }
        }

    private fun oneChapterJson(): String {
        val chapter =
            RegionalChapterDto(
                id = "c1",
                name = "Landesverband Nord",
                activeMemberCount = 0,
                assignedMemberCount = 0,
                activeOfficerCount = 0,
            )
        return jsonOf(RegionalChapterOverviewDto.serializer(), RegionalChapterOverviewDto(chapters = listOf(chapter), unassignedCount = 0))
    }

    private fun structuralButtons(element: HTMLElement): List<String> =
        element
            .all("button")
            .map { it.textContent?.trim().orEmpty() }
            .filter { it in setOf("Landesverband anlegen", "Umbenennen", "Landesvorstand verwalten", "Löschen") }

    @Test
    fun aBoardSession_seesTheCrestAndDescription_butNoStructuralControls(): Promise<Unit> =
        test {
            AppState.setSession(sessionOf(AccountRole.BOARD))
            withFetchStub(respond = { request ->
                if (!request.isRpc) StubResponse() else rpcResult(request.json.id as Int, oneChapterJson())
            }) {
                withMountedRoot("body-regional-chapters-board") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil("the chapter card") { element().textContent.orEmpty().contains("Öffentliche Darstellung") }
                    assertTrue(
                        element().all("button").any { it.textContent.orEmpty().startsWith("Wappen ") },
                        "BOARD keeps the crest controls",
                    )
                    assertEquals(emptyList(), structuralButtons(element()), "create/rename/officers/delete are ADMIN-only on the server")
                    assertFalse(element().textContent.orEmpty().contains("Landesverband anlegen"))
                }
            }
        }

    @Test
    fun anAdminSession_seesTheStructuralControls_andTheCrest(): Promise<Unit> =
        test {
            AppState.setSession(sessionOf(AccountRole.ADMIN))
            withFetchStub(respond = { request ->
                if (!request.isRpc) StubResponse() else rpcResult(request.json.id as Int, oneChapterJson())
            }) {
                withMountedRoot("body-regional-chapters-admin") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil("the chapter card") { element().textContent.orEmpty().contains("Öffentliche Darstellung") }
                    assertEquals(
                        setOf("Landesverband anlegen", "Umbenennen", "Landesvorstand verwalten", "Löschen"),
                        structuralButtons(element()).toSet(),
                    )
                    assertTrue(element().all("button").any { it.textContent.orEmpty().startsWith("Wappen ") })
                }
            }
        }
}
