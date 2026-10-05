package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberAccessPreflightDto
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PeerAction
import network.lapis.cloud.shared.domain.PeerActionDecisionDto
import network.lapis.cloud.shared.domain.PeerActionDecisionsDto
import network.lapis.cloud.shared.domain.PeerDecisionKind
import network.lapis.cloud.shared.domain.PeerDenyReason
import network.lapis.cloud.shared.domain.PrivilegedActionKind
import network.lapis.cloud.shared.domain.PrivilegedActionOverviewDto
import network.lapis.cloud.shared.domain.PrivilegedActionRequestDto
import network.lapis.cloud.shared.domain.PrivilegedActionStatus
import network.lapis.cloud.shared.domain.PrivilegedPasswordResultDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IMemberService
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun targetRow(role: AccountRole?) =
    MemberAdminRowDto(
        id = "member-7",
        displayName = "Tara Zielkonto",
        email = "tara@example.org",
        status = MemberStatus.ACTIVE,
        role = role,
        joinedAt = LocalDate(2026, 1, 1),
    )

private fun decisions(
    tempPassword: PeerActionDecisionDto,
    demote: PeerActionDecisionDto = PeerActionDecisionDto(PeerAction.DEMOTE, PeerDecisionKind.REQUIRES_APPROVAL, eligibleApprovers = 1),
    suspend: PeerActionDecisionDto = PeerActionDecisionDto(PeerAction.SUSPEND, PeerDecisionKind.REQUIRES_APPROVAL, eligibleApprovers = 1),
    resetMail: PeerActionDecisionDto = PeerActionDecisionDto(PeerAction.RESET_MAIL, PeerDecisionKind.ALLOW, notifiesTarget = true),
) = PeerActionDecisionsDto(memberId = "member-7", decisions = listOf(tempPassword, demote, suspend, resetMail))

private val approvalPath = PeerActionDecisionDto(PeerAction.TEMP_PASSWORD, PeerDecisionKind.REQUIRES_APPROVAL, eligibleApprovers = 2)
private val noSecondAdmin =
    PeerActionDecisionDto(PeerAction.TEMP_PASSWORD, PeerDecisionKind.DENY, denyReason = PeerDenyReason.NO_SECOND_ADMIN)

private class FakePeerRpc(
    var answer: PeerActionDecisionsDto,
) : PrivilegedActionRpc {
    val temporaryPasswordRequests = mutableListOf<Pair<String, String>>()
    val demotionRequests = mutableListOf<Triple<String, AccountRole, String>>()
    val suspensionRequests = mutableListOf<Triple<String, MemberStatus, String>>()
    var decisionCalls = 0

    private fun dto(
        action: PrivilegedActionKind,
        id: String,
    ) = PrivilegedActionRequestDto(
        id = id,
        action = action,
        actorMemberId = "caller-1",
        actorDisplayName = "Caller",
        targetMemberId = "member-7",
        targetDisplayName = "Tara Zielkonto",
        reason = "x",
        status = PrivilegedActionStatus.PENDING,
        createdAt = LocalDateTime(2026, 10, 5, 10, 0),
        expiresAt = LocalDateTime(2026, 10, 8, 10, 0),
    )

    override suspend fun decisions(memberId: String): PeerActionDecisionsDto {
        decisionCalls++
        return answer
    }

    override suspend fun requestTemporaryPassword(
        memberId: String,
        reason: String,
    ): PrivilegedActionRequestDto {
        temporaryPasswordRequests += memberId to reason
        return dto(PrivilegedActionKind.TEMP_PASSWORD, "p1")
    }

    override suspend fun requestDemotion(
        memberId: String,
        newRole: AccountRole,
        reason: String,
    ): PrivilegedActionRequestDto {
        demotionRequests += Triple(memberId, newRole, reason)
        return dto(PrivilegedActionKind.DEMOTE, "d1")
    }

    override suspend fun requestSuspension(
        memberId: String,
        newStatus: MemberStatus,
        reason: String,
    ): PrivilegedActionRequestDto {
        suspensionRequests += Triple(memberId, newStatus, reason)
        return dto(PrivilegedActionKind.SUSPEND, "s1")
    }

    override suspend fun approve(requestId: String): PrivilegedActionRequestDto = error("unused")

    override suspend fun reject(requestId: String): PrivilegedActionRequestDto = error("unused")

    override suspend fun withdraw(requestId: String): PrivilegedActionRequestDto = error("unused")

    override suspend fun executeTemporaryPassword(requestId: String): PrivilegedPasswordResultDto = error("unused")

    override suspend fun overview(): PrivilegedActionOverviewDto = PrivilegedActionOverviewDto()
}

