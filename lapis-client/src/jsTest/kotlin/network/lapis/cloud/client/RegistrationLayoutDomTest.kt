package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.rpc.IRegistrationService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.134 -- the wide, two-column registration (member and FRIEND) in a REAL mounted screen with the real stylesheets: the width, the grid (one
 * field per cell), the full-width cells, the reading frame (focusable region, bounded only from 768 px up), the order a keyboard visits the
 * controls in, and "the text changed" (a fresh text in the frame, the consent withdrawn, everything typed kept). Honest limit: the media
 * query follows the REAL Karma window, so the two-column assertions run only when it is at least 768 px wide (the CSS text itself is pinned by
 * the server-side `AgreementFormCssTripwireTest`).
 */
class RegistrationLayoutDomTest {
    private companion object {
        val stylesLoaded: Boolean = loadThemeStyles()
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            assertTrue(stylesLoaded)
            disableModalTransitions()
            try {
                block()
            } finally {
                closeOpenModals()
            }
        }

    private val agreementJson =
        """{"version":"v7","text":"Beitrittsvertrag\n\nZwei Absaetze.\n\n- Punkt eins\n- Punkt zwei","sha256":"sha-agreement-abc"}"""
    private val freshAgreementJson = """{"version":"v8","text":"Neuer Vertragstext","sha256":"sha-agreement-def"}"""
    private val termsJson = """{"version":"f3","text":"Freund-Bedingungen\n\n- Punkt","sha256":"sha-terms-abc"}"""
    private val freshTermsJson = """{"version":"f4","text":"Neue Freund-Bedingungen","sha256":"sha-terms-def"}"""
    private val oneChapterJson = """[{"id":"chapter-1","name":"Landesverband Nord"}]"""

    private fun HTMLElement.inputs(selector: String): List<HTMLInputElement> = allOf(selector).map { it as HTMLInputElement }

    private fun fill(
        input: HTMLInputElement,
        text: String,
    ) {
        input.value = text
        input.dispatchEvent(Event("input"))
        input.dispatchEvent(Event("blur"))
    }

    private fun HTMLElement.fillRequiredFields() {
        fill(inputs("input[type=text]")[0], "Amara Okafor")
        fill(inputs("input[type=email]")[0], "amara@example.org")
        val passwords = inputs("input[type=password]")
        fill(passwords[0], "correct horse battery staple")
        fill(passwords[1], "correct horse battery staple")
        inputs("input[type=checkbox]")[0].click()
    }

    private fun style(element: HTMLElement) = window.getComputedStyle(element)

    /** Every control a keyboard visits in the registration form, in DOM order. */
    private fun HTMLElement.tabStops(): List<HTMLElement> =
        allOf("input, select, textarea, button, [tabindex='0']").filter {
            it.getAttribute("tabindex") !=
                "-1"
        }

    private suspend fun routes(): Triple<String, String, String> =
        Triple(
            routeOf { rpcService<IRegistrationService>().getMembershipAgreement() },
            routeOf { rpcService<IRegistrationService>().listRegionalChapterOptions() },
            routeOf { rpcService<IRegistrationService>().getFriendTerms() },
        )

