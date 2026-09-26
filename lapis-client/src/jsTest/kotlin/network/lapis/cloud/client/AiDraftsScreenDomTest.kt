package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.McpPostDraftDto
import network.lapis.cloud.shared.domain.McpPostDraftReleaseInput
import network.lapis.cloud.shared.domain.McpPostDraftStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.rpc.ISocialNetworkService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.8.2b -- `AiDraftsScreen.kt`'s "KI-Entwürfe" screen. Driven the way a person does (a real
 * mounted root, a stubbed `window.fetch`), same idiom `KeycloakLinkScreenDomTest.kt` establishes.
 * Not exhaustive of every control in the plan's test list -- covers the card shape, the write-switch
 * banner (existing controls stay usable), the discarded-restore footer, and the agent-label tamper
 * hardening (Kares Regel).
 */
class AiDraftsScreenDomTest {
    private fun session(
        mcpEnabled: Boolean = true,
        mcpWriteEnabled: Boolean = true,
    ) = SessionInfoDto(
        memberId = "member-1",
        displayName = "Dana Keller",
        role = AccountRole.MEMBER,
        status = MemberStatus.ACTIVE,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        mcpEnabled = mcpEnabled,
        mcpWriteEnabled = mcpWriteEnabled,
    )

    private fun draft(
        id: String = "draft-1",
        status: McpPostDraftStatus = McpPostDraftStatus.OPEN,
        agentLabel: String = "Claude Desktop",
        statusChangedAt: LocalDateTime? = null,
    ) = McpPostDraftDto(
        id = id,
        content = "Ein von einem Agenten entworfener Beitrag.",
        visibility = SocialPostVisibility.PUBLIC,
        status = status,
        agentLabel = agentLabel,
        createdAt = LocalDateTime(2028, 1, 1, 10, 0),
        updatedAt = LocalDateTime(2028, 1, 1, 10, 0),
        releasedPostId = null,
        statusChangedAt = statusChangedAt,
    )

    @Test
    fun openDraft_showsStatusBadgeVisibilityBadgeAgentPrefixAndContent(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val listRoute = routeOf { rpcService<ISocialNetworkService>().listMyPostDrafts() }
            withFetchStub(
                respond = { request ->
                    if (!request.isRpc) {
                        StubResponse()
                    } else if (request.rpcRoute == listRoute) {
                        request.answerWith(jsonOf(ListSerializer(McpPostDraftDto.serializer()), listOf(draft())))
                    } else {
                        request.answerWith("null")
                    }
                },
            ) {
                mountedForm("ai-drafts-open") { root, element ->
                    renderAiDraftsScreen(root)
                    awaitUntil("draft card rendered", timeoutMs = 800) {
                        element().textContent.orEmpty().contains("Ein von einem Agenten entworfener Beitrag.")
                    }
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Offen"), "status badge missing")
                    assertTrue(text.contains("Öffentlich"), "visibility badge missing")
                    assertTrue(text.contains("Claude Desktop"), "agent label missing")
                    assertTrue(text.contains("Änderungen speichern"), "edit save button missing")
                    assertTrue(text.contains("Entwurf veröffentlichen"), "release button missing")
                    assertTrue(text.contains("Verwerfen"), "discard button missing")
                }
            }
        }

    @Test
    fun writeDisabled_showsBannerButEditReleaseDiscardControlsStillRender(): Promise<Unit> =
        formTest {
            AppState.setSession(session(mcpEnabled = true, mcpWriteEnabled = false))
            val listRoute = routeOf { rpcService<ISocialNetworkService>().listMyPostDrafts() }
            withFetchStub(
                respond = { request ->
                    if (!request.isRpc) {
                        StubResponse()
                    } else if (request.rpcRoute == listRoute) {
                        request.answerWith(jsonOf(ListSerializer(McpPostDraftDto.serializer()), listOf(draft())))
                    } else {
                        request.answerWith("null")
                    }
                },
            ) {
                mountedForm("ai-drafts-write-disabled") { root, element ->
                    renderAiDraftsScreen(root)
                    // Two independent things must both be true: the banner (synchronous, from the
                    // session flags) AND the draft card (async, from the stubbed listMyPostDrafts
                    // round trip) -- awaiting only the banner text races the card's own render.
                    awaitUntil("banner + card rendered", timeoutMs = 800) {
                        val text = element().textContent.orEmpty()
                        text.contains("Schreibwerkzeuge für KI-Agenten abgeschaltet") && text.contains("Änderungen speichern")
                    }
                    val text = element().textContent.orEmpty()
                    // The Geisel-Regel: existing drafts stay fully editable/releasable/discardable.
                    assertTrue(text.contains("Änderungen speichern"), "edit save button must still render")
                    assertTrue(text.contains("Entwurf veröffentlichen"), "release button must still render")
                    assertTrue(text.contains("Verwerfen"), "discard button must still render")
                }
            }
        }