/**
 * Welle V1.9.57 -- the request paths of the dialogs against ANOTHER administrator: the password dialog shows "Freigabe beantragen" instead
 * of the direct zone (or the reason as visible text), the editor's role and status sections turn into requests, the reason stays text and
 * never only a tooltip, and a non-administrator target keeps the unchanged direct dialog.
 */
class PeerProtectionDialogsDomTest {
    private fun adminSession() =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Caller",
            role = AccountRole.ADMIN,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.hasText(fragment: String) = textContent.orEmpty().contains(fragment)

    private fun HTMLElement.isShown(): Boolean = offsetParent != null

    private suspend fun <T> withPreflightStub(block: suspend (List<RecordedRequest>) -> T): T {
        val preflightRoute = routeOf { rpcService<IMemberService>().getMemberAccessPreflight("x") }
        return withFetchStub(
            respond = { request ->
                if (!request.isRpc) {
                    StubResponse()
                } else if (request.rpcRoute == preflightRoute) {
                    request.answerWith(
                        jsonOf(
                            MemberAccessPreflightDto.serializer(),
                            MemberAccessPreflightDto(MailDeliveryState.HANDED_TO_SMTP, activeSessionCount = 1),
                        ),
                    )
                } else {
                    request.answerWith("null")
                }
            },
            block = block,
        )
    }

    // ───────────────────────────── the password dialog ─────────────────────────────

    @Test
    fun passwordDialog_againstAnAdmin_offersTheRequestInsteadOfTheDirectZone(): Promise<Unit> {
        val rpc = FakePeerRpc(decisions(approvalPath))
        return formTest {
            AppState.setSession(adminSession())
            withPreflightStub { _ ->
                mountedForm("peer-pw-request") { _, _ ->
                    openMemberPasswordResetDialog(row = targetRow(AccountRole.ADMIN), onChanged = {}, onRequested = {}, rpc = rpc)
                    awaitUntil("request zone shown") { lastOpenModal().hasText("Freigabe beantragen") }
                    val modal = lastOpenModal()
                    assertFalse(
                        modal.hasText("Passwort setzen und alle Sitzungen beenden") &&
                            modal.allOf("button").any {
                                it.textContent?.trim() == "Passwort setzen und alle Sitzungen beenden" && it.isShown()
                            },
                        "the direct zone is gone",
                    )
                    assertTrue(modal.hasText("Das Administratorkonto wird benachrichtigt."))
                    modal.typeInto("Begründung", "  Passwort vergessen, Rückruf erfolgt  ")
                    delay(60)
                    modal.buttonNamed("Freigabe beantragen").click()
                    awaitUntil("requested") { rpc.temporaryPasswordRequests.isNotEmpty() }
                    assertEquals("member-7" to "Passwort vergessen, Rückruf erfolgt", rpc.temporaryPasswordRequests.single())
                }
            }
        }
    }

    @Test
    fun passwordDialog_withoutASecondAdmin_showsTheReasonAsText_andADisabledButton(): Promise<Unit> {
        val rpc = FakePeerRpc(decisions(noSecondAdmin))
        return formTest {
            AppState.setSession(adminSession())
            withPreflightStub { _ ->
                mountedForm("peer-pw-nsa") { _, _ ->
                    openMemberPasswordResetDialog(row = targetRow(AccountRole.ADMIN), onChanged = {}, onRequested = {}, rpc = rpc)
                    awaitUntil("reason shown") { lastOpenModal().hasText("Es gibt keinen weiteren Administrator für die Freigabe") }
                    val modal = lastOpenModal()
                    assertTrue(modal.hasText("Nutzen Sie die Passwort-Reset-Mail"))
                    assertTrue((modal.buttonNamed("Freigabe beantragen") as HTMLButtonElement).disabled)
                    // the notice is a real element with text, not a tooltip
                    assertTrue(modal.allOf("[data-peer-protection-notice]").any { it.hasText("keinen weiteren Administrator") })
                    // the reset mail stays available, with the hint that the account is told
                    awaitUntil("reset mail enabled") { !(modal.buttonNamed("Reset-E-Mail senden") as HTMLButtonElement).disabled }
                    assertTrue(modal.hasText("Das Administratorkonto wird benachrichtigt."))
                    assertEquals(0, rpc.temporaryPasswordRequests.size)
                }
            }
        }
    }

    @Test
    fun passwordDialog_againstANonAdmin_staysTheUnchangedDirectDialog_withoutAnyDecisionCall(): Promise<Unit> {
        val rpc = FakePeerRpc(decisions(approvalPath))
        return formTest {
            AppState.setSession(adminSession())
            withPreflightStub { _ ->
                mountedForm("peer-pw-direct") { _, _ ->
                    openMemberPasswordResetDialog(row = targetRow(AccountRole.MEMBER), onChanged = {}, onRequested = {}, rpc = rpc)
                    awaitUntil("direct zone ready") {
                        lastOpenModal().allOf("button").any { it.textContent?.trim() == "Passwort setzen und alle Sitzungen beenden" }
                    }
                    delay(150)
                    assertEquals(0, rpc.decisionCalls)
                    assertFalse(lastOpenModal().hasText("Freigabe beantragen"))
                    assertFalse(lastOpenModal().hasText("Das Administratorkonto wird benachrichtigt."))
                }
            }
        }
    }

