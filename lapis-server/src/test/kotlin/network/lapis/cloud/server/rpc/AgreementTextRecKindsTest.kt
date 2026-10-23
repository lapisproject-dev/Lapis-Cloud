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
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.agreementtexts.AgreementTextResolver
import network.lapis.cloud.server.agreementtexts.AgreementTextStore
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.agreementtexts.RecPulpitAssemblyDefault
import network.lapis.cloud.server.agreementtexts.RecPulpitChurchDefault
import network.lapis.cloud.server.agreementtexts.RecRoomAssemblyDefault
import network.lapis.cloud.server.agreementtexts.RecRoomChurchDefault
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextDraftInput
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextVersionSummaryDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.rpc.AgreementTextInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextVersionLabelTakenException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"

/**
 * V1.9.143 -- the two new kinds `REC_ROOM` and `REC_PULPIT` through the agreement-text administration: draft and publication work like for the registration
 * texts, a text or a label equal to EITHER of the two profile placeholders is refused, a published version applies to both profiles, nothing is counted as
 * "older acknowledgment", and the overview lists both kinds with the church placeholder's label.
 */
class AgreementTextRecKindsTest :
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
                    routing { registerRecKindRoutes() }
                }
                block()
            }

        suspend fun HttpClient.save(
            kind: AgreementTextKind,
            label: String,
            body: String,
            who: String = ADMIN_ID,
        ): HttpResponse =
            post("/t/save?kind=${kind.name}&label=$label&validFrom=$today") {
                header("X-Member-Id", who)
                setBody(body)
            }

        test("the overview lists both kinds as placeholders; the label is the church placeholder's") {
            withApp {
                val overview =
                    Json.decodeFromString(
                        AgreementTextOverviewDto.serializer(),
                        client.post("/t/overview") { header("X-Member-Id", ADMIN_ID) }.bodyAsText(),
                    )
                val room = overview.kinds.single { it.kind == AgreementTextKind.REC_ROOM }
                val pulpit = overview.kinds.single { it.kind == AgreementTextKind.REC_PULPIT }
                room.placeholderActive shouldBe true
                room.builtinVersionLabel shouldBe RecRoomChurchDefault.VERSION
                pulpit.builtinVersionLabel shouldBe RecPulpitChurchDefault.VERSION
                room.olderAcknowledgmentCount shouldBe null
            }
        }

        test("a draft and a publication of a REC kind work; a non-admin is refused") {
            withApp {
                val saved = client.save(AgreementTextKind.REC_ROOM, "2026-10-17.v1", "Hier wird nur die Kanzel aufgenommen.")
                saved.status shouldBe HttpStatusCode.OK
                val draft = Json.decodeFromString(AgreementTextDraftDto.serializer(), saved.bodyAsText())
                client.save(AgreementTextKind.REC_ROOM, "2026-10-17.v2", "Anderer Text.", who = BOARD_ID).status shouldBe
                    HttpStatusCode.Forbidden
                val published =
                    client.post("/t/publish?kind=REC_ROOM&sha=${draft.sha256}") { header("X-Member-Id", ADMIN_ID) }
                published.status shouldBe HttpStatusCode.OK
                Json.decodeFromString(AgreementTextVersionSummaryDto.serializer(), published.bodyAsText()).versionLabel shouldBe
                    "2026-10-17.v1"
            }
        }

        test("a text or a label equal to the placeholder of EITHER profile is refused when the draft is saved") {
            withApp {
                listOf(RecRoomChurchDefault.TEXT, RecRoomAssemblyDefault.TEXT).forEach { text ->
                    client.save(AgreementTextKind.REC_ROOM, "2026-10-17.v9", text).status shouldBe HttpStatusCode.Conflict
                }
                listOf(RecPulpitChurchDefault.VERSION, RecPulpitAssemblyDefault.VERSION).forEach { label ->
                    client.save(AgreementTextKind.REC_PULPIT, label, "Ein eigener Zustimmungstext.").status shouldBe HttpStatusCode.Conflict
                }
                // an own text and an own label pass
                client.save(AgreementTextKind.REC_PULPIT, "2026-10-17.v1", "Ein eigener Zustimmungstext.").status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "a published version applies to both profiles; without one each profile has its own placeholder; nothing is counted as older",
        ) {
            transaction {
                AgreementTextResolver
                    .current(
                        kind = AgreementTextKind.REC_ROOM,
                        profile = EncounterProfile.CHURCH_SERVICE,
                    ).versionLabel shouldBe
                    RecRoomChurchDefault.VERSION
                AgreementTextResolver.current(kind = AgreementTextKind.REC_ROOM, profile = EncounterProfile.ASSEMBLY).versionLabel shouldBe
                    RecRoomAssemblyDefault.VERSION
            }
            val sha =
                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.REC_ROOM,
                    label = "2026-10-17.v1",
                    body = "Hier wird nur eine Person aufgenommen.",
                )
            transaction {
                EncounterProfile.entries.forEach { profile ->
                    val current = AgreementTextResolver.current(kind = AgreementTextKind.REC_ROOM, profile = profile)
                    current.versionLabel shouldBe "2026-10-17.v1"
                    current.sha256 shouldBe sha
                    current.placeholder shouldBe false
                    AgreementTextResolver.matchesCurrent(
                        kind = AgreementTextKind.REC_ROOM,
                        version = "2026-10-17.v1",
                        sha256 = sha,
                        profile = profile,
                    ) shouldBe
                        true
                }
                AgreementTextStore.countOlderAcknowledgments(kind = AgreementTextKind.REC_ROOM, currentLabel = "2026-10-17.v1") shouldBe 0
                AgreementTextStore.countOlderAcknowledgments(kind = AgreementTextKind.REC_PULPIT, currentLabel = "any") shouldBe 0
            }
        }
    })

private fun Route.registerRecKindRoutes() {
    fun service(call: ApplicationCall) = AgreementTextAdminService(call = call)

    fun kindOf(call: ApplicationCall) = AgreementTextKind.valueOf(call.request.queryParameters["kind"]!!)
    post("/t/overview") { call.respondText(Json.encodeToString(AgreementTextOverviewDto.serializer(), service(call).getOverview())) }
    post("/t/save") {
        val q = call.request.queryParameters
        val saved =
            service(call).saveDraft(
                AgreementTextDraftInput(
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
}
