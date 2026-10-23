package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttendanceDto
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.InvitationAudience
import network.lapis.cloud.shared.domain.MeetingDetailDto
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberPickDto
import network.lapis.cloud.shared.domain.MemberPickPageDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPostalMailService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.101 -- the General Assembly in the client: the roster view of the assembly (automatic membership, N, offices, collapsed legacy
 * entries), the invitation form (all active members resolved on the server, parts, postal cap), the agenda/attendance forms with the
 * server-side person search, the creation form's committee-bound pickers, the form of the PDF request and the ballot eligibility of a
 * vote. The central NEGATIVE assertion: not one request to `listMembers` for the assembly.
 */
class GeneralAssemblyScreensDomTest {
    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    private fun HTMLElement?.isShown(): Boolean = this != null && this.offsetParent != null

    private fun committee(
        id: String = "committee-1",
        type: CommitteeType = CommitteeType.GENERAL_ASSEMBLY,
        name: String = "Mitgliederversammlung",
    ) = CommitteeDto(
        id = id,
        name = name,
        type = type,
        description = "",
        active = true,
        quorumPercent = 50,
        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
    )

    private fun membership(
        id: String,
        name: String,
        role: CommitteeRole,
    ) = CommitteeMembershipDto(
        id = id,
        committeeId = "committee-1",
        memberId = "member-$id",
        memberDisplayName = name,
        role = role,
        since = LocalDate(2024, 1, 1),
        until = null,
    )

