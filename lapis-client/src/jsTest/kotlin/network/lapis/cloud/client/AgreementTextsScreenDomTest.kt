package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextDraftLookupDto
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextKindOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextVersionDto
import network.lapis.cloud.shared.domain.AgreementTextVersionSummaryDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IAgreementTextAdminService
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.134 / V1.9.196 -- the agreement-text administration in a REAL mounted screen with a stubbed `window.fetch`. V1.9.196 split it into a
 * master table ([renderAgreementTextsScreen]) and one detail page per text ([renderAgreementTextDetail]): the table with its name links, chips and
 * icon action; on the detail page the placeholder warning, the live counter (`aria-live`), the Markdown editor with the server-rendered preview
 * (debounced, the client never renders Markdown), saving the draft, the publish dialog (focus on "Abbrechen", Esc closes it without a call,
 * confirming sends the hash of the stored draft), the field errors of the typed refusals and the history table with format chip and short hash.
 */
class AgreementTextsScreenDomTest {
    private val admin = "00000000-0000-0000-0000-0000000000a1"
    private val builtin = "2026-07-23.v1"
    private val fullHash = "0123456789abcdef".repeat(4)

    private fun summary(
        label: String,
        sequenceNo: Int,
        author: String = "Ada Admin",
        format: AgreementTextFormat = AgreementTextFormat.PLAIN,
    ) = AgreementTextVersionSummaryDto(
        kind = AgreementTextKind.MEMBERSHIP,
        versionLabel = label,
        validFrom = LocalDate(2026, 10, 1),
        sha256 = fullHash,
        charCount = 42,
        authorDisplayName = author,
        publishedAt = LocalDateTime(2026, 10, 1, 9, 30),
        sequenceNo = sequenceNo,
        format = format,
    )

    private fun draftOf(
        label: String = "2026-10-15.v1",
        body: String = "Entwurfstext",
        sha: String = "draft-sha-1",
        format: AgreementTextFormat = AgreementTextFormat.MARKDOWN,
    ) = AgreementTextDraftDto(
        kind = AgreementTextKind.MEMBERSHIP,
        versionLabel = label,
        validFrom = LocalDate(2026, 10, 15),
        body = body,
        sha256 = sha,
        charCount = body.length,
        updatedByDisplayName = "Ada Admin",
        updatedAt = LocalDateTime(2026, 10, 15, 8, 0),
        format = format,
    )

    /** What the stubbed server holds; the test changes it between steps. */
    private class World {
        var current: AgreementTextVersionSummaryDto? = null
        var draft: AgreementTextDraftDto? = null
        var versions: List<AgreementTextVersionSummaryDto> = emptyList()
        var saveFailure: String? = null
        var publishFailure: String? = null
        var previewFailure: Boolean = false

