package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.browser.document
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CrowdfundingDistributionDto
import network.lapis.cloud.shared.domain.CrowdfundingProjectDto
import network.lapis.cloud.shared.domain.CrowdfundingProjectInput
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ICrowdfundingService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.48 -- rule R36B for the crowdfunding screen: "Projekt einreichen" in the page header (the LTR balance strip is the first element of
 * the opened form), "Verteilung berechnen" in the title row "Treuhänder-Werkzeuge" (TREASURER, BOARD and ADMIN only).
 */
class CommunityCollapsibleFormsCrowdfundingDomTest {
    private val conflict = "network.lapis.cloud.shared.rpc.ConflictException"
    private val marker = "###KvI18nS###"
    private val projectFormId = "lapis-create-crowdfunding-project"
    private val distributionFormId = "lapis-create-crowdfunding-distribution"

    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun project(
        id: String,
        title: String,
        description: String = "Eine Idee",
    ) = CrowdfundingProjectDto(
        id = id,
        title = title,
        description = description,
        submitterMemberId = "member-1",
        submitterDisplayName = "Dana Keller",
        initialWeightLtr = 5.0.toDecimal(),
        currentWeightLtr = 5.0.toDecimal(),
        status = CrowdfundingProjectStatus.PENDING,
        effectiveStatus = CrowdfundingProjectStatus.PENDING,
        isAutoApproved = false,
        rejectionReason = null,
        reviewedById = null,
        reviewedByDisplayName = null,
        reviewedAt = null,
        submittedAt = LocalDateTime(2026, 9, 1, 10, 0),
        likeCount = 0,
        dislikeCount = 0,
        basketTotal = 0,
    )

    private fun projects(rows: List<CrowdfundingProjectDto>) = jsonOf(ListSerializer(CrowdfundingProjectDto.serializer()), rows)

    private fun HTMLElement.pageActionButtons(): List<HTMLElement> = allOf(".lapis-page-header .lapis-page-action button")

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.hostOpen(formId: String): Boolean = (querySelector("[id='$formId']")?.childElementCount ?: 0) > 0

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private suspend fun dismissDialogWith(label: String) {
        awaitUntil("a dialog is shown") { document.querySelector(".modal.show") != null }
        lastOpenModal().buttonNamed(label).click()
        awaitUntil("the dialog is gone") { document.querySelector(".modal.show") == null }
    }

    private val seedInput = CrowdfundingProjectInput("t", "d", 1.0.toDecimal())

