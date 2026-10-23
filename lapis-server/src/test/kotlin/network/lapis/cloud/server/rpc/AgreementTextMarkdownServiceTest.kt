package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.agreementtexts.AgreementTextHash
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AgreementTextDraftTable
import network.lapis.cloud.server.db.generated.AgreementTextVersionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextRules
import network.lapis.cloud.shared.domain.AgreementTextVersionDto
import network.lapis.cloud.shared.domain.AgreementTextVersionSummaryDto
import network.lapis.cloud.shared.rpc.AgreementTextDraftChangedException
import network.lapis.cloud.shared.rpc.AgreementTextInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextLayoutInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextVersionLabelTakenException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.RateLimitedException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes

private const val ADMIN = "00000000-0000-0000-0000-000000000001"
private const val BOARD = "00000000-0000-0000-0000-000000000002"
private val json = Json

private const val NOTICE_PLAIN_SUMMARY =
    "Wichtiger Hinweis\n\nErster Kernpunkt.\n\nZweiter Kernpunkt.\n\nDritter Kernpunkt.\n\nAlles Weitere steht im Volltext."

/**
 * V1.9.196 -- [AgreementTextAdminService] and the Markdown format: a saved draft is MARKDOWN, publishing carries the format, nothing can change the
 * format of a published version, the live preview is ADMIN-only / rate limited / bounded, and the block-markup rule protects the plain-text summary
 * of the short and notice kinds.
 */
class AgreementTextMarkdownServiceTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach { AgreementTextTestState.reset() }
        afterSpec { AgreementTextTestState.reset() }

        val previewLimiter = FederationInboxRateLimiter(maxRequests = 3, window = 10.minutes)

        fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            _,
                            ->
                            call.respondText("Unauthenticated", status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, _ -> call.respondText("Forbidden", status = HttpStatusCode.Forbidden) }
                        exception<BadRequestException> { call, _ -> call.respondText("BadRequest", status = HttpStatusCode.BadRequest) }
                        exception<RateLimitedException> {
                            call,
                            _,
                            ->
                            call.respondText("RateLimited", status = HttpStatusCode.TooManyRequests)
                        }
                        exception<AgreementTextInvalidException> {
                            call,
                            _,
                            ->
                            call.respondText("Invalid", status = HttpStatusCode.Conflict)
                        }
                        exception<AgreementTextLayoutInvalidException> {
                            call,
                            _,
                            ->
                            call.respondText("Layout", status = HttpStatusCode.Conflict)
                        }
                        exception<AgreementTextVersionLabelTakenException> {
                            call,
                            _,
                            ->
                            call.respondText("LabelTaken", status = HttpStatusCode.Conflict)
                        }
                        exception<AgreementTextDraftChangedException> {
                            call,
                            _,
                            ->
                            call.respondText("DraftChanged", status = HttpStatusCode.Conflict)
                        }
                    }
                    routing { registerMarkdownTestRoutes(previewLimiter) }
                }
                block()
            }

        val today by lazy { OrganizationTimeZone.today() }

        suspend fun HttpClient.save(
            kind: AgreementTextKind,
            body: String,
            label: String = "2026-10-22.v1",
        ): HttpResponse =
            post("/t/save?kind=${kind.name}&label=$label&validFrom=$today") {
                header("X-Member-Id", ADMIN)
                setBody(body)
            }

        suspend fun HttpClient.publish(
            kind: AgreementTextKind,
            sha: String,
        ): HttpResponse = post("/t/publish?kind=${kind.name}&sha=$sha") { header("X-Member-Id", ADMIN) }

        test("a saved draft is MARKDOWN, publishing carries the format into the version, and getVersion returns the rendered HTML") {
            withApp {
                val draft =
                    json.decodeFromString<AgreementTextDraftDto>(
                        client.save(AgreementTextKind.MEMBERSHIP, "# Vertrag\n\n- eins\n- zwei").bodyAsText(),
                    )
                draft.format shouldBe AgreementTextFormat.MARKDOWN
                transaction { AgreementTextTestState.draftRows().single()[AgreementTextDraftTable.bodyFormat] } shouldBe "markdown"

                val summary =
                    json.decodeFromString<AgreementTextVersionSummaryDto>(
                        client.publish(AgreementTextKind.MEMBERSHIP, draft.sha256).bodyAsText(),
                    )
                summary.format shouldBe AgreementTextFormat.MARKDOWN
                AgreementTextTestState.versionRows().single()[AgreementTextVersionTable.bodyFormat] shouldBe "markdown"

                val version =
                    json.decodeFromString<AgreementTextVersionDto>(
                        client.get("/t/version?kind=MEMBERSHIP&label=2026-10-22.v1") { header("X-Member-Id", ADMIN) }.bodyAsText(),
                    )
                version.body shouldBe "# Vertrag\n\n- eins\n- zwei"
                version.renderedHtml!! shouldContain "<h2>Vertrag</h2>"
                version.renderedHtml!! shouldContain "<li>eins</li>"
                // the hash is over label and SOURCE body, the format is not part of it
                summary.sha256 shouldBe AgreementTextHash.of(versionLabel = "2026-10-22.v1", body = "# Vertrag\n\n- eins\n- zwei")
            }
        }

        test("a legacy PLAIN version has no rendered HTML, its body is untouched and it keeps its hash") {
            val sha =
                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.FRIEND,
                    label = "alt.v1",
                    body = "- kein *Markdown*\n- sondern Text",
                )
            withApp {
                val version =
                    json.decodeFromString<AgreementTextVersionDto>(
                        client.get("/t/version?kind=FRIEND&label=alt.v1") { header("X-Member-Id", ADMIN) }.bodyAsText(),
                    )
                version.summary.format shouldBe AgreementTextFormat.PLAIN
                version.renderedHtml shouldBe null
                version.body shouldBe "- kein *Markdown*\n- sondern Text"
                version.summary.sha256 shouldBe sha
            }
        }

        test("publishing the next version never changes the format of an older one (a plain row stays plain)") {
            AgreementTextTestState.publishDirect(
                kind = AgreementTextKind.MEMBERSHIP,
                label = "alt.v1",
                body = "Alt",
                validFrom = LocalDate(2026, 1, 1),
            )
            val before = AgreementTextTestState.versionRows().single()[AgreementTextVersionTable.bodyFormat]
            before shouldBe "plain"
            withApp {
                val draft =
                    json.decodeFromString<AgreementTextDraftDto>(
                        client.save(AgreementTextKind.MEMBERSHIP, "Neu **fett**").bodyAsText(),
                    )
                client.publish(AgreementTextKind.MEMBERSHIP, draft.sha256).status shouldBe HttpStatusCode.OK
                val formats =
                    AgreementTextTestState.versionRows().associate {
                        it[AgreementTextVersionTable.versionLabel] to
                            it[AgreementTextVersionTable.bodyFormat]
                    }
                formats shouldBe mapOf("alt.v1" to "plain", "2026-10-22.v1" to "markdown")
            }
        }

        test("the preview renders Markdown to sanitized HTML for an ADMIN, and refuses everyone else") {
            withApp {
                val ok =
                    client.post("/t/preview?kind=MEMBERSHIP") {
                        header("X-Member-Id", ADMIN)
                        setBody("**fett** <script>alert(1)</script>")
                    }
                ok.status shouldBe HttpStatusCode.OK
                ok.bodyAsText() shouldContain "<strong>fett</strong>"
                ok.bodyAsText() shouldNotContain "<script"
                client
                    .post("/t/preview?kind=MEMBERSHIP") {
                        header("X-Member-Id", BOARD)
                        setBody("x")
                    }.status shouldBe
                    HttpStatusCode.Forbidden
                client.post("/t/preview?kind=MEMBERSHIP") { setBody("x") }.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("the preview refuses an over-long text, a forbidden character and absurd nesting, and is rate limited per member") {
            withApp {
                client
                    .post("/t/preview?kind=MEMBERSHIP") {
                        header("X-Member-Id", ADMIN)
                        setBody("a".repeat(AgreementTextRules.MAX_CHARS + 1))
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post("/t/preview?kind=MEMBERSHIP") {
                        header("X-Member-Id", ADMIN)
                        setBody("Bidi ‮ gedreht")
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post("/t/preview?kind=MEMBERSHIP") {
                        header("X-Member-Id", ADMIN)
                        setBody(">".repeat(50_000))
                    }.status shouldBe HttpStatusCode.BadRequest
                // the shared limiter of this spec allows three previews, the fourth is RateLimited
                val statuses =
                    (1..4).map {
                        client
                            .post("/t/preview?kind=MEMBERSHIP&limiter=strict") {
                                header("X-Member-Id", ADMIN)
                                setBody("x")
                            }.status
                    }
                statuses shouldBe listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.TooManyRequests)
            }
        }

        test("an absurdly nested text cannot be saved (it could not be rendered when served)") {
            withApp {
                client.save(AgreementTextKind.MEMBERSHIP, ">".repeat(50_000)).bodyAsText() shouldBe "Invalid"
                AgreementTextTestState.draftRows() shouldBe emptyList()
            }
        }

        test(
            "block markup in the summary paragraphs is refused for the notice kinds, but a list in the FULL text and in MEMBERSHIP is fine",
        ) {
            withApp {
                for (kind in listOf(AgreementTextKind.ENC_CHURCH, AgreementTextKind.ENC_ASSEM, AgreementTextKind.EVENT_REC)) {
                    client.save(kind, NOTICE_PLAIN_SUMMARY).status shouldBe HttpStatusCode.OK
                    client.save(kind, "# Überschrift\n\nA.\n\nB.\n\nC.\n\nRest").bodyAsText() shouldBe "Layout"
                    client.save(kind, "Titel\n\n- A\n- B\n\nB.\n\nC.\n\nRest").bodyAsText() shouldBe "Layout"
                    // the rule checks every LINE of paragraphs 1-4, not only their first line
                    client.save(kind, "Titel\n\nA zeile\n1. nummeriert\n\nB.\n\nC.\n\nRest").bodyAsText() shouldBe "Layout"
                    client.save(kind, "Titel\n\n> Zitat\n\nB.\n\nC.\n\nRest").bodyAsText() shouldBe "Layout"
                    client.save(kind, "Titel\n\n```\ncode\n```\n\nB.\n\nC.\n\nRest").bodyAsText() shouldBe "Layout"
                    // from paragraph 5 on (the full text) everything is allowed
                    client.save(kind, "$NOTICE_PLAIN_SUMMARY\n\n## Einzelheiten\n\n- eins\n- zwei").status shouldBe HttpStatusCode.OK
                    AgreementTextTestState.reset()
                }
                client.save(AgreementTextKind.MEMBERSHIP, "# Vertrag\n\n- eins\n- zwei\n\n> Zitat").status shouldBe HttpStatusCode.OK
            }
        }

        test("a legacy PLAIN draft cannot be published (it must be saved again as Markdown first)") {
            withApp {
                val draft =
                    json.decodeFromString<AgreementTextDraftDto>(
                        client.save(AgreementTextKind.MEMBERSHIP, "# Vertrag\n\nText").bodyAsText(),
                    )
                transaction {
                    AgreementTextDraftTable.update({ AgreementTextDraftTable.kind eq AgreementTextKind.MEMBERSHIP.name }) {
                        it[bodyFormat] = AgreementTextFormat.PLAIN.dbValue
                    }
                }
                client.publish(AgreementTextKind.MEMBERSHIP, draft.sha256).bodyAsText() shouldBe "DraftChanged"
                AgreementTextTestState.versionRows() shouldBe emptyList()
                // saving it again turns it into MARKDOWN and publishing works
                val again =
                    json.decodeFromString<AgreementTextDraftDto>(
                        client.save(AgreementTextKind.MEMBERSHIP, "# Vertrag\n\nText").bodyAsText(),
                    )
                client.publish(AgreementTextKind.MEMBERSHIP, again.sha256).status shouldBe HttpStatusCode.OK
            }
        }

        test("the two recording texts are short dialog texts: no block markup anywhere in them") {
            withApp {
                for (kind in listOf(AgreementTextKind.REC_PULPIT, AgreementTextKind.REC_ROOM)) {
                    client.save(kind, "Bitte stimmen Sie zu\n===\nWeitere Angaben").bodyAsText() shouldBe "Layout"
                    client.save(kind, "Text\n\n***\n\nMehr").bodyAsText() shouldBe "Layout"
                    client.save(kind, "Text\n\n    eingerückter Code").bodyAsText() shouldBe "Layout"

                    client.save(kind, "Die Kanzel wird **aufgezeichnet**.").status shouldBe HttpStatusCode.OK
                    client.save(kind, "Hinweis\n\n- eins").bodyAsText() shouldBe "Layout"
                    client.save(kind, "## Titel\n\nText").bodyAsText() shouldBe "Layout"
                }
            }
        }

        test("the draft input has no format field: the client can never choose the format") {
            val names =
                network.lapis.cloud.shared.domain.AgreementTextDraftInput.serializer().descriptor.let { d ->
                    (
                        0 until
                            d.elementsCount
                    ).map { d.getElementName(it) }
                }
            names shouldBe listOf("kind", "versionLabel", "validFrom", "body")
        }
    })

private fun Route.registerMarkdownTestRoutes(limiter: FederationInboxRateLimiter) {
    fun service(call: ApplicationCall): AgreementTextAdminService {
        // "limiter=strict" shares ONE limiter with a budget of three; everything else gets a fresh, generous budget per request.
        val strict = call.request.queryParameters["limiter"] == "strict"
        return AgreementTextAdminService(call = call, previewRateLimiter = if (strict) limiter else FederationInboxRateLimiter())
    }

    fun kindOf(call: ApplicationCall) = AgreementTextKind.valueOf(call.request.queryParameters["kind"]!!)
    post("/t/save") {
        val q = call.request.queryParameters
        val saved =
            service(call).saveDraft(
                network.lapis.cloud.shared.domain.AgreementTextDraftInput(
                    kind = kindOf(call),
                    versionLabel = q["label"]!!,
                    validFrom = LocalDate.parse(q["validFrom"]!!),
                    body = call.receiveText(),
                ),
            )
        call.respondText(Json.encodeToString(AgreementTextDraftDto.serializer(), saved))
    }
    post("/t/publish") {
        val summary = service(call).publishDraft(kind = kindOf(call), expectedDraftSha256 = call.request.queryParameters["sha"]!!)
        call.respondText(Json.encodeToString(AgreementTextVersionSummaryDto.serializer(), summary))
    }
    get("/t/version") {
        val dto = service(call).getVersion(kind = kindOf(call), versionLabel = call.request.queryParameters["label"]!!)
        call.respondText(Json.encodeToString(AgreementTextVersionDto.serializer(), dto))
    }
    post("/t/preview") {
        call.respondText(service(call).previewAgreementText(kind = kindOf(call), body = call.receiveText()))
    }
}