    @Test
    fun discardedDraft_showsRestoreButtonAndDeadline_noEditOrReleaseControls(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val listRoute = routeOf { rpcService<ISocialNetworkService>().listMyPostDrafts() }
            val discarded = draft(status = McpPostDraftStatus.DISCARDED, statusChangedAt = LocalDateTime(2028, 1, 1, 10, 0))
            withFetchStub(
                respond = { request ->
                    if (!request.isRpc) {
                        StubResponse()
                    } else if (request.rpcRoute == listRoute) {
                        request.answerWith(jsonOf(ListSerializer(McpPostDraftDto.serializer()), listOf(discarded)))
                    } else {
                        request.answerWith("null")
                    }
                },
            ) {
                mountedForm("ai-drafts-discarded") { root, element ->
                    renderAiDraftsScreen(root)
                    awaitUntil("discarded card rendered", timeoutMs = 800) {
                        element().textContent.orEmpty().contains("Wiederherstellen")
                    }
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Verworfen"), "discarded status badge missing")
                    assertTrue(text.contains("Wiederherstellbar bis"), "restore deadline missing")
                    assertFalse(text.contains("Änderungen speichern"), "a DISCARDED draft must not show the edit form")
                    assertFalse(text.contains("Entwurf veröffentlichen"), "a DISCARDED draft must not show the release control")
                }
            }
        }

    @Test
    fun emptyState_mcpEnabled_showsTheAgentPrompt(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            withFetchStub(
                respond = { request ->
                    if (request.isRpc) {
                        request.answerWith(jsonOf(ListSerializer(McpPostDraftDto.serializer()), emptyList()))
                    } else {
                        StubResponse()
                    }
                },
            ) {
                mountedForm("ai-drafts-empty-enabled") { root, element ->
                    renderAiDraftsScreen(root)
                    awaitUntil("empty state rendered", timeoutMs = 800) {
                        element().textContent.orEmpty().contains("Ein verbundener KI-Agent kann hier Beitrags-Entwürfe für Sie ablegen")
                    }
                }
            }
        }

    @Test
    fun emptyState_mcpDisabled_showsTheOperatorDisabledText(): Promise<Unit> =
        formTest {
            AppState.setSession(session(mcpEnabled = false, mcpWriteEnabled = false))
            withFetchStub(
                respond = { request ->
                    if (request.isRpc) {
                        request.answerWith(jsonOf(ListSerializer(McpPostDraftDto.serializer()), emptyList()))
                    } else {
                        StubResponse()
                    }
                },
            ) {
                mountedForm("ai-drafts-empty-disabled") { root, element ->
                    renderAiDraftsScreen(root)
                    awaitUntil("empty state rendered", timeoutMs = 800) {
                        element().textContent.orEmpty().contains("Der Betreiber hat die Agenten-Schnittstelle abgeschaltet.")
                    }
                }
            }
        }

