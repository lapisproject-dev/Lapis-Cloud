package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ActivismActionDetailDto
import network.lapis.cloud.shared.domain.ActivismActionInput
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismSignalStateDto
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismViewerRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IActivismService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.Node
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.250 -- the Aktionen form (`/activism/new`, `/activism/{id}/edit`). Since v0.44.0 it crashed while it was built
 * (`LapisField.setVisible` on a check box), so no action could be created or edited. These tests build the real screen.
 */
class ActivismFormDomTest {
    private val progressLabel = "Mit Fortschritt (Menge melden)"
    private val appointmentLabel = "Mit Termin"
    private val confirmLabel = "Gemeldete Mengen müssen bestätigt werden"
    private val signalLabel = "Mit Zusage"

    private fun session() =
        AppState.setSession(
            SessionInfoDto(
                memberId = "member-secret-id",
                displayName = "Mia Mitglied",
                role = AccountRole.MEMBER,
                status = MemberStatus.ACTIVE,
                expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            ),
        )

    private fun detail(
        id: String = "a1",
        progress: Boolean = false,
        appointment: Boolean = false,
        confirm: Boolean = false,
        goal: Int? = null,
        unit: String? = null,
    ) = ActivismActionDetailDto(
        id = id,
        title = "Plakate",
        description = null,
        goalText = null,
        location = null,
        startsAt = if (appointment) LocalDateTime(2026, 11, 20, 10, 0) else null,
        endsAt = null,
        kind = ActivismKind.INFO_STAND,
        formAppointment = appointment,
        formProgress = progress,
        formSignal = false,
        goalQuantity = goal,
        unit = unit,
        confirmContributions = confirm,
        status = ActivismStatus.RUNNING,
        viewerRole = ActivismViewerRole.RESPONSIBLE,
        progressReported = 0,
        progressConfirmed = null,
        signal = ActivismSignalStateDto(countAtLeast5 = null, mine = false),
        noticeText = null,
        ownNotice = null,
        tasks = emptyList(),
        textBlocks = emptyList(),
        material = emptyList(),
        eventShifts = null,
        result = null,
        responsibles = null,
        withdrawnReason = null,
        reviewRejectReason = null,
        costCenterId = null,
        costCenterName = null,
        eventId = null,
        revision = 3,
    )

    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest { block() }

    private fun HTMLElement.hasLabel(text: String): Boolean =
        allOf("label").any {
            it.textContent
                .orEmpty()
                .trim()
                .startsWith(text)
        }

    private fun HTMLElement.labelNamed(text: String): HTMLElement =
        assertNotNull(
            allOf("label").firstOrNull {
                it.textContent
                    .orEmpty()
                    .trim()
                    .startsWith(text)
            },
            "no label '$text'",
        )

    private fun HTMLElement.assertDependentFieldsHidden() {
        for (label in listOf("Zielmenge", "Einheit", confirmLabel, "Beginn", "Ende")) {
            assertTrue(!hasLabel(label), "'$label' must not be in the DOM")
        }
    }

    private fun HTMLElement.assertProgressFieldsShown() {
        for (label in listOf("Zielmenge", "Einheit", confirmLabel)) {
            assertTrue(hasLabel(label), "'$label' must be shown")
        }
    }

    private fun HTMLElement.assertPageIsComplete() {
        val alert = assertNotNull(querySelector(".alert-warning[role=note]") as? HTMLElement, "no warning sentence")
        assertEquals(ActivismTexts.warningSentence(), alert.textContent.orEmpty().trim())
        buttonNamed("Speichern")
        assertTrue(!textContent.orEmpty().contains("Diese Seite konnte nicht geladen werden"))
        assertNull(querySelector(".toast"))
    }

    private suspend fun createRoute() = routeOf { rpcService<IActivismService>().createAction(ActivismActionInput(title = "x")) }

    private fun isAbsent(value: dynamic): Boolean = value == null || jsTypeOf(value) == "undefined"

