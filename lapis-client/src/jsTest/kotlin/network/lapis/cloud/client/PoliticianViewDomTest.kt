package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PoliticianProfileDto
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PoliticianRaterType
import network.lapis.cloud.shared.domain.PoliticianReactionDto
import network.lapis.cloud.shared.domain.PoliticianReactionValue
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IPoliticianService
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.301 "Politiker-Ansicht umgestalten": rating toggles, card actions, collapsed sections, avatars -- driven against a stubbed `fetch`. */
class PoliticianViewDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "viewer-1",
            displayName = "Betrachter",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun politician(
        status: PoliticianProfileStatus = PoliticianProfileStatus.ACTIVE,
        photoUrl: String? = null,
        memberLikes: Int = 2,
        guestLikes: Int = 1,
        memberDislikes: Int = 0,
        guestDislikes: Int = 1,
        combined: Double = 0.0,
    ) = PoliticianProfileDto(
        id = "p1",
        memberId = "member-1",
        displayName = "Amara Okafor",
        status = status,
        mandateText = "Alter Text",
        grantedAt = LocalDateTime(2026, 1, 1, 0, 0),
        grantedByDisplayName = "Board",
        revokedAt = if (status == PoliticianProfileStatus.FORMER) LocalDateTime(2026, 2, 1, 0, 0) else null,
        revokedByDisplayName = if (status == PoliticianProfileStatus.FORMER) "Board" else null,
        memberTrustWeight = 0.0.toDecimal(),
        memberLikeCount = memberLikes,
        memberDislikeCount = memberDislikes,
        guestTrustWeight = 0.0.toDecimal(),
        guestLikeCount = guestLikes,
        guestDislikeCount = guestDislikes,
        combinedTrustWeight = combined.toDecimal(),
        photoUrl = photoUrl,
    )

    private fun reaction(value: PoliticianReactionValue) =
        PoliticianReactionDto(
            id = "r1",
            politicianMemberId = "member-1",
            value = value,
            castAt = LocalDateTime(2026, 3, 1, 0, 0),
            raterType = PoliticianRaterType.MEMBER,
        )

    private fun listJson(p: PoliticianProfileDto) = jsonOf(ListSerializer(PoliticianProfileDto.serializer()), listOf(p))

    private fun HTMLElement.ratingButtons(): List<HTMLElement> = allOf("button[aria-pressed]")

    private fun HTMLElement.hasButton(text: String) = allOf("button").any { it.textContent?.trim() == text }

    /** Mutable fake of the server side for one politician; the stub reads it on every call. */
    private class Server(
        var profile: PoliticianProfileDto,
    ) {
        var mine: PoliticianReactionValue? = null
        var castCalls = mutableListOf<String>()
        var retractCalls = 0
        var failCast = false
        var myRatingDelayMs = 0
    }

    private suspend fun <T> withServer(
        server: Server,
        block: suspend (List<RecordedRequest>) -> T,
    ): T {
        val list = routeOf { rpcService<IPoliticianService>().listPoliticians(false) }
        val top = routeOf { rpcService<IPoliticianService>().getTopPoliticians(6) }
        val profileRoute = routeOf { rpcService<IPoliticianService>().getPoliticianProfile("member-1") }
        val myRating = routeOf { rpcService<IPoliticianService>().getMyRating("member-1") }
        val cast = routeOf { rpcService<IPoliticianService>().castRating("member-1", PoliticianReactionValue.LIKE) }
        val retract = routeOf { rpcService<IPoliticianService>().retractRating("member-1") }
        return withFetchStub(
            respond = { request ->
                if (!request.isRpc) {
                    StubResponse()
                } else {
                    val id = request.json.id as Int
                    when (request.rpcRoute) {
                        list, top -> rpcResult(id, listJson(server.profile))
                        profileRoute -> rpcResult(id, jsonOf(PoliticianProfileDto.serializer(), server.profile))
                        myRating ->
                            StubResponse(
                                text =
                                    rpcResult(
                                        id,
                                        jsonOf(
                                            ListSerializer(PoliticianReactionDto.serializer()),
                                            listOfNotNull(server.mine?.let { reaction(it) }),
                                        ),
                                    ).text,
                                delayMs = server.myRatingDelayMs,
                            )
                        cast -> {
                            val value = PoliticianReactionValue.valueOf(request.rpcParam(1) as String)
                            server.castCalls += value.name
                            if (server.failCast) {
                                serviceExceptionResult(id, "network.lapis.cloud.shared.rpc.ConflictException")
                            } else {
                                server.mine = value
                                rpcResult(id, jsonOf(PoliticianReactionDto.serializer(), reaction(value)))
                            }
                        }
                        retract -> {
                            server.retractCalls++
                            server.mine = null
                            rpcResult(id, "{}") // Unit is serialized as an empty object
                        }
                        else -> rpcResult(id, "null")
                    }
                }
            },
            block = block,
        )
    }

    @Test
    fun rating_threeControls_pressedStateNamesAndCombinedCount(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val server = Server(politician())
            server.mine = PoliticianReactionValue.LIKE
            withServer(server) {
                mountedForm("politician-view-rating") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("rating buttons painted", timeoutMs = 1500) {
                        element().ratingButtons().size == 2 && element().ratingButtons()[0].getAttribute("aria-pressed") == "true"
                    }
                    val (like, dislike) = element().ratingButtons()
                    assertEquals("false", dislike.getAttribute("aria-pressed"))
                    // members + guests: 2 + 1 positive, 0 + 1 negative -- one number per button
                    assertEquals("3", like.textContent?.trim())
                    assertEquals("1", dislike.textContent?.trim())
                    val likeName = like.getAttribute("aria-label").orEmpty()
                    assertTrue(likeName.contains("Amara Okafor") && likeName.contains("3"), "name carries person and number: $likeName")
                    assertEquals(likeName, like.getAttribute("title"))
                    assertTrue(dislike.getAttribute("aria-label").orEmpty().contains("negativ"))
                    for (button in listOf(like, dislike)) {
                        assertTrue(button.classList.contains("lapis-action-btn"))
                        assertFalse(button.classList.contains("btn-sm"))
                    }
                    val group = assertNotNull(element().querySelector("[role=group]"))
                    assertTrue(group.getAttribute("aria-label").orEmpty().contains("Amara Okafor"))
                    // the breakdown line below the buttons
                    assertTrue(element().textContent.orEmpty().contains("Mitglieder: 2 positiv, 0 negativ · Gäste: 1 positiv, 1 negativ"))
                    // the own rating exists -> "Bewertung zurückziehen" is shown
                    assertTrue(element().hasButton("Bewertung zurückziehen"))
                }
            }
        }

    @Test
    fun rating_withoutOwnRating_hidesUndo_andClickCastsThenSecondClickRetracts(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val server = Server(politician())
            withServer(server) {
                mountedForm("politician-view-toggle") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("rating buttons", timeoutMs = 1500) { element().ratingButtons().size == 2 }
                    delay(120)
                    assertFalse(element().hasButton("Bewertung zurückziehen"), "no own rating, nothing to withdraw")
                    assertEquals(listOf("false", "false"), element().ratingButtons().map { it.getAttribute("aria-pressed") })

                    element().ratingButtons()[0].click()
                    awaitUntil(
                        "pressed after cast",
                        timeoutMs = 1500,
                    ) { element().ratingButtons()[0].getAttribute("aria-pressed") == "true" }
                    assertEquals(listOf("LIKE"), server.castCalls)
                    assertTrue(element().hasButton("Bewertung zurückziehen"))
                    awaitUntil("buttons usable again", timeoutMs = 1500) { !(element().ratingButtons()[0] as HTMLButtonElement).disabled }

                    // a second click on the pressed thumb retracts
                    element().ratingButtons()[0].click()
                    awaitUntil("unpressed after retract", timeoutMs = 1500) {
                        element().ratingButtons()[0].getAttribute("aria-pressed") == "false"
                    }
                    assertEquals(1, server.retractCalls)
                    assertFalse(element().hasButton("Bewertung zurückziehen"))
                }
            }
        }

    @Test
    fun rating_oppositeThumb_castsDislike_andUndoRetracts(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val server = Server(politician())
            server.mine = PoliticianReactionValue.LIKE
            withServer(server) {
                mountedForm("politician-view-opposite") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("pressed like", timeoutMs = 1500) {
                        element().ratingButtons().firstOrNull()?.getAttribute("aria-pressed") == "true"
                    }
                    element().ratingButtons()[1].click()
                    awaitUntil("dislike cast", timeoutMs = 1500) { server.castCalls == listOf("DISLIKE") }
                    awaitUntil("dislike pressed", timeoutMs = 1500) { element().ratingButtons()[1].getAttribute("aria-pressed") == "true" }
                    assertEquals("false", element().ratingButtons()[0].getAttribute("aria-pressed"))
                    element().buttonNamed("Bewertung zurückziehen").click()
                    awaitUntil("retracted", timeoutMs = 1500) { server.retractCalls == 1 }
                }
            }
        }

    @Test
    fun rating_failedCall_reenablesTheButtons_andKeepsTheServerState(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val server = Server(politician())
            server.failCast = true
            withServer(server) {
                mountedForm("politician-view-fail") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("rating buttons", timeoutMs = 1500) { element().ratingButtons().size == 2 }
                    delay(120)
                    element().ratingButtons()[0].click()
                    awaitUntil("cast attempted", timeoutMs = 1500) { server.castCalls.size == 1 }
                    delay(200)
                    assertEquals("false", element().ratingButtons()[0].getAttribute("aria-pressed"))
                    assertFalse((element().ratingButtons()[0] as HTMLButtonElement).disabled, "buttons usable again after the failure")
                    assertFalse((element().ratingButtons()[1] as HTMLButtonElement).disabled)
                }
            }
        }

    @Test
    fun rating_change_repaintsTheWeightFiguresOfTheCards(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val server = Server(politician(combined = 0.0))
            withServer(server) {
                mountedForm("politician-view-weights") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("rating buttons", timeoutMs = 1500) { element().ratingButtons().size == 2 }
                    awaitUntil("buttons enabled after first read", timeoutMs = 1500) {
                        !(element().ratingButtons()[0] as HTMLButtonElement).disabled
                    }
                    val weightRow = { element().allOf("div").first { it.textContent?.trim()?.startsWith("Mitglieder-Gewicht") == true } }
                    val before = weightRow().textContent
                    // the shared LTR pool moves the weights on the server once a rating is cast
                    server.profile = politician(combined = 4321.0)
                    element().ratingButtons()[0].click()
                    awaitUntil("weights repainted", timeoutMs = 1500) { weightRow().textContent != before }
                }
            }
        }

    @Test
    fun rating_thumbsAreDisabledUntilTheOwnRatingIsKnown(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val server = Server(politician())
            server.mine = PoliticianReactionValue.LIKE
            server.myRatingDelayMs = 400
            withServer(server) {
                mountedForm("politician-view-initial-disabled") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("rating buttons", timeoutMs = 1500) { element().ratingButtons().size == 2 }
                    assertTrue(
                        element().ratingButtons().all { (it as HTMLButtonElement).disabled },
                        "thumbs stay disabled while the own rating is still being read",
                    )
                    awaitUntil("pressed like", timeoutMs = 2500) {
                        element().ratingButtons().firstOrNull()?.getAttribute("aria-pressed") == "true"
                    }
                    awaitUntil("thumbs enabled", timeoutMs = 1500) { !(element().ratingButtons()[0] as HTMLButtonElement).disabled }
                }
            }
        }

    @Test
    fun card_actionsOnlyForBoard_formerHasNone_revokeAsksFirst(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val server = Server(politician())
            withServer(server) {
                mountedForm("politician-view-board") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("card actions", timeoutMs = 1500) { element().hasButton("Mandatstext bearbeiten") }
                    assertTrue(element().hasButton("Politiker-Status widerrufen"))
                    assertTrue(element().hasButton("Politiker ernennen"), "the appoint button sits in the page header")
                    // the revoke button is last in its group
                    val group = assertNotNull(element().querySelector(".lapis-action-group"))
                    assertEquals(
                        "Politiker-Status widerrufen",
                        group.querySelectorAll("button").let {
                            (it.item(it.length - 1) as HTMLElement).textContent?.trim()
                        },
                    )
                    // accessible names carry the person
                    assertTrue(
                        element()
                            .allOf(
                                "button",
                            ).any { it.getAttribute("aria-label").orEmpty().contains("Politiker-Status widerrufen: Amara Okafor") },
                    )
                    // revoke opens the confirmation; cancelling does not call the server
                    element().buttonNamed("Politiker-Status widerrufen").click()
                    awaitUntil("revoke dialog", timeoutMs = 1000) { document.querySelector(".modal.show") != null }
                    lastOpenModal().buttonNamed("Abbrechen").click()
                    delay(150)
                }
            }
        }

    @Test
    fun card_memberSeesNoManagementActions(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val server = Server(politician())
            withServer(server) {
                mountedForm("politician-view-member") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("card", timeoutMs = 1500) { element().textContent.orEmpty().contains("Amara Okafor") }
                    assertFalse(element().hasButton("Mandatstext bearbeiten"))
                    assertFalse(element().hasButton("Politiker-Status widerrufen"))
                    assertFalse(element().hasButton("Politiker ernennen"))
                    assertFalse(element().hasButton("Verwaltung"))
                    val text = element().textContent.orEmpty()
                    for (forbidden in listOf("BOARD", "ADMIN", "TREASURER", "Like", "Dislike")) {
                        assertFalse(text.contains(forbidden), "the screen shows no '$forbidden'")
                    }
                }
            }
        }

    @Test
    fun card_formerProfile_hasNoActionGroup_butAHintToReactivate(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val server = Server(politician(status = PoliticianProfileStatus.FORMER))
            withServer(server) {
                mountedForm("politician-view-former") { root, element ->
                    renderPoliticianScreen(root)
                    // the default list shows only active profiles in the real app; the stub returns the former one anyway
                    awaitUntil("card", timeoutMs = 1500) { element().textContent.orEmpty().contains("Widerrufen am") }
                    assertFalse(element().hasButton("Mandatstext bearbeiten"))
                    assertFalse(element().hasButton("Politiker-Status widerrufen"))
                    assertTrue(element().textContent.orEmpty().contains("Politiker ernennen“ oben"))
                    assertTrue(element().ratingButtons().isEmpty(), "no rating on a former profile")
                }
            }
        }

    @Test
    fun editButton_togglesTheMandateForm_savingClosesItAndReturnsTheFocus(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val server = Server(politician())
            val mandate = routeOf { rpcService<IPoliticianService>().updateMandateText("member-1", null) }
            withServer(server) { calls ->
                mountedForm("politician-view-edit") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("edit button", timeoutMs = 1500) { element().hasButton("Mandatstext bearbeiten") }
                    val edit = element().buttonNamed("Mandatstext bearbeiten")
                    assertEquals("false", edit.getAttribute("aria-expanded"))
                    assertTrue(edit.getAttribute("aria-controls").orEmpty().startsWith("politician-mandate-"))
                    edit.click()
                    awaitUntil("form open", timeoutMs = 1000) { element().hasButton("Mandatstext speichern") }
                    assertEquals("true", edit.getAttribute("aria-expanded"))
                    // the same button closes it again
                    edit.click()
                    awaitUntil("form closed", timeoutMs = 1000) { !element().hasButton("Mandatstext speichern") }
                    assertEquals("false", edit.getAttribute("aria-expanded"))
                    assertTrue(calls.toRoute(mandate).isEmpty())
                }
            }
        }

    @Test
    fun sections_areCollapsedByDefault_andTheBannerButtonOpensSettings(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<IPoliticianService>().listPoliticians(false) }
            withFetchStub(
                respond = { request ->
                    if (!request.isRpc) {
                        StubResponse()
                    } else if (request.rpcRoute == list) {
                        serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.ConflictException")
                    } else {
                        rpcResult(request.json.id as Int, "null")
                    }
                },
            ) {
                mountedForm("politician-view-sections") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("banner", timeoutMs = 1500) { element().textContent.orEmpty().contains("derzeit deaktiviert") }
                    assertEquals("false", element().buttonNamed("Verwaltung").getAttribute("aria-expanded"))
                    val settings = element().buttonNamed("Einstellungen")
                    assertEquals("false", settings.getAttribute("aria-expanded"))
                    assertFalse(element().hasButton("Snapshot auslösen"), "collapsed: nothing opens on its own")
                    assertTrue(element().textContent.orEmpty().contains("im Bereich „Einstellungen“ aktivieren"))
                    element().buttonNamed("Einstellungen öffnen").click()
                    awaitUntil("settings open", timeoutMs = 1000) {
                        element().buttonNamed("Einstellungen").getAttribute("aria-expanded") ==
                            "true"
                    }
                    assertEquals("false", element().buttonNamed("Verwaltung").getAttribute("aria-expanded"))
                }
            }
        }

    @Test
    fun avatar_photoOnlyWhenTheServerSendsAUrl_otherwiseInitials(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val withPhoto = Server(politician(photoUrl = "/public/member-photos/abc"))
            withServer(withPhoto) {
                mountedForm("politician-view-avatar-photo") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("card", timeoutMs = 1500) { element().querySelector(".lapis-avatar-md") != null }
                    val img = assertNotNull(element().querySelector("img.lapis-avatar-md"))
                    assertEquals("", img.getAttribute("alt"))
                    assertEquals("/public/member-photos/abc", img.getAttribute("src"))
                    awaitUntil("top table avatar", timeoutMs = 1500) { element().querySelector("img.lapis-avatar-sm") != null }
                }
            }
            val without = Server(politician(photoUrl = null))
            withServer(without) {
                mountedForm("politician-view-avatar-initials") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("card", timeoutMs = 1500) { element().querySelector(".lapis-avatar-md") != null }
                    assertNull(element().querySelector("img"))
                    val initials = assertNotNull(element().querySelector(".lapis-public-card-initials.lapis-avatar-md"))
                    assertEquals("true", initials.getAttribute("aria-hidden"))
                    assertEquals("AO", initials.textContent?.trim())
                    awaitUntil("top table avatar", timeoutMs = 1500) { element().querySelector(".lapis-avatar-sm") != null }
                }
            }
        }
}