        /** Labels whose version is MARKDOWN (the stub then answers `getVersion` with server-rendered HTML). */
        var markdownLabels: Set<String> = emptySet()
    }

    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob()).promise {
            disableModalTransitions()
            try {
                AppState.setSession(
                    SessionInfoDto(
                        memberId = admin,
                        displayName = "Ada Admin",
                        role = AccountRole.ADMIN,
                        status = MemberStatus.ACTIVE,
                        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                    ),
                )
                block()
            } finally {
                AppState.setSession(null)
                closeOpenModals()
            }
        }

    private suspend fun withWorld(
        world: World,
        block: suspend (List<RecordedRequest>, Routes4) -> Unit,
    ) {
        val overview = routeOf { rpcService<IAgreementTextAdminService>().getOverview() }
        val draft = routeOf { rpcService<IAgreementTextAdminService>().getDraft(AgreementTextKind.MEMBERSHIP) }
        val save =
            routeOf {
                rpcService<IAgreementTextAdminService>().saveDraft(
                    network.lapis.cloud.shared.domain.AgreementTextDraftInput(
                        AgreementTextKind.MEMBERSHIP,
                        "x",
                        LocalDate(2026, 1, 1),
                        "y",
                    ),
                )
            }
        val publish = routeOf { rpcService<IAgreementTextAdminService>().publishDraft(AgreementTextKind.MEMBERSHIP, "x") }
        val versions = routeOf { rpcService<IAgreementTextAdminService>().listVersions(AgreementTextKind.MEMBERSHIP) }
        val version = routeOf { rpcService<IAgreementTextAdminService>().getVersion(AgreementTextKind.MEMBERSHIP, "x") }
        val preview = routeOf { rpcService<IAgreementTextAdminService>().previewAgreementText(AgreementTextKind.MEMBERSHIP, "x") }
        val routes = Routes4(overview, draft, save, publish, versions, version, preview)
        var savedCount = 0
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == overview -> request.answerWith(jsonOf(AgreementTextOverviewDto.serializer(), overviewOf(world)))
                    request.rpcRoute == draft ->
                        request.answerWith(jsonOf(AgreementTextDraftLookupDto.serializer(), AgreementTextDraftLookupDto(world.draft)))
                    request.rpcRoute == save -> {
                        val failure = world.saveFailure
                        if (failure != null) {
                            serviceExceptionResult(request.json.id as Int, failure)
                        } else {
                            val input = request.rpcParam(0)
                            savedCount += 1
                            val stored =
                                draftOf(
                                    label = input.versionLabel as String,
                                    body = (input.body as String).trimEnd(),
                                    sha = "draft-sha-${savedCount + 1}",
                                )
                            world.draft = stored
                            request.answerWith(jsonOf(AgreementTextDraftDto.serializer(), stored))
                        }
                    }
                    request.rpcRoute == publish -> {
                        val failure = world.publishFailure
                        if (failure != null) {
                            serviceExceptionResult(request.json.id as Int, failure)
                        } else {
                            val stored = summary(label = world.draft!!.versionLabel, sequenceNo = world.versions.size + 1)
                            world.versions = listOf(stored) + world.versions
                            world.current = stored
                            world.draft = null
                            request.answerWith(jsonOf(AgreementTextVersionSummaryDto.serializer(), stored))
                        }
                    }
                    request.rpcRoute == versions ->
                        request.answerWith(jsonOf(ListSerializer(AgreementTextVersionSummaryDto.serializer()), world.versions))
                    request.rpcRoute == version -> {
                        val label = request.rpcParam(1) as String
                        val markdown = label in world.markdownLabels
                        val format = if (markdown) AgreementTextFormat.MARKDOWN else AgreementTextFormat.PLAIN
                        val html = if (markdown) "<h2>Titel $label</h2>\n<p>Der <strong>Text</strong> von $label</p>\n" else null
                        val text = AgreementTextVersionDto(summary(label, 1, format = format), "Der Text von $label", html)
                        request.answerWith(jsonOf(AgreementTextVersionDto.serializer(), text))
                    }
                    request.rpcRoute == preview ->
                        if (world.previewFailure) {
                            StubResponse(status = 500)
                        } else {
                            // the SERVER renders: the stub answers with a recognizable HTML so that a test can tell the client did not
                            request.answerWith(
                                JsonPrimitive("<p>VORSCHAU:" + (request.rpcParam(1) as String).replace("<", "&lt;") + "</p>").toString(),
                            )
                        }
                    else -> request.answerWith("null")
                }
            },
        ) { calls -> block(calls, routes) }
    }

    private class Routes4(
        val overview: String,
        val draft: String,
        val save: String,
        val publish: String,
        val versions: String,
        val version: String,
        val preview: String,
    )

    private fun overviewOf(world: World): AgreementTextOverviewDto =
        AgreementTextOverviewDto(
            kinds =
                listOf(
                    AgreementTextKindOverviewDto(
                        kind = AgreementTextKind.MEMBERSHIP,
                        placeholderActive = world.current == null,
                        current = world.current,
                        builtinVersionLabel = builtin,
                        hasDraft = world.draft != null,
                        olderAcknowledgmentCount = if (world.current == null) null else 3,
                    ),
                    AgreementTextKindOverviewDto(
                        kind = AgreementTextKind.FRIEND,
                        placeholderActive = true,
                        current = null,
                        builtinVersionLabel = "2026-08-15.v1",
                        hasDraft = false,
                        olderAcknowledgmentCount = null,
                    ),
                    AgreementTextKindOverviewDto(
                        kind = AgreementTextKind.FINDER,
                        placeholderActive = true,
                        current = null,
                        builtinVersionLabel = "2026-10-16.v1",
                        hasDraft = false,
                        olderAcknowledgmentCount = null,
                    ),
                    AgreementTextKindOverviewDto(
                        kind = AgreementTextKind.ENC_CHURCH,
                        placeholderActive = true,
                        current = null,
                        builtinVersionLabel = "2026-10-17.v2",
                        hasDraft = false,
                        olderAcknowledgmentCount = null,
                    ),
                    AgreementTextKindOverviewDto(
                        kind = AgreementTextKind.ENC_ASSEM,
                        placeholderActive = true,
                        current = null,
                        builtinVersionLabel = "2026-10-17.assembly.v2",
                        hasDraft = false,
                        olderAcknowledgmentCount = null,
                    ),
                    AgreementTextKindOverviewDto(
                        kind = AgreementTextKind.EVENT_REC,
                        placeholderActive = true,
                        current = null,
                        builtinVersionLabel = "2026-10-18.event-rec.v1",
                        hasDraft = false,
                        olderAcknowledgmentCount = null,
                    ),
                ),
        )

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun HTMLElement.hintOf(label: String): HTMLElement {
        val control = controlOf(label)
        val id =
            control
                .getAttribute("aria-describedby")
                .orEmpty()
                .split(" ")
                .first { it.endsWith("-hint") }
        return assertNotNull(document.getElementById(id) as? HTMLElement, "hint of '$label'")
    }

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private val editorLabel = "Text (Markdown)"

    private suspend fun openDetail(
        name: String,
        world: World,
        kind: AgreementTextKind,
        block: suspend (HTMLElement, () -> HTMLElement, List<RecordedRequest>, Routes4) -> Unit,
    ) {
        withWorld(world) { calls, routes ->
            mountedForm(name) { root, element ->
                renderAgreementTextDetail(root, kind)
                awaitUntil("the editor") { element().allOf("label").any { it.text().trim().startsWith(editorLabel) } }
                try {
                    block(element(), element, calls, routes)
                } finally {
                    // let a pending preview debounce fire INSIDE this stub, so that it cannot reach the stub of the next test
                    kotlinx.coroutines.delay((AGREEMENT_PREVIEW_DEBOUNCE_MS + 150).toLong())
                }
            }
        }
    }

    // ---- master table ---------------------------------------------------------------------------------------------------

    @Test
    fun theList_isATable_withNameLinks_chips_andAnIconOnlyEditAction_inTheOrderOfTheKinds(): Promise<Unit> =
        test {
            val world = World()
            world.draft = draftOf()
            withWorld(world) { _, _ ->
                mountedForm("agreement-texts-list-table") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the table") { element().allOf("a[data-record-id]").size == 6 }
                    assertEquals(0, element().allOf("[role=tab]").size, "no tab strip any more")
                    assertEquals(
                        listOf("Text", "Aktuelle Fassung", "Gültig ab", "Veröffentlicht am", "Status", "Aktion"),
                        element().allOf("thead th").map { it.text().trim() },
                    )
                    assertEquals("Vertragstexte", element().querySelector("caption")?.textContent?.trim())
                    val links = element().allOf("a[data-record-id]")
                    assertEquals(
                        listOf("Beitrittsvertrag", "Freund-Nutzungsbedingungen", "Einwilligung Mitglieder-Finder"),
                        links.take(3).map { it.text().trim() },
                        "the order of AgreementTextKind",
                    )
                    assertEquals("#/agreement-texts/membership", links[0].getAttribute("href"))
                    assertEquals("#/agreement-texts/friend", links[1].getAttribute("href"))
                    assertEquals("membership", links[0].getAttribute("data-record-id"))
                    val rows = element().allOf("tbody tr")
                    assertEquals(6, rows.size)
                    assertTrue(rows[0].text().contains("Platzhalter aktiv"), "membership still on the placeholder")
                    assertTrue(rows[0].text().contains("Entwurf offen"), "the membership draft is flagged")
                    assertTrue(rows[1].text().contains("Platzhalter aktiv"))
                    assertFalse(rows[1].text().contains("Entwurf offen"))
                    assertTrue(rows[0].text().contains(builtin), "the placeholder's own label stands in the current-version column")
                    val action = rows[0].querySelector("button")
                    assertNotNull(action)
                    assertEquals("Bearbeiten: Beitrittsvertrag", action.getAttribute("aria-label"))
                    assertEquals("Bearbeiten: Beitrittsvertrag", action.getAttribute("title"))
                    assertEquals("", action.textContent?.trim(), "icon only")
                    assertFalse(element().text().contains("Einfacher Text"), "no format column in the master table")
                }
            }
        }

    @Test
    fun theList_showsAnOwnVersionWithItsDates_andNoPlaceholderChip(): Promise<Unit> =
        test {
            val world = World()
            world.current = summary("2026-10-01.v1", 1)
            world.versions = listOf(world.current!!)
            withWorld(world) { _, _ ->
                mountedForm("agreement-texts-list-own") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the table") { element().allOf("a[data-record-id]").size == 6 }
                    val membership = element().allOf("tbody tr")[0]
                    assertTrue(membership.text().contains("2026-10-01.v1"))
                    assertTrue(membership.text().contains("Eigene Fassung"))
                    assertFalse(membership.text().contains("Platzhalter aktiv"))
                    assertTrue(membership.text().contains("01.10.2026"), "valid from and published on are dates")
                }
            }
        }

    // ---- detail page ----------------------------------------------------------------------------------------------------

    @Test
    fun theDetailRoute_hasABackButtonAboveTheTitle_andAnUnknownKindLeavesAHint(): Promise<Unit> =
        test {
            withWorld(World()) { _, _ ->
                mountedForm("agreement-texts-deeplink") { root, element ->
                    renderAgreementTextDetailRoute(root, "friend")
                    awaitUntil("the title") {
                        element().querySelector("h1")?.textContent?.trim() ==
                            "Vertragstext: Freund-Nutzungsbedingungen"
                    }
                    val h1 = assertNotNull(element().querySelector("h1"))
                    val back = element().buttonNamed("Zur Übersicht")
                    assertTrue(back.compareDocumentPosition(h1).toInt() and 4 != 0, "the back button is above the title")
                    assertTrue(back.classList.contains(BACK_BUTTON_CLASS))
                }
            }
            withWorld(World()) { calls, _ ->
                mountedForm("agreement-texts-unknown") { root, element ->
                    renderAgreementTextDetailRoute(root, "gibtsnicht")
                    awaitUntil("the hint") { element().text().contains("Diesen Vertragstext gibt es nicht.") }
                    element().buttonNamed("Zur Übersicht")
                    assertEquals(0, calls.rpcCount, "an unknown kind asks the server for nothing")
                }
            }
        }

    @Test
    fun placeholderWarning_isShownWhileNoVersionIsPublished_andGoneWhenOneIs(): Promise<Unit> =
        test {
            val world = World()
            openDetail("agreement-texts-warning", world, AgreementTextKind.MEMBERSHIP) { _, element, _, _ ->
                awaitUntil(
                    "the warning",
                ) { element().text().contains("Platzhaltertext aktiv: Diese Instanz zeigt bei der Registrierung") }
                assertTrue(element().text().contains("(Version $builtin)"))
                assertEquals(0, element().allOf("[role=tab]").size)
                val summary = assertNotNull(element().allOf("details > summary").firstOrNull { it.text().startsWith("Aktuelle Fassung") })
                assertEquals("Aktuelle Fassung: $builtin", summary.text().trim())
                assertFalse((summary.parentElement as HTMLElement).hasAttribute("open"), "the current version is collapsed")
                assertTrue(element().text().contains("Eingebauter Mustertext, Version $builtin."))
            }
            world.current = summary("2026-10-01.v1", 1)
            world.versions = listOf(world.current!!)
            openDetail("agreement-texts-warning-own", world, AgreementTextKind.MEMBERSHIP) { _, element, _, _ ->
                awaitUntil("the version") { element().text().contains("Version: 2026-10-01.v1") }
                assertFalse(element().text().contains("Platzhaltertext aktiv"))
                assertTrue(element().text().contains("Personen ohne Bestätigung dieser Fassung: 3"))
            }
        }

    @Test
    fun theFriendDetail_showsItsOwnPlaceholderWarning(): Promise<Unit> =
        test {
            openDetail("agreement-texts-friend", World(), AgreementTextKind.FRIEND) { _, element, _, _ ->
                awaitUntil("the friend warning") { element().text().contains("(Version 2026-08-15.v1)") }
                assertFalse(element().text().contains("(Version $builtin)"))
            }
        }

    @Test
    fun noDetailPage_showsAnI18nMarker_andTheDateHintReadsAsOneSentence(): Promise<Unit> =
        test {
            useTextTemporalInputs() // the text fallback of V1.9.137 (the picker is the default since V1.9.173)
            for (kind in listOf(
                AgreementTextKind.MEMBERSHIP,
                AgreementTextKind.FRIEND,
                AgreementTextKind.FINDER,
                AgreementTextKind.ENC_CHURCH,
            )) {
                openDetail("agreement-texts-marker-${kind.name}", World(), kind) { _, element, _, _ ->
                    assertFalse(element().text().contains("###KvI18nS###"), "$kind shows a raw i18n marker")
                    if (kind == AgreementTextKind.MEMBERSHIP) {
                        assertTrue(element().text().contains("Nicht in der Zukunft und nicht vor der aktuellen Fassung."))
                        assertTrue(element().text().contains("TT.MM.JJJJ Nicht in der Zukunft"))
                    }
                }
            }
        }

    @Test
    fun theFinderDetail_isAnOptionalFeatureText_withItsHintAndWarning_andSavesUnderItsOwnKind(): Promise<Unit> =
        test {
            openDetail("agreement-texts-finder", World(), AgreementTextKind.FINDER) { _, element, calls, routes ->
                awaitUntil("the hint of the optional feature") {
                    element().text().contains("Gilt nur, wenn der Mitglieder-Finder für die Instanz eingeschaltet ist.")
                }
                awaitUntil("the placeholder warning of the finder text") { element().text().contains("(Version 2026-10-16.v1)") }
                element().typeInto("Version", "2026-10-16.v2")
                element().typeInto(editorLabel, "Einwilligung in den Mitglieder-Finder.")
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("the save call") { calls.toRoute(routes.save).size == 1 }
                assertEquals("FINDER", calls.singleCall(routes.save).rpcParam(0).kind as String)
            }
        }

    @Test
    fun theCounter_isALiveHintLinkedToTheField_andCountsTheNormalizedText(): Promise<Unit> =
        test {
            openDetail("agreement-texts-counter", World(), AgreementTextKind.MEMBERSHIP) { _, element, _, _ ->
                val hint = element().hintOf(editorLabel)
                assertEquals("polite", hint.getAttribute("aria-live"))
                assertEquals("0 von 60000 Zeichen", hint.text().trim())
                element().typeInto(editorLabel, "abc\r\ndef  \n")
                awaitUntil("the counter follows") { hint.text().trim() == "7 von 60000 Zeichen" }
            }
        }

    @Test
    fun saving_sendsTheEditorValues_thenOffersPublish(): Promise<Unit> =
        test {
            val world = World()
            openDetail("agreement-texts-save", world, AgreementTextKind.MEMBERSHIP) { _, element, calls, routes ->
                assertTrue(
                    (element().buttonNamed("Veröffentlichen") as HTMLButtonElement).disabled,
                    "nothing stored yet: publish is off",
                )
                element().typeInto("Version", "2026-10-15.v1")
                element().typeInto(editorLabel, "Unser neuer Vertrag.")
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("the save call") { calls.toRoute(routes.save).size == 1 }
                val sent = calls.singleCall(routes.save).rpcParam(0)
                assertEquals("MEMBERSHIP", sent.kind as String)
                assertEquals("2026-10-15.v1", sent.versionLabel as String)
                assertEquals("Unser neuer Vertrag.", sent.body as String)
                assertEquals(undefined, sent.format, "the client never sends a format")
                awaitUntil(
                    "publish is on for the stored draft",
                ) { !(element().buttonNamed("Veröffentlichen") as HTMLButtonElement).disabled }
                element().typeInto(editorLabel, "Unser neuer Vertrag, geaendert.")
                awaitUntil(
                    "publish is off again for an edited text",
                ) { (element().buttonNamed("Veröffentlichen") as HTMLButtonElement).disabled }
            }
        }

    @Test
    fun plainDraftFromBeforeMarkdown_keepsPublishOff_untilSavedAgain(): Promise<Unit> =
        test {
            val world = World()
            world.draft = draftOf(format = AgreementTextFormat.PLAIN)
            openDetail("agreement-texts-plain-draft", world, AgreementTextKind.MEMBERSHIP) { _, element, calls, routes ->
                awaitUntil("the PLAIN draft is loaded") { element().textContent.orEmpty().contains("erneut speichern") }
                assertTrue(
                    (element().buttonNamed("Veröffentlichen") as HTMLButtonElement).disabled,
                    "a PLAIN draft cannot be published as it is",
                )
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("the save call") { calls.toRoute(routes.save).size == 1 }
                awaitUntil(
                    "publish is on after saving again",
                ) { !(element().buttonNamed("Veröffentlichen") as HTMLButtonElement).disabled }
            }
        }

    @Test
    fun publishing_asksFirst_focusOnCancel_escCloses_confirmSendsTheDraftHash(): Promise<Unit> =
        test {
            val world = World().also { it.draft = draftOf() }
            openDetail("agreement-texts-publish", world, AgreementTextKind.MEMBERSHIP) { _, element, calls, routes ->
                awaitUntil("publish is on") {
                    element().allOf("button").any {
                        it.text().trim() == "Veröffentlichen" &&
                            !(it as HTMLButtonElement).disabled
                    }
                }
                element().buttonNamed("Veröffentlichen").click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                assertTrue(modal.text().contains("Version 2026-10-15.v1, gültig ab"))
                assertTrue(modal.text().contains("Diese Fassung kann nicht mehr geändert oder gelöscht werden."))
                awaitUntil("focus on Abbrechen", 1000) { document.activeElement === modal.buttonNamed("Abbrechen") }

                escape(modal)
                awaitUntil("Esc closes the dialog") { document.querySelector(".modal.show") == null }
                assertEquals(0, calls.toRoute(routes.publish).size, "Esc published nothing")

                element().buttonNamed("Veröffentlichen").click()
                awaitUntil("the dialog again") { document.querySelector(".modal.show") != null }
                lastOpenModal().buttonNamed("Veröffentlichen").click()
                awaitUntil("the publish call") { calls.toRoute(routes.publish).size == 1 }
                val call = calls.singleCall(routes.publish)
                assertEquals("MEMBERSHIP", call.rpcParam(0) as String)
                assertEquals("draft-sha-1", call.rpcParam(1) as String)
                awaitUntil("the page shows the new current version") { element().text().contains("Version: 2026-10-15.v1") }
                assertFalse(element().text().contains("Platzhaltertext aktiv"))
            }
        }

    @Test
    fun aTakenLabel_isShownAtTheVersionField_andAnInvalidText_atTheTextField(): Promise<Unit> =
        test {
            val world = World()
            openDetail("agreement-texts-errors", world, AgreementTextKind.MEMBERSHIP) { _, element, calls, routes ->
                element().typeInto(editorLabel, "Ein Text.")
                world.saveFailure = "network.lapis.cloud.shared.rpc.AgreementTextVersionLabelTakenException"
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("the label error") { element().shownErrors().contains("Diese Versionsbezeichnung ist bereits vergeben.") }
                assertEquals(1, calls.toRoute(routes.save).size)

                world.saveFailure = "network.lapis.cloud.shared.rpc.AgreementTextInvalidException"
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil(
                    "the text error",
                ) { element().shownErrors().any { it.startsWith("Dieser Text oder dieses Datum ist nicht zulässig") } }
                assertFalse(
                    element().shownErrors().contains("Diese Versionsbezeichnung ist bereits vergeben."),
                    "a new submit clears the earlier server error at the label",
                )
            }
        }

    @Test
    fun clientSideChecks_stopABadLabelAndAnEmptyText_beforeAnyCall(): Promise<Unit> =
        test {
            openDetail("agreement-texts-clientchecks", World(), AgreementTextKind.MEMBERSHIP) { _, element, calls, routes ->
                element().typeInto("Version", "-kaputt")
                element().typeInto(editorLabel, "")
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("both errors") { element().shownErrors().size >= 2 }
                assertEquals(0, calls.toRoute(routes.save).size)
            }
        }

    // ---- editor: Markdown source left, server-rendered preview right -------------------------------------------------

    @Test
    fun theEditor_isAGrid_withTheTextAreaAndAPreviewRegion_andAShortHelp(): Promise<Unit> =
        test {
            openDetail("agreement-texts-grid", World(), AgreementTextKind.MEMBERSHIP) { _, element, _, _ ->
                val grid = assertNotNull(element().querySelector(".lapis-agreement-editor-grid") as? HTMLElement)
                assertNotNull(grid.querySelector("textarea"), "the source is in the grid")
                val preview = assertNotNull(grid.querySelector(".lapis-agreement-preview"), "the preview is in the grid")
                assertTrue(grid.text().contains("Vorschau – so sehen Mitglieder den Text"))
                assertTrue(preview.textContent.orEmpty().contains("Die Vorschau erscheint, sobald Sie Text eingeben."))
                assertEquals("20", grid.querySelector("textarea")?.getAttribute("rows"))
                val help = assertNotNull(element().allOf("details > summary").firstOrNull { it.text().trim() == "Kurzhilfe: Formatierung" })
                assertFalse((help.parentElement as HTMLElement).hasAttribute("open"), "the short help is collapsed")
                assertTrue((help.parentElement as HTMLElement).text().contains("https://"), "the help names the link scheme in lower case")
            }
        }

    @Test
    fun thePreview_isAskedOnceAfterThePause_andShowsExactlyTheServersHtml(): Promise<Unit> =
        test {
            openDetail("agreement-texts-preview", World(), AgreementTextKind.MEMBERSHIP) { _, element, calls, routes ->
                element().typeInto(editorLabel, "a")
                element().typeInto(editorLabel, "ab")
                element().typeInto(editorLabel, "**abc**")
                assertEquals(0, calls.toRoute(routes.preview).size, "nothing is asked while the person is still typing (500 ms debounce)")
                awaitUntil("the preview call") { calls.toRoute(routes.preview).isNotEmpty() }
                kotlinx.coroutines.delay(800)
                assertEquals(1, calls.toRoute(routes.preview).size, "three keystrokes, one request")
                val call = calls.singleCall(routes.preview)
                assertEquals("MEMBERSHIP", call.rpcParam(0) as String)
                assertEquals("**abc**", call.rpcParam(1) as String)
                awaitUntil("the server's html is shown") {
                    element().querySelector(".lapis-agreement-preview .$AGREEMENT_BODY_CLASS")?.textContent?.trim() == "VORSCHAU:**abc**"
                }
                // the client did not interpret the Markdown itself: no <strong> although the source has **
                assertEquals(0, element().allOf(".lapis-agreement-preview strong").size)
            }
        }

    @Test
    fun aFailingPreview_isShownInTheFieldItself_andNothingElseBreaks(): Promise<Unit> =
        test {
            val world = World().also { it.previewFailure = true }
            openDetail("agreement-texts-preview-error", world, AgreementTextKind.MEMBERSHIP) { _, element, calls, routes ->
                element().typeInto(editorLabel, "Text")
                awaitUntil("the preview call") { calls.toRoute(routes.preview).isNotEmpty() }
                awaitUntil("the local error") {
                    element()
                        .querySelector(
                            ".lapis-agreement-preview",
                        )?.textContent
                        .orEmpty()
                        .contains("Die Vorschau ist gerade nicht verfügbar.")
                }
                assertTrue(element().text().contains("Als Entwurf speichern"), "the form is intact")
            }
        }

    @Test
    fun aTooLongOrIllegalText_isNotSentToThePreview(): Promise<Unit> =
        test {
            openDetail("agreement-texts-preview-guard", World(), AgreementTextKind.MEMBERSHIP) { _, element, calls, routes ->
                element().typeInto(editorLabel, "mit NUL \u0000 drin")
                awaitUntil("the local note") {
                    element()
                        .querySelector(".lapis-agreement-preview")
                        ?.textContent
                        .orEmpty()
                        .contains("keine Vorschau möglich")
                }
                kotlinx.coroutines.delay(700)
                assertEquals(0, calls.toRoute(routes.preview).size)
            }
        }

    @Test
    fun aNewDraft_startsFromTheSourceOfTheLiveVersion_andAPlainOneGetsTheMarkdownNote(): Promise<Unit> =
        test {
            val world = World()
            world.current = summary("2026-10-01.v1", 1)
            world.versions = listOf(world.current!!)
            openDetail("agreement-texts-seed-plain", world, AgreementTextKind.MEMBERSHIP) { _, element, calls, routes ->
                assertEquals("Der Text von 2026-10-01.v1", (element().controlOf(editorLabel) as HTMLTextAreaElement).value)
                assertTrue(element().text().contains("Die bisherige Fassung ist einfacher Text."))
                assertTrue(element().text().contains("(ein Punktzeichen wird nicht umgewandelt)"))
                awaitUntil("the preview of the seeded text") { calls.toRoute(routes.preview).size == 1 }
            }
            world.markdownLabels = setOf("2026-10-01.v1")
            world.current = summary("2026-10-01.v1", 1, format = AgreementTextFormat.MARKDOWN)
            openDetail("agreement-texts-seed-markdown", world, AgreementTextKind.MEMBERSHIP) { _, element, _, _ ->
                assertEquals("Der Text von 2026-10-01.v1", (element().controlOf(editorLabel) as HTMLTextAreaElement).value)
                assertFalse(element().text().contains("Die bisherige Fassung ist einfacher Text."))
            }
        }

    @Test
    fun anExistingDraft_winsOverTheSeed(): Promise<Unit> =
        test {
            val world = World()
            world.current = summary("2026-10-01.v1", 1)
            world.versions = listOf(world.current!!)
            world.draft = draftOf(body = "Mein Entwurf")
            openDetail("agreement-texts-seed-draft", world, AgreementTextKind.MEMBERSHIP) { _, element, _, _ ->
                assertEquals("Mein Entwurf", (element().controlOf(editorLabel) as HTMLTextAreaElement).value)
            }
        }

    // ---- current version and history ---------------------------------------------------------------------------------

    @Test
    fun theHistory_isATable_withFormatChip_shortHash_andTheTextOnDemand(): Promise<Unit> =
        test {
            val world = World()
            val older = summary("2026-09-01.v1", 1)
            val newer = summary("2026-10-01.v2", 2, format = AgreementTextFormat.MARKDOWN)
            world.current = newer
            world.versions = listOf(newer, older)
            world.markdownLabels = setOf("2026-10-01.v2")
            openDetail("agreement-texts-history", world, AgreementTextKind.MEMBERSHIP) { _, element, calls, routes ->
                awaitUntil("the history") { element().text().contains("2026-09-01.v1") }
                val table = element().allOf("table").first { it.querySelector("caption")?.textContent?.trim() == "Verlauf der Fassungen" }
                assertEquals(
                    listOf("Version", "Gültig ab", "Veröffentlicht am", "Von", "Format", "Prüfsumme", "Text"),
                    table.allOf("thead th").map { it.text().trim() },
                )
                val rows = table.allOf("tbody tr")
                assertEquals(2, rows.size)
                assertTrue(rows[0].text().contains("Markdown"))
                assertTrue(rows[1].text().contains("Einfacher Text"))
                val hash = rows[0].querySelector("span.font-monospace")
                assertNotNull(hash)
                assertEquals(
                    12,
                    hash.textContent
                        .orEmpty()
                        .trim()
                        .length,
                    "twelve characters visible",
                )
                assertEquals(fullHash, hash.getAttribute("title"), "the full hash is the tooltip")
                val before = calls.toRoute(routes.version).size
                // open the older (plain) version: shown as text inside the reading frame
                val view = assertNotNull(rows[1].querySelector("button") as? HTMLElement)
                assertEquals("Text anzeigen: 2026-09-01.v1", view.getAttribute("aria-label"))
                assertEquals("false", view.getAttribute("aria-expanded"))
                view.click()
                awaitUntil("the text of the older version") { element().text().contains("Der Text von 2026-09-01.v1") }
                assertEquals(before + 1, calls.toRoute(routes.version).size)
                assertEquals(0, element().allOf(".$AGREEMENT_FRAME_CLASS .$AGREEMENT_BODY_CLASS").size, "plain text has no html body")
                // open the newer (markdown) version: shown through the server's html
                rows[0].querySelector("button")!!.let { (it as HTMLElement).click() }
                awaitUntil("the markdown version as html") {
                    element().querySelector(".$AGREEMENT_FRAME_CLASS .$AGREEMENT_BODY_CLASS h2")?.textContent?.trim() ==
                        "Titel 2026-10-01.v2"
                }
                assertTrue(element().querySelector(".$AGREEMENT_FRAME_CLASS .$AGREEMENT_BODY_CLASS strong") != null)
            }
        }

    @Test
    fun theCurrentVersion_isCollapsed_andLoadsItsTextOnDemand_markdownAsHtml(): Promise<Unit> =
        test {
            val world = World()
            val current = summary("2026-10-01.v1", 1, format = AgreementTextFormat.MARKDOWN)
            world.current = current
            world.versions = listOf(current)
            world.markdownLabels = setOf("2026-10-01.v1")
            openDetail("agreement-texts-current", world, AgreementTextKind.MEMBERSHIP) { _, element, calls, routes ->
                val details =
                    element().allOf("details").first {
                        it.querySelector("summary")?.textContent?.startsWith("Aktuelle Fassung") ==
                            true
                    }
                assertFalse(details.hasAttribute("open"))
                val toggles = details.allOf("button").filter { it.text().trim() == "Text anzeigen" }
                assertEquals(1, toggles.size)
                val loadedBefore = calls.toRoute(routes.version).size
                toggles.single().click()
                awaitUntil("the html") {
                    details.querySelector(".$AGREEMENT_BODY_CLASS h2")?.textContent?.trim() == "Titel 2026-10-01.v1"
                }
                assertEquals("true", toggles.single().getAttribute("aria-expanded"))
                assertEquals(loadedBefore + 1, calls.toRoute(routes.version).size)
            }
        }

    // ---- notice kinds ---------------------------------------------------------------------------------------------------

    @Test
    fun theEncounterDetails_areOptionalFeatureTexts_withTheirHintTheSystemNoteAndThePlaceholderWarning(): Promise<Unit> =
        test {
            openDetail("agreement-texts-enc-church", World(), AgreementTextKind.ENC_CHURCH) { _, element, _, _ ->
                awaitUntil("the hint") {
                    element().text().contains("Gilt nur für Begegnungsräume, die Gäste oder Freunde zulassen.")
                }
                awaitUntil("the placeholder warning") { element().text().contains("(Version 2026-10-17.v2)") }
                awaitUntil("the system note") {
                    element().text().contains(
                        "Die Aussage zur Kanzel-Aufzeichnung fügt das System selbst an, wenn ein Raum sie erlaubt.",
                    )
                }
            }
            openDetail("agreement-texts-enc-assem", World(), AgreementTextKind.ENC_ASSEM) { _, element, _, _ ->
                awaitUntil("the assembly warning") { element().text().contains("(Version 2026-10-17.assembly.v2)") }
            }
        }

    @Test
    fun anEncounterNotice_needsFourParagraphs_theMessageSaysSo_andNothingIsSent(): Promise<Unit> =
        test {
            openDetail("agreement-texts-enc-layout", World(), AgreementTextKind.ENC_CHURCH) { _, element, calls, routes ->
                element().typeInto("Version", "2026-10-18.v1")
                element().typeInto(editorLabel, "Überschrift\n\nPunkt eins\n\nPunkt zwei")
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("the message") {
                    element().text().contains("Der Hinweis braucht mindestens vier Absätze: Überschrift und drei Kernpunkte.")
                }
                assertEquals(0, calls.toRoute(routes.save).size, "a violated rule blocks the save")
                // a long label is named as well
                element().typeInto(editorLabel, "Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei")
                element().typeInto("Version", "a".repeat(47))
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("the label message") { element().text().contains("Die Version darf höchstens 46 Zeichen lang sein.") }
                assertEquals(0, calls.toRoute(routes.save).size)
            }
        }

    @Test
    fun blockMarkupInTheSummaryParagraphs_isNamed_andNothingIsSent(): Promise<Unit> =
        test {
            openDetail("agreement-texts-enc-block", World(), AgreementTextKind.ENC_CHURCH) { _, element, calls, routes ->
                element().typeInto("Version", "2026-10-18.v1")
                element().typeInto(editorLabel, "Überschrift\n\n- Punkt eins\n\nPunkt zwei\n\nPunkt drei")
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("the message") {
                    element().text().contains(
                        "In Überschrift und Kernpunkten (Absatz 1 bis 4) sind Überschriften, Listen, Zitate und Codeblöcke nicht erlaubt.",
                    )
                }
                assertEquals(0, calls.toRoute(routes.save).size)
                // a list in the full text (paragraph 5 on) is fine
                element().typeInto(editorLabel, "Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\n- Detail eins\n- Detail zwei")
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("saved") { calls.toRoute(routes.save).size == 1 }
            }
        }

    @Test
    fun theRecordingTexts_allowNoBlockMarkupAtAll(): Promise<Unit> =
        test {
            // the stub overview has no REC kind; the client rule is the same function the server uses
            assertTrue(
                network.lapis.cloud.shared.domain.EncounterConsentLayout
                    .hasBlockMarkup("Kurz.\n\n- Liste"),
            )
            assertFalse(
                network.lapis.cloud.shared.domain.EncounterConsentLayout
                    .hasBlockMarkup("Kurz **und** knapp."),
            )
        }

    @Test
    fun anEncounterNotice_sendsTheRecordingSentenceWithThePreviewOnRequest(): Promise<Unit> =
        test {
            openDetail("agreement-texts-enc-preview", World(), AgreementTextKind.ENC_CHURCH) { _, element, calls, routes ->
                element().typeInto(editorLabel, "Meine Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetailabsatz")
                awaitUntil("the first preview") { calls.toRoute(routes.preview).size == 1 }
                assertFalse((calls.toRoute(routes.preview).last().rpcParam(1) as String).contains("kann die Kanzel aufgezeichnet werden"))
                element().tick("Raum mit Kanzel-Aufzeichnung")
                awaitUntil("the preview with the system's sentence") {
                    calls.toRoute(routes.preview).size == 2 &&
                        (calls.toRoute(routes.preview).last().rpcParam(1) as String).endsWith(
                            "In diesem Raum kann die Kanzel aufgezeichnet werden. Ihr eigenes Bild und Ihr Ton werden nie aufgezeichnet.",
                        )
                }
                awaitUntil("shown") {
                    element()
                        .querySelector(
                            ".lapis-agreement-preview",
                        )?.textContent
                        .orEmpty()
                        .contains("kann die Kanzel aufgezeichnet werden")
                }
                // the saved source never contains the system's sentence
                element().typeInto("Version", "2026-10-18.v1")
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("the save") { calls.toRoute(routes.save).size == 1 }
                assertFalse((calls.singleCall(routes.save).rpcParam(0).body as String).contains("kann die Kanzel aufgezeichnet werden"))
            }
        }

    @Test
    fun aTextThatSaysNothingIsRecorded_getsAWarning_butIsNotBlocked(): Promise<Unit> =
        test {
            openDetail("agreement-texts-enc-warning", World(), AgreementTextKind.ENC_CHURCH) { _, element, calls, routes ->
                val warning = "Dieser Text widerspricht der Kanzel-Aufzeichnung in Räumen, die sie erlauben."
                assertFalse(element().text().contains(warning))
                element().typeInto("Version", "2026-10-18.v1")
                element().typeInto(editorLabel, "Titel\n\nEins\n\nZwei\n\nEs wird nichts aufgezeichnet.")
                awaitUntil("the warning") {
                    element().allOf("[role=status]").any {
                        it.text().contains(warning) &&
                            it.offsetParent != null
                    }
                }
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("saving is not blocked") { calls.toRoute(routes.save).size == 1 }
                assertEquals("ENC_CHURCH", calls.singleCall(routes.save).rpcParam(0).kind as String)
            }
        }

    @Test
    fun theEventRecDetail_isAnOptionalFeatureText_withItsHintAndPlaceholderWarning_andNoRecordingSentenceOfTheSystem(): Promise<Unit> =
        test {
            openDetail("agreement-texts-event-rec", World(), AgreementTextKind.EVENT_REC) { _, element, _, _ ->
                awaitUntil("the hint") {
                    element().text().contains(
                        "Gilt nur, wenn die Aufzeichnung in Veranstaltungsräumen für die Instanz eingeschaltet ist.",
                    )
                }
                awaitUntil("the placeholder warning") { element().text().contains("(Version 2026-10-18.event-rec.v1)") }
                // the system owns NO recording sentence here (the operator's own text says what is recorded) and there is no "room with recording" box
                assertFalse(element().text().contains("Die Aussage zur Kanzel-Aufzeichnung fügt das System selbst an"))
                assertFalse(element().allOf("label").any { it.text().contains("Raum mit Kanzel-Aufzeichnung") })
            }
        }

    @Test
    fun anEventRecText_needsFourParagraphs_withTheFullLabelLength_andSavesUnderItsOwnKind(): Promise<Unit> =
        test {
            openDetail("agreement-texts-event-rec-layout", World(), AgreementTextKind.EVENT_REC) { _, element, calls, routes ->
                element().typeInto("Version", "2026-10-18.v1")
                element().typeInto(editorLabel, "Überschrift\n\nPunkt eins\n\nPunkt zwei")
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("the message") {
                    element().text().contains("Der Hinweis braucht mindestens vier Absätze: Überschrift und drei Kernpunkte.")
                }
                assertEquals(0, calls.toRoute(routes.save).size)
                // a 47-character label is fine here (no reserve for a system suffix), the save goes out under its own kind
                element().typeInto(editorLabel, "Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei")
                element().typeInto("Version", "a".repeat(47))
                element().buttonNamed("Als Entwurf speichern").click()
                awaitUntil("saved") { calls.toRoute(routes.save).size == 1 }
                assertEquals("EVENT_REC", calls.singleCall(routes.save).rpcParam(0).kind as String)
            }
        }
}
