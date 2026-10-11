package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ArticleImportImageRowDto
import network.lapis.cloud.shared.domain.ArticleImportImageStatus
import network.lapis.cloud.shared.domain.ArticleImportImagesPreviewDto
import network.lapis.cloud.shared.domain.ArticleImportImagesResultDto
import network.lapis.cloud.shared.domain.ArticleImportPreviewDto
import network.lapis.cloud.shared.domain.ArticleImportPreviewRowDto
import network.lapis.cloud.shared.domain.ArticleImportResultDto
import network.lapis.cloud.shared.domain.ArticleImportRowStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IArticleImportService
import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.files.File
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

    // ---- V1.9.106: image ZIP ----------------------------------------------------------------------------------------------------

    private fun chooseZip(
        element: HTMLElement,
        size: Int = 10,
    ) {
        val input = element.querySelector("input[type=file]") as HTMLInputElement
        val transfer = js("new DataTransfer()")
        transfer.items.add(File(arrayOf<dynamic>(ArrayBuffer(size)), "bilder.zip"))
        input.asDynamic().files = transfer.files
        input.dispatchEvent(Event("change"))
    }

    private fun imageRow(
        slug: String,
        status: ArticleImportImageStatus,
        w: Int? = null,
        h: Int? = null,
    ) = ArticleImportImageRowDto(slug = slug, status = status, width = w, height = h)

    private fun rowFor(
        index: Int,
        slug: String,
        status: ArticleImportRowStatus = ArticleImportRowStatus.CREATE,
    ) = ArticleImportPreviewRowDto(
        index = index,
        slug = slug,
        title = "Artikel $slug",
        publishedAt = "2024-05-05T00:00",
        byline = "PdV",
        status = status,
        reasons = emptyList(),
        hints = listOf("Ohne Titelbild – nach dem Import einzeln hochladen."),
    )

    @Test
    fun theScreenOffersAnOptionalZipField_withTheNamingHint(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) { _ ->
                mountedForm("v106-article-import-zip-field") { root, element ->
                    renderArticleImportScreen(root)
                    awaitUntil("the screen is built") { element().text().contains("Bilder (ZIP, optional)") }
                    val file = element().querySelector("input[type=file]") as HTMLInputElement
                    assertEquals(".zip", file.getAttribute("accept"))
                    assertTrue(element().text().contains("Dateiname = Slug, z. B. mein-artikel.jpg."))
                    assertTrue(element().text().contains("höchstens 100 Bilder, ohne Ordner"))
                }
            }
        }

    @Test
    fun withAZip_theTableHasAnImageColumnWithAllStatusesAsWords_andTheListsBelow(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val previewRoute = routeOf { rpcService<IArticleImportService>().previewArticleImport("[]", emptyList()) }
            val answer =
                preview(
                    rowFor(0, "ok"),
                    rowFor(1, "klein"),
                    rowFor(2, "kaputt"),
                    rowFor(3, "fehlt"),
                    rowFor(4, "doppelt"),
                    rowFor(5, "vorhanden", ArticleImportRowStatus.SKIP_SLUG_EXISTS),
                    rowFor(6, "nichts", ArticleImportRowStatus.SKIP_SLUG_EXISTS),
                )
            val images =
                ArticleImportImagesPreviewDto(
                    rows =
                        listOf(
                            imageRow("ok", ArticleImportImageStatus.OK, 1200, 800),
                            imageRow("klein", ArticleImportImageStatus.TOO_SMALL, 250, 200),
                            imageRow("kaputt", ArticleImportImageStatus.INVALID_FORMAT),
                            imageRow("fehlt", ArticleImportImageStatus.MISSING),
                            imageRow("doppelt", ArticleImportImageStatus.DUPLICATE),
                            imageRow("vorhanden", ArticleImportImageStatus.IGNORED_EXISTING_ARTICLE),
                        ),
                    unmatchedFiles = listOf("zuviel.jpg", "weiteres.png"),
                    unmatchedCount = 2,
                    ignoredSystemFiles = 3,
                )
            withFetchStub(
                respond = { request ->
                    when {
                        request.isRpc && request.rpcRoute == previewRoute ->
                            request.answerWith(jsonOf(ArticleImportPreviewDto.serializer(), answer))
                        request.isRpc -> request.answerWith("[]")
                        request.url.endsWith("/api/articles/import/images/preview") ->
                            StubResponse(text = jsonOf(ArticleImportImagesPreviewDto.serializer(), images))
                        else -> StubResponse()
                    }
                },
            ) { calls ->
                mountedForm("v106-article-import-zip-preview") { root, element ->
                    renderArticleImportScreen(root)
                    awaitUntil("the screen is built") { element().text().contains("Frühere Artikel importieren") }
                    element().typeInto("Import-Daten (JSON)", "[]")
                    chooseZip(element())
                    element().buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the preview is shown") { element().text().contains("5 werden angelegt") }
                    val text = element().text()
                    assertTrue(text.contains("Bild ok (1200 × 800)"))
                    assertTrue(text.contains("Ungeeignete Größe (250 × 200; mindestens 300 px breit und 150 px hoch"))
                    assertTrue(text.contains("Seitenverhältnis zwischen 1:2 und 4:1)"))
                    assertTrue(text.contains("Ungültiges Format"))
                    assertTrue(text.contains("Fehlt"))
                    assertTrue(text.contains("Doppelt im ZIP"))
                    assertTrue(text.contains("Wird ignoriert (Artikel existiert)"))
                    assertTrue(text.contains("Nicht nötig (Artikel existiert)"))
                    assertTrue(text.indexOf("Status") < text.indexOf("Bild"), "the image column follows the status")
                    assertFalse(text.contains("Ohne Titelbild"), "with a ZIP the no-cover reminder is gone")
                    assertTrue(text.contains("Bilder ohne passenden Artikel: 2"))
                    assertTrue(text.contains("Systemdateien ignoriert: 3"))
                    val sent = calls.single { it.url.endsWith("/api/articles/import/images/preview") }
                    assertEquals("POST", sent.method)
                    assertTrue(sent.body.contains("<file:bilder.zip:"), "the ZIP goes along")
                    // the slugs of every importable row (create AND skip) go to the image preview
                    assertTrue(sent.body.contains("vorhanden") && sent.body.contains("klein"))
                }
            }
        }

    @Test
    fun withoutAZip_everyRowSaysNoImagePackage_andTheCoverHintStays(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val previewRoute = routeOf { rpcService<IArticleImportService>().previewArticleImport("[]", emptyList()) }
            val answer = preview(rowFor(0, "a"))
            withFetchStub(
                respond = { request ->
                    when {
                        request.isRpc && request.rpcRoute == previewRoute ->
                            request.answerWith(jsonOf(ArticleImportPreviewDto.serializer(), answer))
                        request.isRpc -> request.answerWith("[]")
                        else -> StubResponse()
                    }
                },
            ) { calls ->
                mountedForm("v106-article-import-no-zip") { root, element ->
                    renderArticleImportScreen(root)
                    awaitUntil("the screen is built") { element().text().contains("Frühere Artikel importieren") }
                    element().typeInto("Import-Daten (JSON)", "[]")
                    element().buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the preview is shown") { element().text().contains("1 werden angelegt") }
                    assertTrue(element().text().contains("Kein Bild-Paket"))
                    assertTrue(element().text().contains("Ohne Titelbild – nach dem Import einzeln hochladen."))
                    assertEquals(0, calls.count { it.url.contains("/images/") }, "no image request without a ZIP")
                }
            }
        }

    @Test
    fun aZipOverTwentyFiveMiB_isRefusedBeforeAnyRequest(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            withFetchStub(respond = { request -> if (request.isRpc) request.answerWith("[]") else StubResponse() }) { calls ->
                mountedForm("v106-article-import-zip-too-big") { root, element ->
                    renderArticleImportScreen(root)
                    awaitUntil("the screen is built") { element().text().contains("Frühere Artikel importieren") }
                    element().typeInto("Import-Daten (JSON)", "[]")
                    chooseZip(element(), size = 26 * 1024 * 1024)
                    element().buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the message") { element().text().contains("Das ZIP ist zu groß (maximal 25 MiB).") }
                    assertEquals(0, calls.size, "neither the preview nor the ZIP is sent")
                }
            }
        }

    @Test
    fun aRefusedZip_showsTheFixedSentenceAndStaysOnTheEditingView(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val previewRoute = routeOf { rpcService<IArticleImportService>().previewArticleImport("[]", emptyList()) }
            val answer = preview(rowFor(0, "a"))
            withFetchStub(
                respond = { request ->
                    when {
                        request.isRpc && request.rpcRoute == previewRoute ->
                            request.answerWith(jsonOf(ArticleImportPreviewDto.serializer(), answer))
                        request.isRpc -> request.answerWith("[]")
                        request.url.contains("/images/preview") -> StubResponse(422, "Bilder müssen direkt im ZIP liegen, ohne Ordner.")
                        else -> StubResponse()
                    }
                },
            ) { _ ->
                mountedForm("v106-article-import-zip-refused") { root, element ->
                    renderArticleImportScreen(root)
                    awaitUntil("the screen is built") { element().text().contains("Frühere Artikel importieren") }
                    element().typeInto("Import-Daten (JSON)", "[]")
                    chooseZip(element())
                    element().buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the message") { element().text().contains("Bilder müssen direkt im ZIP liegen, ohne Ordner.") }
                    assertFalse(element().text().contains("werden angelegt"), "no half preview")
                    assertFalse((element().querySelector("textarea") as HTMLTextAreaElement).disabled, "the form stays editable")
                }
            }
        }

    @Test
    fun aCommitWithAZip_sendsTheImagesOfThisRun_reportsBothParts_andPointsToTheBulkApproval(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val previewRoute = routeOf { rpcService<IArticleImportService>().previewArticleImport("[]", emptyList()) }
            val commitRoute = routeOf { rpcService<IArticleImportService>().commitArticleImport("[]", "x") }
            val answer = preview(rowFor(0, "a"))
            val batch = "0b0b0b0b-0000-4000-8000-000000000001"
            withFetchStub(
                respond = { request ->
                    when {
                        request.isRpc && request.rpcRoute == previewRoute ->
                            request.answerWith(jsonOf(ArticleImportPreviewDto.serializer(), answer))
                        request.isRpc && request.rpcRoute == commitRoute ->
                            request.answerWith(
                                jsonOf(
                                    ArticleImportResultDto.serializer(),
                                    ArticleImportResultDto(1, 0, emptyList(), importBatchId = batch),
                                ),
                            )
                        request.isRpc -> request.answerWith("[]")
                        request.url.endsWith("/images/preview") ->
                            StubResponse(
                                text =
                                    jsonOf(
                                        ArticleImportImagesPreviewDto.serializer(),
                                        ArticleImportImagesPreviewDto(
                                            listOf(imageRow("a", ArticleImportImageStatus.OK, 400, 300)),
                                            emptyList(),
                                            0,
                                            0,
                                        ),
                                    ),
                            )
                        request.url.endsWith("/images/commit") ->
                            StubResponse(
                                text = jsonOf(ArticleImportImagesResultDto.serializer(), ArticleImportImagesResultDto(2, 1, emptyList())),
                            )
                        else -> StubResponse()
                    }
                },
            ) { calls ->
                mountedForm("v106-article-import-zip-commit") { root, element ->
                    renderArticleImportScreen(root)
                    awaitUntil("the screen is built") { element().text().contains("Frühere Artikel importieren") }
                    element().typeInto("Import-Daten (JSON)", "[]")
                    chooseZip(element())
                    element().buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the preview is shown") { element().text().contains("1 werden angelegt") }
                    element().buttonNamed("1 Artikel importieren").click()
                    awaitUntil("both parts are reported") { element().text().contains("Bilder: 2 übernommen, 1 abgelehnt.") }
                    assertTrue(element().text().contains("1 Artikel angelegt, 0 übersprungen."))
                    assertTrue(element().text().contains("im Reiter „Freigabe“ gesammelt freigeben"))
                    val sent = calls.single { it.url.endsWith("/images/commit") }
                    assertTrue(sent.body.contains(batch), "the batch id of the import run goes along")
                }
            }
        }

    @Test
    fun aFailingImageCommit_keepsTheArticles_andOffersAnIdempotentRetry(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val previewRoute = routeOf { rpcService<IArticleImportService>().previewArticleImport("[]", emptyList()) }
            val commitRoute = routeOf { rpcService<IArticleImportService>().commitArticleImport("[]", "x") }
            val answer = preview(rowFor(0, "a"))
            val batch = "0b0b0b0b-0000-4000-8000-000000000002"
            var imageCommits = 0
            withFetchStub(
                respond = { request ->
                    when {
                        request.isRpc && request.rpcRoute == previewRoute ->
                            request.answerWith(jsonOf(ArticleImportPreviewDto.serializer(), answer))
                        request.isRpc && request.rpcRoute == commitRoute ->
                            request.answerWith(
                                jsonOf(
                                    ArticleImportResultDto.serializer(),
                                    ArticleImportResultDto(1, 0, emptyList(), importBatchId = batch),
                                ),
                            )
                        request.isRpc -> request.answerWith("[]")
                        request.url.endsWith("/images/preview") ->
                            StubResponse(
                                text =
                                    jsonOf(
                                        ArticleImportImagesPreviewDto.serializer(),
                                        ArticleImportImagesPreviewDto(
                                            listOf(imageRow("a", ArticleImportImageStatus.OK, 400, 300)),
                                            emptyList(),
                                            0,
                                            0,
                                        ),
                                    ),
                            )
                        request.url.endsWith("/images/commit") -> {
                            imageCommits++
                            if (imageCommits == 1) {
                                StubResponse(429, "Zu viele Anfragen")
                            } else {
                                StubResponse(
                                    text =
                                        jsonOf(
                                            ArticleImportImagesResultDto.serializer(),
                                            ArticleImportImagesResultDto(1, 0, emptyList()),
                                        ),
                                )
                            }
                        }
                        else -> StubResponse()
                    }
                },
            ) { _ ->
                mountedForm("v106-article-import-zip-retry") { root, element ->
                    renderArticleImportScreen(root)
                    awaitUntil("the screen is built") { element().text().contains("Frühere Artikel importieren") }
                    element().typeInto("Import-Daten (JSON)", "[]")
                    chooseZip(element())
                    element().buttonNamed("Vorschau prüfen").click()
                    awaitUntil("the preview is shown") { element().text().contains("1 werden angelegt") }
                    element().buttonNamed("1 Artikel importieren").click()
                    awaitUntil("the failure is reported") {
                        element().text().contains("Die Artikel wurden angelegt, die Bilder konnten nicht übernommen werden.")
                    }
                    assertTrue(element().text().contains("1 Artikel angelegt, 0 übersprungen."), "the import itself stands")
                    element().buttonNamed("Bilder erneut übernehmen").click()
                    awaitUntil("the retry worked") { element().text().contains("Bilder: 1 übernommen, 0 abgelehnt.") }
                    assertEquals(2, imageCommits)
                }
            }
        }
}