    private suspend fun withStub(
        existing: ActivismActionDetailDto? = null,
        block: suspend (List<RecordedRequest>, () -> HTMLElement) -> Unit,
    ) {
        session()
        val get = routeOf { rpcService<IActivismService>().getAction("a1") }
        val create = createRoute()
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == get && existing != null ->
                        request.answerWith(jsonOf(ActivismActionDetailDto.serializer(), existing))
                    request.rpcRoute == create ->
                        request.answerWith(jsonOf(ActivismActionDetailDto.serializer(), detail(id = "new1")))
                    else -> request.answerWith("null")
                }
            },
        ) { calls ->
            mountedForm("activism-form-screen") { root, element ->
                if (existing == null) renderActivismFormScreen(root) else renderActivismFormScreen(root, "a1")
                awaitAppScopeIdle("the form finished loading")
                block(calls, element)
            }
        }
    }

    @Test
    fun aNewActionBuildsWithoutAnError_withTheWarningAndTheSaveButton(): Promise<Unit> =
        test {
            withStub { _, page ->
                val form = page()
                form.assertPageIsComplete()
                form.assertDependentFieldsHidden()
                assertEquals(1, form.allOf("h1").size)
            }
        }

    @Test
    fun tickingTheFormsShowsAndHidesTheirFields(): Promise<Unit> =
        test {
            withStub { _, page ->
                val form = page()
                form.tick(progressLabel)
                form.assertProgressFieldsShown()
                val confirm = form.labelNamed(confirmLabel)
                val signal = form.labelNamed(signalLabel)
                assertTrue(
                    confirm.compareDocumentPosition(signal).toInt() and Node.DOCUMENT_POSITION_FOLLOWING.toInt() != 0,
                    "the confirm box stays in front of 'Mit Zusage'",
                )
                form.controlOf(progressLabel).click()
                form.assertDependentFieldsHidden()

                form.tick(appointmentLabel)
                assertTrue(form.hasLabel("Beginn"))
                assertTrue(form.hasLabel("Ende"))
                form.controlOf(appointmentLabel).click()
                assertTrue(!form.hasLabel("Beginn"))
                assertTrue(!form.hasLabel("Ende"))
                form.assertPageIsComplete()
            }
        }

    @Test
    fun anExistingActionShowsItsDependentFieldsPrefilled(): Promise<Unit> =
        test {
            withStub(existing = detail(progress = true, appointment = true, confirm = true, goal = 500, unit = "Plakate")) { _, page ->
                val form = page()
                form.assertPageIsComplete()
                form.assertProgressFieldsShown()
                assertTrue(form.hasLabel("Beginn"))
                assertEquals("500", (form.controlOf("Zielmenge") as HTMLInputElement).value)
                assertEquals("Plakate", (form.controlOf("Einheit") as HTMLInputElement).value)
                assertTrue((form.controlOf(confirmLabel) as HTMLInputElement).checked)
            }
        }

    @Test
    fun hiddenFieldsAreNeverSent_evenWithStaleValues(): Promise<Unit> =
        test {
            val create = createRoute()
            withStub { calls, page ->
                val form = page()
                form.typeInto("Titel", "Plakatieren")
                form.tick(progressLabel)
                form.tick(confirmLabel)
                form.typeInto("Zielmenge", "abc")
                form.typeInto("Einheit", "Plakate")
                form.controlOf(progressLabel).click()
                form.buttonNamed("Speichern").click()
                awaitUntil("createAction went out") { calls.toRoute(create).size == 1 }
                val sent = calls.singleCall(create).rpcParam(0)
                assertTrue(sent.confirmContributions != true, "a hidden, checked confirm box must not be sent as true")
                assertTrue(isAbsent(sent.goalQuantity), "a hidden goal quantity must not be sent")
                assertTrue(sent.formProgress != true)
                assertTrue(isAbsent(sent.unit) || sent.unit == "", "a hidden unit must not be sent")
                assertEquals(emptyList(), form.shownErrors(), "a hidden, invalid Zielmenge must not block saving")
                awaitAppScopeIdle("the save finished")
            }
        }

    @Test
    fun visibleProgressFieldsAreSent(): Promise<Unit> =
        test {
            val create = createRoute()
            withStub { calls, page ->
                val form = page()
                form.typeInto("Titel", "Plakatieren")
                form.tick(progressLabel)
                form.tick(confirmLabel)
                form.typeInto("Zielmenge", "500")
                form.typeInto("Einheit", "Plakate")
                form.buttonNamed("Speichern").click()
                awaitUntil("createAction went out") { calls.toRoute(create).size == 1 }
                val sent = calls.singleCall(create).rpcParam(0)
                assertEquals(true, sent.confirmContributions)
                assertEquals(500, sent.goalQuantity)
                assertEquals("Plakate", sent.unit)
                assertEquals(true, sent.formProgress)
                awaitAppScopeIdle("the save finished")
            }
        }

    @Test
    fun anInvalidVisibleGoalShowsTheErrorAndSendsNothing(): Promise<Unit> =
        test {
            withStub { calls, page ->
                val form = page()
                form.typeInto("Titel", "Plakatieren")
                form.tick(progressLabel)
                form.typeInto("Zielmenge", "abc")
                val before = calls.rpcCount
                form.buttonNamed("Speichern").click()
                awaitUntil("the goal error shows") { form.shownErrors().isNotEmpty() }
                assertEquals(before, calls.rpcCount, "no request may go out")
            }
        }

    @Test
    fun setVisibleOnACheckOrSelectFieldFailsLoud(): Promise<Unit> =
        test {
            mountedForm("activism-form-grammar-guard") { root, _ ->
                val form = root.lapisForm()
                val check = form.checkField(label = tr("Haken"))
                val select = form.selectField(label = tr("Auswahl"), options = listOf("a" to "A"), value = "a")
                val checkError = assertFailsWith<IllegalStateException> { check.setVisible(false) }
                assertTrue(checkError.message.orEmpty().contains("supports text controls only"))
                val selectError = assertFailsWith<IllegalStateException> { select.setVisible(false) }
                assertTrue(selectError.message.orEmpty().contains("supports text controls only"))
            }
        }

    @Test
    fun theRelatedScreensBuildWithoutAnError(): Promise<Unit> =
        test {
            withStub(existing = detail()) { _, page ->
                page().assertPageIsComplete()
            }
            session()
            val get = routeOf { rpcService<IActivismService>().getAction("a1") }
            val contributions = routeOf { rpcService<IActivismService>().listContributions("a1") }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == get -> request.answerWith(jsonOf(ActivismActionDetailDto.serializer(), detail()))
                        request.rpcRoute == contributions -> request.answerWith("null")
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("activism-detail-smoke") { root, element ->
                    renderActivismDetailScreen(root, "a1")
                    awaitAppScopeIdle("the detail page finished loading")
                    assertNotNull(element().querySelector("h1"), "the detail page has a heading")
                }
            }
        }
}
