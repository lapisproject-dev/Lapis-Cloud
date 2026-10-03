package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamDestinationDto
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IConferenceStreamingService
import network.lapis.cloud.shared.rpc.IDocumentService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.49 -- rule R57 for the remaining groups: buttons that got a standard icon keep their visible text (the accessible name does not change),
 * carry a decorative icon, and the buttons of domain verbs (activate, "Auktion aktivieren …") stay without one.
 */
class RestGroupActionIconsDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "caller-1",
            displayName = "Vera Vorstand",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.buttonText(text: String): HTMLElement =
        assertNotNull(allOf("button").firstOrNull { it.textContent?.trim() == text }, "no button '$text'")

    private fun HTMLElement.assertIcon(
        text: String,
        icon: String,
    ) {
        val button = buttonText(text)
        val i = assertNotNull(button.querySelector("[aria-hidden='true']") as? HTMLElement, "'$text' has no hidden icon")
        assertTrue(i.classList.contains(icon), "'$text': expected $icon, got ${i.className}")
    }

    private fun HTMLElement.assertNoIcon(text: String) {
        assertNull(buttonText(text).querySelector("i, [aria-hidden='true']"), "'$text' is a domain verb without a standard icon")
    }

    private fun destination(
        id: String,
        enabled: Boolean,
    ) = ConferenceStreamDestinationDto(
        id = id,
        label = "Kanal $id",
        platform = ConferenceStreamPlatform.GENERIC_RTMP,
        rtmpUrl = "rtmps://ingest.example.org/live",
        streamKeyMask = "********",
        streamKeySetAt = LocalDateTime(2026, 1, 1, 0, 0),
        createdByDisplayName = "Ada Admin",
        enabled = enabled,
    )

    @Test
    fun auction_adminButtons(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) {
                mountedForm("r49-icons-auction") { root, element ->
                    renderAuctionScreen(root)
                    awaitUntil("the admin section") { element().textContent.orEmpty().contains("Wertobergrenze") }
                    element().assertIcon("Auktion deaktivieren", "fa-ban")
                    element().assertIcon("Obergrenze speichern", "fa-floppy-disk")
                    element().assertNoIcon("Auktion aktivieren …")
                    element().assertIcon("Neues Angebot", "fa-plus")
                }
            }
        }

    @Test
    fun streamDestinations_deactivateHasTheIcon_activateDoesNot(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val list = routeOf { rpcService<IConferenceStreamingService>().listDestinations() }
            withFetchStub(
                respond = { request ->
                    if (request.isRpc && request.rpcRoute == list) {
                        request.answerWith(
                            jsonOf(
                                ListSerializer(ConferenceStreamDestinationDto.serializer()),
                                listOf(destination("a", enabled = true), destination("b", enabled = false)),
                            ),
                        )
                    } else {
                        request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("r49-icons-stream") { root, element ->
                    renderConferenceStreamDestinationsScreen(root)
                    awaitUntil("both rows") { element().textContent.orEmpty().contains("Kanal b") }
                    element().assertIcon("Deaktivieren", "fa-ban")
                    element().assertNoIcon("Aktivieren")
                    element().assertIcon("Neues Stream-Ziel", "fa-plus")
                }
            }
        }

    @Test
    fun documents_changeVisibilityUsesTheAccessIcon_inTheFolderAndTheDocumentRow(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val listFolders = routeOf { rpcService<IDocumentService>().listFolders() }
            val listDocuments = routeOf { rpcService<IDocumentService>().listDocuments("x") }
            val folder =
                DocumentFolderDto(
                    id = "fa",
                    name = "Satzungen",
                    parentFolderId = null,
                    documentCount = 1,
                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                )
            val document =
                DocumentDto(
                    id = "d1",
                    folderId = "fa",
                    title = "Satzung 2026",
                    currentVersionId = null,
                    createdBy = "caller-1",
                    createdByDisplayName = "Vera Vorstand",
                    createdAt = LocalDateTime(2026, 1, 1, 10, 0),
                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                    isDeleted = false,
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listFolders ->
                            request.answerWith(
                                jsonOf(ListSerializer(DocumentFolderDto.serializer()), listOf(folder)),
                            )
                        request.rpcRoute == listDocuments ->
                            request.answerWith(
                                jsonOf(ListSerializer(DocumentDto.serializer()), listOf(document)),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) {
                mountedForm("r49-icons-documents") { root, element ->
                    renderDocumentsScreen(root)
                    awaitUntil("the folder row") { element().textContent.orEmpty().contains("Satzungen") }

                    fun accessButtons() = element().allOf("button").filter { it.getAttribute("aria-label") == "Sichtbarkeit ändern" }
                    assertEquals(1, accessButtons().size)
                    assertNotNull(accessButtons().single().querySelector("i.fa-user-lock"), "the folder row uses the access icon")
                    element().allOf("a").first { it.textContent?.trim() == "Satzungen" }.click()
                    awaitUntil("the document row") { accessButtons().size == 2 }
                    assertTrue(accessButtons().all { it.querySelector("i.fa-user-lock") != null }, "both rows use the access icon")
                    element().assertIcon("Neuer Ordner", "fa-plus")
                }
            }
        }

    @Test
    fun dunning_deactivateButtonsHaveTheIcon_activateDoesNot(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) {
                mountedForm("r49-icons-dunning") { root, element ->
                    renderDunningSettingsScreen(root)
                    awaitUntil(
                        "the status section",
                    ) { element().allOf("button").any { it.textContent?.trim() == "Mahnwesen deaktivieren" } }
                    element().assertIcon("Mahnwesen deaktivieren", "fa-ban")
                    element().assertNoIcon("Mahnwesen aktivieren …")
                }
                mountedForm("r49-icons-receivables") { root, element ->
                    renderReceivableDunningSettingsScreen(root)
                    awaitUntil("the status section") {
                        element().allOf("button").any { it.textContent?.trim() == "Forderungs-Mahnwesen deaktivieren" }
                    }
                    element().assertIcon("Forderungs-Mahnwesen deaktivieren", "fa-ban")
                    element().assertNoIcon("Forderungs-Mahnwesen aktivieren")
                }
            }
        }
}
