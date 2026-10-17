package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
import network.lapis.cloud.server.agreementtexts.AgreementTextStore
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.agreementtexts.EncounterAssemblyConsentDefault
import network.lapis.cloud.server.agreementtexts.EncounterChurchConsentDefault
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextDraftInput
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextVersionDto
import network.lapis.cloud.shared.domain.AgreementTextVersionSummaryDto
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.rpc.AgreementTextInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextLayoutInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextVersionLabelTakenException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000003"

private const val VALID_BODY = "Eigene Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetailabsatz."

/**
 * V1.9.148 -- the two kinds `ENC_CHURCH` and `ENC_ASSEM` through the agreement-text administration: ADMIN only, the paragraph rule and the 46-character label
 * limit, one label namespace across both kinds and every label the code ever wrote, audit entries without the text, and no count of anybody.
 */
class AgreementTextEncounterKindsTest :
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
                        exception<AgreementTextLayoutInvalidException> {
                            call,
                            _,
                            ->
                            call.respondText("Layout", status = HttpStatusCode.Conflict)
                        }
                        exception<AgreementTextVersionLabelTakenException> { call, _ ->
                            call.respondText("LabelTaken", status = HttpStatusCode.Conflict)
                        }
                    }
                    routing { registerEncounterKindRoutes() }
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

        suspend fun HttpClient.publish(
            kind: AgreementTextKind,
            draft: HttpResponse,
        ): HttpResponse {
            val sha = Json.decodeFromString(AgreementTextDraftDto.serializer(), draft.bodyAsText()).sha256
            return post("/t/publish?kind=${kind.name}&sha=$sha") { header("X-Member-Id", ADMIN_ID) }
        }

        test("the overview lists eight kinds; both ENC kinds are placeholders with their own label and no count") {
            withApp {
                val overview =
                    Json.decodeFromString(
                        AgreementTextOverviewDto.serializer(),
                        client.post("/t/overview") { header("X-Member-Id", ADMIN_ID) }.bodyAsText(),
                    )
                overview.kinds.size shouldBe 8
                val church = overview.kinds.single { it.kind == AgreementTextKind.ENC_CHURCH }
                val assembly = overview.kinds.single { it.kind == AgreementTextKind.ENC_ASSEM }
                church.placeholderActive shouldBe true
                assembly.placeholderActive shouldBe true
                church.builtinVersionLabel shouldBe EncounterChurchConsentDefault.VERSION
                assembly.builtinVersionLabel shouldBe EncounterAssemblyConsentDefault.VERSION
                church.olderAcknowledgmentCount shouldBe null
            }
        }

        test("after a publication the overview still counts nobody (Art. 9 aggregate: ALWAYS zero)") {
            withApp {
                client
                    .publish(
                        AgreementTextKind.ENC_CHURCH,
                        client.save(AgreementTextKind.ENC_CHURCH, "2026-10-18.v1", VALID_BODY),
                    ).status shouldBe
                    HttpStatusCode.OK
                val overview =
                    Json.decodeFromString(
                        AgreementTextOverviewDto.serializer(),
                        client.post("/t/overview") { header("X-Member-Id", ADMIN_ID) }.bodyAsText(),
                    )
                overview.kinds.single { it.kind == AgreementTextKind.ENC_CHURCH }.olderAcknowledgmentCount shouldBe 0
            }
        }

        test("only an ADMIN may save or publish; BOARD and MEMBER are refused") {
            withApp {
                listOf(BOARD_ID, MEMBER_ID).forEach { who ->
                    client.save(AgreementTextKind.ENC_CHURCH, "2026-10-18.v1", VALID_BODY, who = who).status shouldBe
                        HttpStatusCode.Forbidden
                }
                val saved = client.save(AgreementTextKind.ENC_CHURCH, "2026-10-18.v1", VALID_BODY)
                saved.status shouldBe HttpStatusCode.OK
                val sha = Json.decodeFromString(AgreementTextDraftDto.serializer(), saved.bodyAsText()).sha256
                client.post("/t/publish?kind=ENC_CHURCH&sha=$sha") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("the paragraph rule is enforced: three paragraphs, a long headline, a long key point are refused with the layout exception") {
            withApp {
                val tooLongKeyPoint = "x".repeat(601)
                listOf(
                    "Überschrift\n\nPunkt eins\n\nPunkt zwei",
                    "${"h".repeat(161)}\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei",
                    "Überschrift\n\nPunkt eins\n\n$tooLongKeyPoint\n\nPunkt drei",
                ).forEach { body ->
                    val r = client.save(AgreementTextKind.ENC_ASSEM, "2026-10-18.v1", body)
                    r.status shouldBe HttpStatusCode.Conflict
                    r.bodyAsText() shouldBe "Layout"
                }
                // exactly at the limits it passes
                val ok = "${"h".repeat(160)}\n\nPunkt eins\n\n${"k".repeat(600)}\n\nPunkt drei"
                client.save(AgreementTextKind.ENC_ASSEM, "2026-10-18.v1", ok).status shouldBe HttpStatusCode.OK
            }
        }

        test("a 47-character label is refused (room for +rec), a 46-character one passes; other kinds keep the 50-character limit") {
            withApp {
                client.save(AgreementTextKind.ENC_CHURCH, "a".repeat(47), VALID_BODY).bodyAsText() shouldBe "Layout"
                client.save(AgreementTextKind.ENC_CHURCH, "a".repeat(46), VALID_BODY).status shouldBe HttpStatusCode.OK
                client.save(AgreementTextKind.REC_ROOM, "a".repeat(50), "Ein Satz.").status shouldBe HttpStatusCode.OK
            }
        }

        test("a label already used in the OTHER encounter kind, or one the code ever wrote, is refused as taken") {
            withApp {
                client
                    .publish(
                        AgreementTextKind.ENC_CHURCH,
                        client.save(AgreementTextKind.ENC_CHURCH, "2026-10-18.v1", VALID_BODY),
                    ).status shouldBe
                    HttpStatusCode.OK
                client.save(AgreementTextKind.ENC_ASSEM, "2026-10-18.v1", VALID_BODY.replace("Eigene", "Andere")).bodyAsText() shouldBe
                    "LabelTaken"
                listOf(
                    "2026-10-06.v1",
                    "2026-10-07.assembly.v1",
                    "2026-10-16.rec.v1",
                    "2026-10-16.assembly-rec.v1",
                    EncounterChurchConsentDefault.VERSION,
                    EncounterAssemblyConsentDefault.VERSION,
                ).forEach { label ->
                    client.save(AgreementTextKind.ENC_CHURCH, label, VALID_BODY).bodyAsText() shouldBe "LabelTaken"
                    client.save(AgreementTextKind.ENC_ASSEM, label, VALID_BODY).bodyAsText() shouldBe "LabelTaken"
                }
                // a label of its own passes
                client.save(AgreementTextKind.ENC_ASSEM, "2026-10-19.v1", VALID_BODY).status shouldBe HttpStatusCode.OK
            }
        }

        test("two drafts with the same label: whichever kind publishes second is refused at publication") {
            withApp {
                val church = client.save(AgreementTextKind.ENC_CHURCH, "2026-10-20.v1", VALID_BODY)
                val assembly = client.save(AgreementTextKind.ENC_ASSEM, "2026-10-20.v1", VALID_BODY.replace("Eigene", "Andere"))
                church.status shouldBe HttpStatusCode.OK
                assembly.status shouldBe HttpStatusCode.OK
                client.publish(AgreementTextKind.ENC_CHURCH, church).status shouldBe HttpStatusCode.OK
                client.publish(AgreementTextKind.ENC_ASSEM, assembly).bodyAsText() shouldBe "LabelTaken"
                AgreementTextTestState.versionRows().size shouldBe 1
            }
        }

        test("text and label equal to the placeholder of the kind are refused, an own text passes") {
            withApp {
                client.save(AgreementTextKind.ENC_CHURCH, "2026-10-18.v1", EncounterChurchConsentDefault.TEXT).bodyAsText() shouldBe
                    "Invalid"
                client.save(AgreementTextKind.ENC_ASSEM, "2026-10-18.v1", EncounterAssemblyConsentDefault.TEXT).bodyAsText() shouldBe
                    "Invalid"
                client.save(AgreementTextKind.ENC_CHURCH, "2026-10-18.v1", VALID_BODY).status shouldBe HttpStatusCode.OK
            }
        }

        test("a body with blank lines made of spaces is accepted as paragraphs (the layout reads the normalized text)") {
            withApp {
                client.save(AgreementTextKind.ENC_CHURCH, "2026-10-18.v1", "A\r\n\r\nB\r\n \r\nC\n\n\nD").status shouldBe HttpStatusCode.OK
            }
        }

        test("getVersion returns the placeholder with the build-in date; a published version returns its text") {
            withApp {
                val builtin =
                    Json.decodeFromString(
                        AgreementTextVersionDto.serializer(),
                        client
                            .post("/t/version?kind=ENC_ASSEM&label=${EncounterAssemblyConsentDefault.VERSION}") {
                                header("X-Member-Id", ADMIN_ID)
                            }.bodyAsText(),
                    )
                builtin.body shouldBe EncounterAssemblyConsentDefault.TEXT
                builtin.summary.sha256 shouldBe EncounterAssemblyConsentDefault.SHA256
                builtin.summary.validFrom shouldBe LocalDate(2026, 10, 17)
                builtin.summary.sequenceNo shouldBe 0
            }
        }

        test("audit: one entry per draft save and one POST per publication; the snapshot never holds the text") {
            withApp {
                val saved = client.save(AgreementTextKind.ENC_CHURCH, "2026-10-18.v1", VALID_BODY)
                client.save(AgreementTextKind.ENC_CHURCH, "2026-10-18.v1", VALID_BODY.replace("eins", "ein"))
                val again = client.save(AgreementTextKind.ENC_CHURCH, "2026-10-18.v1", VALID_BODY)
                saved.status shouldBe HttpStatusCode.OK
                client.publish(AgreementTextKind.ENC_CHURCH, again).status shouldBe HttpStatusCode.OK
                val entries =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.AGREEMENT_TEXT }
                            .map { it[AuditLogEntryTable.action] to (it[AuditLogEntryTable.afterSnapshot] ?: "") }
                    }
                entries.map { it.first }.count { it == AuditAction.POST } shouldBe 1
                entries.map { it.first }.count { it == AuditAction.CREATE } shouldBe 1
                entries.map { it.first }.count { it == AuditAction.UPDATE } shouldBe 2
                entries.forEach { (_, snapshot) ->
                    snapshot shouldNotContain "Überschrift"
                    snapshot shouldNotContain "Punkt eins"
                }
            }
        }

        test("a published ENC version is stored as an ordinary version of its kind and nothing else is written") {
            withApp {
                val published =
                    client.publish(AgreementTextKind.ENC_ASSEM, client.save(AgreementTextKind.ENC_ASSEM, "2026-10-18.v1", VALID_BODY))
                Json.decodeFromString(AgreementTextVersionSummaryDto.serializer(), published.bodyAsText()).kind shouldBe
                    AgreementTextKind.ENC_ASSEM
                transaction { AgreementTextStore.currentVersion(AgreementTextKind.ENC_ASSEM)!!.versionLabel } shouldBe "2026-10-18.v1"
                transaction { AgreementTextStore.currentVersion(AgreementTextKind.ENC_CHURCH) } shouldBe null
            }
        }
    })

private fun Route.registerEncounterKindRoutes() {
    fun service(call: ApplicationCall) = AgreementTextAdminService(call)

    fun kindOf(call: ApplicationCall) = AgreementTextKind.valueOf(call.request.queryParameters["kind"]!!)
    post("/t/overview") { call.respondText(Json.encodeToString(AgreementTextOverviewDto.serializer(), service(call).getOverview())) }
    post("/t/version") {
        val v = service(call).getVersion(kind = kindOf(call), versionLabel = call.request.queryParameters["label"]!!)
        call.respondText(Json.encodeToString(AgreementTextVersionDto.serializer(), v))
    }
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