    // MAJOR security fix (V1.8.2 wave 3, release-integrity) -- see AiDraftsScreen.kt's
    // renderReleaseControl KDoc. Before this fix, an unsaved edit in the textarea had NO effect on
    // the release button: clicking "Entwurf veröffentlichen" always published the SERVER-STORED
    // (i.e. still the original agent) content, never what the member currently saw on screen.
    @Test
    fun editingContentDisablesReleaseButtonUntilSavedOrReverted(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val listRoute = routeOf { rpcService<ISocialNetworkService>().listMyPostDrafts() }
            withFetchStub(
                respond = { request ->
                    if (!request.isRpc) {
                        StubResponse()
                    } else if (request.rpcRoute == listRoute) {
                        request.answerWith(jsonOf(ListSerializer(McpPostDraftDto.serializer()), listOf(draft())))
                    } else {
                        request.answerWith("null")
                    }
                },
            ) {
                mountedForm("ai-drafts-dirty-release-gate") { root, element ->
                    renderAiDraftsScreen(root)
                    awaitUntil("draft card rendered", timeoutMs = 800) {
                        element().textContent.orEmpty().contains("Ein von einem Agenten entworfener Beitrag.")
                    }

                    val releaseButton = element().buttonNamed("Entwurf veröffentlichen")
                    assertFalse(releaseButton.hasAttribute("disabled"), "release button starts enabled on a freshly rendered card")
                    assertTrue(
                        element().allOf(".alert-warning").none { it.textContent.orEmpty().contains("ungespeicherte") },
                        "no unsaved-changes hint before any edit",
                    )

                    element().typeInto("Beitragstext", "Ein GEÄNDERTER Beitragstext, den der Agent so nie geschrieben hat.")
                    awaitUntil("release button disables once the textarea is dirty", timeoutMs = 800) {
                        element().buttonNamed("Entwurf veröffentlichen").hasAttribute("disabled")
                    }
                    assertTrue(
                        element().allOf(".alert-warning").any { it.textContent.orEmpty().contains("ungespeicherte") },
                        "the unsaved-changes hint must show while the edit is dirty",
                    )

                    // Reverting the textarea to the ORIGINAL server content clears the dirty state
                    // again (the same check `renderDraftEditForm.currentlyChanged()` uses) -- the
                    // gate reacts to actual content equality, not merely "has been touched".
                    element().typeInto("Beitragstext", draft().content)
                    awaitUntil("release button re-enables once the edit is reverted", timeoutMs = 800) {
                        !element().buttonNamed("Entwurf veröffentlichen").hasAttribute("disabled")
                    }
                    assertTrue(
                        element().allOf(".alert-warning").none { it.textContent.orEmpty().contains("ungespeicherte") },
                        "the unsaved-changes hint must disappear again once the edit is reverted",
                    )
                }
            }
        }

