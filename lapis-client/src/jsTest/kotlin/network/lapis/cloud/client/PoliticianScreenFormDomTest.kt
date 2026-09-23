package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.PoliticianProfileDto
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PoliticianReactionDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IPoliticianService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W4d (this batch): `PoliticianScreen.kt`'s three migrated `lapisForm`s ("Mandatstext" on a board card,
 * "Politiker-Status erteilen" -- member select + mandate text, "Gewichts-Snapshot auslösen" -- a month
 * text field), driven the way a person does (type, blur, choose, click) against a stubbed `window.fetch`,
 * same idiom `FormGrammarPart3DomTest.kt`/`FormSubmitBodyPart4DomTest.kt` already establish. `canBoard`
 * (BOARD role) is required for the "Verwaltung" panel these three forms live in.
 */
class PoliticianScreenFormDomTest {
    private val boardSession =
        SessionInfoDto(
            memberId = "board-member-1",
            displayName = "Board-Testperson",
            role = AccountRole.BOARD,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val activePolitician =
        PoliticianProfileDto(
            id = "p1",
            memberId = "member-1",
            displayName = "Amara Okafor",
            status = PoliticianProfileStatus.ACTIVE,
            mandateText = "Alter Text",
            grantedAt = LocalDateTime(2026, 1, 1, 0, 0),
            grantedByDisplayName = "Board-Testperson",
            revokedAt = null,
            revokedByDisplayName = null,
            memberTrustWeight = 0.0.toDecimal(),
            memberLikeCount = 0,
            memberDislikeCount = 0,
            guestTrustWeight = 0.0.toDecimal(),
            guestLikeCount = 0,
            guestDislikeCount = 0,
            combinedTrustWeight = 0.0.toDecimal(),
        )

    /** Every RPC answers `null`, except the ones [answers] names (same pattern as `FormGrammarPart3DomTest.kt`). */
    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    @Test
    fun snapshotForm_sendsTheTypedMonth_forAValidDate(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listPoliticiansRoute = routeOf { rpcService<IPoliticianService>().listPoliticians(false) }
            val getTopPoliticiansRoute = routeOf { rpcService<IPoliticianService>().getTopPoliticians(6) }
            val listMembersRoute = routeOf { rpcService<IMemberService>().listMembers() }
            val snapshotRoute = routeOf { rpcService<IPoliticianService>().snapshotWeights(LocalDate(2026, 1, 1)) }
            withFetchStub(
                respond =
                    answering(
                        listPoliticiansRoute to jsonOf(ListSerializer(PoliticianProfileDto.serializer()), emptyList()),
                        getTopPoliticiansRoute to jsonOf(ListSerializer(PoliticianProfileDto.serializer()), emptyList()),
                        listMembersRoute to jsonOf(ListSerializer(MemberSummaryDto.serializer()), emptyList()),
                        snapshotRoute to jsonOf(ListSerializer(PoliticianProfileDto.serializer()), emptyList()),
                    ),
            ) { calls ->
                mountedForm("politician-snapshot-happy") { root, element ->
                    renderPoliticianScreen(root)
                    delay(80)
                    element().typeInto("Monat (JJJJ-MM-TT, Tag wird ignoriert)", "  2026-03-14  ")
                    element().buttonNamed("Snapshot auslösen").click()
                    awaitUntil("snapshotWeights", timeoutMs = 800) { calls.toRoute(snapshotRoute).size == 1 }
                    val call = calls.singleCall(snapshotRoute)
                    assertEquals("2026-03-14", call.rpcParam(0) as String, "the typed date is sent, trimmed")
                    assertTrue(element().shownErrors().isEmpty(), "no field error for a valid date")
                }
            }
        }

    @Test
    fun snapshotForm_withAnInvalidDate_sendsNothingAndShowsAFieldError(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listPoliticiansRoute = routeOf { rpcService<IPoliticianService>().listPoliticians(false) }
            val getTopPoliticiansRoute = routeOf { rpcService<IPoliticianService>().getTopPoliticians(6) }
            val listMembersRoute = routeOf { rpcService<IMemberService>().listMembers() }
            val snapshotRoute = routeOf { rpcService<IPoliticianService>().snapshotWeights(LocalDate(2026, 1, 1)) }
            withFetchStub(
                respond =
                    answering(
                        listPoliticiansRoute to jsonOf(ListSerializer(PoliticianProfileDto.serializer()), emptyList()),
                        getTopPoliticiansRoute to jsonOf(ListSerializer(PoliticianProfileDto.serializer()), emptyList()),
                        listMembersRoute to jsonOf(ListSerializer(MemberSummaryDto.serializer()), emptyList()),
                    ),
            ) { calls ->
                mountedForm("politician-snapshot-invalid") { root, element ->
                    renderPoliticianScreen(root)
                    delay(80)
                    element().typeInto("Monat (JJJJ-MM-TT, Tag wird ignoriert)", "  nicht-ein-datum  ")
                    element().buttonNamed("Snapshot auslösen").click()
                    delay(80)
                    // NOT "no RPC at all": the screen's own background reads (listPoliticians, getTopPoliticians,
                    // listMembers) already ran before the click -- the claim is specifically "no snapshot write".
                    assertTrue(calls.toRoute(snapshotRoute).isEmpty(), "no snapshotWeights call for an invalid date")
                    assertTrue(element().shownErrors().isNotEmpty(), "the invalid date is reported on the field")
                }
            }
        }

    @Test
    fun grantForm_confirmedGrant_sendsTheSelectedMemberAndTheTrimmedMandateText(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listPoliticiansRoute = routeOf { rpcService<IPoliticianService>().listPoliticians(false) }
            val getTopPoliticiansRoute = routeOf { rpcService<IPoliticianService>().getTopPoliticians(6) }
            val listMembersRoute = routeOf { rpcService<IMemberService>().listMembers() }
            val grantRoute = routeOf { rpcService<IPoliticianService>().grantPoliticianStatus("member-1", null) }
            withFetchStub(
                respond =
                    answering(
                        listPoliticiansRoute to jsonOf(ListSerializer(PoliticianProfileDto.serializer()), emptyList()),
                        getTopPoliticiansRoute to jsonOf(ListSerializer(PoliticianProfileDto.serializer()), emptyList()),
                        listMembersRoute to
                            jsonOf(
                                ListSerializer(MemberSummaryDto.serializer()),
                                listOf(MemberSummaryDto(id = "member-1", displayName = "Amara Okafor")),
                            ),
                        grantRoute to jsonOf(PoliticianProfileDto.serializer(), activePolitician),
                    ),
            ) { calls ->
                mountedForm("politician-grant-happy") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("members loaded", timeoutMs = 800) { calls.toRoute(listMembersRoute).size == 1 }
                    delay(80)
                    // `selectField` (like the old raw `select()` before it) does not auto-select a value on its
                    // own -- a real user always chooses from the dropdown, so the test does too.
                    element().chooseIn("Mitglied", "member-1")
                    // Two "Mandatstext..." labels can exist once a card is also rendered; here the politicians
                    // list is empty, so the only match is this form's own field (nth = 0 is enough, kept explicit).
                    element().typeInto("Mandatstext (optional)", "  Neuer Mandatstext  ", nth = 0)
                    element().buttonNamed("Erteilen / Aktualisieren").click()
                    delay(80)
                    lastOpenModal().buttonNamed("Erteilen").click()
                    awaitUntil("grantPoliticianStatus", timeoutMs = 800) { calls.toRoute(grantRoute).size == 1 }
                    val call = calls.singleCall(grantRoute)
                    assertEquals("member-1", call.rpcParam(0) as String, "the only member in the list is selected")
                    assertEquals("Neuer Mandatstext", call.rpcParam(1) as String, "the mandate text is trimmed")
                }
            }
        }