    @Test
    fun submitForm_collapsedInHeader_balanceStripComesFirst_confirmThenSaveFoldsBackAndReloads(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val list = routeOf { rpcService<ICrowdfundingService>().listProjects() }
            val submit = routeOf { rpcService<ICrowdfundingService>().submitProject(seedInput) }
            val rows = mutableListOf(project("p1", "Altprojekt"))
            var conflictNext = true
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(projects(rows))
                        request.rpcRoute == submit ->
                            if (conflictNext) {
                                conflictNext = false
                                serviceExceptionResult(request.json.id as Int, conflict)
                            } else {
                                val added = project("p2", "Neuprojekt")
                                rows += added
                                request.answerWith(jsonOf(CrowdfundingProjectDto.serializer(), added))
                            }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-crowdfunding-submit") { root, element ->
                    renderCrowdfundingScreen(root)
                    awaitUntil("the list is shown") { element().shows("Altprojekt") }
                    val screen = element()
                    assertEquals(listOf("Projekt einreichen"), screen.pageActionButtons().map { it.textContent?.trim() })
                    assertFalse(screen.shows("Neues Projekt einreichen"), "the old always-visible section title is gone")
                    assertFalse(screen.hostOpen(projectFormId), "collapsed after the load")
                    assertTrue(screen.querySelector("[id='$distributionFormId']") == null, "a plain member has no treasury tools")

                    val host = openCreateFormHost(screen, projectFormId)
                    val first = host.querySelector("[role=status], label") as HTMLElement
                    assertEquals("status", first.getAttribute("role"), "the LTR balance strip precedes every field")
                    assertTrue(document.activeElement is HTMLInputElement, "the focus moved into the first field")
                    escape(host)
                    awaitUntil("closed without a question") { !screen.hostOpen(projectFormId) }

                    val reopened = openCreateFormHost(screen, projectFormId)
                    reopened.typeInto("Titel", "Neuprojekt")
                    escape(reopened)
                    dismissDialogWith("Weiter bearbeiten")
                    assertEquals("Neuprojekt", (reopened.controlOf("Titel") as HTMLInputElement).value)

                    reopened.typeInto("Beschreibung", "Eine Idee")
                    reopened.typeInto("Sichtbarkeits-Gewicht", "5")
                    reopened.buttonNamed("Projekt einreichen").click()
                    dismissDialogWith("Einreichen")
                    awaitUntil("the first write was attempted") { calls.toRoute(submit).size == 1 }
                    awaitUntil("the failed save is over") { !reopened.buttonNamed("Projekt einreichen").hasAttribute("disabled") }
                    assertTrue(screen.hostOpen(projectFormId), "a conflict never folds the form back")
                    assertEquals("Neuprojekt", (reopened.controlOf("Titel") as HTMLInputElement).value)

                    val listCallsBefore = calls.toRoute(list).size
                    reopened.buttonNamed("Projekt einreichen").click()
                    dismissDialogWith("Einreichen")
                    awaitUntil("the second write was made") { calls.toRoute(submit).size == 2 }
                    awaitUntil("the form folded back") { !screen.hostOpen(projectFormId) }
                    assertEquals(
                        "Neuprojekt",
                        calls
                            .toRoute(submit)
                            .last()
                            .rpcParam(0)
                            .title as String,
                    )
                    awaitUntil("the list was reloaded") { calls.toRoute(list).size > listCallsBefore }
                    awaitUntil("the new project is listed") { screen.shows("Neuprojekt") }
                }
            }
        }

    @Test
    fun distributionForm_sitsInTheTreasuryTitleRow_dateTypedMakesEscapeAsk_computeFoldsBackAndReloads(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.TREASURER))
            val list = routeOf { rpcService<ICrowdfundingService>().listProjects() }
            val distributions = routeOf { rpcService<ICrowdfundingService>().listDistributions() }
            val compute =
                routeOf {
                    rpcService<ICrowdfundingService>().computeMonthlyDistribution(LocalDate(2026, 9, 1), LocalDate(2026, 9, 30))
                }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == list -> request.answerWith(projects(emptyList()))
                        request.rpcRoute == distributions ->
                            request.answerWith(jsonOf(ListSerializer(CrowdfundingDistributionDto.serializer()), emptyList()))
                        request.rpcRoute == compute ->
                            request.answerWith(jsonOf(ListSerializer(CrowdfundingDistributionDto.serializer()), emptyList()))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("r36b-crowdfunding-distribution") { root, element ->
                    renderCrowdfundingScreen(root)
                    awaitUntil("the treasury tools are shown") { element().shows("Treuhänder-Werkzeuge") }
                    val screen = element()
                    val toolButton = screen.buttonNamed("Verteilung berechnen")
                    assertEquals(distributionFormId, toolButton.getAttribute("aria-controls"))
                    assertTrue(toolButton.closest(".lapis-page-header") == null, "the tool button is not in the page header")
                    assertEquals(
                        listOf("Projekt einreichen"),
                        screen.pageActionButtons().map { it.textContent?.trim() },
                        "the page header holds only the project button",
                    )
                    assertFalse(screen.hostOpen(distributionFormId), "collapsed")
                    assertFalse(screen.shows("Monatliche Verteilung berechnen"), "the old inline heading is gone")

                    val host = openCreateFormHost(screen, distributionFormId)
                    host.typeInto("Von", "2026-09-01")
                    escape(host)
                    dismissDialogWith("Weiter bearbeiten")
                    assertTrue(screen.hostOpen(distributionFormId), "a typed date counts as a change")

                    host.typeInto("Bis", "2026-09-30")
                    val distCallsBefore = calls.toRoute(distributions).size
                    host.buttonNamed("Verteilung berechnen").click()
                    awaitUntil("computeMonthlyDistribution was called") { calls.toRoute(compute).size == 1 }
                    awaitUntil("the form folded back") { !screen.hostOpen(distributionFormId) }
                    awaitUntil("the history was reloaded") { calls.toRoute(distributions).size > distCallsBefore }
                }
            }
        }

    @Test
    fun hostileProjectTextAndAPlainMember_areHandledSafely(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val list = routeOf { rpcService<ICrowdfundingService>().listProjects() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(projects(listOf(project("p1", "${marker}Quorum heißt", "${marker}Beschreibung"))))
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("r36b-crowdfunding-hostile") { root, element ->
                    renderCrowdfundingScreen(root)
                    awaitUntil("the project card") { element().shows("Quorum") }
                    assertFalse(element().shows(marker), "the marker never reaches the DOM")
                    assertFalse(element().shows("Treuhänder-Werkzeuge"), "no treasury tools for a plain member")
                }
            }
        }
}
