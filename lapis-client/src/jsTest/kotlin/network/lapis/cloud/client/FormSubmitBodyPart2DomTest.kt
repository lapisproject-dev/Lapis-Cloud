package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminCreateMemberInput
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.EventCheckInRowDto
import network.lapis.cloud.shared.domain.EventInvoiceRequestDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingInput
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionResolutionInput
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.SocialPostReportCategory
import network.lapis.cloud.shared.domain.SocialPostReportDto
import network.lapis.cloud.shared.domain.SocialPostReportStatus
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IRegistrationService
import network.lapis.cloud.shared.rpc.ISocialNetworkService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Welle V1.4.29 (W4b): WHAT the migrated forms send -- the same rule as `FormSubmitBodyDomTest` (W4a), applied to the forms of
 * this wave that have same-typed neighbours (a swap would pass every other test): the three vote counts, name/e-mail, the four
 * billing-address lines, start/end dates, two textareas of one panel. Each test drives the REAL form in a mounted root with a
 * stubbed `window.fetch` and parses what went over the wire. Values are deliberately DISTINCT and padded with spaces: text is
 * trimmed, a PASSWORD never is.
 */
class FormSubmitBodyPart2DomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            // Leftovers of other test classes (a hidden modal, a live focus trap) must not be found as 'the last modal'.
            closeOpenModals(timeoutMs = 300)
            try {
                block()
            } finally {
                closeOpenModals(timeoutMs = 400)
                AppState.setSession(null)
            }
        }

    /**
     * [withMountedRoot] that closes every open modal BEFORE the root is disposed: disposing the root tears the modal out of the
     * document while Bootstrap still holds its focus trap, which pulls `document.activeElement` onto a modal button in whichever
     * test runs next (see [closeOpenModals]).
     */
    private suspend inline fun <T> mounted(
        id: String,
        block: (io.kvision.panel.Root, () -> HTMLElement) -> T,
    ): T =
        withMountedRoot(id) { root, element ->
            try {
                block(root, element)
            } finally {
                closeOpenModals(timeoutMs = 1000)
            }
        }

    private fun HTMLElement.all(selector: String): List<HTMLElement> =
        (0 until querySelectorAll(selector).length).map { querySelectorAll(selector).item(it) as HTMLElement }

    private fun HTMLElement.button(text: String): HTMLElement = all("button").first { it.textContent?.trim() == text }

    private fun lastModal(): HTMLElement {
        val modals = document.querySelectorAll(".modal")
        return assertNotNull(modals.item(modals.length - 1) as? HTMLElement, "no modal")
    }

    /** The control of the field whose label starts with [label] (`for`-linked, the star suffix ignored); [nth] picks among equal labels. */
    private fun HTMLElement.field(
        label: String,
        nth: Int = 0,
    ): HTMLElement {
        val labels =
            all("label").filter {
                it.textContent
                    .orEmpty()
                    .trim()
                    .removeSuffix("*")
                    .trim()
                    .startsWith(label)
            }
        val target = assertNotNull(labels.getOrNull(nth), "no label '$label' #$nth")
        return assertNotNull(document.getElementById(target.getAttribute("for").orEmpty()) as? HTMLElement, "no control for '$label'")
    }

    private fun HTMLElement.type(
        label: String,
        text: String,
        nth: Int = 0,
    ) {
        val control = field(label, nth)
        when (control) {
            is HTMLInputElement -> control.value = text
            is HTMLTextAreaElement -> control.value = text
            else -> error("not a text control: $label")
        }
        control.dispatchEvent(Event("input"))
        control.dispatchEvent(Event("blur"))
    }

    private fun HTMLElement.choose(
        label: String,
        value: String,
        nth: Int = 0,
    ) {
        val select = field(label, nth) as HTMLSelectElement
        select.value = value
        select.dispatchEvent(Event("change"))
    }

    private fun adminSession(): SessionInfoDto =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2030, 1, 1, 12, 0),
        )

    private fun row(
        role: AccountRole? = AccountRole.MEMBER,
        status: MemberStatus = MemberStatus.ACTIVE,
    ) = MemberAdminRowDto(
        id = "member-5",
        displayName = "Amara Okafor",
        email = "amara@example.org",
        status = status,
        role = role,
        joinedAt = LocalDate(2026, 1, 1),
        anonymized = false,
    )

    /**
     * A request is attributed to a service method by its ROUTE ([routeOf] learns it by calling the method itself), never by its number
     * of parameters: several methods share a parameter count, so a form that called the wrong one would pass a count-based test.
     */
    private suspend fun routes(): Routes =
        Routes(
            coreData = routeOf { rpcService<IMemberService>().updateMemberCoreData("m", "n", "e") },
            status = routeOf { rpcService<IMemberService>().updateMemberStatus("m", MemberStatus.ACTIVE, "r") },
            role = routeOf { rpcService<IMemberService>().updateMemberRole("m", AccountRole.MEMBER) },
            tier = routeOf { rpcService<IMemberService>().updateMemberMembershipTier("m", null, "r") },
            grant = routeOf { rpcService<IMemberService>().grantMemberAccount("m", "p", AccountRole.MEMBER) },
            createMember =
                routeOf {
                    rpcService<IRegistrationService>().createMemberDirect(AdminCreateMemberInput("n", "e", AccountRole.MEMBER, "p"))
                },
            recordResolution =
                routeOf {
                    rpcService<IGovernanceService>().recordResolution(
                        "m",
                        ResolutionInput(null, "t", "x", 0, 0, 0, ResolutionStatus.ADOPTED),
                    )
                },
            resolveMotion =
                routeOf {
                    rpcService<IGovernanceService>().resolveMotion(
                        "m",
                        MotionResolutionInput(0, 0, 0, ResolutionStatus.ADOPTED),
                    )
                },
            createCommittee =
                routeOf {
                    rpcService<IGovernanceService>().createCommittee(
                        CommitteeInput("n", CommitteeType.OTHER, "d"),
                    )
                },
            createMeeting =
                routeOf {
                    rpcService<IGovernanceService>().createMeeting(
                        MeetingInput("c", "t", LocalDateTime(2026, 1, 1, 1, 1), null, MeetingFormat.IN_PERSON),
                    )
                },
            issueInvoice =
                routeOf {
                    rpcService<IEventService>().issueEventInvoice(
                        EventInvoiceRequestDto("r", null, null, null, null, 14),
                    )
                },
            decideReport = routeOf { rpcService<ISocialNetworkService>().decideReport("r", SocialPostReportStatus.OPEN, null) },
            removePost = routeOf { rpcService<ISocialNetworkService>().removePostForLegalReason("p", "r") },
        )

    private class Routes(
        val coreData: String,
        val status: String,
        val role: String,
        val tier: String,
        val grant: String,
        val createMember: String,
        val recordResolution: String,
        val resolveMotion: String,
        val createCommittee: String,
        val createMeeting: String,
        val issueInvoice: String,
        val decideReport: String,
        val removePost: String,
    )

    // ── member editor: five independent forms in one modal ─────────────────────────────────────────────────

    @Test
    fun memberEditor_everySectionSendsItsOwnFields_inTheRightSlots_trimmed(): Promise<Unit> =
        test {
            AppState.setSession(adminSession())
            val r = routes()
            withFetchStub { calls ->
                mounted("body2-editor") { _, _ ->
                    openMemberEditorDialog(row(), onChanged = {})
                    val modal = lastModal()

                    // Stammdaten: name / e-mail (two same-typed text fields)
                    modal.type("Name", "  Amara Neu  ")
                    modal.type("E-Mail", "  neu@example.org ")
                    modal.button("Stammdaten speichern").click()
                    awaitUntil("updateMemberCoreData", timeoutMs = 600) { calls.toRoute(r.coreData).isNotEmpty() }
                    val core = calls.singleCall(r.coreData)
                    assertEquals("member-5", core.rpcParam(0) as String)
                    assertEquals("Amara Neu", core.rpcParam(1) as String, "the name is trimmed and in its own slot")
                    assertEquals("neu@example.org", core.rpcParam(2) as String, "the e-mail is trimmed and in its own slot")

                    // Status: target + death date + reason
                    modal.choose("Neuer Status", "DECEASED")
                    modal.type("Sterbedatum", "  2026-03-14 ")
                    modal.type("Begründung", "  Sterbefall gemeldet, Angehörige  ", nth = 0)
                    modal.button("Status ändern").click()
                    awaitUntil("updateMemberStatus", timeoutMs = 600) { calls.toRoute(r.status).isNotEmpty() }
                    val status = calls.singleCall(r.status)
                    assertEquals("member-5", status.rpcParam(0) as String)
                    assertEquals("DECEASED", status.rpcParam(1) as String)
                    assertEquals("Sterbefall gemeldet, Angehörige", status.rpcParam(2) as String)
                    assertEquals("2026-03-14", status.rpcParam(3) as String)

                    // Rolle
                    modal.choose("Rolle", "BOARD")
                    modal.button("Rolle ändern").click()
                    awaitUntil("updateMemberRole", timeoutMs = 600) { calls.toRoute(r.role).isNotEmpty() }
                    val role = calls.singleCall(r.role)
                    assertEquals("member-5", role.rpcParam(0) as String)
                    assertEquals("BOARD", role.rpcParam(1) as String)

                    // Beitragstarif (ADMIN): no tier chosen = remove, with the SECOND "Begründung" of the modal
                    modal.type("Begründung", "  Tarif entfällt  ", nth = 1)
                    modal.button("Tarif speichern").click()
                    awaitUntil("updateMemberMembershipTier", timeoutMs = 600) { calls.toRoute(r.tier).isNotEmpty() }
                    val tier = calls.singleCall(r.tier)
                    assertEquals("member-5", tier.rpcParam(0) as String)
                    assertNull(tier.rpcParam(1), "no tier chosen is null")
                    assertEquals("Tarif entfällt", tier.rpcParam(2) as String)
                }
            }
        }

    @Test
    fun memberEditor_grantAccount_sendsThePasswordUntrimmed_andTheChosenRole(): Promise<Unit> =
        test {
            AppState.setSession(adminSession())
            val r = routes()
            withFetchStub { calls ->
                mounted("body2-grant") { _, _ ->
                    openMemberEditorDialog(row(role = null), onChanged = {})
                    val modal = lastModal()
                    modal.type("Vorläufiges Passwort", "  pass word 4711 x  ")
                    modal.choose("Rolle", "TREASURER")
                    // Two buttons carry this text? No: the heading is an h2, the action a button.
                    modal.button("Konto anlegen").click()
                    awaitUntil("grantMemberAccount", timeoutMs = 600) { calls.toRoute(r.grant).isNotEmpty() }
                    val grant = calls.singleCall(r.grant)
                    assertEquals("member-5", grant.rpcParam(0) as String)
                    assertEquals("  pass word 4711 x  ", grant.rpcParam(1) as String, "a password is NEVER trimmed")
                    assertEquals("TREASURER", grant.rpcParam(2) as String)
                }
            }
        }

    @Test
    fun memberEditor_aTooShortPassword_isReportedAtTheField_andNothingIsSent(): Promise<Unit> =
        test {
            AppState.setSession(adminSession())
            val r = routes()
            withFetchStub { calls ->
                mounted("body2-grant-invalid") { _, _ ->
                    openMemberEditorDialog(row(role = null), onChanged = {})
                    val modal = lastModal()
                    modal.type("Vorläufiges Passwort", "kurz")
                    delay(100)
                    val before = calls.count { it.isRpc }
                    modal.button("Konto anlegen").click()
                    delay(100)
                    assertEquals(before, calls.count { it.isRpc }, "an invalid password sends no RPC at all")
                    assertEquals(0, calls.toRoute(r.grant).size, "an invalid password blocks the request")
                    assertEquals(1, modal.querySelectorAll(".lapis-field-error--shown").length, "the error stands at the password field")
                }
            }
        }

    @Test
    fun rejectApplication_passesTheTrimmedReason(): Promise<Unit> =
        test {
            mounted("body2-reject") { _, _ ->
                var reason: String? = null
                rejectApplicationDialog(applicantName = "Kofi", onConfirm = { reason = it })
                val modal = lastModal()
                modal.type("Begründung", "  Passt nicht zur Satzung  ")
                modal.button("Ablehnen").click()
                assertEquals("Passt nicht zur Satzung", reason)
            }
        }

    @Test
    fun directMemberCreation_sendsNameEmailPasswordAndRoleEachInItsSlot(): Promise<Unit> =
        test {
            AppState.setSession(adminSession())
            val r = routes()
            withFetchStub { calls ->
                mounted("body2-direct") { root, element ->
                    renderDirectMemberCreation(root)
                    val screen = element()
                    screen.type("Name", "  Kofi Mensah  ")
                    screen.type("E-Mail", "  kofi@example.org ")
                    screen.type("Vorläufiges Passwort", "  pass word 4711 x  ")
                    screen.choose("Rolle", "BOARD")
                    screen.button("Mitglied anlegen").click()
                    awaitUntil("createMemberDirect", timeoutMs = 600) { calls.toRoute(r.createMember).isNotEmpty() }
                    val input = calls.singleCall(r.createMember).rpcParam(0)
                    assertEquals("Kofi Mensah", input.displayName as String)
                    assertEquals("kofi@example.org", input.email as String)
                    assertEquals("  pass word 4711 x  ", input.temporaryPassword as String, "a password is NEVER trimmed")
                    assertEquals("BOARD", input.role as String)
                }
            }
        }

    private fun motionForTests() =
        MotionDto(
            id = "motion-3",
            targetCommitteeId = "committee-1",
            targetCommitteeName = "Vorstand",
            targetCommitteeType = CommitteeType.EXECUTIVE_BOARD,
            title = "Antrag",
            rationale = "",
            text = "Text",
            submitterMemberId = "m1",
            submitterDisplayName = "Amara",
            status = MotionStatus.REVIEWED,
            submittedAt = LocalDateTime(2026, 8, 1, 12, 0),
            reviewedById = null,
            reviewedByDisplayName = null,
            reviewedAt = null,
            reviewNote = null,
            meetingId = null,
            agendaItemId = null,
            resolutionId = null,
        )

    // ── meetings, motions, committees ────────────────────────────────────────────────────────────────────────

    @Test
    fun meetingResolution_theThreeVoteCountsAreNotSwapped(): Promise<Unit> =
        test {
            val r = routes()
            withFetchStub { calls ->
                mounted("body2-resolution") { root, element ->
                    renderRecordResolutionForm(panel = root, meetingId = "meeting-9", agenda = emptyList(), onChanged = {})
                    val screen = element()
                    screen.type("Titel", "  Haushalt 2027  ")
                    screen.type("Beschlusstext", "  Der Haushalt wird beschlossen.  ")
                    screen.type("Ja-Stimmen", " 7 ")
                    screen.type("Nein-Stimmen", " 3 ")
                    screen.type("Enthaltungen", " 2 ")
                    screen.choose("Status", "REJECTED")
                    screen.button("Beschluss speichern").click()
                    awaitUntil("recordResolution", timeoutMs = 600) { calls.toRoute(r.recordResolution).isNotEmpty() }
                    val request = calls.singleCall(r.recordResolution)
                    assertEquals("meeting-9", request.rpcParam(0) as String)
                    val input = request.rpcParam(1)
                    assertEquals("Haushalt 2027", input.title as String)
                    assertEquals("Der Haushalt wird beschlossen.", input.text as String)
                    assertEquals(7, input.votesYes as Int)
                    assertEquals(3, input.votesNo as Int)
                    assertEquals(2, input.votesAbstain as Int)
                    assertEquals("REJECTED", input.status as String)
                }
            }
        }

    @Test
    fun committeeQuorumResolution_theThreeVoteCountsAreNotSwapped(): Promise<Unit> =
        test {
            val r = routes()
            withFetchStub { calls ->
                mounted("body2-quorum") { root, element ->
                    val motion = motionForTests()
                    renderCommitteeQuorumResolutionForm(panel = root, motion = motion, onChanged = {})
                    val screen = element()
                    screen.type("Ja-Stimmen", " 11 ")
                    screen.type("Nein-Stimmen", " 5 ")
                    screen.type("Enthaltungen", " 1 ")
                    screen.button("Entscheidung speichern").click()
                    awaitUntil("resolveMotion", timeoutMs = 600) { calls.toRoute(r.resolveMotion).isNotEmpty() }
                    val request = calls.singleCall(r.resolveMotion)
                    assertEquals("motion-3", request.rpcParam(0) as String)
                    val input = request.rpcParam(1)
                    assertEquals(11, input.votesYes as Int)
                    assertEquals(5, input.votesNo as Int)
                    assertEquals(1, input.votesAbstain as Int)
                }
            }
        }

    @Test
    fun committeeCreation_nameDescriptionAndQuorumEachInTheirSlot(): Promise<Unit> =
        test {
            val r = routes()
            withFetchStub { calls ->
                mounted("body2-committee") { root, element ->
                    renderCommitteeCreation(root = root, onCreated = {})
                    val screen = element()
                    screen.type("Name", "  Finanzausschuss  ")
                    screen.type("Beschreibung", "  Prüft den Haushalt  ")
                    screen.type("Quorum", " 66 ")
                    screen.button("Gremium anlegen").click()
                    awaitUntil("createCommittee", timeoutMs = 600) { calls.toRoute(r.createCommittee).isNotEmpty() }
                    val input = calls.singleCall(r.createCommittee).rpcParam(0)
                    assertEquals("Finanzausschuss", input.name as String)
                    assertEquals("Prüft den Haushalt", input.description as String)
                    assertEquals(66, input.quorumPercent as Int)
                }
            }
        }

    @Test
    fun committeeCreation_aQuorumOutsideTheRange_isReportedAtTheField_andNothingIsSent(): Promise<Unit> =
        test {
            val r = routes()
            withFetchStub { calls ->
                mounted("body2-committee-invalid") { root, element ->
                    renderCommitteeCreation(root = root, onCreated = {})
                    val screen = element()
                    screen.type("Name", "Finanzausschuss")
                    screen.type("Quorum", "101")
                    screen.button("Gremium anlegen").click()
                    delay(100)
                    assertEquals(0, calls.count { it.isRpc }, "no RPC at all")
                    assertEquals(0, calls.toRoute(r.createCommittee).size)
                    assertEquals(
                        "Bitte eine ganze Zahl zwischen 0 und 100 eingeben.",
                        screen
                            .all(".lapis-field-error--shown")
                            .first()
                            .textContent
                            ?.trim(),
                    )
                }
            }
        }

    @Test
    fun meetingCreation_titleLocationAndTimeEachInTheirSlot(): Promise<Unit> =
        test {
            val r = routes()
            withFetchStub { calls ->
                mounted("body2-meeting") { root, element ->
                    val committee =
                        CommitteeDto(
                            id = "committee-1",
                            name = "Vorstand",
                            type = CommitteeType.EXECUTIVE_BOARD,
                            description = "",
                            active = true,
                            quorumPercent = 50,
                            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
                        )
                    renderMeetingCreationForm(
                        panel = root,
                        committees = listOf(committee),
                        memberCandidates = listOf(MemberSummaryDto(id = "m1", displayName = "Amara")),
                        onCreated = {},
                    )
                    val screen = element()
                    screen.type("Titel", "  Vorstandssitzung  ")
                    screen.type("Termin", "  2026-08-15T18:00 ")
                    screen.type("Ort", "  Halle 3  ")
                    screen.button("Sitzung anlegen").click()
                    awaitUntil("createMeeting", timeoutMs = 600) { calls.toRoute(r.createMeeting).isNotEmpty() }
                    val input = calls.singleCall(r.createMeeting).rpcParam(0)
                    assertEquals("committee-1", input.committeeId as String)
                    assertEquals("Vorstandssitzung", input.title as String)
                    assertEquals("Halle 3", input.location as String)
                    assertEquals("2026-08-15T18:00", input.scheduledAt as String)
                }
            }
        }

    // ── the door: the invoice modal has four same-typed address lines ───────────────────────────────────────

    @Test
    fun eventInvoice_theFourBillingLinesAreNotSwapped_andTheDueDaysAreANumber(): Promise<Unit> =
        test {
            val r = routes()
            withFetchStub { calls ->
                mounted("body2-invoice") { _, _ ->
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
                    val modal = lastModal()
                    modal.type("Straße", "  Hauptstraße 1  ")
                    modal.type("PLZ", "  38100 ")
                    modal.type("Ort", "  Braunschweig  ")
                    modal.type("Land", "  Deutschland ")
                    modal.type("Fälligkeitsfrist", " 21 ")
                    modal.button("Rechnung stellen").click()
                    awaitUntil("issueEventInvoice", timeoutMs = 600) { calls.toRoute(r.issueInvoice).isNotEmpty() }
                    val input = calls.singleCall(r.issueInvoice).rpcParam(0)
                    assertEquals("reg-1", input.registrationId as String)
                    assertEquals("Hauptstraße 1", input.billingStreet as String)
                    assertEquals("38100", input.billingPostalCode as String)
                    assertEquals("Braunschweig", input.billingCity as String)
                    assertEquals("Deutschland", input.billingCountry as String)
                    assertEquals(21, input.dueInDays as Int)
                }
            }
        }

    // ── social moderation: two textareas in one panel ────────────────────────────────────────────────────────

    private fun report(status: SocialPostReportStatus = SocialPostReportStatus.OPEN) =
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
            status = status,
            decidedBy = null,
            decidedAt = null,
            decisionNote = null,
        )

    @Test
    fun moderation_theInternalNoteAndThePublicRemovalReasonAreNotSwapped(): Promise<Unit> =
        test {
            val r = routes()
            withFetchStub { calls ->
                mounted("body2-moderation") { root, element ->
                    renderReportDecidePanel(row = root, report = report(), onChanged = {})
                    val screen = element()
                    screen.type("Entscheidungsnotiz", "  nur intern  ")
                    screen.type("Begründung für Beitragsentfernung", "  öffentlich sichtbar  ")
                    screen.button("In Prüfung nehmen").click()
                    awaitUntil("decideReport", timeoutMs = 600) { calls.toRoute(r.decideReport).isNotEmpty() }
                    val decide = calls.singleCall(r.decideReport)
                    assertEquals("report-1", decide.rpcParam(0) as String)
                    assertEquals("UNDER_REVIEW", decide.rpcParam(1) as String)
                    assertEquals("nur intern", decide.rpcParam(2) as String, "the internal note, not the public reason")

                    screen.button("Beitrag entfernen").click()
                    awaitUntil("the confirm dialog", timeoutMs = 600) { document.querySelectorAll(".modal").length > 0 }
                    lastModal().button("Entfernen").click()
                    awaitUntil("removePostForLegalReason", timeoutMs = 600) { calls.toRoute(r.removePost).isNotEmpty() }
                    val remove = calls.singleCall(r.removePost)
                    assertEquals("post-9", remove.rpcParam(0) as String)
                    assertEquals("öffentlich sichtbar", remove.rpcParam(1) as String, "the public reason, not the internal note")
                }
            }
        }

    @Test
    fun moderation_removalWithoutAReason_reportsAtTheField_andOpensNoDialog(): Promise<Unit> =
        test {
            // With a stubbed fetch: a request that escaped would otherwise reach the real (absent) server unseen.
            withFetchStub { calls ->
                mounted("body2-moderation-empty") { root, element ->
                    renderReportDecidePanel(row = root, report = report(), onChanged = {})
                    val screen = element()
                    screen.button("Beitrag entfernen").click()
                    delay(60)
                    assertEquals(0, document.querySelectorAll(".modal").length, "no confirmation dialog for an empty reason")
                    assertEquals(1, screen.all(".lapis-field-error--shown").size)
                    assertEquals(0, calls.count { it.isRpc }, "no RPC at all for an empty removal reason")
                }
            }
        }
}
