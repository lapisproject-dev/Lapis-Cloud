package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleImportPreviewDto
import network.lapis.cloud.shared.domain.ArticleImportPreviewRowDto
import network.lapis.cloud.shared.domain.ArticleImportResultDto
import network.lapis.cloud.shared.domain.ArticleImportRowStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IArticleImportService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.105 -- the admin article import screen: a preview with error rows first (status as symbol AND word, the byline and the date shown),
 * the organization names go along with the preview request, the confirm button stays disabled while there is an error, the text field is locked
 * in the preview, and the commit sends the text and hash of the preview unchanged. Plus the pure helpers.
 */
class ArticleImportScreenDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun row(
        index: Int,
        status: ArticleImportRowStatus,
        title: String,
        reasons: List<String> = emptyList(),
        hints: List<String> = emptyList(),
        byline: String? = "PdV",
    ) = ArticleImportPreviewRowDto(
        index = index,
        slug = "slug-$index",
        title = title,
        publishedAt = if (status == ArticleImportRowStatus.ERROR) null else "2024-05-05T00:00",
        byline = byline,
        status = status,
        reasons = reasons,
        hints = hints,
    )

    private fun preview(vararg rows: ArticleImportPreviewRowDto) =
        ArticleImportPreviewDto(
            rows = rows.toList(),
            createCount = rows.count { it.status == ArticleImportRowStatus.CREATE },
            skipCount = rows.count { it.status == ArticleImportRowStatus.SKIP_SLUG_EXISTS },
            errorCount = rows.count { it.status == ArticleImportRowStatus.ERROR },
            payloadSha256 = "cd".repeat(32),
            timeZoneId = "Europe/Berlin",
        )

    private fun HTMLElement.text(): String = textContent.orEmpty()

    @Test
    fun organizationLabels_areSplitTrimmedAndDeduplicated() {
        assertEquals(listOf("PdV", "Partei der Vernunft"), parseOrganizationLabels(" PdV , Partei der Vernunft,, PdV;"))
        assertEquals(emptyList(), parseOrganizationLabels("  ,  "))
        assertEquals(listOf("a", "b"), parseOrganizationLabels("a\nb"))
    }

    @Test
    fun theDateOfAMidnightValue_isShownWithoutTheTime() {
        assertEquals("2024-05-05", articleImportDateText("2024-05-05T00:00"))
        assertEquals("2024-05-05 19:30", articleImportDateText("2024-05-05T19:30"))
        assertEquals("", articleImportDateText(null))
    }

    @Test
    fun theKnownServerSentences_areTranslatedAndTheRestStaysText() {
        assertTrue(translateArticleImportSentence("Enthält HTML – wird als Text angezeigt.") is TrArg)
        assertEquals("Eintrag 3: Der Slug ist zu lang.", translateArticleImportSentence("Eintrag 3: Der Slug ist zu lang."))
    }

    @Test
    fun aPreviewWithAnError_listsErrorsFirst_sendsTheOrganizationNames_locksTheText_andBlocksTheConfirmation(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val previewRoute = routeOf { rpcService<IArticleImportService>().previewArticleImport("[]", emptyList()) }
            val commitRoute = routeOf { rpcService<IArticleImportService>().commitArticleImport("[]", "x") }
            val answer =
                preview(
                    row(
                        1,
                        ArticleImportRowStatus.ERROR,
                        "Kaputter Artikel",
                        reasons = listOf("Eintrag 2: Der Slug ist zu lang."),
                        byline = null,
                    ),
                    row(
                        0,
                        ArticleImportRowStatus.CREATE,
                        "Guter Artikel",
                        hints = listOf("Autorenzeile ist kein Organisationsname – Einwilligung klären."),
                    ),
                    row(
                        2,
                        ArticleImportRowStatus.SKIP_SLUG_EXISTS,
                        "Schon da",
                        hints = listOf("Slug existiert bereits, wird nicht verändert."),
                    ),
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == previewRoute -> request.answerWith(jsonOf(ArticleImportPreviewDto.serializer(), answer))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("v105-article-import-preview") { root, element ->
                    renderArticleImportScreen(root)
                    awaitUntil("the screen is built") { element().text().contains("Frühere Artikel importieren") }
                    element().typeInto("Import-Daten (JSON)", "[{\"slug\":\"x\"}]")
                    element().typeInto(
                        "Organisationsnamen als Autorenzeile (durch Komma getrennt, höchstens 5)",
                        "PdV, Partei der Vernunft",
                    )
                    element().buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the preview is shown") { element().text().contains("1 werden angelegt · 1 übersprungen · 1 Fehler") }

                    val sent = calls.singleCall(previewRoute)
                    assertEquals("[{\"slug\":\"x\"}]", sent.rpcParam(0) as String, "the text goes out exactly as typed")
                    assertEquals(listOf("PdV", "Partei der Vernunft"), (sent.rpcParam(1) as Array<String>).toList())

                    val text = element().text()
                    assertTrue(
                        text.contains("Fehler") && text.contains("Wird angelegt") && text.contains("Übersprungen"),
                        "status as words",
                    )
                    assertTrue(text.indexOf("Kaputter Artikel") < text.indexOf("Guter Artikel"), "errors first")
                    assertTrue(text.contains("2024-05-05"), "the date is shown")
                    assertTrue(text.contains("Autorenzeile ist kein Organisationsname"), "the consent hint is shown")
                    assertTrue((element().querySelector("textarea") as HTMLTextAreaElement).disabled, "the text is locked in the preview")
                    val commit = element().buttonNamed("1 Artikel importieren")
                    assertTrue(commit.asDynamic().disabled as Boolean, "an error row blocks the confirmation")
                    commit.click()
                    assertEquals(0, calls.toRoute(commitRoute).size, "nothing is written")
                }
            }
        }

    @Test
    fun aCleanPreview_commitsTheTextAndHashOfThePreviewUnchanged(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val previewRoute = routeOf { rpcService<IArticleImportService>().previewArticleImport("[]", emptyList()) }
            val commitRoute = routeOf { rpcService<IArticleImportService>().commitArticleImport("[]", "x") }
            val answer = preview(row(0, ArticleImportRowStatus.CREATE, "Guter Artikel"))
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == previewRoute -> request.answerWith(jsonOf(ArticleImportPreviewDto.serializer(), answer))
                        request.rpcRoute == commitRoute ->
                            request.answerWith(jsonOf(ArticleImportResultDto.serializer(), ArticleImportResultDto(1, 0, emptyList())))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("v105-article-import-commit") { root, element ->
                    renderArticleImportScreen(root)
                    awaitUntil("the screen is built") { element().text().contains("Frühere Artikel importieren") }
                    element().typeInto("Import-Daten (JSON)", "[ {\"slug\":\"x\"} ]")
                    element().buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the preview is shown") { element().text().contains("1 werden angelegt") }
                    val commit = element().buttonNamed("1 Artikel importieren")
                    assertFalse(commit.asDynamic().disabled as Boolean)
                    commit.click()
                    awaitUntil("the result is shown") { element().text().contains("1 Artikel angelegt, 0 übersprungen.") }
                    val sent = calls.singleCall(commitRoute)
                    assertEquals("[ {\"slug\":\"x\"} ]", sent.rpcParam(0) as String)
                    assertEquals("cd".repeat(32), sent.rpcParam(1) as String)
                }
            }
        }

    @Test
    fun moreThanFiveOrganizationNames_areRefusedBeforeAnyRequest(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) { calls ->
                mountedForm("v105-article-import-labels") { root, element ->
                    renderArticleImportScreen(root)
                    awaitUntil("the screen is built") { element().text().contains("Frühere Artikel importieren") }
                    element().typeInto("Import-Daten (JSON)", "[]")
                    element().typeInto("Organisationsnamen als Autorenzeile (durch Komma getrennt, höchstens 5)", "a,b,c,d,e,f")
                    element().buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the message") { element().text().contains("höchstens 5 Organisationsnamen") }
                    assertEquals(0, calls.rpcCount, "no request for a refused input")
                }
            }
        }
}