    // MINOR security fix (V1.8.2 review round 2, restlücke in the MAJOR fix above) -- see
    // AiDraftsScreen.kt's renderReleaseControl KDoc. `confirmDialog` hides its modal BEFORE running
    // its confirm callback (`ConfirmDialog.kt`), so the edit textarea stays interactive for the whole
    // release round-trip. Before this fix, `runGuardedAction`'s own `finally` (`FormGrammar.kt`)
    // always had the last word on `releaseButton.disabled`, hardcoded to `false` -- regardless of
    // whether an unsaved edit stood at that moment, and regardless of whether the request was still
    // outstanding when the dirty gate fired in between.
    @Test
    fun releaseInFlight_dirtyGateNeverLosesToTheGenericBusyReset(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val listRoute = routeOf { rpcService<ISocialNetworkService>().listMyPostDrafts() }
            val releaseRoute =
                routeOf {
                    rpcService<ISocialNetworkService>().releaseMyPostDraft(
                        McpPostDraftReleaseInput(draftId = "x", initialWeightLtr = 1.0.toDecimal()),
                    )
                }
            var releaseShouldFail = false
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listRoute ->
                            request.answerWith(jsonOf(ListSerializer(McpPostDraftDto.serializer()), listOf(draft())))
                        request.rpcRoute == releaseRoute ->
                            // A real network failure, deliberately delayed: long enough that the
                            // in-flight assertions below run WHILE the request is still outstanding,
                            // not after it already settled.
                            if (releaseShouldFail) StubResponse(networkError = true, delayMs = 400) else request.answerWith("null")
                        else -> request.answerWith("null")
                    }
                },
            ) {
                mountedForm("ai-drafts-release-race") { root, element ->
                    renderAiDraftsScreen(root)
                    awaitUntil("draft card rendered", timeoutMs = 800) {
                        element().textContent.orEmpty().contains("Ein von einem Agenten entworfener Beitrag.")
                    }

                    releaseShouldFail = true
                    element().typeInto("Einsatz (LTR)", "1")
                    val releaseButtonBefore = element().buttonNamed("Entwurf veröffentlichen")
                    assertFalse(releaseButtonBefore.hasAttribute("disabled"), "clean and untouched: release button starts enabled")
                    releaseButtonBefore.click()
                    lastOpenModal().buttonNamed("Jetzt veröffentlichen").click()

                    awaitUntil("release button disables the instant the request goes out", timeoutMs = 200) {
                        element().buttonNamed("Entwurf veröffentlichen").hasAttribute("disabled")
                    }

                    // Symptom 2 of the finding: editing -- and even reverting -- WHILE the request is
                    // still in flight must never re-enable the button. The release RPC is still
                    // outstanding at this point (the stub answers only after 400ms).
                    element().typeInto("Beitragstext", "Eine Änderung während der laufenden Veröffentlichung.")
                    assertTrue(
                        element().buttonNamed("Entwurf veröffentlichen").hasAttribute("disabled"),
                        "still in flight: must stay disabled once dirty",
                    )
                    element().typeInto("Beitragstext", draft().content)
                    assertTrue(
                        element().buttonNamed("Entwurf veröffentlichen").hasAttribute("disabled"),
                        "still in flight: reverting to clean must NOT re-enable the button before the request settles",
                    )

                    // Leave a real, unsaved edit standing for when the failure below resolves (symptom 1).
                    element().typeInto("Beitragstext", "Der Agentensatz muss raus, das hier bleibt jetzt so.")

                    // Longer than the stub's 400ms delay: the request has now definitely failed and
                    // `guarded {}` has returned `null` (no `onChanged()`/reload -- the card is still
                    // this same DOM).
                    delay(700)

                    assertTrue(
                        element().buttonNamed("Entwurf veröffentlichen").hasAttribute("disabled"),
                        "the release failed while an unsaved edit stood -- the button must stay disabled, " +
                            "not fall back to the generic 'request is over' reset",
                    )
                    assertTrue(
                        element().allOf(".alert-warning").any { it.textContent.orEmpty().contains("ungespeicherte") },
                        "the unsaved-changes hint must still show after the failed release",
                    )

                    // Sanity: the gate still fully works afterwards -- reverting now DOES re-enable it.
                    element().typeInto("Beitragstext", draft().content)
                    awaitUntil("release button re-enables once genuinely clean again", timeoutMs = 800) {
                        !element().buttonNamed("Entwurf veröffentlichen").hasAttribute("disabled")
                    }
                }
            }
        }

    // Kares Regel: the self-declared, unverified agent label is never a heading, never bold, and
    // never a `title` attribute -- a forged marker in it must render as harmless text only.
    @Test
    fun agentLabel_neverRendersAsAHeadingOrBoldOrTitleAttribute(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val listRoute = routeOf { rpcService<ISocialNetworkService>().listMyPostDrafts() }
            val taggedAgent = draft(agentLabel = "###KvI18nS###Agent<b>X</b>")
            withFetchStub(
                respond = { request ->
                    if (!request.isRpc) {
                        StubResponse()
                    } else if (request.rpcRoute == listRoute) {
                        request.answerWith(jsonOf(ListSerializer(McpPostDraftDto.serializer()), listOf(taggedAgent)))
                    } else {
                        request.answerWith("null")
                    }
                },
            ) {
                mountedForm("ai-drafts-tamper") { root, element ->
                    renderAiDraftsScreen(root)
                    awaitUntil("draft card rendered", timeoutMs = 800) {
                        element().textContent.orEmpty().contains("Agent")
                    }
                    val root0 = element()
                    for (level in 1..6) {
                        // The page's own h1 (the "KI-Entwürfe" pageHeader title) is expected and
                        // unrelated -- the check is that the AGENT LABEL specifically never sits in
                        // any heading, not that no heading exists at all.
                        assertTrue(
                            root0.allOf("h$level").none { it.textContent.orEmpty().contains("Agent") },
                            "agent label must never sit in an h$level",
                        )
                    }
                    assertTrue(
                        root0.allOf(".fw-bold").none { it.textContent.orEmpty().contains("Agent") },
                        "agent label must never render inside a .fw-bold element",
                    )
                    assertTrue(
                        root0.allOf("*").none { it.getAttribute("title")?.contains("Agent") == true },
                        "agent label must never sit in a title attribute",
                    )
                }
            }
        }
}