    // ───────────────────────────── the editor's role and status sections ─────────────────────────────

    private fun roleButton(modal: HTMLElement) = modal.buttonNamed("Freigabe beantragen")

    @Test
    fun editor_roleSectionAgainstAnAdmin_isARequestWithAReason(): Promise<Unit> {
        val rpc = FakePeerRpc(decisions(approvalPath))
        return formTest {
            AppState.setSession(adminSession())
            withFetchStub { _ ->
                mountedForm("peer-editor-role") { _, _ ->
                    openMemberEditorDialog(row = targetRow(AccountRole.ADMIN), onChanged = {}, onRequested = {}, rpc = rpc)
                    awaitUntil("decisions arrived") { rpc.decisionCalls == 1 }
                    val modal = lastOpenModal()
                    awaitUntil("approval hint") { modal.hasText("Zustimmung eines zweiten Administrators erforderlich") }
                    modal.chooseIn("Rolle", AccountRole.BOARD.name)
                    // the status section has its own reason field (first), the role section's is the second
                    modal.typeInto("Begründung", "Rolle wird nicht mehr benötigt", nth = 1)
                    delay(60)
                    val buttons = modal.allOf("button").filter { it.textContent?.trim() == "Freigabe beantragen" }
                    assertTrue(buttons.isNotEmpty())
                    // there is no "Rolle ändern" for an administrator target: only the request
                    assertFalse(modal.allOf("button").any { it.textContent?.trim() == "Rolle ändern" })
                    buttons.last().click()
                    awaitUntil("requested") { rpc.demotionRequests.isNotEmpty() }
                    assertEquals(Triple("member-7", AccountRole.BOARD, "Rolle wird nicht mehr benötigt"), rpc.demotionRequests.single())
                }
            }
        }
    }

    @Test
    fun editor_withoutASecondAdmin_theRoleRequestIsDisabled_andTheReasonIsText(): Promise<Unit> {
        val denyDemote = PeerActionDecisionDto(PeerAction.DEMOTE, PeerDecisionKind.DENY, denyReason = PeerDenyReason.NO_SECOND_ADMIN)
        val denySuspend = PeerActionDecisionDto(PeerAction.SUSPEND, PeerDecisionKind.DENY, denyReason = PeerDenyReason.NO_SECOND_ADMIN)
        val rpc = FakePeerRpc(decisions(noSecondAdmin, demote = denyDemote, suspend = denySuspend))
        return formTest {
            AppState.setSession(adminSession())
            withFetchStub { _ ->
                mountedForm("peer-editor-nsa") { _, _ ->
                    openMemberEditorDialog(row = targetRow(AccountRole.ADMIN), onChanged = {}, onRequested = {}, rpc = rpc)
                    awaitUntil("reason in the dialog") { lastOpenModal().hasText("Es gibt keinen weiteren Administrator für die Freigabe") }
                    val modal = lastOpenModal()
                    assertTrue(modal.allOf("[data-peer-protection-notice]").size >= 1)
                    assertTrue(
                        modal
                            .allOf(
                                "button",
                            ).filter { it.textContent?.trim() == "Freigabe beantragen" }
                            .all { (it as HTMLButtonElement).disabled },
                    )
                    assertEquals(0, rpc.demotionRequests.size)
                }
            }
        }
    }

    @Test
    fun editor_aBlockingStatusAgainstAnAdmin_isARequest_aNonBlockingOneStaysDirect(): Promise<Unit> {
        val rpc = FakePeerRpc(decisions(approvalPath))
        val blockedAdmin = targetRow(AccountRole.ADMIN).copy(status = MemberStatus.DONOR)
        return formTest {
            AppState.setSession(adminSession())
            withFetchStub { _ ->
                mountedForm("peer-editor-status") { _, _ ->
                    // an ACTIVE administrator: every offered target blocks the login -> a request
                    openMemberEditorDialog(row = targetRow(AccountRole.ADMIN), onChanged = {}, onRequested = {}, rpc = rpc)
                    awaitUntil("decisions") { rpc.decisionCalls == 1 }
                    val modal = lastOpenModal()
                    awaitUntil("status section is a request") {
                        modal.allOf("button").count {
                            it.textContent?.trim() ==
                                "Freigabe beantragen"
                        } >=
                            2
                    }
                    assertFalse(
                        modal.allOf("button").any { it.textContent?.trim() == "Status ändern" },
                        "no direct status change against an administrator",
                    )
                    modal.typeInto("Begründung", "Mitgliedschaft beendet, Austritt liegt vor", nth = 0)
                    delay(60)
                    val statusRequest = modal.allOf("button").filter { it.textContent?.trim() == "Freigabe beantragen" }.first()
                    statusRequest.click()
                    awaitUntil("suspension requested") { rpc.suspensionRequests.isNotEmpty() }
                    assertEquals("member-7", rpc.suspensionRequests.single().first)
                    assertTrue(rpc.suspensionRequests.single().second in MemberStatusSetsForTests.loginBlocked)
                }
            }
        }
    }

