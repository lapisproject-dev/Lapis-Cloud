package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.PartgReviewerSessionInfoDto
import network.lapis.cloud.shared.domain.PartgReviewerVersionDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.310 (R6b) -- the page of the auditor: the token leaves the address bar at once and is never sent in a URL or stored, the code field is a
 * one-time-code field, the header is plain text (no live region), exactly one tab "Versionen", a card per version with three downloads, and a 401
 * during the work ends in one neutral message.
 */
class ReviewerAppDomTest {
    private val token = "Tok-" + "x".repeat(39)
    private val at = LocalDateTime(2026, 11, 8, 10, 30)
    private val info =
        PartgReviewerSessionInfoDto(
            year = 2025,
            invitationExpiresAt = LocalDateTime(2026, 12, 1, 10, 0),
            sessionEndsAt = LocalDateTime(2026, 11, 18, 14, 0),
        )
    private val versions =
        listOf(
            PartgReviewerVersionDto(id = "v1", versionNumber = 1, frozenAt = at, contentHash = "ab".repeat(32), finalized = false),
            PartgReviewerVersionDto(id = "v2", versionNumber = 2, frozenAt = at, contentHash = "cd".repeat(32), finalized = true),
        )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun ok(text: String) = StubResponse(status = 200, text = text)

    /** Runs [block] with the address bar at `/pruefung#t=token`; the original address is restored afterwards. */
    private suspend fun withAuditorAddress(
        withToken: Boolean,
        block: suspend () -> Unit,
    ) {
        val original = window.location.pathname + window.location.search + window.location.hash
        window.history.replaceState(null, "", "/pruefung" + if (withToken) "#t=$token" else "")
        try {
            block()
        } finally {
            window.history.replaceState(null, "", original)
        }
    }

    @Test
    fun theToken_isReadOnce_leavesTheAddressBar_andIsNeverSentInAUrlOrStored(): Promise<Unit> =
        formTest {
            var signedIn = false
            withAuditorAddress(withToken = true) {
                withFetchStub(
                    respond = { request ->
                        when {
                            request.url.endsWith("/api/pruefung/session") && request.method == "GET" ->
                                if (signedIn) {
                                    ok(
                                        Json.encodeToString(PartgReviewerSessionInfoDto.serializer(), info),
                                    )
                                } else {
                                    StubResponse(status = 401, text = "{}")
                                }
                            request.url.endsWith("/api/pruefung/code") -> StubResponse(status = 202, text = "{}")
                            request.url.endsWith("/api/pruefung/session") && request.method == "POST" -> {
                                signedIn = true
                                StubResponse(status = 200, text = "")
                            }
                            request.url.endsWith("/api/pruefung/versions") ->
                                ok(Json.encodeToString(ListSerializer(PartgReviewerVersionDto.serializer()), versions))
                            else -> StubResponse(status = 404, text = "{}")
                        }
                    },
                ) { calls ->
                    mountedForm("reviewer-app") { root, element ->
                        root.renderReviewerApp()
                        awaitUntil("the code request button") { element().allOf("#reviewer-send-code").isNotEmpty() }
                        assertEquals("", window.location.hash, "the fragment is gone from the address bar")
                        assertEquals("/pruefung", window.location.pathname)
                        element().buttonNamed("Code per E-Mail senden").click()
                        awaitUntil("the code field") { element().allOf("label").any { it.shows("Code aus der E-Mail") } }
                        val code = element().controlOf("Code aus der E-Mail") as HTMLInputElement
                        assertEquals("numeric", code.getAttribute("inputmode"))
                        assertEquals("one-time-code", code.getAttribute("autocomplete"))
                        assertEquals("7", code.getAttribute("maxlength"))
                        assertTrue(element().shows("Falls die Einladung gültig ist, wurde ein Code per E-Mail gesendet."))
                        code.value = "482 913"
                        code.dispatchEvent(
                            org.w3c.dom.events
                                .Event("input"),
                        )
                        element().buttonNamed("Anmelden").click()
                        awaitUntil("the versions") { element().allOf("[data-reviewer-version]").size == 2 }
                        // the token only ever travelled in a POST body
                        assertTrue(calls.none { token in it.url }, "no URL carries the token")
                        assertTrue(calls.filter { token in it.body }.all { it.method == "POST" })
                        assertEquals(2, calls.count { token in it.body }, "the code request and the login")
                        val keys = (0 until window.localStorage.length).mapNotNull { window.localStorage.key(it) }
                        assertTrue(keys.none { token in (window.localStorage.getItem(it).orEmpty()) || token in it })
                        // the header: plain text, no live region; exactly one tab
                        val header = element().allOf("[data-reviewer-header]").single()
                        assertTrue(header.shows("Lesezugang für die Prüfung"))
                        assertTrue(header.shows("Rechenschaftsbericht 2025"))
                        assertTrue(
                            element().allOf("[aria-live]").isEmpty() || element().allOf(".lapis-reviewer-header [aria-live]").isEmpty(),
                        )
                        val tabs = element().allOf("[role=tab]")
                        assertEquals(1, tabs.size)
                        assertEquals("Versionen", tabs.single().textContent?.trim())
                        // a card per version with the three downloads and the shortened checksum
                        val first = element().allOf("[data-reviewer-version]").first()
                        assertTrue(first.classList.contains("lapis-reviewer-card"))
                        assertTrue(first.shows("Prüfsumme: abababababab"))
                        val links = first.allOf("a.lapis-reviewer-download")
                        assertEquals(
                            listOf(
                                "/api/pruefung/versions/v1/pdf",
                                "/api/pruefung/versions/v1/xlsx",
                                "/api/pruefung/versions/v1/document?download=1",
                            ),
                            links.map { it.getAttribute("href") },
                        )
                        assertTrue(links.all { it.hasAttribute("download") })
                        assertTrue(element().allOf("[data-reviewer-version]")[1].shows("Intern final"))
                    }
                }
            }
        }