    private fun meeting() =
        MeetingDto(
            id = "meeting-9",
            committeeId = "committee-1",
            committeeName = "Mitgliederversammlung",
            title = "Jahresversammlung",
            scheduledAt = LocalDateTime(2026, 9, 12, 18, 30),
            location = null,
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

    private fun attendance(
        n: Int,
        status: AttendanceStatus = AttendanceStatus.PRESENT,
    ) = AttendanceDto(
        id = "att-$n",
        meetingId = "meeting-9",
        memberId = "m$n",
        memberDisplayName = "Person $n",
        status = status,
        representedByMemberId = if (status == AttendanceStatus.REPRESENTED) "rep-$n" else null,
        representedByDisplayName = if (status == AttendanceStatus.REPRESENTED) "Vertreter $n" else null,
        note = "Notiz $n",
        recordedAt = LocalDateTime(2026, 9, 12, 18, 0),
    )

    private fun searchPage(vararg picks: MemberPickDto) =
        jsonOf(MemberPickPageDto.serializer(), MemberPickPageDto(picks.toList(), hasMore = false))

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

    private suspend fun searchRoute(): String = routeOf { rpcService<IGovernanceService>().searchCommitteeCandidates("c", "ab", 0) }

    private suspend fun listMembersRoute(): String = routeOf { rpcService<IMemberService>().listMembers() }

    // ── Gremien: the assembly's roster ──────────────────────────────────────────────────────────────────

    @Test
    fun assemblyRoster_showsAutomaticMembershipAndN_officesOnly_legacyCollapsed_noMemberDirectory(): Promise<Unit> =
        formTest {
            val count = routeOf { rpcService<IGovernanceService>().countGeneralAssemblyEligible("c") }
            val roster = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c", true) }
            val everyone = listMembersRoute()
            val members =
                jsonOf(
                    ListSerializer(CommitteeMembershipDto.serializer()),
                    listOf(
                        membership("1", "Cora Chair", CommitteeRole.CHAIR),
                        membership("2", "Mira Legacy", CommitteeRole.MEMBER),
                        membership("3", "Lars Legacy", CommitteeRole.MEMBER),
                    ),
                )
            withFetchStub(respond = answering(count to "4242", roster to members)) { calls ->
                mountedForm("ga-roster") { root, element ->
                    renderCommitteeRoster(root, committee(), canManage = true)
                    awaitUntil(
                        "N is shown",
                    ) { element().textContent.orEmpty().contains("Alle aktiven Mitglieder (automatisch), aktuell 4242") }
                    awaitUntil("the offices are listed") { element().textContent.orEmpty().contains("Cora Chair") }
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Diese Zahl ist die Grundlage für Beschlussfähigkeit, Abstimmungen und Wahlen."))
                    assertTrue(text.contains("Ämter der Versammlung"))
                    // nobody can be added as a plain member any more
                    assertTrue(element().allOf("button").none { it.textContent?.trim() == "Mitglied hinzufügen" }, "no add-member button")
                    val roleSelect = element().controlOf("Rolle") as HTMLSelectElement
                    val roles = (0 until roleSelect.options.length).map { roleSelect.options.item(it)!!.getAttribute("value") }
                    assertFalse("MEMBER" in roles, "membership is automatic: MEMBER is not an office")
                    assertTrue("CHAIR" in roles)
                    assertEquals("CHAIR", roleSelect.value, "the chair is preselected")
                    // legacy entries: collapsed, read only, never deletable
                    val details = assertNotNull(element().querySelector("details"), "the legacy entries sit in a <details>")
                    assertFalse(details.hasAttribute("open"), "collapsed by default")
                    assertTrue(details.textContent.orEmpty().contains("Frühere Einzeleinträge (2)"))
                    assertTrue(details.textContent.orEmpty().contains("wirken nicht auf Teilnahme, Stimmrecht oder Beschlussfähigkeit"))
                    assertTrue(details.textContent.orEmpty().contains("Mira Legacy"))
                    assertTrue(element().allOf("button").none { (it.textContent.orEmpty()).contains("Löschen") }, "no delete function")
                    // the person search is the only way to find a member -- and the whole directory is never loaded
                    assertEquals(0, calls.toRoute(everyone).size, "no listMembers for the assembly")
                    assertEquals(1, calls.toRoute(count).size)
                }
            }
        }

    @Test
    fun assemblyRoster_aFailedCount_leavesTheSentenceWithoutANumber_andNoErrorBlock(): Promise<Unit> =
        formTest {
            val count = routeOf { rpcService<IGovernanceService>().countGeneralAssemblyEligible("c") }
            val roster = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c", true) }
            val empty = jsonOf(ListSerializer(CommitteeMembershipDto.serializer()), emptyList())
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == count -> StubResponse(networkError = true)
                        request.rpcRoute == roster -> request.answerWith(empty)
                        else -> request.answerWith("null")
                    }
                },
            ) { calls ->
                mountedForm("ga-roster-nocount") { root, element ->
                    renderCommitteeRoster(root, committee(), canManage = false)
                    awaitUntil("the count was asked") { calls.toRoute(count).isNotEmpty() }
                    awaitUntil("the roster was loaded") { calls.toRoute(roster).isNotEmpty() }
                    delay(120)
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Alle aktiven Mitglieder (automatisch)"))
                    assertFalse(text.contains("aktuell"), "no figure without a count")
                    assertNull(element().querySelector(".alert"), "no error block")
                    assertTrue(element().allOf("button").none { it.textContent?.trim() == "Amt vergeben" }, "a reader has no office form")
                }
            }
        }

    @Test
    fun otherCommittees_keepTheirRoster_andNeverAskForTheAssemblyCount(): Promise<Unit> =
        formTest {
            val count = routeOf { rpcService<IGovernanceService>().countGeneralAssemblyEligible("c") }
            val roster = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c", true) }
            val members =
                jsonOf(
                    ListSerializer(CommitteeMembershipDto.serializer()),
                    listOf(membership("1", "Cora Chair", CommitteeRole.CHAIR), membership("2", "Mira Member", CommitteeRole.MEMBER)),
                )
            withFetchStub(respond = answering(roster to members)) { calls ->
                mountedForm("ga-roster-other") { root, element ->
                    renderCommitteeRoster(root, committee(type = CommitteeType.WORKING_GROUP, name = "AG"), canManage = true)
                    awaitUntil("both members are listed") { element().textContent.orEmpty().contains("Mira Member") }
                    assertTrue(
                        element().allOf("button").any { it.textContent?.trim() == "Mitglied hinzufügen" },
                        "the add-member form stays",
                    )
                    assertEquals(0, calls.toRoute(count).size)
                    assertNull(element().querySelector("details"))
                }
            }
        }

    // ── Sitzungen: the invitation to the assembly ───────────────────────────────────────────────────────

    private fun Root.einladung(count: Int?) =
        renderEinladungSection(
            this,
            meeting(),
            canManage = true,
            isBoardOrAdminGlobal = true,
            audience = MeetingAudience.Derived(committeeId = "committee-1", eligibleCount = count),
        )

    @Test
    fun invitation_defaultsToAllActive_andOffersOneDownloadPerThousand(): Promise<Unit> =
        formTest {
            val settings = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val everyone = listMembersRoute()
            withFetchStub(respond = answering(settings to orgSettingsJson(postal = true))) { calls ->
                mountedForm("ga-invite-parts") { root, element ->
                    root.einladung(count = 2500)
                    element().openInvitation()
                    awaitUntil("the form") { element().querySelector(".lapis-form") != null }
                    val audience = element().controlOf("Empfänger") as HTMLSelectElement
                    assertEquals("ALL_ACTIVE", audience.value, "all active members is preselected")
                    assertTrue(element().textContent.orEmpty().contains("An alle aktiven Mitglieder (2500)"))
                    assertTrue(element().textContent.orEmpty().contains("eine E-Mail wird nicht verschickt"))
                    assertEquals(
                        listOf("Teil 1 von 3 herunterladen", "Teil 2 von 3 herunterladen", "Teil 3 von 3 herunterladen"),
                        element().allOf("button").map { it.textContent.orEmpty().trim() }.filter { it.startsWith("Teil ") },
                    )
                    assertTrue(element().allOf("button").none { it.textContent?.trim() == "Als PDF herunterladen" })
                    // N > 50: no postal dispatch, the reason is said
                    awaitUntil("the postal gate") { calls.toRoute(settings).isNotEmpty() }
                    delay(150)
                    val postal = element().allOf("button").firstOrNull { it.textContent?.trim() == "Per Post versenden" }
                    assertFalse(postal.isShown(), "more than 50 recipients: no postal button")
                    assertTrue(
                        element().textContent.orEmpty().contains(
                            "Postversand ist auf 50 Empfänger begrenzt; die Mitgliederversammlung hat aktuell 2500",
                        ),
                    )
                    assertEquals(0, calls.toRoute(everyone).size, "the directory is never loaded")
                    // the picker of the explicit selection is only there for "Ausgewählte Mitglieder"
                    // a hidden KVision widget is not rendered at all
                    assertTrue(element().allOf("label").none { it.textContent.orEmpty().startsWith("Mitglied hinzufügen") })
                    audience.value = "SELECTED"
                    audience.dispatchEvent(
                        org.w3c.dom.events
                            .Event("change"),
                    )
                    awaitUntil(
                        "the picker appears",
                    ) { element().allOf("label").any { it.textContent.orEmpty().startsWith("Mitglied hinzufügen") } }
                    assertEquals(
                        listOf("Als PDF herunterladen"),
                        element()
                            .allOf("button")
                            .map {
                                it.textContent.orEmpty().trim()
                            }.filter { it.contains("herunterladen") },
                    )
                }
            }
        }

    @Test
    fun invitation_smallAssembly_oneDownload_andPostalGoesToAllWithTheExactCount(): Promise<Unit> =
        formTest {
            val settings = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val dispatch =
                routeOf {
                    rpcService<IPostalMailService>().dispatchEinladungByPost(
                        network.lapis.cloud.shared.domain
                            .PostalInvitationDispatchInput("t", LocalDateTime(2026, 1, 1, 1, 1), "l", "b"),
                    )
                }
            withFetchStub(respond = answering(settings to orgSettingsJson(postal = true), dispatch to "[]")) { calls ->
                mountedForm("ga-invite-postal") { root, element ->
                    root.einladung(count = 40)
                    element().openInvitation()
                    awaitUntil("the postal button") { element().allOf("button").any { it.textContent?.trim() == "Per Post versenden" } }
                    assertTrue(
                        element().allOf("button").any { it.textContent?.trim() == "Als PDF herunterladen" },
                        "one part: one plain button",
                    )
                    assertTrue(element().allOf("button").none { it.textContent.orEmpty().startsWith("Teil ") })
                    element().typeInto("Ort", "  Gemeindehaus  ")
                    element().typeInto("Einladungstext", "  Wir freuen uns auf Sie.  ")
                    element().buttonNamed("Per Post versenden").click()
                    awaitUntil("the confirmation") { lastOpenModalOrNull()?.textContent.orEmpty().contains("40 physische Briefe") }
                    assertTrue(
                        lastOpenModal().textContent.orEmpty().contains("aller aktiven Mitglieder"),
                        "no names are listed: they are not loaded",
                    )
                    lastOpenModal().buttonNamed("Jetzt per Post versenden").click()
                    awaitUntil("the dispatch") { calls.toRoute(dispatch).isNotEmpty() }
                    val input = calls.singleCall(dispatch).rpcParam(0)
                    assertEquals("ALL_ACTIVE", input.audience as String)
                    assertEquals("committee-1", input.committeeId as String)
                    assertEquals("Gemeindehaus", input.location as String)
                    val ids: dynamic = input.recipientMemberIds
                    assertTrue(ids == null || (ids as Array<*>).isEmpty(), "the recipients are resolved on the server, none are sent")
                }
            }
        }

    @Test
    fun invitation_selectedMembers_needAtLeastOne_andShowChips(): Promise<Unit> =
        formTest {
            val search = searchRoute()
            val settings = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val page = searchPage(MemberPickDto("m2", "Kofi Mensah"), MemberPickDto("m3", "Lena Berg"))
            withFetchStub(respond = answering(search to page, settings to orgSettingsJson(postal = false))) { calls ->
                mountedForm("ga-invite-selected") { root, element ->
                    root.einladung(count = 7)
                    element().openInvitation()
                    awaitUntil("the form") { element().querySelector(".lapis-form") != null }
                    val audience = element().controlOf("Empfänger") as HTMLSelectElement
                    audience.value = "SELECTED"
                    audience.dispatchEvent(
                        org.w3c.dom.events
                            .Event("change"),
                    )
                    element().typeInto("Ort", "Halle")
                    element().typeInto("Einladungstext", "Text")
                    element().buttonNamed("Als PDF herunterladen").click()
                    delay(80)
                    assertTrue(element().textContent.orEmpty().contains("Bitte mindestens einen Empfänger auswählen."))
                    assertEquals(0, calls.toRoute(search).size)

                    element().chooseRemote("Mitglied hinzufügen", query = "ko", id = "m2")
                    awaitUntil("the chip") { element().textContent.orEmpty().contains("Kofi Mensah") }
                    assertEquals("committee-1", calls.toRoute(search).first().rpcParam(0) as String, "the search is scoped to the assembly")
                    assertEquals("", (element().controlOf("Mitglied hinzufügen") as HTMLInputElement).value, "the field is empty again")
                    element().chooseRemote("Mitglied hinzufügen", query = "le", id = "m3")
                    awaitUntil("the second chip") { element().textContent.orEmpty().contains("Lena Berg") }
                    element().allOf(".lapis-chip-remove").first().click()
                    delay(40)
                    assertFalse(element().textContent.orEmpty().contains("Kofi Mensah"), "a chip can be removed")
                    assertTrue(element().textContent.orEmpty().contains("Lena Berg"))
                }
            }
        }

    @Test
    fun invitation_withoutAKnownCount_saysSo_andOffersOnlyThePartOne(): Promise<Unit> =
        formTest {
            val settings = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            withFetchStub(respond = answering(settings to orgSettingsJson(postal = true))) { calls ->
                mountedForm("ga-invite-nocount") { root, element ->
                    root.einladung(count = null)
                    element().openInvitation()
                    awaitUntil("the form") { element().querySelector(".lapis-form") != null }
                    awaitUntil("the postal gate") { calls.toRoute(settings).isNotEmpty() }
                    delay(150)
                    assertTrue(element().textContent.orEmpty().contains("Die Mitgliederzahl konnte nicht ermittelt werden"))
                    assertTrue(element().allOf("button").any { it.textContent?.trim() == "Als PDF herunterladen" })
                    val postal = element().allOf("button").firstOrNull { it.textContent?.trim() == "Per Post versenden" }
                    assertFalse(postal.isShown(), "no postal dispatch to an unknown number of people")
                }
            }
        }

    @Test
    fun theForm_ofThePdfRequest_carriesTheAudienceAndNoRecipientIds(): Promise<Unit> =
        formTest {
            val all =
                MailmergeHttp.buildEinladungForm(
                    title = "t",
                    eventDateTime = LocalDateTime(2026, 9, 12, 18, 30),
                    location = "l",
                    bodyText = "b",
                    recipientMemberIds = listOf("ignored"),
                    audience = InvitationAudience.ALL_ACTIVE,
                    committeeId = "committee-1",
                    part = 2,
                )

            fun values(
                form: org.w3c.dom.HTMLFormElement,
                name: String,
            ) = (0 until form.querySelectorAll("input[name='$name']").length).map {
                (form.querySelectorAll("input[name='$name']").item(it) as HTMLInputElement).value
            }
            assertEquals(listOf("ALL_ACTIVE"), values(all, "audience"))
            assertEquals(listOf("committee-1"), values(all, "committeeId"))
            assertEquals(listOf("2"), values(all, "part"))
            assertEquals(emptyList(), values(all, "recipientMemberId"), "the server resolves the recipients")
            assertEquals("/api/mailmerge/invitations", all.getAttribute("action"))

            val selected =
                MailmergeHttp.buildEinladungForm(
                    title = "t",
                    eventDateTime = LocalDateTime(2026, 9, 12, 18, 30),
                    location = "l",
                    bodyText = "b",
                    recipientMemberIds = listOf("m1", "m2"),
                )
            assertEquals(listOf("m1", "m2"), values(selected, "recipientMemberId"))
            assertEquals(emptyList(), values(selected, "audience"), "an explicit selection sends the old request, unchanged")
            assertEquals(emptyList(), values(selected, "part"))
        }

    // ── Sitzungen: agenda, attendance, creation ─────────────────────────────────────────────────────────

    @Test
    fun agendaPresenter_ofTheAssembly_isFoundByServerSideSearch(): Promise<Unit> =
        formTest {
            val search = searchRoute()
            val add =
                routeOf {
                    rpcService<IGovernanceService>().addAgendaItem(
                        "m",
                        network.lapis.cloud.shared.domain
                            .AgendaItemInput(1, "t"),
                    )
                }
            val everyone = listMembersRoute()
            withFetchStub(respond = answering(search to searchPage(MemberPickDto("m2", "Kofi Mensah")))) { calls ->
                mountedForm("ga-agenda") { root, element ->
                    renderAddAgendaItemForm(root, "meeting-9", 3, PresenterSource.Remote("committee-1"), collapse = {}, onChanged = {})
                    element().typeInto("Titel", "Haushalt")
                    element().chooseRemote("Vortragend", query = "ko", id = "m2")
                    element().buttonNamed("Hinzufügen").click()
                    awaitUntil("addAgendaItem") { calls.toRoute(add).isNotEmpty() }
                    assertEquals("m2", calls.singleCall(add).rpcParam(1).presenterMemberId as String)
                    assertEquals("committee-1", calls.toRoute(search).first().rpcParam(0) as String)
                    assertEquals(0, calls.toRoute(everyone).size)
                }
            }
        }

    @Test
    fun attendance_ofTheAssembly_isOneForm_andEditFillsIt(): Promise<Unit> =
        formTest {
            val record =
                routeOf {
                    rpcService<IGovernanceService>().recordAttendance(
                        "m",
                        network.lapis.cloud.shared.domain
                            .AttendanceInput("a", AttendanceStatus.PRESENT),
                    )
                }
            val everyone = listMembersRoute()
            val detail =
                MeetingDetailDto(
                    meeting = meeting(),
                    agenda = emptyList(),
                    attendance = listOf(attendance(1), attendance(2, AttendanceStatus.REPRESENTED), attendance(3)),
                    resolutions = emptyList(),
                    quorum =
                        QuorumResultDto(
                            "meeting-9",
                            eligibleMemberCount = 100,
                            presentCount = 3,
                            requiredCount = 50,
                            quorumPercent = 50,
                            met = false,
                        ),
                )
            withFetchStub(respond = answering()) { calls ->
                mountedForm("ga-attendance") { root, element ->
                    renderAttendanceSection(
                        root,
                        detail,
                        canManage = true,
                        audience = MeetingAudience.Derived("committee-1", 100),
                        onChanged = {},
                    )
                    assertEquals(0, element().allOf(".lapis-form").size, "closed until the person asks for it")
                    element().awaitButton("Anwesenheit erfassen").click()
                    awaitUntil("the form") { element().allOf(".lapis-form").size == 1 }
                    assertEquals(1, element().allOf(".lapis-form").size, "ONE form, not one per person")
                    element().awaitButton("Anwesende anzeigen").click()
                    awaitUntil("the rows") { element().allOf("button").count { it.textContent?.trim() == "Bearbeiten" } == 3 }
                    val edits = element().allOf("button").filter { it.textContent?.trim() == "Bearbeiten" }
                    assertEquals(3, edits.size, "every recorded row can be edited")
                    edits[1].click()
                    awaitUntil("the form is filled") { (element().controlOf("Mitglied") as HTMLInputElement).value == "Person 2" }
                    assertEquals("Person 2", (element().controlOf("Mitglied") as HTMLInputElement).value)
                    assertEquals("REPRESENTED", (element().controlOf("Status") as HTMLSelectElement).value)
                    assertEquals("Vertreter 2", (element().controlOf("Vertreten durch") as HTMLInputElement).value)
                    assertEquals("Notiz 2", (element().controlOf("Notiz") as HTMLInputElement).value)
                    element().buttonNamed("Speichern").click()
                    awaitUntil("recordAttendance") { calls.toRoute(record).isNotEmpty() }
                    val input = calls.singleCall(record).rpcParam(1)
                    assertEquals("m2", input.memberId as String)
                    assertEquals("REPRESENTED", input.status as String)
                    assertEquals("rep-2", input.representedByMemberId as String)
                    assertEquals(0, calls.toRoute(everyone).size)
                }
            }
        }

    @Test
    fun meetingCreation_pickersFollowTheChosenCommittee_andClearOnChange_withoutLoadingMembers(): Promise<Unit> =
        formTest {
            val search = searchRoute()
            val everyone = listMembersRoute()
            withFetchStub(respond = answering(search to searchPage(MemberPickDto("m2", "Kofi Mensah")))) { calls ->
                mountedForm("ga-create") { root, element ->
                    renderMeetingCreationForm(
                        root,
                        listOf(committee(), committee(id = "committee-2", type = CommitteeType.WORKING_GROUP, name = "AG")),
                        onCreated = {},
                    )
                    val chair = element().controlOf("Sitzungsleitung") as HTMLInputElement
                    assertFalse(chair.disabled)
                    element().chooseRemote("Sitzungsleitung", query = "ko", id = "m2")
                    assertEquals("Kofi Mensah", chair.value)
                    assertEquals("committee-1", calls.toRoute(search).last().rpcParam(0) as String, "searched for the chosen committee")
                    element().chooseIn("Gremium", "committee-2")
                    assertEquals("", chair.value, "a person found for one committee is not a choice for another")
                    element().chooseRemote("Protokollführung", query = "ko", id = "m2")
                    assertEquals("committee-2", calls.toRoute(search).last().rpcParam(0) as String)
                    assertEquals(0, calls.toRoute(everyone).size, "the creation form never loads the member directory")
                }
            }
        }

    // ── Anträge: eligibility to ballot ──────────────────────────────────────────────────────────────────

    private fun memberJson(status: MemberStatus) =
        jsonOf(
            MemberDto.serializer(),
            MemberDto(
                id = "m1",
                displayName = "Anna",
                email = "anna@example.org",
                status = status,
                joinedAt = LocalDate(2026, 1, 1),
                role = AccountRole.MEMBER,
            ),
        )

    @Test
    fun ballotEligibility_ofTheAssembly_isTheCallersOwnStatus_neverTheDirectory(): Promise<Unit> =
        formTest {
            val own = routeOf { rpcService<IMemberService>().getCurrentMember() }
            val everyone = listMembersRoute()
            withFetchStub(respond = answering(own to memberJson(MemberStatus.ACTIVE))) { calls ->
                assertTrue(isEligibleToBallot(committee(), emptyList(), "m1"))
                assertEquals(0, calls.toRoute(everyone).size)
                assertEquals(1, calls.toRoute(own).size)
            }
            withFetchStub(respond = answering(own to memberJson(MemberStatus.WITHDRAWN))) { _ ->
                assertFalse(isEligibleToBallot(committee(), emptyList(), "m1"), "a member who is no longer active may not ballot")
            }
            withFetchStub(respond = { request -> if (request.isRpc) StubResponse(networkError = true) else StubResponse() }) { _ ->
                assertFalse(
                    isEligibleToBallot(committee(), emptyList(), "m1"),
                    "an own record that cannot be loaded counts as not eligible",
                )
            }
        }

    @Test
    fun ballotEligibility_ofAnotherCommittee_isItsRoster_withoutAnyRequest(): Promise<Unit> =
        formTest {
            withFetchStub(respond = answering()) { calls ->
                val roster = listOf(membership("1", "Anna", CommitteeRole.MEMBER).copy(memberId = "m1"))
                assertTrue(isEligibleToBallot(committee(type = CommitteeType.WORKING_GROUP), roster, "m1"))
                assertFalse(isEligibleToBallot(committee(type = CommitteeType.WORKING_GROUP), roster, "m2"))
                assertEquals(0, calls.rpcCount)
            }
        }
}

private fun lastOpenModalOrNull(): HTMLElement? {
    val modals = kotlinx.browser.document.querySelectorAll(".modal")
    return modals.item(modals.length - 1) as? HTMLElement
}
