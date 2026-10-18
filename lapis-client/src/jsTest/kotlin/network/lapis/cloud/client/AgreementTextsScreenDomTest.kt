package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextDraftLookupDto
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
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.134 -- the agreement-text administration in a REAL mounted screen with a stubbed `window.fetch`: the placeholder warning, the live
 * counter (`aria-live`), saving the draft, the publish dialog (focus on "Abbrechen", Esc closes it without a call, confirming sends the hash
 * of the stored draft), the field errors of the two typed refusals and the list of published versions with the short hash.
 */
class AgreementTextsScreenDomTest {
    private val admin = "00000000-0000-0000-0000-0000000000a1"
    private val builtin = "2026-07-23.v1"
    private val fullHash = "0123456789abcdef".repeat(4)

    private fun summary(
        label: String,
        sequenceNo: Int,
        author: String = "Ada Admin",
    ) = AgreementTextVersionSummaryDto(
        kind = AgreementTextKind.MEMBERSHIP,
        versionLabel = label,
        validFrom = LocalDate(2026, 10, 1),
        sha256 = fullHash,
        charCount = 42,
        authorDisplayName = author,
        publishedAt = LocalDateTime(2026, 10, 1, 9, 30),
        sequenceNo = sequenceNo,
    )

    private fun draftOf(
        label: String = "2026-10-15.v1",
        body: String = "Entwurfstext",
        sha: String = "draft-sha-1",
    ) = AgreementTextDraftDto(
        kind = AgreementTextKind.MEMBERSHIP,
        versionLabel = label,
        validFrom = LocalDate(2026, 10, 15),
        body = body,
        sha256 = sha,
        charCount = body.length,
        updatedByDisplayName = "Ada Admin",
        updatedAt = LocalDateTime(2026, 10, 15, 8, 0),
    )

    /** What the stubbed server holds; the test changes it between steps. */
    private class World {
        var current: AgreementTextVersionSummaryDto? = null
        var draft: AgreementTextDraftDto? = null
        var versions: List<AgreementTextVersionSummaryDto> = emptyList()
        var saveFailure: String? = null
        var publishFailure: String? = null
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
        val routes = Routes4(overview, draft, save, publish, versions, version)
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
                        val text = AgreementTextVersionDto(summary(label, 1), "Der Text von $label")
                        request.answerWith(jsonOf(AgreementTextVersionDto.serializer(), text))
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

    @Test
    fun placeholderWarning_isShownWhileNoVersionIsPublished_andGoneWhenOneIs(): Promise<Unit> =
        test {
            val world = World()
            withWorld(world) { _, _ ->
                mountedForm("agreement-texts-warning") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil(
                        "the warning",
                    ) { element().text().contains("Platzhaltertext aktiv: Diese Instanz zeigt bei der Registrierung") }
                    assertTrue(element().text().contains("(Version $builtin)"))
                    // V1.9.144: + the FINDER consent text, V1.9.148: + the two entry notices, V1.9.156: + the event-room recording consent
                    assertEquals(8, element().allOf("[role=tab]").size)
                    assertEquals("true", element().allOf("[role=tab]")[0].getAttribute("aria-selected"))
                    assertEquals(
                        "Eingebauter Mustertext, Version $builtin.",
                        element()
                            .allOf("div")
                            .first {
                                it.text().startsWith("Eingebauter Mustertext")
                            }.text(),
                    )

                    world.current = summary("2026-10-01.v1", 1)
                    world.versions = listOf(world.current!!)
                    element().buttonNamed("Freund-Nutzungsbedingungen").click()
                    awaitUntil("the friend tab's warning") { element().text().contains("(Version 2026-08-15.v1)") }
                    element().buttonNamed("Beitrittsvertrag").click()
                    awaitUntil(
                        "the membership tab without a warning, with the version",
                    ) { element().text().contains("Version: 2026-10-01.v1") }
                    assertFalse(element().text().contains("Platzhaltertext aktiv"))
                    assertTrue(element().text().contains("Personen ohne Bestätigung dieser Fassung: 3"))
                }
            }
        }

