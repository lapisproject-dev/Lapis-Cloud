package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgendaItemDto
import network.lapis.cloud.shared.domain.AiAssistantStateDto
import network.lapis.cloud.shared.domain.BoardMembershipDto
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.EventCheckInRowDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.SocialPostErasureDto
import network.lapis.cloud.shared.domain.SocialPostErasureStatus
import network.lapis.cloud.shared.domain.SocialPostReportCategory
import network.lapis.cloud.shared.domain.SocialPostReportDto
import network.lapis.cloud.shared.domain.SocialPostReportStatus
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteOptionDto
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.IAiAssistantService
import network.lapis.cloud.shared.rpc.IBoardMembershipService
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPostalMailService
import network.lapis.cloud.shared.rpc.ISocialNetworkService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.4.29 audit M-3 / M-5 / m-13: WHAT the forms of the second half of the wave send, field by field -- the rule of
 * [FormSubmitBodyPart2DomTest] and [FormSubmitBodyDomTest], applied to the forms that had no such test. Values of same-typed
 * neighbours are DISTINCT (chair vs minute-taker, note vs public reason, the four strings of a postal invitation), text is typed
 * padded with spaces (the client trims), boolean decision flags are asserted explicitly as `true` AND `false`, an empty optional field
 * is asserted as ABSENT/`null` (never `""`), and a call is attributed by its ROUTE ([routeOf], the service method itself), not by its
 * parameter count. A negative case asserts "no RPC AT ALL".
 */
class FormSubmitBodyPart3DomTest {
    private fun HTMLElement.first(selector: String): HTMLElement = assertNotNull(querySelector(selector) as? HTMLElement, selector)

    /** Null, or absent (a `null` default is not encoded): an empty optional field must not be `""`. */
    private fun assertNoValue(
        value: dynamic,
        message: String,
    ) {
        assertTrue(value == null, "$message (was: ${JSON.stringify(value)})")
    }

    private val two =
        listOf(MemberSummaryDto(id = "m1", displayName = "Amara Okafor"), MemberSummaryDto(id = "m2", displayName = "Kofi Mensah"))
    private val three = two + MemberSummaryDto(id = "m3", displayName = "Lena Berg")

    private fun membersJson(list: List<MemberSummaryDto>) = jsonOf(ListSerializer(MemberSummaryDto.serializer()), list)