    @Test
    fun memberRegistration_isWide_everyFieldHasItsOwnCell_andTheFrameIsAFocusableRegion(): Promise<Unit> =
        test {
            val (agreementRoute, chaptersRoute, _) = routes()
            val respond: (RecordedRequest) -> StubResponse = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == agreementRoute -> request.answerWith(agreementJson)
                    request.rpcRoute == chaptersRoute -> request.answerWith(oneChapterJson)
                    else -> request.answerWith("null")
                }
            }
            withFetchStub(respond = respond) {
                withMountedRoot("body-registration-layout-member") { root, element ->
                    renderRegistrationScreen(root)
                    awaitUntil("the form and the chapter select") {
                        element().querySelector("select") != null && element().querySelector(".$AGREEMENT_FRAME_CLASS") != null
                    }
                    val screen = element()
                    assertEquals("960px", style(screen.querySelector(".mx-auto") as HTMLElement).maxWidth, "the wide registration width")

                    val grid = assertNotNull(screen.querySelector(".$FORM_GRID_CLASS") as? HTMLElement, "the field grid")
                    val cells = grid.allOf(".$FORM_CELL_CLASS")
                    assertEquals(5, cells.size, "name, e-mail, password, confirmation, chapter")
                    cells.forEach { cell ->
                        assertEquals(1, cell.allOf("input, select, textarea").size, "exactly one field per cell: ${cell.textContent}")
                    }
                    assertEquals(listOf(false, false, false, false, true), cells.map { it.classList.contains(FORM_CELL_FULL_CLASS) })
                    if (windowIsWide()) {
                        assertEquals(2, grid.gridTrackCount(), "two columns from ${WIDE_BREAKPOINT_PX}px")
                        assertEquals("start", style(grid).alignItems)
                        assertEquals("-1", style(cells.last()).getPropertyValue("grid-column-end"), "the chapter spans both columns")
                    } else {
                        assertEquals(1, grid.gridTrackCount(), "one column below ${WIDE_BREAKPOINT_PX}px")
                    }

                    val frame = assertNotNull(screen.querySelector(".$AGREEMENT_FRAME_CLASS") as? HTMLElement, "the reading frame")
                    assertEquals("0", frame.getAttribute("tabindex"))
                    assertEquals("region", frame.getAttribute("role"))
                    assertEquals("Beitrittsvertrag (Version v7)", frame.getAttribute("aria-label"))
                    assertEquals(2, frame.querySelectorAll("p").length)
                    assertEquals(2, frame.querySelectorAll("ul > li").length)
                    assertTrue(screen.textContent.orEmpty().contains("Bitte lesen Sie den vollständigen Text."))
                    if (windowIsWide()) {
                        assertEquals("320px", style(frame).height, "20rem")
                        assertEquals("auto", style(frame).overflowY)
                        assertEquals("contain", style(frame).getPropertyValue("overscroll-behavior-y"))
                    } else {
                        assertEquals("visible", style(frame).overflowY, "below ${WIDE_BREAKPOINT_PX}px the frame grows with its text")
                    }
                    assertEquals("none", style(frame).maxHeight, "no max-height cap in either layout")
                }
            }
        }

    @Test
    fun memberRegistration_keyboardOrder_fieldsThenTheText_thenTheConsent_thenSubmit(): Promise<Unit> =
        test {
            val (agreementRoute, chaptersRoute, _) = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == agreementRoute -> request.answerWith(agreementJson)
                        request.rpcRoute == chaptersRoute -> request.answerWith(oneChapterJson)
                        else -> request.answerWith("null")
                    }
                },
            ) {
                withMountedRoot("body-registration-layout-order") { root, element ->
                    renderRegistrationScreen(root)
                    awaitUntil("the form") {
                        element().querySelector("select") != null &&
                            element().querySelector(".$AGREEMENT_FRAME_CLASS") != null
                    }
                    val screen = element()
                    val stops = screen.tabStops()
                    val name = stops.first { it.getAttribute("type") == "text" }
                    val email = stops.first { it.getAttribute("type") == "email" }
                    val password = screen.inputs("input[type=password]")[0]
                    val confirm = screen.inputs("input[type=password]")[1]
                    val chapter = stops.first { it.tagName == "SELECT" }
                    val frame = stops.first { it.classList.contains(AGREEMENT_FRAME_CLASS) }
                    val consent = stops.first { it.getAttribute("type") == "checkbox" }
                    val submit = stops.first { it.tagName == "BUTTON" && it.textContent?.trim() == "Antrag einreichen" }
                    val order = listOf(name, email, password, confirm, chapter, frame, consent, submit).map { stops.indexOf(it) }
                    assertTrue(order.all { it >= 0 }, "every control is a tab stop: $order")
                    assertEquals(
                        order.sorted(),
                        order,
                        "the keyboard visits name, e-mail, password, confirmation, chapter, text, consent, submit in that order",
                    )
                    assertEquals(order.toSet().size, order.size)
                }
            }
        }

    @Test
    fun memberRegistration_withoutChapters_hasFourCells_andTheFullWidthCellIsOnlyForTheChapter(): Promise<Unit> =
        test {
            val (agreementRoute, chaptersRoute, _) = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == agreementRoute -> request.answerWith(agreementJson)
                        request.rpcRoute == chaptersRoute -> request.answerWith("[]")
                        else -> request.answerWith("null")
                    }
                },
            ) {
                withMountedRoot("body-registration-layout-nochapter") { root, element ->
                    renderRegistrationScreen(root)
                    awaitUntil("the form") { element().querySelector(".$AGREEMENT_FRAME_CLASS") != null }
                    // Four required-field cells in the first grid, plus the two optional cells (country, nationality) of V1.9.141.
                    assertEquals(
                        4,
                        element().querySelector(".$FORM_GRID_CLASS")!!.let { (it as HTMLElement).allOf(".$FORM_CELL_CLASS").size },
                    )
                    assertEquals(6, element().allOf(".$FORM_CELL_CLASS").size)
                    assertEquals(0, element().allOf(".$FORM_CELL_FULL_CLASS").size)
                    assertNull(element().querySelector("select"))
                }
            }
        }

    @Test
    fun friendRegistration_isWide_withFourCells_andItsFrame(): Promise<Unit> =
        test {
            val (_, _, termsRoute) = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == termsRoute -> request.answerWith(termsJson)
                        else -> request.answerWith("null")
                    }
                },
            ) {
                withMountedRoot("body-registration-layout-friend") { root, element ->
                    renderFriendRegistrationScreen(root)
                    awaitUntil("the form") { element().querySelector(".$AGREEMENT_FRAME_CLASS") != null }
                    val screen = element()
                    assertEquals("960px", style(screen.querySelector(".mx-auto") as HTMLElement).maxWidth)
                    val grid = assertNotNull(screen.querySelector(".$FORM_GRID_CLASS") as? HTMLElement)
                    assertEquals(4, grid.allOf(".$FORM_CELL_CLASS").size)
                    assertEquals(if (windowIsWide()) 2 else 1, grid.gridTrackCount())
                    val frame = assertNotNull(screen.querySelector(".$AGREEMENT_FRAME_CLASS") as? HTMLElement)
                    assertEquals("0", frame.getAttribute("tabindex"))
                    assertEquals("region", frame.getAttribute("role"))
                    assertEquals("Nutzungsbedingungen für Freund-Konten (Version f3)", frame.getAttribute("aria-label"))
                    val stops = screen.tabStops()
                    val frameIndex = stops.indexOf(frame)
                    val consentIndex = stops.indexOfFirst { it.getAttribute("type") == "checkbox" }
                    val confirmIndex = stops.indexOf(screen.inputs("input[type=password]")[1])
                    assertTrue(
                        confirmIndex < frameIndex && frameIndex < consentIndex,
                        "fields, then the text, then the consent: $confirmIndex $frameIndex $consentIndex",
                    )
                }
            }
        }

    @Test
    fun registration_aMarkdownText_isShownAsTheServersHtml_insideTheSameReadingFrame(): Promise<Unit> =
        test {
            val (agreementRoute, chaptersRoute, termsRoute) = routes()
            val markdownAgreement =
                """{"version":"m1","text":"# Quelltext","sha256":"sha-m1","format":"MARKDOWN","renderedHtml":"<h2>Beitritt</h2>\n<p>Mit <strong>Wirkung</strong>.</p>\n"}"""
            val markdownTerms =
                """{"version":"m2","text":"Quelltext","sha256":"sha-m2","format":"MARKDOWN","renderedHtml":"<p>Freund <em>Bedingungen</em></p>\n"}"""
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == agreementRoute -> request.answerWith(markdownAgreement)
                        request.rpcRoute == chaptersRoute -> request.answerWith(oneChapterJson)
                        request.rpcRoute == termsRoute -> request.answerWith(markdownTerms)
                        else -> request.answerWith("null")
                    }
                },
            ) {
                withMountedRoot("body-registration-markdown-member") { root, element ->
                    renderRegistrationScreen(root)
                    awaitUntil("the html body") { element().querySelector(".$AGREEMENT_FRAME_CLASS .$AGREEMENT_BODY_CLASS") != null }
                    val frame = assertNotNull(element().querySelector(".$AGREEMENT_FRAME_CLASS") as? HTMLElement)
                    assertEquals("region", frame.getAttribute("role"))
                    assertEquals("0", frame.getAttribute("tabindex"))
                    assertEquals("Beitritt", frame.querySelector("h2")?.textContent)
                    assertEquals("Wirkung", frame.querySelector("strong")?.textContent)
                    assertFalse(frame.textContent.orEmpty().contains("Quelltext"))
                }
                withMountedRoot("body-registration-markdown-friend") { root, element ->
                    renderFriendRegistrationScreen(root)
                    awaitUntil("the html body") { element().querySelector(".$AGREEMENT_FRAME_CLASS .$AGREEMENT_BODY_CLASS") != null }
                    assertEquals("Bedingungen", element().querySelector(".$AGREEMENT_FRAME_CLASS em")?.textContent)
                }
            }
        }

    @Test
    fun memberRegistration_textChanged_loadsTheFreshText_withdrawsTheConsent_andKeepsWhatWasTyped(): Promise<Unit> =
        test {
            val (agreementRoute, chaptersRoute, _) = routes()
            val registerRoute =
                routeOf {
                    rpcService<IRegistrationService>().registerApplication(
                        buildRegistrationInput(
                            "n",
                            "e",
                            "p",
                            network.lapis.cloud.shared.domain
                                .MembershipAgreementDto("v", "t", "s"),
                        ),
                    )
                }
            var agreementReads = 0
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == agreementRoute -> {
                            agreementReads += 1
                            request.answerWith(if (agreementReads == 1) agreementJson else freshAgreementJson)
                        }
                        request.rpcRoute == chaptersRoute -> request.answerWith(oneChapterJson)
                        request.rpcRoute == registerRoute ->
                            serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.AgreementTextOutdatedException")
                        else -> request.answerWith("null")
                    }
                },
            ) { calls ->
                withMountedRoot("body-registration-layout-outdated") { root, element ->
                    renderRegistrationScreen(root)
                    awaitUntil("the form") {
                        element().querySelector("select") != null &&
                            element().querySelector(".$AGREEMENT_FRAME_CLASS") != null
                    }
                    element().fillRequiredFields()
                    assertTrue(element().inputs("input[type=checkbox]")[0].checked)
                    element().buttonNamed("Antrag einreichen").click()
                    awaitUntil("the fresh text is in the frame") {
                        element().querySelector(".$AGREEMENT_FRAME_CLASS")?.getAttribute("aria-label") == "Beitrittsvertrag (Version v8)"
                    }
                    assertTrue(element().textContent.orEmpty().contains("Neuer Vertragstext"))
                    assertFalse(element().textContent.orEmpty().contains("Zwei Absaetze."), "the old text is gone")
                    assertFalse(element().inputs("input[type=checkbox]")[0].checked, "the consent is withdrawn")
                    assertEquals("Amara Okafor", element().inputs("input[type=text]")[0].value)
                    assertEquals("amara@example.org", element().inputs("input[type=email]")[0].value)
                    assertEquals("correct horse battery staple", element().inputs("input[type=password]")[0].value)
                    assertEquals(1, calls.toRoute(registerRoute).size)
                    assertTrue(element().querySelector("input[type=email]") != null, "still the form, not the pending screen")

                    // Consenting again sends the FRESH version and hash.
                    element().inputs("input[type=checkbox]")[0].click()
                    element().buttonNamed("Antrag einreichen").click()
                    awaitUntil("the second submit") { calls.toRoute(registerRoute).size == 2 }
                    val sent = calls.toRoute(registerRoute).last().rpcParam(0)
                    assertEquals("v8", sent.agreementVersion as String)
                    assertEquals("sha-agreement-def", sent.agreementSha256 as String)
                }
            }
        }

    @Test
    fun friendRegistration_termsChanged_loadsTheFreshTerms_andWithdrawsTheConsent(): Promise<Unit> =
        test {
            val (_, _, termsRoute) = routes()
            val registerRoute =
                routeOf {
                    rpcService<IRegistrationService>().registerFriend(
                        buildFriendRegistrationInput(
                            "n",
                            "e",
                            "p",
                            network.lapis.cloud.shared.domain
                                .FriendTermsDto("v", "t", "s"),
                        ),
                    )
                }
            var termsReads = 0
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == termsRoute -> {
                            termsReads += 1
                            request.answerWith(if (termsReads == 1) termsJson else freshTermsJson)
                        }
                        request.rpcRoute == registerRoute ->
                            serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.AgreementTextOutdatedException")
                        else -> request.answerWith("null")
                    }
                },
            ) {
                withMountedRoot("body-registration-layout-friend-outdated") { root, element ->
                    renderFriendRegistrationScreen(root)
                    awaitUntil("the form") { element().querySelector(".$AGREEMENT_FRAME_CLASS") != null }
                    element().fillRequiredFields()
                    element().buttonNamed("Freund-Konto anlegen").click()
                    awaitUntil("the fresh terms are in the frame") {
                        element().querySelector(".$AGREEMENT_FRAME_CLASS")?.getAttribute("aria-label") ==
                            "Nutzungsbedingungen für Freund-Konten (Version f4)"
                    }
                    assertTrue(element().textContent.orEmpty().contains("Neue Freund-Bedingungen"))
                    assertFalse(element().inputs("input[type=checkbox]")[0].checked)
                    assertEquals("Amara Okafor", element().inputs("input[type=text]")[0].value)
                }
            }
        }
}
