package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
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
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.agreementtexts.MemberFinderConsentDefault
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextDraftInput
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextVersionSummaryDto
import network.lapis.cloud.shared.rpc.AgreementTextInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextVersionLabelTakenException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"

/**
 * V1.9.144 -- the new kind `FINDER` through the agreement-text administration: draft and publication work like for the other kinds, a text or a label
 * equal to the placeholder is refused, and the overview lists the kind as a placeholder WITHOUT any count (a count would be the exact number of members
 * who consented to the finder).
 */
class AgreementTextFinderKindTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach { AgreementTextTestState.reset() }
        afterSpec { AgreementTextTestState.reset() }

        val today by lazy { OrganizationTimeZone.today() }

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
                        exception<AgreementTextInvalidException> {
                            call,
                            _,
                            ->
                            call.respondText("Invalid", status = HttpStatusCode.Conflict)
                        }
                        exception<AgreementTextVersionLabelTakenException> {
                            call,
                            _,
                            ->
                            call.respondText("LabelTaken", status = HttpStatusCode.Conflict)
                        }
                    }
                    routing {
                        fun service(call: ApplicationCall) = AgreementTextAdminService(call = call)
                        post(
                            "/t/overview",
                        ) { call.respondText(Json.encodeToString(AgreementTextOverviewDto.serializer(), service(call).getOverview())) }
                        post("/t/save") {
                            val q = call.request.queryParameters
                            val saved =
                                service(call).saveDraft(
                                    AgreementTextDraftInput(
                                        kind = AgreementTextKind.FINDER,
                                        versionLabel = q["label"]!!,
                                        validFrom = LocalDate.parse(q["validFrom"]!!),
                                        body = call.receiveText(),
                                    ),
                                )
                            call.respondText(Json.encodeToString(AgreementTextDraftDto.serializer(), saved))
                        }
                        post("/t/publish") {
                            val summary =
                                service(
                                    call,
                                ).publishDraft(kind = AgreementTextKind.FINDER, expectedDraftSha256 = call.request.queryParameters["sha"]!!)
                            call.respondText(Json.encodeToString(AgreementTextVersionSummaryDto.serializer(), summary))
                        }
                    }
                }
                block()
            }

        suspend fun HttpClient.save(
            label: String,
            body: String,
            who: String = ADMIN_ID,
        ): HttpResponse =
            post("/t/save?label=$label&validFrom=$today") {
                header("X-Member-Id", who)
                setBody(body)
            }

        test("the overview lists FINDER as a placeholder with the placeholder's label and no count") {
            withApp {
                val overview =
                    Json.decodeFromString(
                        AgreementTextOverviewDto.serializer(),
                        client.post("/t/overview") { header("X-Member-Id", ADMIN_ID) }.bodyAsText(),
                    )
                val finder = overview.kinds.single { it.kind == AgreementTextKind.FINDER }
                finder.placeholderActive shouldBe true
                finder.builtinVersionLabel shouldBe MemberFinderConsentDefault.VERSION
                finder.olderAcknowledgmentCount shouldBe null
            }
        }

        test("a draft and a publication work; a non-admin is refused; a text or a label equal to the placeholder is refused") {
            withApp {
                client.save("2026-10-17.v9", MemberFinderConsentDefault.TEXT).status shouldBe HttpStatusCode.Conflict
                client.save(MemberFinderConsentDefault.VERSION, "Ein eigener Einwilligungstext.").status shouldBe HttpStatusCode.Conflict
                client.save("2026-10-17.v2", "Anderer Text.", who = BOARD_ID).status shouldBe HttpStatusCode.Forbidden
                val saved = client.save("2026-10-17.v1", "Ein eigener, rechtlich geprüfter Einwilligungstext.")
                saved.status shouldBe HttpStatusCode.OK
                val draft = Json.decodeFromString(AgreementTextDraftDto.serializer(), saved.bodyAsText())
                val published = client.post("/t/publish?sha=${draft.sha256}") { header("X-Member-Id", ADMIN_ID) }
                published.status shouldBe HttpStatusCode.OK
                Json.decodeFromString(AgreementTextVersionSummaryDto.serializer(), published.bodyAsText()).versionLabel shouldBe
                    "2026-10-17.v1"
            }
        }
    })