    @Test
    fun noTab_showsAnI18nMarker_andTheDateHintReadsAsOneSentence(): Promise<Unit> =
        test {
            val world = World()
            withWorld(world) { _, _ ->
                mountedForm("agreement-texts-marker") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the screen") { element().allOf("[role=tab]").size == 8 }
                    val tabs = element().allOf("[role=tab]")
                    for (index in tabs.indices) {
                        element().allOf("[role=tab]")[index].click()
                        awaitUntil("the tab ${tabs[index].text()} selected") {
                            element().allOf("[role=tab]")[index].getAttribute("aria-selected") == "true"
                        }
                        assertFalse(
                            element().text().contains("###KvI18nS###"),
                            "tab ${tabs[index].text()} shows a raw i18n marker",
                        )
                    }
                    element().allOf("[role=tab]")[0].click()
                    awaitUntil("the date hint") { element().text().contains("Nicht in der Zukunft und nicht vor der aktuellen Fassung.") }
                    assertTrue(element().text().contains("TT.MM.JJJJ Nicht in der Zukunft"))
                }
            }
        }

    @Test
    fun theFinderTab_isAnOptionalFeatureText_withItsHintAndWarning_andSavesUnderItsOwnKind(): Promise<Unit> =
        test {
            withWorld(World()) { calls, routes ->
                mountedForm("agreement-texts-finder") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the tabs") { element().allOf("[role=tab]").size == 8 }
                    element().buttonNamed("Einwilligung Mitglieder-Finder").click()
                    awaitUntil("the hint of the optional feature") {
                        element().text().contains("Gilt nur, wenn der Mitglieder-Finder für die Instanz eingeschaltet ist.")
                    }
                    awaitUntil("the placeholder warning of the finder text") { element().text().contains("(Version 2026-10-16.v1)") }
                    awaitUntil("the editor") { element().allOf("label").any { it.text().trim().startsWith("Vertragstext") } }
                    element().typeInto("Version", "2026-10-16.v2")
                    element().typeInto("Vertragstext", "Einwilligung in den Mitglieder-Finder.")
                    element().buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("the save call") { calls.toRoute(routes.save).size == 1 }
                    assertEquals("FINDER", calls.singleCall(routes.save).rpcParam(0).kind as String)
                }
            }
        }

    @Test
    fun theCounter_isALiveHintLinkedToTheField_andCountsTheNormalizedText(): Promise<Unit> =
        test {
            withWorld(World()) { _, _ ->
                mountedForm("agreement-texts-counter") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the editor") { element().allOf("label").any { it.text().trim().startsWith("Vertragstext") } }
                    val hint = element().hintOf("Vertragstext")
                    assertEquals("polite", hint.getAttribute("aria-live"))
                    assertEquals("0 von 60000 Zeichen", hint.text().trim())
                    element().typeInto("Vertragstext", "abc\r\ndef  \n")
                    awaitUntil("the counter follows") { hint.text().trim() == "7 von 60000 Zeichen" }
                }
            }
        }

    @Test
    fun saving_sendsTheEditorValues_thenOffersPublish(): Promise<Unit> =
        test {
            val world = World()
            withWorld(world) { calls, routes ->
                mountedForm("agreement-texts-save") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the editor") { element().allOf("label").any { it.text().trim().startsWith("Vertragstext") } }
                    assertTrue(
                        (element().buttonNamed("Veröffentlichen") as HTMLButtonElement).disabled,
                        "nothing stored yet: publish is off",
                    )
                    element().typeInto("Version", "2026-10-15.v1")
                    element().typeInto("Vertragstext", "Unser neuer Vertrag.")
                    element().buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("the save call") { calls.toRoute(routes.save).size == 1 }
                    val sent = calls.singleCall(routes.save).rpcParam(0)
                    assertEquals("MEMBERSHIP", sent.kind as String)
                    assertEquals("2026-10-15.v1", sent.versionLabel as String)
                    assertEquals("Unser neuer Vertrag.", sent.body as String)
                    awaitUntil(
                        "publish is on for the stored draft",
                    ) { !(element().buttonNamed("Veröffentlichen") as HTMLButtonElement).disabled }
                    element().typeInto("Vertragstext", "Unser neuer Vertrag, geaendert.")
                    awaitUntil(
                        "publish is off again for an edited text",
                    ) { (element().buttonNamed("Veröffentlichen") as HTMLButtonElement).disabled }
                }
            }
        }

    @Test
    fun publishing_asksFirst_focusOnCancel_escCloses_confirmSendsTheDraftHash(): Promise<Unit> =
        test {
            val world = World().also { it.draft = draftOf() }
            withWorld(world) { calls, routes ->
                mountedForm("agreement-texts-publish") { root, element ->
                    renderAgreementTextsScreen(root)
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
                    awaitUntil("the screen shows the new current version") { element().text().contains("Version: 2026-10-15.v1") }
                    assertFalse(element().text().contains("Platzhaltertext aktiv"))
                }
            }
        }

    @Test
    fun aTakenLabel_isShownAtTheVersionField_andAnInvalidText_atTheTextField(): Promise<Unit> =
        test {
            val world = World()
            withWorld(world) { calls, routes ->
                mountedForm("agreement-texts-errors") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the editor") { element().allOf("label").any { it.text().trim().startsWith("Vertragstext") } }
                    element().typeInto("Vertragstext", "Ein Text.")
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
        }

    @Test
    fun clientSideChecks_stopABadLabelAndAnEmptyText_beforeAnyCall(): Promise<Unit> =
        test {
            withWorld(World()) { calls, routes ->
                mountedForm("agreement-texts-clientchecks") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the editor") { element().allOf("label").any { it.text().trim().startsWith("Vertragstext") } }
                    element().typeInto("Version", "-kaputt")
                    element().typeInto("Vertragstext", "")
                    element().buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("both errors") { element().shownErrors().size >= 2 }
                    assertEquals(0, calls.toRoute(routes.save).size)
                }
            }
        }

    @Test
    fun theVersionList_showsTheShortHash_andLoadsTheTextOnDemand(): Promise<Unit> =
        test {
            val world = World()
            val older = summary("2026-09-01.v1", 1)
            val newer = summary("2026-10-01.v2", 2)
            world.current = newer
            world.versions = listOf(newer, older)
            withWorld(world) { calls, routes ->
                mountedForm("agreement-texts-list") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the list") { element().text().contains("2026-09-01.v1") }
                    val hashSpans = element().allOf("span.font-monospace")
                    assertTrue(hashSpans.isNotEmpty())
                    hashSpans.forEach {
                        assertEquals(8, it.text().trim().length)
                        assertEquals(fullHash, it.getAttribute("title"), "the full hash is the tooltip")
                    }
                    assertEquals(0, calls.toRoute(routes.version).size, "no text is fetched before it is asked for")
                    val toggles = element().allOf("button").filter { it.text().trim() == "Text anzeigen" }
                    val last = toggles.last()
                    assertEquals("false", last.getAttribute("aria-expanded"))
                    last.click()
                    awaitUntil("the text of the older version") { element().text().contains("Der Text von 2026-09-01.v1") }
                    assertEquals("true", last.getAttribute("aria-expanded"))
                    assertEquals(1, calls.toRoute(routes.version).size)
                    assertNotNull(element().querySelector(".$AGREEMENT_FRAME_CLASS"))
                    last.click()
                    assertEquals("false", last.getAttribute("aria-expanded"))
                    last.click()
                    assertEquals(1, calls.toRoute(routes.version).size, "shown again from memory")
                }
            }
        }

    @Test
    fun theEncounterTabs_areOptionalFeatureTexts_withTheirHintTheSystemNoteAndThePlaceholderWarning(): Promise<Unit> =
        test {
            withWorld(World()) { _, _ ->
                mountedForm("agreement-texts-enc-tabs") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the tabs") { element().allOf("[role=tab]").size == 8 }
                    assertNotNull(element().allOf("[role=tab]").firstOrNull { it.text().trim() == "Eintrittshinweis Gottesdienst" })
                    assertNotNull(element().allOf("[role=tab]").firstOrNull { it.text().trim() == "Eintrittshinweis Versammlung" })
                    element().buttonNamed("Eintrittshinweis Gottesdienst").click()
                    awaitUntil("the hint") {
                        element().text().contains("Gilt nur für Begegnungsräume, die Gäste oder Freunde zulassen.")
                    }
                    awaitUntil("the placeholder warning") { element().text().contains("(Version 2026-10-17.v2)") }
                    awaitUntil("the system note") {
                        element().text().contains(
                            "Die Aussage zur Kanzel-Aufzeichnung fügt das System selbst an, wenn ein Raum sie erlaubt.",
                        )
                    }
                    element().buttonNamed("Eintrittshinweis Versammlung").click()
                    awaitUntil("the assembly warning") { element().text().contains("(Version 2026-10-17.assembly.v2)") }
                }
            }
        }

    @Test
    fun anEncounterNotice_needsFourParagraphs_theMessageSaysSo_andNothingIsSent(): Promise<Unit> =
        test {
            withWorld(World()) { calls, routes ->
                mountedForm("agreement-texts-enc-layout") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the tabs") { element().allOf("[role=tab]").size == 8 }
                    element().buttonNamed("Eintrittshinweis Gottesdienst").click()
                    awaitUntil("the editor") { element().allOf("label").any { it.text().trim().startsWith("Vertragstext") } }
                    element().typeInto("Version", "2026-10-18.v1")
                    element().typeInto("Vertragstext", "Überschrift\n\nPunkt eins\n\nPunkt zwei")
                    element().buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("the message") {
                        element().text().contains("Der Hinweis braucht mindestens vier Absätze: Überschrift und drei Kernpunkte.")
                    }
                    assertEquals(0, calls.toRoute(routes.save).size, "a violated rule blocks the save")
                    // a long label is named as well
                    element().typeInto("Vertragstext", "Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei")
                    element().typeInto("Version", "a".repeat(47))
                    element().buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("the label message") { element().text().contains("Die Version darf höchstens 46 Zeichen lang sein.") }
                    assertEquals(0, calls.toRoute(routes.save).size)
                }
            }
        }

    @Test
    fun anEncounterNotice_isPreviewedLikeTheGuestSeesIt_withTheRecordingSentenceOnRequest(): Promise<Unit> =
        test {
            withWorld(World()) { _, _ ->
                mountedForm("agreement-texts-enc-preview") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the tabs") { element().allOf("[role=tab]").size == 8 }
                    element().buttonNamed("Eintrittshinweis Gottesdienst").click()
                    awaitUntil("the editor") { element().allOf("label").any { it.text().trim().startsWith("Vertragstext") } }
                    element().typeInto("Vertragstext", "Meine Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetailabsatz")
                    awaitUntil("the preview shows the headline and the key points") {
                        element().allOf("h3").any { it.text().trim() == "Meine Überschrift" } &&
                            element().allOf("div.small").count { it.text().trim() in setOf("Punkt eins", "Punkt zwei", "Punkt drei") } == 3
                    }
                    assertFalse(element().text().contains("kann die Kanzel aufgezeichnet werden"))
                    element().tick("Raum mit Kanzel-Aufzeichnung")
                    awaitUntil("the recording sentence is the last key point") {
                        element()
                            .allOf(
                                "div.small",
                            ).any { it.text().trim().startsWith("In diesem Raum kann die Kanzel aufgezeichnet werden.") }
                    }
                }
            }
        }

    @Test
    fun aTextThatSaysNothingIsRecorded_getsAWarning_butIsNotBlocked(): Promise<Unit> =
        test {
            withWorld(World()) { calls, routes ->
                mountedForm("agreement-texts-enc-warning") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the tabs") { element().allOf("[role=tab]").size == 8 }
                    element().buttonNamed("Eintrittshinweis Gottesdienst").click()
                    awaitUntil("the editor") { element().allOf("label").any { it.text().trim().startsWith("Vertragstext") } }
                    val warning = "Dieser Text widerspricht der Kanzel-Aufzeichnung in Räumen, die sie erlauben."
                    assertFalse(element().text().contains(warning))
                    element().typeInto("Version", "2026-10-18.v1")
                    element().typeInto("Vertragstext", "Titel\n\nEins\n\nZwei\n\nEs wird nichts aufgezeichnet.")
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
        }

    @Test
    fun theEventRecTab_isAnOptionalFeatureText_withItsHintAndPlaceholderWarning_andNoRecordingSentenceOfTheSystem(): Promise<Unit> =
        test {
            withWorld(World()) { _, _ ->
                mountedForm("agreement-texts-event-rec") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the tabs") { element().allOf("[role=tab]").size == 8 }
                    assertNotNull(element().allOf("[role=tab]").firstOrNull { it.text().trim() == "Aufzeichnung (Veranstaltung)" })
                    element().buttonNamed("Aufzeichnung (Veranstaltung)").click()
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
        }

    @Test
    fun anEventRecText_isPreviewedLikeAParticipantSeesIt_andNeedsFourParagraphs_withTheFullLabelLength(): Promise<Unit> =
        test {
            withWorld(World()) { calls, routes ->
                mountedForm("agreement-texts-event-rec-layout") { root, element ->
                    renderAgreementTextsScreen(root)
                    awaitUntil("the tabs") { element().allOf("[role=tab]").size == 8 }
                    element().buttonNamed("Aufzeichnung (Veranstaltung)").click()
                    awaitUntil("the editor") { element().allOf("label").any { it.text().trim().startsWith("Vertragstext") } }
                    assertTrue(element().text().contains("So sehen Teilnehmende den Hinweis"))
                    element().typeInto("Vertragstext", "Meine Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetailabsatz")
                    awaitUntil("the preview shows the headline and the key points") {
                        element().allOf("h3").any { it.text().trim() == "Meine Überschrift" } &&
                            element().allOf("div.small").count { it.text().trim() in setOf("Punkt eins", "Punkt zwei", "Punkt drei") } == 3
                    }
                    // fewer than four paragraphs: the concrete message, nothing is sent
                    element().typeInto("Version", "2026-10-18.v1")
                    element().typeInto("Vertragstext", "Überschrift\n\nPunkt eins\n\nPunkt zwei")
                    element().buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("the message") {
                        element().text().contains("Der Hinweis braucht mindestens vier Absätze: Überschrift und drei Kernpunkte.")
                    }
                    assertEquals(0, calls.toRoute(routes.save).size)
                    // a 47-character label is fine here (no reserve for a system suffix), the save goes out under its own kind
                    element().typeInto("Vertragstext", "Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei")
                    element().typeInto("Version", "a".repeat(47))
                    element().buttonNamed("Als Entwurf speichern").click()
                    awaitUntil("saved") { calls.toRoute(routes.save).size == 1 }
                    assertEquals("EVENT_REC", calls.singleCall(routes.save).rpcParam(0).kind as String)
                }
            }
        }
}