    @Test
    fun withoutALinkAndASession_thePageSaysWhatToDo(): Promise<Unit> =
        formTest {
            withAuditorAddress(withToken = false) {
                withFetchStub(respond = { StubResponse(status = 401, text = "{}") }) {
                    mountedForm("reviewer-app-nolink") { root, element ->
                        root.renderReviewerApp()
                        awaitUntil("the hint") { element().shows("Bitte öffnen Sie den Link aus Ihrer Einladung.") }
                        assertTrue(element().allOf("#reviewer-send-code").isEmpty())
                    }
                }
            }
        }

    @Test
    fun aNewLink_whileASessionIsAlive_endsTheOldSession_andStartsTheCodeFlow(): Promise<Unit> =
        formTest {
            withAuditorAddress(withToken = true) {
                var ended = false
                withFetchStub(
                    respond = { request ->
                        when {
                            request.url.endsWith("/api/pruefung/session") && request.method == "GET" ->
                                ok(Json.encodeToString(PartgReviewerSessionInfoDto.serializer(), info))
                            request.url.endsWith("/api/pruefung/session/end") -> {
                                ended = true
                                StubResponse(status = 200, text = "")
                            }
                            else -> StubResponse(status = 404, text = "{}")
                        }
                    },
                ) {
                    mountedForm("reviewer-app-newlink") { root, element ->
                        root.renderReviewerApp()
                        awaitUntil("the code request button") { element().allOf("#reviewer-send-code").isNotEmpty() }
                        assertTrue(ended, "the old session is ended")
                        assertTrue(element().allOf("[data-reviewer-version]").isEmpty())
                    }
                }
            }
        }

    @Test
    fun anExistingSession_opensTheVersionsAtOnce(): Promise<Unit> =
        formTest {
            withAuditorAddress(withToken = false) {
                withFetchStub(
                    respond = { request ->
                        when {
                            request.url.endsWith(
                                "/api/pruefung/session",
                            ) -> ok(Json.encodeToString(PartgReviewerSessionInfoDto.serializer(), info))
                            request.url.endsWith("/api/pruefung/versions") ->
                                ok(Json.encodeToString(ListSerializer(PartgReviewerVersionDto.serializer()), versions))
                            else -> StubResponse(status = 404, text = "{}")
                        }
                    },
                ) {
                    mountedForm("reviewer-app-session") { root, element ->
                        root.renderReviewerApp()
                        awaitUntil("the versions") { element().allOf("[data-reviewer-version]").size == 2 }
                        assertTrue(element().allOf("#reviewer-send-code").isEmpty())
                        assertTrue(element().shows("Versionen vergleichen"))
                    }
                }
            }
        }

    @Test
    fun a401DuringTheWork_endsInOneNeutralMessage(): Promise<Unit> =
        formTest {
            withAuditorAddress(withToken = false) {
                withFetchStub(
                    respond = { request ->
                        when {
                            request.url.endsWith(
                                "/api/pruefung/session",
                            ) -> ok(Json.encodeToString(PartgReviewerSessionInfoDto.serializer(), info))
                            else -> StubResponse(status = 401, text = "{}")
                        }
                    },
                ) {
                    mountedForm("reviewer-app-401") { root, element ->
                        root.renderReviewerApp()
                        awaitUntil("the message") { element().shows("Sitzung beendet. Bitte öffnen Sie den Link erneut.") }
                        assertFalse(element().shows("Versionen vergleichen"))
                    }
                }
            }
        }

    @Test
    fun aWrongCode_isOneNeutralMessage(): Promise<Unit> =
        formTest {
            withAuditorAddress(withToken = true) {
                withFetchStub(
                    respond = { request ->
                        when {
                            request.url.endsWith(
                                "/api/pruefung/session",
                            ) &&
                                request.method == "GET" -> StubResponse(status = 401, text = "{}")
                            request.url.endsWith("/api/pruefung/code") -> StubResponse(status = 202, text = "{}")
                            else -> StubResponse(status = 401, text = "{}")
                        }
                    },
                ) {
                    mountedForm("reviewer-app-wrong") { root, element ->
                        root.renderReviewerApp()
                        awaitUntil("the button") { element().allOf("#reviewer-send-code").isNotEmpty() }
                        element().buttonNamed("Code per E-Mail senden").click()
                        awaitUntil("the field") { element().allOf("label").any { it.shows("Code aus der E-Mail") } }
                        val code = element().controlOf("Code aus der E-Mail") as HTMLInputElement
                        code.value = "000000"
                        code.dispatchEvent(
                            org.w3c.dom.events
                                .Event("input"),
                        )
                        element().buttonNamed("Anmelden").click()
                        awaitUntil("the message") { element().shows("Code ungültig oder abgelaufen.") }
                        val short = element().controlOf("Code aus der E-Mail") as HTMLInputElement
                        short.value = "12"
                        short.dispatchEvent(
                            org.w3c.dom.events
                                .Event("input"),
                        )
                        element().buttonNamed("Anmelden").click()
                        awaitUntil("the same message again") { element().shows("Code ungültig oder abgelaufen.") }
                    }
                }
            }
        }
}
