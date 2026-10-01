package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.PollStatus
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.31: text a member typed (question, description, option, the creator's name) can carry a forged i18n marker. Every place that shows such
 * text must show it literally -- never resolved into a catalog text or a forged amount.
 */
class PollMaliciousTextDomTest {
    private val marker = "###KvI18nS###"

    private fun evilPoll(status: PollStatus = PollStatus.OPEN) =
        pollDto(
            status = status,
            question = "${marker}Frage <img src=x onerror=alert(1)>",
            description = "${marker}Beschreibung",
            options =
                listOf(
                    pollOptionDto("o-a", "${marker}Option A", 0),
                    pollOptionDto("o-b", "${marker}Option B", 1),
                ),
            createdBy = "${marker}Eve",
            responseCount = if (status == PollStatus.CLOSED) 7 else null,
        )

    private fun assertLiteral(
        el: org.w3c.dom.HTMLElement,
        where: String,
    ) {
        assertFalse(el.innerHTML.contains(marker), "$where: the marker must not survive in the DOM")
        // the markup in the text is shown as text; it must never become an element or an event attribute
        assertEquals(0, el.allOf("img, [onerror]").size, "$where: no element from member text")
    }

    @Test
    fun theList_showsAForgedQuestionLiterally(): Promise<Unit> =
        formTest {
            AppState.setSession(pollSession())
            val routes = pollRoutes()
            val world = PollWorld(all = listOf(evilPoll()))
            withFetchStub(respond = world.respond(routes)) { _ ->
                mountedForm("poll-evil-list") { root, element ->
                    renderPollScreen(root)
                    awaitUntil("list rendered", 3000) { element().flatText().contains("Frage") }
                    assertLiteral(element(), "list")
                    assertEquals(0, element().allOf("img").size)
                }
            }
        }

    @Test
    fun theDetail_showsQuestionDescriptionOptionsAndCreatorLiterally(): Promise<Unit> =
        formTest {
            AppState.setSession(pollSession())
            val routes = pollRoutes()
            val world = PollWorld(evilPoll(), pollParticipation(hasResponded = true))
            withFetchStub(respond = world.respond(routes)) { _ ->
                mountedForm("poll-evil-detail") { root, element ->
                    renderPollDetail(root, "p1", PollUiContext("m-1"))
                    awaitUntil("detail rendered", 3000) { element().flatText().contains("Beschreibung") }
                    assertLiteral(element(), "detail")
                    assertTrue(element().flatText().contains("Gestartet von"))
                    assertEquals(0, element().allOf("img").size)
                }
            }
        }

    @Test
    fun theBoothAndItsCheckStep_showAForgedOptionLiterally(): Promise<Unit> =
        formTest {
            val world = PollWorld(evilPoll())
            val routes = pollRoutes()
            withFetchStub(respond = world.respond(routes)) { _ ->
                mountedForm("poll-evil-booth") { root, element ->
                    renderPollBooth(root, world.poll, onReview = {}, onExit = {})
                    assertLiteral(element(), "booth choice")
                    element().allOf("label").first { it.textContent.orEmpty().contains("Option B") }.click()
                    awaitUntil("enabled", 1500) { !element().isButtonDisabled("Weiter") }
                    element().buttonNamed("Weiter").click()
                    awaitUntil("review", 1500) { element().hasButton("Endgültig abgeben") }
                    assertLiteral(element(), "booth check step")
                    assertTrue(element().flatText().contains("Option B"))
                }
            }
        }

    @Test
    fun theResult_showsAForgedOptionLiterally(): Promise<Unit> =
        formTest {
            mountedForm("poll-evil-result") { root, element ->
                val poll = evilPoll(PollStatus.CLOSED)
                renderPollResult(
                    root,
                    poll,
                    pollResult(head = listOf("o-a" to 4, "o-b" to 3), weighted = listOf("o-a" to 60, "o-b" to 40)),
                )
                assertLiteral(element(), "result")
                assertTrue(element().flatText().contains("Option A"))
            }
        }
}
