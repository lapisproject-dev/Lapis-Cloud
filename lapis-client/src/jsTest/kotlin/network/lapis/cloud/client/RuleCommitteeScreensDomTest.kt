package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeAutoEndNoticeDto
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeModeChangeBlock
import network.lapis.cloud.shared.domain.CommitteeModeChangeImpactDto
import network.lapis.cloud.shared.domain.CommitteeRulePreviewDto
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingDetailDto
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleCondition
import network.lapis.cloud.shared.domain.rules.RuleOp
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IGovernanceService
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.112 "regelbasierte Gremien" -- the committee screen and the meetings screen with rule committees: the card of a rule committee (badge
 * with the word, sentence for BOARD only, live count in a polite region, the three notes, no roster, no add-member form), the "Mitglieder" choice
 * of the create form (fieldset with legend, the rule builder in the committee wording, only manual committees offered, hidden for the assembly and
 * the board, the request that is sent), and the confirmation of a mode switch (number of ending seats, the refusal reason with a disabled button).
 */
class RuleCommitteeScreensDomTest {
    private val manualId = "11111111-1111-1111-1111-111111111111"
    private val ruleId = "22222222-2222-2222-2222-222222222222"
    private val assemblyId = "33333333-3333-3333-3333-333333333333"

    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun committee(
        id: String,
        name: String,
        type: CommitteeType = CommitteeType.WORKING_GROUP,
        mode: CommitteeMembershipMode = CommitteeMembershipMode.MANUAL,
        ruleJson: String? = null,
    ) = CommitteeDto(
        id = id,
        name = name,
        type = type,
        description = "",
        active = true,
        quorumPercent = 50,
        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        membershipMode = mode,
        ruleJson = ruleJson,
    )

    private val ruleOfManual: String =
        MemberRuleCodec.encode(
            MemberRule(op = RuleOp.ALL, conditions = listOf(RuleCondition.Committee(setOf("11111111-1111-1111-1111-111111111111")))),
        )

    private fun committeesJson(forBoard: Boolean) =
        jsonOf(
            ListSerializer(CommitteeDto.serializer()),
            listOf(
                committee(manualId, "Handgremium"),
                committee(ruleId, "Regelrunde", mode = CommitteeMembershipMode.RULE, ruleJson = if (forBoard) ruleOfManual else null),
                committee(assemblyId, "Mitgliederversammlung", type = CommitteeType.GENERAL_ASSEMBLY),
            ),
        )

    private val emptyNotice =
        jsonOf(
            CommitteeAutoEndNoticeDto.serializer(),
            CommitteeAutoEndNoticeDto(
                recentAutoEnds = emptyList(),
                vacantMandatoryRoles = emptyList(),
                openSeatsWithoutMembership = emptyList(),
                electionSeatChanges = emptyList(),
            ),
        )

    private fun Routes.answer(
        request: RecordedRequest,
        forBoard: Boolean,
        count: String = "7",
        extra: (RecordedRequest) -> StubResponse? = { null },
    ): StubResponse =
        when {
            !request.isRpc -> StubResponse()
            request.rpcRoute == list -> request.answerWith(committeesJson(forBoard))
            request.rpcRoute == notice -> request.answerWith(emptyNotice)
            request.rpcRoute == countEligible -> request.answerWith(count)
            request.rpcRoute == countAssembly -> request.answerWith("100")
            request.rpcRoute == tiers -> request.answerWith(jsonOf(ListSerializer(MembershipTierDto.serializer()), emptyList()))
            else -> extra(request) ?: request.answerWith("null")
        }

    private class Routes(
        val list: String,
        val notice: String,
        val countEligible: String,
        val countAssembly: String,
        val tiers: String,
        val members: String,
        val preview: String,
        val impact: String,
        val create: String,
        val setRule: String,
    )