    /** Every RPC answers `null` (the form's request is what is under test), except the ones [answers] names. */
    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request ->
            if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null")
        }
    }

    private fun adminSession() =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2030, 1, 1, 12, 0),
        )

    // ── communication: a mailing list, a draft message ─────────────────────────────────────────────────────────────

    @Test
    fun mailingList_creation_sendsNameAndDescriptionEachInItsSlot_trimmed_andABlankDescriptionIsNull(): Promise<Unit> =
        formTest {
            val create = routeOf { rpcService<IMailingService>().createMailingList("n", null) }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-list-create") { root, element ->
                    renderCreateMailingListForm(root, refreshSelfService = {}, onCreated = {})
                    element().typeInto("Name", "  Vorstand-Info  ")
                    element().typeInto("Beschreibung", "  Neuigkeiten für den Vorstand  ")
                    element().buttonNamed("Anlegen").click()
                    awaitUntil("createMailingList", timeoutMs = 800) { calls.toRoute(create).size == 1 }
                    val first = calls.singleCall(create)
                    assertEquals("Vorstand-Info", first.rpcParam(0) as String, "the name is trimmed and in its own slot")
                    assertEquals(
                        "Neuigkeiten für den Vorstand",
                        first.rpcParam(1) as String,
                        "the description is trimmed and in its own slot",
                    )
                    awaitUntil("unlocked", timeoutMs = 800) { !element().buttonNamed("Anlegen").hasAttribute("disabled") }

                    element().typeInto("Name", "  Ohne Beschreibung  ")
                    element().typeInto("Beschreibung", "   ")
                    element().buttonNamed("Anlegen").click()
                    awaitUntil("the second createMailingList", timeoutMs = 800) { calls.toRoute(create).size == 2 }
                    val second = calls.toRoute(create)[1]
                    assertEquals("Ohne Beschreibung", second.rpcParam(0) as String)
                    assertNoValue(second.rpcParam(1), "a blank description is null, not an empty string")
                }
            }
        }

    @Test
    fun mailingList_creation_withoutAName_sendsNothingAtAll(): Promise<Unit> =
        formTest {
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-list-create-empty") { root, element ->
                    renderCreateMailingListForm(root, refreshSelfService = {}, onCreated = {})
                    element().buttonNamed("Anlegen").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "no RPC at all")
                    assertEquals(1, element().shownErrors().size)
                }
            }
        }

    private fun mailingList() =
        network.lapis.cloud.shared.domain.MailingListDto(
            id = "list-1",
            name = "Vorstand",
            description = null,
            createdBy = "admin-1",
            subscriberCount = 0,
            isSubscribedByCurrentMember = false,
        )

    @Test
    fun mailingMessage_draft_sendsSubjectAndBodyEachInItsSlot_trimmed(): Promise<Unit> =
        formTest {
            val draft = routeOf { rpcService<IMailingService>().createDraftMessage("l", "s", "b") }
            withFetchStub(respond = answering(routeOf { rpcService<IMemberService>().listMembers() } to membersJson(two))) { calls ->
                mountedForm("p3-draft") { root, element ->
                    renderMailingListDetail(root, mailingList(), refreshSelfService = {})
                    element().typeInto("Betreff", "  Einladung zur Sitzung  ")
                    element().typeInto("Text", "  Liebe Mitglieder, wir laden ein.  ")
                    element().buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("createDraftMessage", timeoutMs = 800) { calls.toRoute(draft).size == 1 }
                    val sent = calls.singleCall(draft)
                    assertEquals("list-1", sent.rpcParam(0) as String)
                    assertEquals("Einladung zur Sitzung", sent.rpcParam(1) as String, "the subject, not the body")
                    assertEquals("Liebe Mitglieder, wir laden ein.", sent.rpcParam(2) as String, "the body, not the subject")
                }
            }
        }

    @Test
    fun mailingMessage_draft_withoutSubjectAndBody_sendsNothingAtAll(): Promise<Unit> =
        formTest {
            val draft = routeOf { rpcService<IMailingService>().createDraftMessage("l", "s", "b") }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-draft-empty") { root, element ->
                    renderMailingListDetail(root, mailingList(), refreshSelfService = {})
                    delay(100)
                    val before = calls.rpcCount
                    element().buttonNamed("Als Entwurf speichern").click()
                    delay(80)
                    assertEquals(before, calls.rpcCount, "no RPC at all")
                    assertEquals(0, calls.toRoute(draft).size)
                }
            }
        }

    // ── board membership: an appointment ────────────────────────────────────────────────────────────────────────────

    @Test
    fun boardAppointment_sendsTheChosenMemberRoleAndDate_eachInItsSlot(): Promise<Unit> =
        formTest {
            val appoint =
                routeOf {
                    rpcService<IBoardMembershipService>().appointBoardMember(
                        network.lapis.cloud.shared.domain
                            .BoardMembershipInput("m", CommitteeRole.MEMBER, LocalDate(2026, 1, 1)),
                    )
                }
            val listMembers = routeOf { rpcService<IMemberService>().listMembers() }
            withFetchStub(respond = answering(listMembers to membersJson(three))) { calls ->
                mountedForm("p3-board-appoint") { root, element ->
                    renderAppointmentForm(root, currentBoardProvider = { emptyList() }, onAppointed = {})
                    awaitUntil("the members are loaded", timeoutMs = 800) {
                        (element().controlOf("Mitglied") as org.w3c.dom.HTMLSelectElement).options.length ==
                            3
                    }
                    element().chooseIn("Mitglied", "m3")
                    element().chooseIn("Rolle", "SECRETARY")
                    element().typeInto("Seit", "  2026-04-01 ")
                    element().buttonNamed("Vorstandsmitglied ernennen").click()
                    awaitUntil("appointBoardMember", timeoutMs = 800) { calls.toRoute(appoint).size == 1 }
                    val input = calls.singleCall(appoint).rpcParam(0)
                    assertEquals("m3", input.memberId as String, "the member the person chose, not the preselected first one")
                    assertEquals("SECRETARY", input.committeeRole as String)
                    assertEquals("2026-04-01", input.startedAt as String)
                }
            }
        }

    @Test
    fun boardAppointment_aBadDate_sendsNothingAtAll(): Promise<Unit> =
        formTest {
            val listMembers = routeOf { rpcService<IMemberService>().listMembers() }
            withFetchStub(respond = answering(listMembers to membersJson(three))) { calls ->
                mountedForm("p3-board-appoint-bad") { root, element ->
                    renderAppointmentForm(root, currentBoardProvider = { emptyList() }, onAppointed = {})
                    awaitUntil("the members are loaded", timeoutMs = 800) {
                        (element().controlOf("Mitglied") as org.w3c.dom.HTMLSelectElement).options.length ==
                            3
                    }
                    val before = calls.rpcCount
                    element().typeInto("Seit", "kein Datum")
                    element().buttonNamed("Vorstandsmitglied ernennen").click()
                    delay(80)
                    assertEquals(before, calls.rpcCount, "no RPC at all")
                    assertEquals(1, element().shownErrors().size)
                }
            }
        }

    @Test
    fun boardAppointment_displacingAnIncumbent_asksFirst_andADoubleClickAppointsOnce(): Promise<Unit> =
        formTest {
            val appoint =
                routeOf {
                    rpcService<IBoardMembershipService>().appointBoardMember(
                        network.lapis.cloud.shared.domain
                            .BoardMembershipInput("m", CommitteeRole.MEMBER, LocalDate(2026, 1, 1)),
                    )
                }
            val listMembers = routeOf { rpcService<IMemberService>().listMembers() }
            val incumbent =
                BoardMembershipDto(
                    id = "bm-1",
                    memberId = "m1",
                    memberDisplayName = "Amara Okafor",
                    committeeRole = CommitteeRole.CHAIR,
                    startedAt = LocalDate(2025, 1, 1),
                    endedAt = null,
                )
            withFetchStub(respond = answering(listMembers to membersJson(three))) { calls ->
                mountedForm("p3-board-displace") { root, element ->
                    renderAppointmentForm(root, currentBoardProvider = { listOf(incumbent) }, onAppointed = {})
                    awaitUntil("the members are loaded", timeoutMs = 800) {
                        (element().controlOf("Mitglied") as org.w3c.dom.HTMLSelectElement).options.length ==
                            3
                    }
                    element().chooseIn("Mitglied", "m2")
                    element().chooseIn("Rolle", "CHAIR")
                    element().buttonNamed("Vorstandsmitglied ernennen").click()
                    delay(60)
                    assertEquals(0, calls.toRoute(appoint).size, "nothing is sent before the person confirmed the displacement")
                    val modal = lastOpenModal()
                    assertTrue(modal.textContent.orEmpty().contains("Amara Okafor"), "the dialog names who is displaced")
                    val confirm = modal.buttonNamed("Ernennen")
                    confirm.click()
                    confirm.click()
                    delay(30)
                    confirm.click()
                    awaitUntil("appointBoardMember", timeoutMs = 800) { calls.toRoute(appoint).isNotEmpty() }
                    delay(150)
                    assertEquals(1, calls.toRoute(appoint).size, "three clicks on the confirm button, one appointment")
                    assertEquals("m2", calls.singleCall(appoint).rpcParam(0).memberId as String)
                }
            }
        }

    // ── statute Q&A ─────────────────────────────────────────────────────────────────────────────────────────────────

    private fun assistantState(optIn: Boolean) =
        jsonOf(
            AiAssistantStateDto.serializer(),
            AiAssistantStateDto(
                featureEnabled = true,
                optIn = optIn,
                optInDefault = false,
                minQuestionChars = 10,
                maxQuestionChars = 300,
                indexedScope = emptyList(),
                unindexedScope = emptyList(),
            ),
        )

    private fun aiSession() = adminSession().copy(aiAssistantEnabled = true)

    @Test
    fun statuteQuestion_sendsTheTrimmedQuestion_andATooShortOneNeverLeaves(): Promise<Unit> =
        formTest {
            AppState.setSession(aiSession())
            val ask = routeOf { rpcService<IAiAssistantService>().askStatuteQuestion("q") }
            val state = routeOf { rpcService<IAiAssistantService>().getAssistantState() }
            withFetchStub(respond = answering(state to assistantState(optIn = true))) { calls ->
                mountedForm("p3-statute") { root, element ->
                    renderStatuteQaScreen(root)
                    awaitUntil("the screen has its state", timeoutMs = 800) {
                        element().querySelector("textarea")?.hasAttribute("disabled") ==
                            false
                    }
                    val button = element().buttonNamed("Frage stellen")
                    element().typeInto("Ihre Frage", "kurz")
                    delay(40)
                    assertTrue(button.hasAttribute("disabled"), "a question below the minimum length cannot be submitted")
                    val before = calls.rpcCount
                    button.click()
                    delay(60)
                    assertEquals(before, calls.rpcCount, "no RPC at all for a too short question")
                    assertEquals(0, calls.toRoute(ask).size)

                    element().typeInto("Ihre Frage", "  Wie hoch ist der Jahresbeitrag?  ")
                    awaitUntil("the button is enabled", timeoutMs = 800) { !button.hasAttribute("disabled") }
                    button.click()
                    awaitUntil("askStatuteQuestion", timeoutMs = 800) { calls.toRoute(ask).size == 1 }
                    assertEquals(
                        "Wie hoch ist der Jahresbeitrag?",
                        calls.singleCall(ask).rpcParam(0) as String,
                        "the question arrives trimmed",
                    )
                }
            }
        }

    @Test
    fun statuteQuestion_withoutTheConsent_theFieldIsLocked_andTickingTheConsentSendsTheOptIn(): Promise<Unit> =
        formTest {
            AppState.setSession(aiSession())
            val ask = routeOf { rpcService<IAiAssistantService>().askStatuteQuestion("q") }
            val optIn =
                routeOf { rpcService<IAiAssistantService>().setMemberOptIn(network.lapis.cloud.shared.domain.AiFeature.STATUTE_QA, true) }
            val state = routeOf { rpcService<IAiAssistantService>().getAssistantState() }
            withFetchStub(respond = answering(state to assistantState(optIn = false))) { calls ->
                mountedForm("p3-statute-consent") { root, element ->
                    renderStatuteQaScreen(root)
                    awaitUntil("the consent checkbox", timeoutMs = 800) { element().querySelector("input[type=checkbox]") != null }
                    assertTrue(element().first("textarea").hasAttribute("disabled"), "without the consent the question field is locked")
                    assertTrue(element().buttonNamed("Frage stellen").hasAttribute("disabled"))
                    assertEquals(0, calls.toRoute(ask).size)
                    element().first("input[type=checkbox]").click()
                    awaitUntil("setMemberOptIn", timeoutMs = 800) { calls.toRoute(optIn).size == 1 }
                    val sent = calls.singleCall(optIn)
                    assertEquals("STATUTE_QA", sent.rpcParam(0) as String)
                    assertEquals(true, sent.rpcParam(1) as Boolean, "ticking sends checked = true")
                }
            }
        }

    // ── meetings ────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun committee() =
        CommitteeDto(
            id = "committee-1",
            name = "Vorstand",
            type = CommitteeType.EXECUTIVE_BOARD,
            description = "",
            active = true,
            quorumPercent = 50,
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        )

    @Test
    fun meetingCreation_theChairAndTheMinuteTakerAreNotSwapped_andBlankOptionalsAreNull(): Promise<Unit> =
        formTest {
            val create =
                routeOf {
                    rpcService<IGovernanceService>().createMeeting(
                        network.lapis.cloud.shared.domain.MeetingInput(
                            "c",
                            "t",
                            LocalDateTime(2026, 1, 1, 1, 1),
                            null,
                            MeetingFormat.IN_PERSON,
                        ),
                    )
                }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-meeting") { root, element ->
                    renderMeetingCreationForm(root, listOf(committee()), three, onCreated = {})
                    element().typeInto("Titel", "  Vorstandssitzung  ")
                    element().typeInto("Termin", "  2026-08-15T18:00 ")
                    element().typeInto("Ort", "  Halle 3  ")
                    element().chooseIn("Format", "HYBRID")
                    element().chooseIn("Sitzungsleitung", "m2")
                    element().chooseIn("Protokollführung", "m3")
                    element().buttonNamed("Sitzung anlegen").click()
                    awaitUntil("createMeeting", timeoutMs = 800) { calls.toRoute(create).size == 1 }
                    val input = calls.singleCall(create).rpcParam(0)
                    assertEquals("m2", input.chairMemberId as String, "the chair is the person chosen as chair")
                    assertEquals("m3", input.minuteTakerMemberId as String, "the minute taker is the person chosen as minute taker")
                    assertEquals("HYBRID", input.format as String)
                    assertEquals("Halle 3", input.location as String)
                }
            }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-meeting-blank") { root, element ->
                    renderMeetingCreationForm(root, listOf(committee()), three, onCreated = {})
                    element().typeInto("Titel", "Nur ein Titel")
                    element().typeInto("Termin", "2026-08-15T18:00")
                    element().typeInto("Ort", "   ")
                    element().buttonNamed("Sitzung anlegen").click()
                    awaitUntil("createMeeting", timeoutMs = 800) { calls.toRoute(create).size == 1 }
                    val input = calls.singleCall(create).rpcParam(0)
                    assertNoValue(input.location, "a blank place is null, not an empty string")
                    assertNoValue(input.chairMemberId, "no chair chosen: null, not an empty string")
                    assertNoValue(input.minuteTakerMemberId, "no minute taker chosen: null, not an empty string")
                }
            }
        }

    @Test
    fun agendaItem_sendsPositionTitleDescriptionAndPresenterEachInItsSlot_andBlankOptionalsAreNull(): Promise<Unit> =
        formTest {
            val add =
                routeOf {
                    rpcService<IGovernanceService>().addAgendaItem(
                        "m",
                        network.lapis.cloud.shared.domain
                            .AgendaItemInput(1, "t"),
                    )
                }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-agenda") { root, element ->
                    renderAddAgendaItemForm(root, "meeting-9", 3, three, onChanged = {})
                    element().typeInto("Position", " 7 ")
                    element().typeInto("Titel", "  Haushalt 2027  ")
                    element().typeInto("Beschreibung", "  Entwurf liegt vor  ")
                    element().chooseIn("Vortragend", "m2")
                    element().buttonNamed("Hinzufügen").click()
                    awaitUntil("addAgendaItem", timeoutMs = 800) { calls.toRoute(add).size == 1 }
                    val request = calls.singleCall(add)
                    assertEquals("meeting-9", request.rpcParam(0) as String)
                    val input = request.rpcParam(1)
                    assertEquals(7, input.position as Int)
                    assertEquals("Haushalt 2027", input.title as String)
                    assertEquals("Entwurf liegt vor", input.description as String)
                    assertEquals("m2", input.presenterMemberId as String)
                }
            }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-agenda-blank") { root, element ->
                    renderAddAgendaItemForm(root, "meeting-9", 3, three, onChanged = {})
                    element().typeInto("Titel", "Nur ein Titel")
                    element().typeInto("Beschreibung", "   ")
                    element().buttonNamed("Hinzufügen").click()
                    awaitUntil("addAgendaItem", timeoutMs = 800) { calls.toRoute(add).size == 1 }
                    val input = calls.singleCall(add).rpcParam(1)
                    assertEquals(3, input.position as Int, "the preset next position")
                    assertNoValue(input.description, "a blank description is null")
                    assertNoValue(input.presenterMemberId, "no presenter chosen is null")
                }
            }
        }

    @Test
    fun resolution_theAgendaItemIsSentWhenChosen_andIsNullWhenNot(): Promise<Unit> =
        formTest {
            val record =
                routeOf {
                    rpcService<IGovernanceService>().recordResolution(
                        "m",
                        network.lapis.cloud.shared.domain.ResolutionInput(
                            null,
                            "t",
                            "x",
                            0,
                            0,
                            0,
                            network.lapis.cloud.shared.domain.ResolutionStatus.ADOPTED,
                        ),
                    )
                }
            val agenda =
                listOf(
                    AgendaItemDto("ai-1", "meeting-9", 1, "Eröffnung", null, null, null),
                    AgendaItemDto("ai-2", "meeting-9", 2, "Haushalt", null, null, null),
                )
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-resolution-agenda") { root, element ->
                    renderRecordResolutionForm(root, "meeting-9", agenda, onChanged = {})
                    element().chooseIn("Tagesordnungspunkt", "ai-2")
                    element().typeInto("Titel", "Haushalt")
                    element().typeInto("Beschlusstext", "Beschlossen.")
                    element().buttonNamed("Beschluss speichern").click()
                    awaitUntil("recordResolution", timeoutMs = 800) { calls.toRoute(record).size == 1 }
                    assertEquals("ai-2", calls.singleCall(record).rpcParam(1).agendaItemId as String)
                }
            }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-resolution-noagenda") { root, element ->
                    renderRecordResolutionForm(root, "meeting-9", agenda, onChanged = {})
                    element().typeInto("Titel", "Haushalt")
                    element().typeInto("Beschlusstext", "Beschlossen.")
                    element().buttonNamed("Beschluss speichern").click()
                    awaitUntil("recordResolution", timeoutMs = 800) { calls.toRoute(record).size == 1 }
                    assertNoValue(calls.singleCall(record).rpcParam(1).agendaItemId, "no agenda item chosen is null")
                }
            }
        }

    @Test
    fun attendance_eachPersonHasHisOwnForm_theRightPersonIsSent_andBlankOptionalsAreNull(): Promise<Unit> =
        formTest {
            val record =
                routeOf {
                    rpcService<IGovernanceService>().recordAttendance(
                        "m",
                        network.lapis.cloud.shared.domain
                            .AttendanceInput("x", network.lapis.cloud.shared.domain.AttendanceStatus.PRESENT),
                    )
                }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-attendance") { root, element ->
                    renderAttendanceRecordingForm(root, "meeting-9", three, emptyList(), onChanged = {})
                    val forms = element().allOf(".lapis-form")
                    assertEquals(3, forms.size, "one mini form per person")

                    // The SECOND person, represented by the THIRD, with a note.
                    val kofi = forms[1]
                    kofi.chooseIn("Status", "REPRESENTED")
                    kofi.chooseIn("Vertreten durch", "m3")
                    kofi.typeInto("Notiz", "  war verhindert  ")
                    kofi.buttonNamed("Speichern").click()
                    awaitUntil("recordAttendance", timeoutMs = 800) { calls.toRoute(record).size == 1 }
                    val first = calls.singleCall(record)
                    assertEquals("meeting-9", first.rpcParam(0) as String)
                    val input = first.rpcParam(1)
                    assertEquals("m2", input.memberId as String, "the person of the form that was saved, not the first or the last")
                    assertEquals("REPRESENTED", input.status as String)
                    assertEquals("m3", input.representedByMemberId as String)
                    assertEquals("war verhindert", input.note as String)
                    awaitUntil("unlocked", timeoutMs = 800) { !kofi.buttonNamed("Speichern").hasAttribute("disabled") }

                    // The THIRD person, present, nothing else: the optionals are null.
                    val lena = forms[2]
                    lena.typeInto("Notiz", "   ")
                    lena.buttonNamed("Speichern").click()
                    awaitUntil("the second recordAttendance", timeoutMs = 800) { calls.toRoute(record).size == 2 }
                    val second = calls.toRoute(record)[1].rpcParam(1)
                    assertEquals("m3", second.memberId as String)
                    assertEquals("PRESENT", second.status as String)
                    assertNoValue(second.representedByMemberId, "nobody chosen is null")
                    assertNoValue(second.note, "a blank note is null")
                }
            }
        }

    @Test
    fun attendance_representedWithoutSaying_by_whom_sendsNothingAtAll(): Promise<Unit> =
        formTest {
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-attendance-rep") { root, element ->
                    renderAttendanceRecordingForm(root, "meeting-9", three, emptyList(), onChanged = {})
                    val amara = element().allOf(".lapis-form")[0]
                    amara.chooseIn("Status", "REPRESENTED")
                    amara.buttonNamed("Speichern").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "no RPC at all")
                    assertTrue(
                        amara
                            .first("[role=alert]")
                            .textContent
                            .orEmpty()
                            .contains("Vertreten"),
                        "the rule is reported in the form of that person",
                    )
                }
            }
        }

    private fun meeting() =
        MeetingDto(
            id = "meeting-9",
            committeeId = "committee-1",
            committeeName = "Vorstand",
            title = "Sitzung",
            scheduledAt = LocalDateTime(2026, 8, 15, 18, 0),
            location = "Halle 1",
            format = MeetingFormat.IN_PERSON,
            status = MeetingStatus.PLANNED,
            calledById = null,
            calledByDisplayName = null,
            calledAt = null,
            chairMemberId = null,
            chairDisplayName = null,
            minuteTakerMemberId = null,
            minuteTakerDisplayName = null,
            protocolDocumentId = null,
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        )

    private fun orgSettingsJson(postal: Boolean) =
        jsonOf(
            OrganizationSettingsDto.serializer(),
            OrganizationSettingsDto(
                id = "org",
                name = "Verein",
                street = null,
                postalCode = null,
                city = null,
                country = null,
                bankIban = null,
                bankBic = null,
                taxExemptionAuthority = null,
                taxExemptionDate = null,
                postalMailEnabled = postal,
            ),
        )

    @Test
    fun postalInvitation_theFourStringsAreNotSwapped_theRecipientIsTheTickedOne_andADoubleClickDispatchesOnce(): Promise<Unit> =
        formTest {
            val dispatch =
                routeOf {
                    rpcService<IPostalMailService>().dispatchEinladungByPost(
                        network.lapis.cloud.shared.domain.PostalInvitationDispatchInput(
                            "t",
                            LocalDateTime(2026, 1, 1, 1, 1),
                            "l",
                            "b",
                            emptyList(),
                        ),
                    )
                }
            val settings = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            withFetchStub(respond = answering(settings to orgSettingsJson(postal = true), dispatch to "[]")) { calls ->
                mountedForm("p3-postal") { root, element ->
                    renderEinladungSection(root, meeting(), canManage = true, isBoardOrAdminGlobal = true, eligibleMembers = three)
                    awaitUntil("the postal button", timeoutMs = 1000) {
                        element().allOf("button").any {
                            it.textContent?.trim() ==
                                "Per Post versenden"
                        }
                    }
                    element().typeInto("Titel", "  Sommerfest  ")
                    element().typeInto("Termin", "  2026-09-05T15:30 ")
                    element().typeInto("Ort", "  Gemeindehaus  ")
                    element().typeInto("Einladungstext", "  Wir freuen uns auf Sie.  ")

                    // No recipient yet: nothing may go out, and no dialog opens.
                    val before = calls.rpcCount
                    element().buttonNamed("Per Post versenden").click()
                    delay(80)
                    assertEquals(before, calls.rpcCount, "no RPC at all without a recipient")
                    assertEquals(0, document.querySelectorAll(".modal.show").length, "no confirmation dialog")

                    element().tick("Kofi Mensah")
                    element().buttonNamed("Per Post versenden").click()
                    awaitUntil("the confirmation dialog", timeoutMs = 800) { document.querySelectorAll(".modal").length > 0 }
                    val confirm = lastOpenModal().buttonNamed("Jetzt per Post versenden")
                    confirm.click()
                    confirm.click()
                    delay(30)
                    confirm.click()
                    awaitUntil("dispatchEinladungByPost", timeoutMs = 800) { calls.toRoute(dispatch).isNotEmpty() }
                    delay(200)
                    assertEquals(1, calls.toRoute(dispatch).size, "a paid, irreversible dispatch: three clicks, ONE dispatch")
                    val input = calls.singleCall(dispatch).rpcParam(0)
                    assertEquals("Sommerfest", input.title as String)
                    assertEquals("2026-09-05T15:30", input.eventDateTime as String)
                    assertEquals("Gemeindehaus", input.location as String)
                    assertEquals("Wir freuen uns auf Sie.", input.bodyText as String)
                    assertEquals(listOf("m2"), (input.recipientMemberIds as Array<String>).toList(), "exactly the ticked recipient")
                }
            }
        }

    // ── motions ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun otherCommittee() = committee().copy(id = "committee-2", name = "Kassenprüfung", type = CommitteeType.COMMISSION)

    private fun motion(status: MotionStatus = MotionStatus.SUBMITTED) =
        MotionDto(
            id = "motion-3",
            targetCommitteeId = "committee-1",
            targetCommitteeName = "Vorstand",
            targetCommitteeType = CommitteeType.EXECUTIVE_BOARD,
            title = "Hauptantrag",
            rationale = "",
            text = "Text",
            submitterMemberId = "m1",
            submitterDisplayName = "Amara",
            status = status,
            submittedAt = LocalDateTime(2026, 8, 1, 12, 0),
            reviewedById = null,
            reviewedByDisplayName = null,
            reviewedAt = null,
            reviewNote = null,
            meetingId = null,
            agendaItemId = null,
            resolutionId = null,
        )

    @Test
    fun motionSubmission_theChosenCommitteeTitleRationaleAndTextEachInTheirSlot(): Promise<Unit> =
        formTest {
            val submit =
                routeOf {
                    rpcService<IGovernanceService>().submitMotion(
                        network.lapis.cloud.shared.domain
                            .MotionInput("c", "t", "r", "x"),
                    )
                }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-motion") { root, element ->
                    renderMotionSubmissionForm(
                        root,
                        listOf(committee(), otherCommittee()),
                        listOf(committee() to motion()),
                        onSubmitted = {},
                    )
                    element().chooseIn("Zielgremium", "committee-2")
                    element().typeInto("Titel", "  Neuer Antrag  ")
                    element().typeInto("Begründung", "  Weil es sinnvoll ist  ")
                    element().typeInto("Antragstext", "  Der Verein möge beschließen.  ")
                    element().buttonNamed("Antrag einreichen").click()
                    awaitUntil("submitMotion", timeoutMs = 800) { calls.toRoute(submit).size == 1 }
                    val input = calls.singleCall(submit).rpcParam(0)
                    assertEquals("committee-2", input.targetCommitteeId as String)
                    assertEquals("Neuer Antrag", input.title as String)
                    assertEquals("Weil es sinnvoll ist", input.rationale as String, "the rationale, not the title or the text")
                    assertEquals("Der Verein möge beschließen.", input.text as String, "the text, not the rationale")
                    assertNoValue(input.amendsMotionId, "a new main motion amends nothing")
                }
            }
        }

    @Test
    fun motionSubmission_anAmendment_usesTheCommitteeOfTheAmendedMotion_andABlankRationaleStaysAnEmptyString(): Promise<Unit> =
        formTest {
            val submit =
                routeOf {
                    rpcService<IGovernanceService>().submitMotion(
                        network.lapis.cloud.shared.domain
                            .MotionInput("c", "t", "r", "x"),
                    )
                }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-motion-amend") { root, element ->
                    renderMotionSubmissionForm(
                        root,
                        listOf(committee(), otherCommittee()),
                        listOf(committee() to motion()),
                        onSubmitted = {},
                    )
                    element().chooseIn("Zielgremium", "committee-2")
                    element().chooseIn("Ändert bestehenden Antrag", "motion-3")
                    element().typeInto("Titel", "Änderung")
                    element().typeInto("Begründung", "   ")
                    element().typeInto("Antragstext", "Ersetze Absatz 2.")
                    element().buttonNamed("Antrag einreichen").click()
                    awaitUntil("submitMotion", timeoutMs = 800) { calls.toRoute(submit).size == 1 }
                    val input = calls.singleCall(submit).rpcParam(0)
                    assertEquals(
                        "committee-1",
                        input.targetCommitteeId as String,
                        "the committee of the amended motion overrides the chosen one",
                    )
                    assertEquals("motion-3", input.amendsMotionId as String)
                    assertEquals("", input.rationale as String, "the rationale is a non-null String in MotionInput: blank stays empty")
                }
            }
        }

    @Test
    fun motionSubmission_withoutTitleAndText_sendsNothingAtAll(): Promise<Unit> =
        formTest {
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-motion-empty") { root, element ->
                    renderMotionSubmissionForm(root, listOf(committee()), emptyList(), onSubmitted = {})
                    element().buttonNamed("Antrag einreichen").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "no RPC at all")
                    assertEquals(2, element().shownErrors().size, "one error at the title, one at the text")
                }
            }
        }

    @Test
    fun motionReview_acceptAndRejectCarryTheirOwnDecision_andTheNoteIsTrimmedOrNull(): Promise<Unit> =
        formTest {
            val review =
                routeOf {
                    rpcService<IGovernanceService>().reviewMotion(
                        "i",
                        network.lapis.cloud.shared.domain.MotionReviewDecision.ACCEPT,
                        null,
                    )
                }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-review") { root, element ->
                    renderReviewSection(root, motion(), canManage = true, onChanged = {})
                    element().typeInto("Notiz", "  Formal in Ordnung  ")
                    element().buttonNamed("Annehmen").click()
                    awaitUntil("accept", timeoutMs = 800) { calls.toRoute(review).size == 1 }
                    val accept = calls.singleCall(review)
                    assertEquals("motion-3", accept.rpcParam(0) as String)
                    assertEquals("ACCEPT", accept.rpcParam(1) as String, "Annehmen sends ACCEPT")
                    assertEquals("Formal in Ordnung", accept.rpcParam(2) as String)
                    awaitUntil("unlocked", timeoutMs = 800) { !element().buttonNamed("Vorläufig ablehnen").hasAttribute("disabled") }

                    element().typeInto("Notiz", "   ")
                    element().buttonNamed("Vorläufig ablehnen").click()
                    awaitUntil("reject", timeoutMs = 800) { calls.toRoute(review).size == 2 }
                    val reject = calls.toRoute(review)[1]
                    assertEquals("REJECT", reject.rpcParam(1) as String, "Vorläufig ablehnen sends REJECT")
                    assertNoValue(reject.rpcParam(2), "a blank note is null")
                }
            }
        }

    private fun vote() =
        VoteDto(
            id = "vote-4",
            motionId = "motion-3",
            meetingId = "meeting-9",
            title = "Abstimmung",
            status = VoteStatus.OPEN,
            options =
                listOf(
                    VoteOptionDto("opt-a", "vote-4", "Ja", 0, 0.0.toDecimal()),
                    VoteOptionDto("opt-b", "vote-4", "Nein", 1, 0.0.toDecimal()),
                    VoteOptionDto("opt-c", "vote-4", "Enthaltung", 2, 0.0.toDecimal()),
                ),
            winnerOptionId = null,
            secondPriceLtr = null,
            openedById = "m1",
            openedByDisplayName = "Amara",
            openedAt = LocalDateTime(2026, 8, 2, 12, 0),
            closedAt = null,
            resolutionId = null,
        )

    @Test
    fun voteBallot_sendsTheChosenOptionAndTheStake_andAnInvalidStakeSendsNothingAtAll(): Promise<Unit> =
        formTest {
            val cast =
                routeOf {
                    rpcService<IGovernanceService>().castVoteBallot(
                        network.lapis.cloud.shared.domain
                            .VoteBallotInput("v", "o", 1.0.toDecimal()),
                    )
                }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-ballot") { root, element ->
                    renderBallotForm(root, vote(), currentOptionId = null, onChanged = {})
                    element().chooseIn("Option", "opt-c")
                    element().typeInto("Einsatz", "abc")
                    element().buttonNamed("Gebot abgeben").click()
                    delay(80)
                    assertEquals(0, calls.rpcCount, "no RPC at all for an invalid stake")
                    assertEquals(1, element().shownErrors().size)

                    element().typeInto("Einsatz", "  12.5 ")
                    element().buttonNamed("Gebot abgeben").click()
                    awaitUntil("castVoteBallot", timeoutMs = 800) { calls.toRoute(cast).size == 1 }
                    val input = calls.singleCall(cast).rpcParam(0)
                    assertEquals("vote-4", input.voteId as String)
                    assertEquals("opt-c", input.optionId as String, "the option the person chose (the third), not the first")
                    assertEquals("12.5", input.stakeLtr.toString(), "the stake, as a decimal")
                }
            }
        }

    @Test
    fun voteBallot_theCurrentOptionIsPreselected_andSentWithoutChoosingAgain(): Promise<Unit> =
        formTest {
            val cast =
                routeOf {
                    rpcService<IGovernanceService>().castVoteBallot(
                        network.lapis.cloud.shared.domain
                            .VoteBallotInput("v", "o", 1.0.toDecimal()),
                    )
                }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-ballot-current") { root, element ->
                    renderBallotForm(root, vote(), currentOptionId = "opt-b", onChanged = {})
                    element().typeInto("Einsatz", "3")
                    element().buttonNamed("Gebot abgeben").click()
                    awaitUntil("castVoteBallot", timeoutMs = 800) { calls.toRoute(cast).size == 1 }
                    assertEquals("opt-b", calls.singleCall(cast).rpcParam(0).optionId as String)
                }
            }
        }

    // ── member administration: the death-date correction ───────────────────────────────────────────────────────────

    private fun deceased() =
        MemberAdminRowDto(
            id = "member-5",
            displayName = "Amara Okafor",
            email = "amara@example.org",
            status = MemberStatus.DECEASED,
            role = AccountRole.MEMBER,
            joinedAt = LocalDate(2026, 1, 1),
            anonymized = false,
        )

    @Test
    fun deathDateCorrection_sendsTheDateAndItsOwnReason_andAnEmptyDateIsNull(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            val correct = routeOf { rpcService<IMemberService>().correctDateOfDeath("m", null, "r") }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-death") { _, _ ->
                    openMemberEditorDialog(deceased(), onChanged = {})
                    val form =
                        lastOpenModal().allOf(".lapis-form").first { f ->
                            f.allOf("button").any {
                                it.textContent?.trim() ==
                                    "Sterbedatum korrigieren"
                            }
                        }
                    form.typeInto("Sterbedatum", "  2026-03-14 ")
                    form.typeInto("Begründung", "  Erfassungsfehler korrigiert  ")
                    form.buttonNamed("Sterbedatum korrigieren").click()
                    awaitUntil("correctDateOfDeath", timeoutMs = 800) { calls.toRoute(correct).size == 1 }
                    val first = calls.singleCall(correct)
                    assertEquals("member-5", first.rpcParam(0) as String)
                    assertEquals("2026-03-14", first.rpcParam(1) as String)
                    assertEquals(
                        "Erfassungsfehler korrigiert",
                        first.rpcParam(2) as String,
                        "the reason of THIS form, not another section's",
                    )
                }
            }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-death-empty") { _, _ ->
                    openMemberEditorDialog(deceased(), onChanged = {})
                    val form =
                        lastOpenModal().allOf(".lapis-form").first { f ->
                            f.allOf("button").any {
                                it.textContent?.trim() ==
                                    "Sterbedatum korrigieren"
                            }
                        }
                    form.typeInto("Sterbedatum", "   ")
                    form.typeInto("Begründung", "Irrtümlich erfasst, zurückgenommen")
                    form.buttonNamed("Sterbedatum korrigieren").click()
                    awaitUntil("correctDateOfDeath", timeoutMs = 800) { calls.toRoute(correct).size == 1 }
                    assertNoValue(calls.singleCall(correct).rpcParam(1), "an empty date takes the recorded date back: null")
                }
            }
        }

    @Test
    fun deathDateCorrection_aFutureDate_sendsNothingAtAll(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            val correct = routeOf { rpcService<IMemberService>().correctDateOfDeath("m", null, "r") }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-death-future") { _, _ ->
                    openMemberEditorDialog(deceased(), onChanged = {})
                    delay(100)
                    val before = calls.rpcCount
                    val form =
                        lastOpenModal().allOf(".lapis-form").first { f ->
                            f.allOf("button").any {
                                it.textContent?.trim() ==
                                    "Sterbedatum korrigieren"
                            }
                        }
                    form.typeInto("Sterbedatum", "2999-01-01")
                    form.typeInto("Begründung", "Erfassungsfehler korrigiert")
                    form.buttonNamed("Sterbedatum korrigieren").click()
                    delay(80)
                    assertEquals(before, calls.rpcCount, "no RPC at all")
                    assertEquals(0, calls.toRoute(correct).size)
                }
            }
        }

    // ── committees ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun committeeMember_sendsTheChosenMemberRoleAndDate_eachInItsSlot(): Promise<Unit> =
        formTest {
            val add =
                routeOf {
                    rpcService<IGovernanceService>().addCommitteeMember(
                        "c",
                        network.lapis.cloud.shared.domain
                            .CommitteeMembershipInput("m", CommitteeRole.MEMBER, LocalDate(2026, 1, 1)),
                    )
                }
            val listMembers = routeOf { rpcService<IMemberService>().listMembers() }
            withFetchStub(respond = answering(listMembers to membersJson(three))) { calls ->
                mountedForm("p3-committee-member") { root, element ->
                    renderAddCommitteeMemberForm(root, "committee-1", onAdded = {})
                    awaitUntil("the members are loaded", timeoutMs = 800) {
                        (element().controlOf("Mitglied") as org.w3c.dom.HTMLSelectElement).options.length ==
                            3
                    }
                    element().chooseIn("Mitglied", "m3")
                    element().chooseIn("Rolle", "SECRETARY")
                    element().typeInto("Seit", "  2026-05-01 ")
                    element().buttonNamed("Mitglied hinzufügen").click()
                    awaitUntil("addCommitteeMember", timeoutMs = 800) { calls.toRoute(add).size == 1 }
                    val request = calls.singleCall(add)
                    assertEquals("committee-1", request.rpcParam(0) as String)
                    val input = request.rpcParam(1)
                    assertEquals("m3", input.memberId as String)
                    assertEquals("SECRETARY", input.role as String)
                    assertEquals("2026-05-01", input.since as String)
                }
            }
        }

    @Test
    fun committeeEdit_sendsEveryFieldInItsSlot_theTickIsTheActiveFlag(): Promise<Unit> =
        formTest {
            val update =
                routeOf {
                    rpcService<IGovernanceService>().updateCommittee(
                        "c",
                        network.lapis.cloud.shared.domain
                            .CommitteeInput("n", CommitteeType.OTHER, "d"),
                    )
                }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-committee-edit") { root, element ->
                    renderCommitteeEditForm(root, committee(), onSaved = {})
                    element().typeInto("Name", "  Finanzausschuss  ")
                    element().chooseIn("Typ", "COMMISSION")
                    element().typeInto("Beschreibung", "  Prüft den Haushalt  ")
                    element().typeInto("Quorum", " 75 ")
                    // active starts ticked (committee.active = true): unticking must send false
                    (element().controlOf("Aktiv") as HTMLInputElement).click()
                    element().buttonNamed("Speichern").click()
                    awaitUntil("updateCommittee", timeoutMs = 800) { calls.toRoute(update).size == 1 }
                    val request = calls.singleCall(update)
                    assertEquals("committee-1", request.rpcParam(0) as String)
                    val input = request.rpcParam(1)
                    assertEquals("Finanzausschuss", input.name as String)
                    assertEquals("COMMISSION", input.type as String)
                    assertEquals("Prüft den Haushalt", input.description as String)
                    assertEquals(75, input.quorumPercent as Int)
                    assertEquals(false, input.active as Boolean, "unticked: active = false")
                }
            }
        }

    // ── social moderation ───────────────────────────────────────────────────────────────────────────────────────────

    private fun erasure() =
        SocialPostErasureDto(
            id = "erasure-2",
            postId = "post-9",
            requestedAt = LocalDateTime(2026, 8, 1, 12, 0),
            requestedBy = "m1",
            subjectMemberId = "m1",
            requesterContact = null,
            reason = "Art. 17",
            status = SocialPostErasureStatus.REQUESTED,
            decidedBy = null,
            decidedAt = null,
            decisionNote = null,
            executedAt = null,
            sourceReportId = null,
        )

    @Test
    fun contentErasure_approveAndRejectCarryTheirOwnFlag_andTheNoteIsTrimmedOrNull(): Promise<Unit> =
        formTest {
            val decide = routeOf { rpcService<ISocialNetworkService>().decideContentErasure("e", true, null) }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-erasure") { root, element ->
                    renderErasureDecidePanel(root, erasure(), onChanged = {})
                    element().buttonNamed("Genehmigen").click()
                    awaitUntil("approve", timeoutMs = 800) { calls.toRoute(decide).size == 1 }
                    val approve = calls.singleCall(decide)
                    assertEquals("erasure-2", approve.rpcParam(0) as String)
                    assertEquals(true, approve.rpcParam(1) as Boolean, "Genehmigen sends approve = true")
                    assertNoValue(approve.rpcParam(2), "no note is null, not an empty string")
                    awaitUntil("unlocked", timeoutMs = 800) { !element().buttonNamed("Ablehnen").hasAttribute("disabled") }

                    element().typeInto("Entscheidungsnotiz", "  kein Rechtsgrund  ")
                    element().buttonNamed("Ablehnen").click()
                    awaitUntil("reject", timeoutMs = 800) { calls.toRoute(decide).size == 2 }
                    val reject = calls.toRoute(decide)[1]
                    assertEquals(false, reject.rpcParam(1) as Boolean, "Ablehnen sends approve = false")
                    assertEquals("kein Rechtsgrund", reject.rpcParam(2) as String)
                }
            }
        }

    @Test
    fun reportDecision_aBlankInternalNoteIsNull(): Promise<Unit> =
        formTest {
            val decide = routeOf { rpcService<ISocialNetworkService>().decideReport("r", SocialPostReportStatus.OPEN, null) }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-report-blank") { root, element ->
                    val report =
                        SocialPostReportDto(
                            id = "report-1",
                            postId = "post-9",
                            postExcerpt = "Auszug",
                            postState = SocialPostState.VISIBLE,
                            postVisibility = SocialPostVisibility.PUBLIC,
                            reportedAt = LocalDateTime(2026, 8, 1, 12, 0),
                            reporterMemberId = "m1",
                            reporterContact = null,
                            category = SocialPostReportCategory.SPAM,
                            description = "Spam",
                            goodFaithConfirmed = true,
                            status = SocialPostReportStatus.OPEN,
                            decidedBy = null,
                            decidedAt = null,
                            decisionNote = null,
                        )
                    renderReportDecidePanel(root, report, onChanged = {})
                    element().typeInto("Entscheidungsnotiz", "   ")
                    element().buttonNamed("Abgelehnt").click()
                    awaitUntil("decideReport", timeoutMs = 800) { calls.toRoute(decide).size == 1 }
                    val sent = calls.singleCall(decide)
                    assertEquals("DISMISSED", sent.rpcParam(1) as String)
                    assertNoValue(sent.rpcParam(2), "a blank note is null")
                }
            }
        }

    // ── the door: the invoice address ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun eventInvoice_blankAddressLinesAreNull_notEmptyStrings(): Promise<Unit> =
        formTest {
            val issue =
                routeOf {
                    rpcService<IEventService>().issueEventInvoice(
                        network.lapis.cloud.shared.domain
                            .EventInvoiceRequestDto("r", null, null, null, null, 14),
                    )
                }
            withFetchStub(respond = answering()) { calls ->
                mountedForm("p3-invoice-blank") { _, _ ->
                    eventInvoiceModal(
                        row =
                            EventCheckInRowDto(
                                registrationId = "reg-1",
                                displayName = "Amara",
                                status = EventRegistrationStatus.CONFIRMED,
                                email = null,
                            ),
                        onIssued = {},
                    )
                    val modal = lastOpenModal()
                    modal.typeInto("PLZ", "  38100 ")
                    modal.typeInto("Straße", "   ")
                    modal.buttonNamed("Rechnung stellen").click()
                    awaitUntil("issueEventInvoice", timeoutMs = 800) { calls.toRoute(issue).size == 1 }
                    val input = calls.singleCall(issue).rpcParam(0)
                    assertEquals("38100", input.billingPostalCode as String)
                    assertNoValue(input.billingStreet, "a blank street is null")
                    assertNoValue(input.billingCity, "an untouched city is null")
                    assertNoValue(input.billingCountry, "an untouched country is null")
                    // 14 is the DTO default and therefore not encoded: absent or 14, never anything else.
                    assertTrue(input.dueInDays == null || (input.dueInDays as Int) == 14, "the default term of 14 days")
                }
            }
        }
}