    @Test
    fun mandateForm_onABoardCard_savesTheTrimmedTextForThatPolitician(): Promise<Unit> =
        formTest {
            AppState.setSession(boardSession)
            val listPoliticiansRoute = routeOf { rpcService<IPoliticianService>().listPoliticians(false) }
            val getTopPoliticiansRoute = routeOf { rpcService<IPoliticianService>().getTopPoliticians(6) }
            val listMembersRoute = routeOf { rpcService<IMemberService>().listMembers() }
            val myRatingRoute = routeOf { rpcService<IPoliticianService>().getMyRating("member-1") }
            val mandateRoute = routeOf { rpcService<IPoliticianService>().updateMandateText("member-1", null) }
            withFetchStub(
                respond =
                    answering(
                        listPoliticiansRoute to jsonOf(ListSerializer(PoliticianProfileDto.serializer()), listOf(activePolitician)),
                        getTopPoliticiansRoute to jsonOf(ListSerializer(PoliticianProfileDto.serializer()), listOf(activePolitician)),
                        listMembersRoute to jsonOf(ListSerializer(MemberSummaryDto.serializer()), emptyList()),
                        myRatingRoute to jsonOf(ListSerializer(PoliticianReactionDto.serializer()), emptyList()),
                        mandateRoute to jsonOf(PoliticianProfileDto.serializer(), activePolitician.copy(mandateText = "Neuer Mandatstext")),
                    ),
            ) { calls ->
                mountedForm("politician-mandate-save") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("politician card rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Mandatstext speichern" }
                    }
                    delay(80)
                    element().typeInto("Mandatstext", "  Neuer Mandatstext  ")
                    element().buttonNamed("Mandatstext speichern").click()
                    awaitUntil("updateMandateText", timeoutMs = 800) { calls.toRoute(mandateRoute).size == 1 }
                    val call = calls.singleCall(mandateRoute)
                    assertEquals("member-1", call.rpcParam(0) as String)
                    assertEquals("Neuer Mandatstext", call.rpcParam(1) as String)
                }
            }
        }
}