    private suspend fun routes() =
        Routes(
            list = routeOf { rpcService<IGovernanceService>().listCommittees(true) },
            notice = routeOf { rpcService<IGovernanceService>().getCommitteeAutoEndNotice() },
            countEligible = routeOf { rpcService<IGovernanceService>().countCommitteeEligible("c") },
            countAssembly = routeOf { rpcService<IGovernanceService>().countGeneralAssemblyEligible("c") },
            tiers = routeOf { rpcService<IContributionService>().listMembershipTiers() },
            members = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c", true) },
            preview = routeOf { rpcService<IGovernanceService>().previewCommitteeRule("c", "{}") },
            impact = routeOf { rpcService<IGovernanceService>().getCommitteeModeChangeImpact("c", CommitteeMembershipMode.RULE) },
            create =
                routeOf {
                    rpcService<IGovernanceService>().createCommittee(
                        network.lapis.cloud.shared.domain
                            .CommitteeInput("n", CommitteeType.OTHER, "d"),
                    )
                },
            setRule = routeOf { rpcService<IGovernanceService>().setCommitteeMembershipRule("c", CommitteeMembershipMode.RULE, null) },
        )

    private fun HTMLElement.radioNamed(text: String): HTMLInputElement {
        val label = assertNotNull(allOf("label").firstOrNull { it.textContent.orEmpty().trim() == text }, "no radio label '$text'")
        val byFor = label.getAttribute("for")?.let { id -> kotlinx.browser.document.getElementById(id) as? HTMLInputElement }
        return assertNotNull(byFor ?: label.querySelector("input") as? HTMLInputElement, "no radio input for '$text'")
    }