    @Test
    fun editor_aNonAdminTarget_hasNoPeerElementsAndNoDecisionCall(): Promise<Unit> {
        val rpc = FakePeerRpc(decisions(approvalPath))
        return formTest {
            AppState.setSession(adminSession())
            withFetchStub { _ ->
                mountedForm("peer-editor-plain") { _, _ ->
                    openMemberEditorDialog(row = targetRow(AccountRole.MEMBER), onChanged = {}, onRequested = {}, rpc = rpc)
                    awaitUntil("the editor") { lastOpenModal().hasText("Rolle ändern") }
                    delay(150)
                    assertEquals(0, rpc.decisionCalls)
                    assertEquals(0, lastOpenModal().allOf("[data-peer-protection-notice]").size)
                    assertFalse(lastOpenModal().allOf("button").any { it.textContent?.trim() == "Freigabe beantragen" })
                    assertTrue(lastOpenModal().allOf("button").any { it.textContent?.trim() == "Status ändern" })
                }
            }
        }
    }

    // ───────────────────────────── protected data ─────────────────────────────

    private class UnusedAdminRpc : MemberAddressAdminRpc {
        var loads = 0

        override suspend fun loadForAdministration(memberId: String): network.lapis.cloud.shared.domain.MemberAddressDataDto {
            loads++
            return network.lapis.cloud.shared.domain.MemberAddressDataDto(
                memberId = memberId,
                displayName = "Tara Zielkonto",
                street = null,
                postalCode = null,
                city = null,
                country = null,
                dateOfBirth = null,
                nationality = null,
                dateOfDeath = null,
                protectedTarget = true,
            )
        }

        override suspend fun updateAddress(
            memberId: String,
            street: String?,
            postalCode: String?,
            city: String?,
            country: String?,
        ) = error("never written")

        override suspend fun updateBeneficialOwnerData(
            memberId: String,
            dateOfBirth: LocalDate?,
            nationality: String?,
        ) = error("never written")
    }

    @Test
    fun boardOpeningAnAdminsData_seesGeschuetzt_notDots_withNoServerCall_andDisabledSaves(): Promise<Unit> {
        val rpc = UnusedAdminRpc()
        return formTest {
            AppState.setSession(adminSession().copy(role = AccountRole.BOARD))
            mountedForm("peer-address-protected") { _, _ ->
                openMemberAddressAdminDialog(targetRow(AccountRole.ADMIN), rpc)
                val modal = lastOpenModal()
                awaitUntil("protected state") { modal.hasText("Geschützt") }
                assertTrue(modal.allOf(".fa-shield-halved").size >= 6, "a shield per protected field")
                assertTrue(modal.hasText("Die Daten von Administratoren sind für den Vorstand nicht einsehbar"))
                assertTrue(
                    modal
                        .allOf("button")
                        .filter {
                            it.textContent?.trim() in listOf("Anschrift speichern", "Angaben speichern")
                        }.all { (it as HTMLButtonElement).disabled },
                )
                assertEquals(0, modal.allOf("input").size, "no field, no dots")
                delay(120)
                assertEquals(0, rpc.loads, "no audited read for a read that returns nothing")
            }
        }
    }

    @Test
    fun aStaleRowThatTheServerAnswersProtected_showsTheSameProtectedState(): Promise<Unit> {
        val rpc = UnusedAdminRpc()
        return formTest {
            AppState.setSession(adminSession().copy(role = AccountRole.BOARD))
            mountedForm("peer-address-stale") { _, _ ->
                // the row still says MEMBER, the server knows better
                openMemberAddressAdminDialog(targetRow(AccountRole.MEMBER), rpc)
                lastOpenModal().buttonNamed("Angaben anzeigen").click()
                awaitUntil(
                    "protected state",
                ) { lastOpenModal().hasText("Die Daten von Administratoren sind für den Vorstand nicht einsehbar") }
                assertEquals(1, rpc.loads)
                assertEquals(0, lastOpenModal().allOf("input").size)
            }
        }
    }
}

/** The login-blocking statuses, spelled out for the assertion (the shared set is the production truth). */
private object MemberStatusSetsForTests {
    val loginBlocked = network.lapis.cloud.shared.domain.MemberStatusSets.LOGIN_BLOCKED
}
