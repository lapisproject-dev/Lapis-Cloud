package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.domain.RegionalChapterOverviewDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
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
            }
        }

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
}