    // ── the card ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCardOfARuleCommittee_forTheBoard_hasBadgeSentenceCountNotes_andNoRosterForm(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val r = routes()
            withFetchStub(respond = { request -> with(r) { answer(request, forBoard = true) } }) { calls ->
                mountedForm("rule-card-board") { root, element ->
                    renderCommitteesScreen(root)
                    awaitUntil("the cards") { element().shows("Regelrunde") && element().shows("7 Mitglieder gehören dazu") }
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Regel"), "the badge word")
                    // the icon is decoration, the word carries the meaning
                    val badge = element().allOf(".badge").first { it.textContent.orEmpty().trim() == "Regel" }
                    assertEquals("true", badge.querySelector("[aria-hidden]")?.getAttribute("aria-hidden"), "the icon is decoration")
                    // the sentence names the referenced committee (BOARD only)
                    assertTrue(text.contains("Aktive Mitglieder, auf die zutrifft: Mitglied im Gremium Handgremium."), text)
                    assertTrue(text.contains("Bei einer Abstimmung wird der Kreis zum Start festgeschrieben."))
                    assertTrue(text.contains("Regelgremien haben keine Ämter."))
                    assertTrue(text.contains("Sitzungen und Abstimmungen dieses Gremiums leiten Vorstand oder Administration."))
                    // the count sits in a polite status region
                    val region = element().allOf("[role=status]").first { it.textContent.orEmpty().contains("7 Mitglieder gehören dazu") }
                    assertEquals("polite", region.getAttribute("aria-live"))
                    // exactly one count call per rule committee, and the manual committee's card has no rule facts
                    assertEquals(1, calls.toRoute(r.countEligible).size)
                    assertEquals(1, element().allOf(".badge").count { it.textContent.orEmpty().trim() == "Regel" })
                }
            }
        }

    @Test
    fun theCardOfARuleCommittee_forAPlainMember_showsTheCountButNeverTheRule(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val r = routes()
            withFetchStub(respond = { request -> with(r) { answer(request, forBoard = false) } }) {
                mountedForm("rule-card-member") { root, element ->
                    renderCommitteesScreen(root)
                    awaitUntil("the count") { element().shows("7 Mitglieder gehören dazu") }
                    assertFalse(element().shows("auf die zutrifft"), "no rule sentence for a plain member")
                    assertTrue(element().allOf("button").none { it.textContent.orEmpty().trim() == "Bearbeiten" }, "no edit button")
                }
            }
        }

    @Test
    fun theRosterViewOfARuleCommittee_listsNobody_andOffersNoMemberForm(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val r = routes()
            withFetchStub(respond = { request -> with(r) { answer(request, forBoard = true) } }) { calls ->
                mountedForm("rule-roster") { root, element ->
                    renderCommitteeRoster(
                        root,
                        committee(ruleId, "Regelrunde", mode = CommitteeMembershipMode.RULE, ruleJson = ruleOfManual),
                        canManage = true,
                    )
                    awaitUntil("the count") { element().shows("7 Mitglieder gehören dazu") }
                    assertTrue(element().shows("Besetzung: Regelrunde"))
                    assertTrue(element().shows("Bei einer Abstimmung wird der Kreis zum Start festgeschrieben."))
                    assertTrue(element().allOf("button").none { it.textContent.orEmpty().trim() == "Mitglied hinzufügen" })
                    assertEquals(0, calls.toRoute(r.members).size, "no roster is requested for a rule committee")
                }
            }
        }

    // ── the create form ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCreateForm_offersTheChoice_buildsTheCommitteeBuilder_offersOnlyManualCommittees_andSendsTheRule(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val r = routes()
            withFetchStub(
                respond = { request ->
                    with(r) {
                        answer(request, forBoard = true) { req ->
                            when (req.rpcRoute) {
                                preview ->
                                    req.answerWith(
                                        jsonOf(CommitteeRulePreviewDto.serializer(), CommitteeRulePreviewDto(eligible = 3)),
                                    )
                                create -> req.answerWith(jsonOf(CommitteeDto.serializer(), committee("new", "Neu")))
                                else -> null
                            }
                        }
                    }
                },
            ) { calls ->
                mountedForm("rule-create") { root, element ->
                    renderCommitteesScreen(root)
                    awaitUntil("the create button") { element().createButtonLabels() == listOf("Neues Gremium") }
                    val host = openCreateForm(element(), "lapis-create-committee")
                    val legend = assertNotNull(host.querySelector("fieldset > legend"), "a fieldset with a legend")
                    assertEquals("Mitglieder", legend.textContent.orEmpty().trim())
                    assertFalse(host.shows("Vorlagen"), "manual is the default: no builder")

                    host.radioNamed("Nach Regel").click()
                    awaitUntil("the builder") { host.shows("Vorlagen") }
                    assertTrue(host.shows("Immer: nur aktive Mitglieder, nicht anonymisiert, nicht verstorben."))
                    assertTrue(host.shows("Bei einer Abstimmung wird der Kreis zum Start festgeschrieben."))
                    val chips = host.allOf("button").map { it.textContent.orEmpty().trim() }
                    assertTrue("Vorstand" in chips && "Neue Mitglieder (30 Tage)" in chips && "Ein Gremium" in chips)
                    assertFalse("Alle aktiven Mitglieder" in chips, "an all-members rule is no sensible committee preset")

                    // the committee preset picks the FIRST OFFERED committee: only the manual one is offered
                    host.buttonNamed("Ein Gremium").click()
                    awaitUntil("the committee row") { host.shows("Handgremium") }
                    assertFalse(host.shows("Regelrunde"), "a rule committee is never offered in a rule")
                    assertEquals(
                        1,
                        host.allOf("label").count { it.textContent.orEmpty().trim() == "Handgremium" },
                        "one checkbox for the manual committee",
                    )
                    awaitUntil("the live count") { host.shows("3 Mitglieder gehören dazu") }

                    host.typeInto("Name", "Neues Regelgremium")
                    host.buttonNamed("Gremium anlegen").click()
                    awaitUntil("the create request") { calls.toRoute(r.create).isNotEmpty() }
                    val body = calls.toRoute(r.create).single().body
                    assertTrue(body.contains("RULE"), body)
                    assertTrue(body.contains(manualId), body)
                }
            }
        }

    @Test
    fun theChoice_isHiddenForTheAssemblyAndTheBoard_andComesBackForAnOrdinaryType(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val r = routes()
            withFetchStub(respond = { request -> with(r) { answer(request, forBoard = true) } }) {
                mountedForm("rule-create-type") { root, element ->
                    renderCommitteesScreen(root)
                    awaitUntil("the create button") { element().createButtonLabels() == listOf("Neues Gremium") }
                    val host = openCreateForm(element(), "lapis-create-committee")

                    // an invisible widget is taken out of the DOM by KVision and rebuilt when it is shown again: look it up every time
                    fun choiceShown(): Boolean = host.querySelector("fieldset") != null && host.shows("Nach Regel")

                    assertTrue(choiceShown(), "visible for a working group")
                    host.radioNamed("Nach Regel").click()
                    awaitUntil("the builder") { host.shows("Vorlagen") }
                    host.chooseIn("Typ", CommitteeType.GENERAL_ASSEMBLY.name)
                    awaitUntil("hidden for the assembly") { !choiceShown() }
                    assertFalse(host.shows("Vorlagen"), "the builder is gone with the choice")
                    host.chooseIn("Typ", CommitteeType.EXECUTIVE_BOARD.name)
                    delay(200)
                    assertFalse(choiceShown(), "hidden for the board")
                    host.chooseIn("Typ", CommitteeType.COMMISSION.name)
                    awaitUntil("back for a commission") { choiceShown() }
                    assertFalse(host.shows("Vorlagen"), "and it starts as manual again, without a builder")
                }
            }
        }

    // ── the confirmation of a switch ───────────────────────────────────────────────────────────────────

    @Test
    fun theSwitchDialog_namesTheEndingSeats_andIsOnlyWrittenAfterTheConfirmation(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val r = routes()
            withFetchStub(
                respond = { request ->
                    with(r) {
                        answer(request, forBoard = true) { req ->
                            when (req.rpcRoute) {
                                impact ->
                                    req.answerWith(
                                        jsonOf(CommitteeModeChangeImpactDto.serializer(), CommitteeModeChangeImpactDto(openSeats = 4)),
                                    )
                                setRule ->
                                    req.answerWith(
                                        jsonOf(
                                            CommitteeDto.serializer(),
                                            committee(
                                                manualId,
                                                "Handgremium",
                                                mode = CommitteeMembershipMode.RULE,
                                                ruleJson = ruleOfManual,
                                            ),
                                        ),
                                    )
                                preview ->
                                    req.answerWith(
                                        jsonOf(CommitteeRulePreviewDto.serializer(), CommitteeRulePreviewDto(eligible = 1)),
                                    )
                                else -> null
                            }
                        }
                    }
                },
            ) { calls ->
                mountedForm("rule-switch") { root, element ->
                    renderCommitteeMembershipSection(root, committee(manualId, "Handgremium")) {}
                    awaitUntil("the section") { element().shows("Mitglieder bestimmen") }
                    element().radioNamed("Nach Regel").click()
                    awaitUntil("the builder") { element().shows("Vorlagen") }
                    element().buttonNamed("Neue Mitglieder (30 Tage)").click()
                    awaitUntil("the day field") { (element().controlOf("Tage") as HTMLInputElement).value == "30" }
                    element().buttonNamed("Besetzung speichern").click()
                    awaitUntil("the dialog") { lastOpenModalText().contains("4 offene Sitze enden heute.") }
                    assertTrue(lastOpenModalText().contains("Eine Regel bestimmt dann die Mitglieder. Sitze gibt es nicht mehr."))
                    assertEquals(0, calls.toRoute(r.setRule).size, "nothing is written before the confirmation")
                    val confirm = lastOpenModal().buttonNamed("Umstellen") as HTMLButtonElement
                    assertFalse(confirm.disabled)
                    confirm.click()
                    awaitUntil("the write") { calls.toRoute(r.setRule).isNotEmpty() }
                    assertEquals(1, calls.toRoute(r.setRule).size)
                }
            }
        }

    @Test
    fun aRefusedSwitch_showsTheReasonAsText_andTheConfirmButtonIsDisabled(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val r = routes()
            withFetchStub(
                respond = { request ->
                    with(r) {
                        answer(request, forBoard = true) { req ->
                            when (req.rpcRoute) {
                                impact ->
                                    req.answerWith(
                                        jsonOf(
                                            CommitteeModeChangeImpactDto.serializer(),
                                            CommitteeModeChangeImpactDto(
                                                openSeats = 2,
                                                blocked = CommitteeModeChangeBlock.MEETING_IN_PROGRESS,
                                            ),
                                        ),
                                    )
                                preview ->
                                    req.answerWith(
                                        jsonOf(CommitteeRulePreviewDto.serializer(), CommitteeRulePreviewDto(eligible = 1)),
                                    )
                                else -> null
                            }
                        }
                    }
                },
            ) { calls ->
                mountedForm("rule-refused") { root, element ->
                    renderCommitteeMembershipSection(root, committee(manualId, "Handgremium")) {}
                    awaitUntil("the section") { element().shows("Mitglieder bestimmen") }
                    element().radioNamed("Nach Regel").click()
                    awaitUntil("the builder") { element().shows("Vorlagen") }
                    element().buttonNamed("Neue Mitglieder (30 Tage)").click()
                    awaitUntil("the day field") { (element().controlOf("Tage") as HTMLInputElement).value == "30" }
                    element().buttonNamed("Besetzung speichern").click()
                    awaitUntil("the dialog") { lastOpenModalText().contains("Eine Sitzung dieses Gremiums hat begonnen") }
                    val confirm = lastOpenModal().buttonNamed("Umstellen") as HTMLButtonElement
                    assertTrue(confirm.disabled, "a refused switch cannot be confirmed")
                    assertFalse(lastOpenModalText().contains("offene Sitze enden"), "no promise of ending seats while it is refused")
                    delay(300)
                    assertEquals(0, calls.toRoute(r.setRule).size)
                }
            }
        }

    @Test
    fun theSectionIsAbsent_forTheAssemblyAndTheBoard(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) {
                mountedForm("rule-section-absent") { root, element ->
                    renderCommitteeMembershipSection(
                        root,
                        committee(assemblyId, "Mitgliederversammlung", type = CommitteeType.GENERAL_ASSEMBLY),
                    ) {}
                    renderCommitteeMembershipSection(root, committee(manualId, "Vorstand", type = CommitteeType.EXECUTIVE_BOARD)) {}
                    assertFalse(element().shows("Mitglieder bestimmen"))
                }
            }
        }

    // ── the meetings of a rule committee ──────────────────────────────────────────────────────────────

    private fun meeting() =
        MeetingDto(
            id = "meeting-9",
            committeeId = ruleId,
            committeeName = "Regelrunde",
            title = "Regelsitzung",
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

    @Test
    fun theInvitationToARuleCommittee_isAddressedToItsMembers_withTheirNumber(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("null") else StubResponse() }) {
                mountedForm("rule-invite") { root, element ->
                    renderEinladungSection(
                        root,
                        meeting(),
                        canManage = true,
                        isBoardOrAdminGlobal = true,
                        audience = MeetingAudience.Derived(committeeId = ruleId, eligibleCount = 12, kind = DerivedAudienceKind.RULE),
                    )
                    awaitUntil("the form") { element().querySelector(".lapis-form") != null }
                    assertTrue(element().shows("An alle Mitglieder des Gremiums (12)"), element().textContent.orEmpty())
                    assertFalse(element().shows("An alle aktiven Mitglieder"), "the assembly's wording is not used")
                    assertEquals("ALL_ACTIVE", (element().controlOf("Empfänger") as HTMLSelectElement).value)
                }
            }
        }

    @Test
    fun theInvitationWithoutACount_keepsTheCommitteeWording(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("null") else StubResponse() }) {
                mountedForm("rule-invite-nocount") { root, element ->
                    renderEinladungSection(
                        root,
                        meeting(),
                        canManage = true,
                        isBoardOrAdminGlobal = true,
                        audience = MeetingAudience.Derived(committeeId = ruleId, eligibleCount = null, kind = DerivedAudienceKind.RULE),
                    )
                    awaitUntil("the form") { element().querySelector(".lapis-form") != null }
                    assertTrue(element().shows("An alle Mitglieder des Gremiums"))
                    assertFalse(element().shows("(null)"))
                }
            }
        }

    private fun detailWith(frozen: Boolean) =
        MeetingDetailDto(
            meeting = meeting(),
            agenda = emptyList(),
            attendance = emptyList(),
            resolutions = emptyList(),
            quorum =
                QuorumResultDto(
                    meetingId = "meeting-9",
                    eligibleMemberCount = 3,
                    presentCount = 2,
                    requiredCount = 2,
                    quorumPercent = 50,
                    met = true,
                    frozen = frozen,
                ),
        )

    @Test
    fun theQuorumSaysWhenItsCircleIsFrozen_andOnlyThen(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("null") else StubResponse() }) {
                mountedForm("rule-quorum-frozen") { root, element ->
                    renderAttendanceSection(
                        root,
                        detailWith(frozen = true),
                        canManage = false,
                        audience = MeetingAudience.Roster(emptyList()),
                        onChanged = {},
                    )
                    assertTrue(element().shows("Festgeschrieben zum Start der ersten Abstimmung"))
                }
                mountedForm("rule-quorum-live") { root, element ->
                    renderAttendanceSection(
                        root,
                        detailWith(frozen = false),
                        canManage = false,
                        audience = MeetingAudience.Roster(emptyList()),
                        onChanged = {},
                    )
                    assertTrue(element().shows("Quorum erreicht"))
                    assertFalse(element().shows("Festgeschrieben"))
                }
            }
        }

    // ── pure labels ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyRefusalReason_andEverySeatEndReason_hasAText() {
        CommitteeModeChangeBlock.entries.forEach { assertTrue(committeeModeBlockText(it).isNotBlank(), "$it") }
        network.lapis.cloud.shared.domain.CommitteeSeatAutoEndReason.entries.forEach {
            assertTrue(committeeSeatEndReasonLabel(it).isNotBlank(), "$it")
        }
        assertEquals("Ausgetreten", committeeSeatEndReasonLabel(network.lapis.cloud.shared.domain.CommitteeSeatAutoEndReason.WITHDRAWN))
        assertEquals(
            "Umstellung auf Regel",
            committeeSeatEndReasonLabel(network.lapis.cloud.shared.domain.CommitteeSeatAutoEndReason.RULE_MODE),
        )
    }

    @Test
    fun theCommitteesRulesMayReference_areTheActiveManualOnes_exceptItselfAndTheAssembly() {
        val all =
            listOf(
                committee(manualId, "Handgremium"),
                committee(ruleId, "Regelrunde", mode = CommitteeMembershipMode.RULE, ruleJson = ruleOfManual),
                committee(assemblyId, "Mitgliederversammlung", type = CommitteeType.GENERAL_ASSEMBLY),
                committee("44444444-4444-4444-4444-444444444444", "Zweites"),
            )
        assertEquals(listOf("Handgremium", "Zweites"), committeesForRules(all).map { it.name })
        assertEquals(listOf("Zweites"), committeesForRules(all, selfId = manualId).map { it.name })
        // an id an already stored rule points at stays visible, so it can still be taken out
        assertEquals(listOf("Regelrunde", "Zweites"), committeesForRules(all, selfId = manualId, keep = setOf(ruleId)).map { it.name })
    }

    private fun lastOpenModalText(): String = runCatching { lastOpenModal().textContent.orEmpty() }.getOrDefault("")
}
