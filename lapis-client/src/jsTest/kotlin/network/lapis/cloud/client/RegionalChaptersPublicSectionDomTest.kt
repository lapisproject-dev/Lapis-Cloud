package network.lapis.cloud.client

import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.RegionalChapterDto
import network.lapis.cloud.shared.domain.RegionalChapterOverviewDto
import network.lapis.cloud.shared.rpc.IRegionalChapterService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun chapter(
    id: String,
    name: String,
    description: String? = null,
    crestUrl: String? = null,
): RegionalChapterDto =
    RegionalChapterDto(
        id = id,
        name = name,
        activeMemberCount = 0,
        assignedMemberCount = 0,
        activeOfficerCount = 0,
        description = description,
        crestUrl = crestUrl,
        hasCrest = crestUrl != null,
    )

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the public-presentation block of every chapter card on the
 * management screen: the fixed crest tile (image or placeholder), the crest removal with its confirmation,
 * and the description with its live code-point counter.
 */
class RegionalChaptersPublicSectionDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest(block)

    private fun HTMLElement.isShown(): Boolean = getClientRects().length > 0

    private fun overviewOf(vararg chapters: RegionalChapterDto): String =
        jsonOf(RegionalChapterOverviewDto.serializer(), RegionalChapterOverviewDto(chapters = chapters.toList(), unassignedCount = 0))

    private suspend fun routes(): Triple<String, String, String> =
        Triple(
            routeOf { rpcService<IRegionalChapterService>().listChapters() },
            routeOf { rpcService<IRegionalChapterService>().removeChapterCrest("x") },
            routeOf { rpcService<IRegionalChapterService>().updateChapterDescription("x", "y") },
        )

    @Test
    fun aChapterWithACrest_showsTheImageWithAnAltText_aChapterWithoutOne_showsAHiddenPlaceholder(): Promise<Unit> =
        test {
            val (listRoute, _, _) = routes()
            val overview =
                overviewOf(
                    chapter("c1", "Landesverband Nord", crestUrl = "https://lapis.example/public/chapter-crests/TOKEN"),
                    chapter("c2", "Landesverband Sued"),
                )
            withFetchStub(respond = { request ->
                if (request.isRpc && request.rpcRoute == listRoute) rpcResult(request.json.id as Int, overview) else StubResponse()
            }) {
                mountedForm("chapter-public-crest") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil("both chapter cards") { element().allOf(".lapis-crest-tile").size == 2 }
                    val tiles = element().allOf(".lapis-crest-tile")
                    val img = assertNotNull(tiles[0].querySelector("img"))
                    assertEquals("https://lapis.example/public/chapter-crests/TOKEN", img.getAttribute("src"))
                    assertEquals("Wappen Landesverband Nord", img.getAttribute("alt"))
                    assertNull(tiles[1].querySelector("img"))
                    assertEquals("true", tiles[1].querySelector(".fa-shield-halved")?.getAttribute("aria-hidden"))
                    // "Wappen entfernen" exists only for the chapter that HAS a crest.
                    assertEquals(1, element().allOf("button").count { it.textContent?.trim() == "Wappen entfernen" })
                    assertEquals(
                        "Wappen ersetzen",
                        element()
                            .allOf("button")
                            .first { it.textContent.orEmpty().startsWith("Wappen ") }
                            .textContent
                            ?.trim(),
                    )
                }
            }
        }

    @Test
    fun removingACrest_needsTheConfirmationDialog_andThenCallsTheRpcWithTheChapterId(): Promise<Unit> =
        test {
            val (listRoute, removeRoute, _) = routes()
            val with = chapter("chapter-1", "Landesverband Nord", crestUrl = "https://lapis.example/public/chapter-crests/T")
            val overview = overviewOf(with)
            val removed = jsonOf(RegionalChapterDto.serializer(), with.copy(crestUrl = null, hasCrest = false))
            withFetchStub(respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == listRoute -> rpcResult(request.json.id as Int, overview)
                    request.rpcRoute == removeRoute -> rpcResult(request.json.id as Int, removed)
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }) { recorded ->
                mountedForm("chapter-public-remove") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil("the remove button") { element().allOf("button").any { it.textContent?.trim() == "Wappen entfernen" } }
                    element().buttonNamed("Wappen entfernen").click()
                    delay(80)
                    assertTrue(recorded.none { it.isRpc && it.rpcRoute == removeRoute }, "nothing removed before the confirmation")
                    val dialog = lastOpenModal()
                    assertTrue(dialog.textContent.orEmpty().contains("Landesverband Nord"), "the dialog names the chapter")
                    dialog.buttonNamed("Entfernen").click()
                    awaitUntil("the removal RPC") { recorded.any { it.isRpc && it.rpcRoute == removeRoute } }
                    assertEquals("chapter-1", recorded.singleCall(removeRoute).rpcParam(0) as String)
                }
            }
        }

    @Test
    fun theDescriptionIsPrefilled_hasALiveCodePointCounter_andSavingSendsTheText(): Promise<Unit> =
        test {
            val (listRoute, _, describeRoute) = routes()
            val target = chapter("chapter-2", "Landesverband Sued", description = "Zustaendig fuer den Sueden")
            val overview = overviewOf(target)
            withFetchStub(respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == listRoute -> rpcResult(request.json.id as Int, overview)
                    request.rpcRoute == describeRoute ->
                        rpcResult(request.json.id as Int, jsonOf(RegionalChapterDto.serializer(), target))
                    else -> rpcResult(request.json.id as Int, "null")
                }
            }) { recorded ->
                mountedForm("chapter-public-description") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil(
                        "the description field",
                    ) { element().allOf("label").any { it.textContent.orEmpty().startsWith("Öffentliche Beschreibung") } }
                    assertEquals(
                        "Zustaendig fuer den Sueden",
                        (element().controlOf("Öffentliche Beschreibung") as org.w3c.dom.HTMLTextAreaElement).value,
                    )
                    assertTrue(element().textContent.orEmpty().contains("26 / 300"), "counter of the prefilled text")

                    element().typeInto("Öffentliche Beschreibung", "😀😀")
                    delay(60)
                    assertTrue(element().textContent.orEmpty().contains("2 / 300"), "an emoji counts as one character")

                    element().typeInto("Öffentliche Beschreibung", "😀".repeat(301))
                    delay(60)
                    val over = assertNotNull(element().allOf(".text-danger").firstOrNull { it.textContent.orEmpty().contains("301 / 300") })
                    assertTrue(over.isShown())

                    element().typeInto("Öffentliche Beschreibung", "Neue Beschreibung")
                    delay(60)
                    element().buttonNamed("Beschreibung speichern").click()
                    awaitUntil("the description RPC") { recorded.any { it.isRpc && it.rpcRoute == describeRoute } }
                    val call = recorded.singleCall(describeRoute)
                    assertEquals("chapter-2", call.rpcParam(0) as String)
                    assertEquals("Neue Beschreibung", call.rpcParam(1) as String)
                }
            }
        }

    @Test
    fun aDescriptionOverTheLimit_isNotSent_theFieldShowsAFixedSentence(): Promise<Unit> =
        test {
            val (listRoute, _, describeRoute) = routes()
            val overview = overviewOf(chapter("chapter-3", "Landesverband West"))
            withFetchStub(respond = { request ->
                if (request.isRpc && request.rpcRoute == listRoute) rpcResult(request.json.id as Int, overview) else StubResponse()
            }) { recorded ->
                mountedForm("chapter-public-description-limit") { root, element ->
                    renderRegionalChaptersScreen(root)
                    awaitUntil(
                        "the description field",
                    ) { element().allOf("label").any { it.textContent.orEmpty().startsWith("Öffentliche Beschreibung") } }
                    element().typeInto("Öffentliche Beschreibung", "a".repeat(301))
                    delay(60)
                    element().buttonNamed("Beschreibung speichern").click()
                    delay(150)
                    assertTrue(
                        recorded.none { it.isRpc && it.rpcRoute == describeRoute },
                        "an invalid description never leaves the browser",
                    )
                    assertTrue(element().shownErrors().any { it.contains("höchstens 300 Zeichen") }, "errors: ${element().shownErrors()}")
                }
            }
        }
}
