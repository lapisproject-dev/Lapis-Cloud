package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.agreementtexts.AgreementTextHash
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AgreementTextDraftTable
import network.lapis.cloud.server.db.generated.AgreementTextVersionTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AgreementTextDraftDto
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextRules
import network.lapis.cloud.shared.domain.AgreementTextVersionDto
import network.lapis.cloud.shared.domain.AgreementTextVersionSummaryDto
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.AgreementTextDraftChangedException
import network.lapis.cloud.shared.rpc.AgreementTextInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextVersionLabelTakenException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

private val json = Json

/** A marker that must never appear in an audit snapshot (see [AgreementTextAdminServiceTest] audit tests). */
private const val SECRET_MARKER = "GEHEIMER-VERTRAGSSATZ-4711"

/**
 * V1.9.134 -- [AgreementTextAdminService]: the ADMIN-only rights matrix, draft saving, validation, publication (append-only, optimistic lock by
 * hash), the overview, version lookup and the audit entries (event, kind, label, hash, counts -- never the text). Every test that publishes
 * resets the shared H2 afterwards (`AgreementTextTestState.reset`), otherwise every registration test of the JVM would see the new text.
 */
class AgreementTextAdminServiceTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach { AgreementTextTestState.reset() }
        afterSpec {
            AgreementTextTestState.reset()
            fixtures.cleanup()
        }

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
                        exception<NotFoundException> { call, _ -> call.respondText("NotFound", status = HttpStatusCode.NotFound) }
                        exception<AgreementTextInvalidException> {
                            call,
                            _,
                            ->
                            call.respondText("Invalid", status = HttpStatusCode.Conflict)
                        }
                        exception<AgreementTextVersionLabelTakenException> { call, _ ->
                            call.respondText("LabelTaken", status = HttpStatusCode.Conflict)
                        }
                        exception<AgreementTextDraftChangedException> { call, _ ->
                            call.respondText("DraftChanged", status = HttpStatusCode.Conflict)
                        }
                    }
                    routing { registerAgreementAdminTestRoutes() }
                }
                block()
            }

        val today by lazy { OrganizationTimeZone.today() }

        suspend fun HttpClient.save(
            who: String = ADMIN_ID,
            kind: AgreementTextKind = AgreementTextKind.MEMBERSHIP,
            label: String = "2026-10-01.v1",
            validFrom: LocalDate = today,
            body: String = "Unser Beitrittsvertrag.",
        ): HttpResponse =
            post("/t/save?kind=${kind.name}&label=$label&validFrom=$validFrom") {
                header("X-Member-Id", who)
                setBody(body)
            }

        suspend fun HttpClient.publish(
            sha: String,
            who: String = ADMIN_ID,
            kind: AgreementTextKind = AgreementTextKind.MEMBERSHIP,
        ): HttpResponse = post("/t/publish?kind=${kind.name}&sha=$sha") { header("X-Member-Id", who) }

        suspend fun HttpClient.overview(): AgreementTextOverviewDto =
            json.decodeFromString(get("/t/overview") { header("X-Member-Id", ADMIN_ID) }.bodyAsText())

        fun draftDto(text: String): AgreementTextDraftDto = json.decodeFromString(text)

        fun summaryDto(text: String): AgreementTextVersionSummaryDto = json.decodeFromString(text)

        test("only an ADMIN may use any of the six methods; every other role and an anonymous caller is refused") {
            val friend = fixtures.newMember(status = MemberStatus.FRIEND)
            val applicant = fixtures.newMember(status = MemberStatus.APPLICATION)
            withApp {
                val paths =
                    listOf(
                        "get" to "/t/overview",
                        "get" to "/t/draft?kind=MEMBERSHIP",
                        "post" to "/t/save?kind=MEMBERSHIP&label=x.v1&validFrom=$today",
                        "post" to "/t/publish?kind=MEMBERSHIP&sha=abc",
                        "get" to "/t/versions?kind=MEMBERSHIP",
                        "get" to "/t/version?kind=MEMBERSHIP&label=x.v1",
                    )
                for (who in listOf(BOARD_ID, TREASURER_ID, MEMBER_ID, friend.toString(), applicant.toString())) {
                    for ((method, path) in paths) {
                        val response =
                            if (method == "get") {
                                client.get(path) { header("X-Member-Id", who) }
                            } else {
                                client.post(path) {
                                    header("X-Member-Id", who)
                                    setBody("Text")
                                }
                            }
                        withClue(clue = "$method $path as $who") { response.status shouldBe HttpStatusCode.Forbidden }
                    }
                }
                for ((method, path) in paths) {
                    val response = if (method == "get") client.get(path) else client.post(path) { setBody("Text") }
                    withClue(clue = "$method $path anonymous") { response.status shouldBe HttpStatusCode.Unauthorized }
                }
                AgreementTextTestState.draftRows() shouldBe emptyList()
            }
        }

        test(
            "saving creates a draft with the normalized text and the hash over label and body; saving again overwrites it and keeps its id",
        ) {
            withApp {
                val first = draftDto(client.save(body = "Zeile eins\r\nZeile zwei  \r\n\r\n").bodyAsText())
                first.body shouldBe "Zeile eins\nZeile zwei"
                first.sha256 shouldBe AgreementTextHash.of(versionLabel = "2026-10-01.v1", body = "Zeile eins\nZeile zwei")
                first.charCount shouldBe first.body.length
                val idBefore = AgreementTextTestState.draftRows().single()[AgreementTextDraftTable.id]

                val second = draftDto(client.save(label = "2026-10-01.v2", body = "Neu").bodyAsText())
                second.versionLabel shouldBe "2026-10-01.v2"
                AgreementTextTestState.draftRows().size shouldBe 1
                AgreementTextTestState.draftRows().single()[AgreementTextDraftTable.id] shouldBe idBefore
                draftDto(client.get("/t/draft?kind=MEMBERSHIP") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()).body shouldBe "Neu"
                client.get("/t/draft?kind=FRIEND") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "null"
            }
        }

        test("validation: empty, 60000 ok, 60001 refused, control and bidi characters refused, bad label refused") {
            withApp {
                client.save(body = "   \n ").bodyAsText() shouldBe "Invalid"
                client.save(body = "a".repeat(AgreementTextRules.MAX_CHARS)).status shouldBe HttpStatusCode.OK
                client.save(body = "a".repeat(AgreementTextRules.MAX_CHARS + 1)).bodyAsText() shouldBe "Invalid"
                client.save(body = "mit NUL \u0000 drin").bodyAsText() shouldBe "Invalid"
                client.save(body = "Bidi ‮ gedreht").bodyAsText() shouldBe "Invalid"
                client.save(body = "Isolate ⁦ eins").bodyAsText() shouldBe "Invalid"
                client.save(body = "Tab\tund Zeilenumbruch\nsind erlaubt").status shouldBe HttpStatusCode.OK
                client.save(label = "-führend").bodyAsText() shouldBe "Invalid"
                client.save(label = "x".repeat(51)).bodyAsText() shouldBe "Invalid"
            }
        }

        test(
            "validation: validFrom may not lie in the future nor before the current version; the built-in text and its label are refused",
        ) {
            withApp {
                client.save(validFrom = today.plus(1, kotlinx.datetime.DateTimeUnit.DAY)).bodyAsText() shouldBe "Invalid"
                // the placeholder's own text would silence the warning while the placeholder stays what people see
                val builtin = MembershipAgreementDisclaimer.TEXT
                client.save(body = builtin).bodyAsText() shouldBe "Invalid"
                // the placeholder's label would make old acknowledgments ambiguous
                client.save(label = MembershipAgreementDisclaimer.VERSION).bodyAsText() shouldBe "LabelTaken"
                client.save(kind = AgreementTextKind.FRIEND, label = FriendTermsDisclaimer.VERSION).bodyAsText() shouldBe "LabelTaken"

                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.MEMBERSHIP,
                    label = "2026-10-01.v1",
                    body = "Alt",
                    validFrom = today,
                )
                client.save(label = "2026-10-01.v2", validFrom = today.plus(-1, kotlinx.datetime.DateTimeUnit.DAY)).bodyAsText() shouldBe
                    "Invalid"
                client.save(label = "2026-10-01.v1").bodyAsText() shouldBe "LabelTaken"
                client.save(label = "2026-10-01.v2").status shouldBe HttpStatusCode.OK
            }
        }

        test("publishing appends a version, deletes the draft, raises the sequence number and makes it the current one") {
            withApp {
                val draft = draftDto(client.save(body = "Erste Fassung").bodyAsText())
                val published = summaryDto(client.publish(sha = draft.sha256).bodyAsText())
                published.sequenceNo shouldBe 1
                published.versionLabel shouldBe "2026-10-01.v1"
                published.sha256 shouldBe draft.sha256
                AgreementTextTestState.draftRows() shouldBe emptyList()
                val row = AgreementTextTestState.versionRows().single()
                row[AgreementTextVersionTable.body] shouldBe "Erste Fassung"
                row[AgreementTextVersionTable.sha256] shouldBe AgreementTextHash.of(versionLabel = "2026-10-01.v1", body = "Erste Fassung")

                val second = draftDto(client.save(label = "2026-10-01.v2", body = "Zweite Fassung").bodyAsText())
                summaryDto(client.publish(sha = second.sha256).bodyAsText()).sequenceNo shouldBe 2
                val overview = client.overview()
                val membership = overview.kinds.single { it.kind == AgreementTextKind.MEMBERSHIP }
                membership.current?.versionLabel shouldBe "2026-10-01.v2"
                membership.placeholderActive shouldBe false
                membership.hasDraft shouldBe false
            }
        }

        test("publishing with a wrong hash or without a draft is DraftChanged and writes nothing") {
            withApp {
                client.publish(sha = "a".repeat(64)).bodyAsText() shouldBe "DraftChanged"
                val draft = draftDto(client.save().bodyAsText())
                client.publish(sha = "b".repeat(64)).bodyAsText() shouldBe "DraftChanged"
                client.publish(sha = "not-hex").bodyAsText() shouldBe "DraftChanged"
                AgreementTextTestState.versionRows() shouldBe emptyList()
                AgreementTextTestState.draftRows().size shouldBe 1
                // a second admin changed the draft after the first one previewed it
                client.save(body = "Anderer Text")
                client.publish(sha = draft.sha256).bodyAsText() shouldBe "DraftChanged"
                AgreementTextTestState.versionRows() shouldBe emptyList()
            }
        }

        test("a draft whose label was taken in the meantime cannot be published") {
            withApp {
                val draft = draftDto(client.save(label = "2026-10-01.v9", body = "Entwurf").bodyAsText())
                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.MEMBERSHIP,
                    label = "2026-10-01.v9",
                    body = "Andere",
                    validFrom = today,
                )
                client.publish(sha = draft.sha256).bodyAsText() shouldBe "LabelTaken"
                AgreementTextTestState.versionRows().size shouldBe 1
            }
        }

        test("the two kinds are independent: overview, drafts and sequence numbers") {
            withApp {
                val m = draftDto(client.save(kind = AgreementTextKind.MEMBERSHIP, label = "m.v1", body = "Mitglieder").bodyAsText())
                val f = draftDto(client.save(kind = AgreementTextKind.FRIEND, label = "f.v1", body = "Freunde").bodyAsText())
                client.publish(sha = m.sha256, kind = AgreementTextKind.MEMBERSHIP).status shouldBe HttpStatusCode.OK
                val overview = client.overview()
                val membership = overview.kinds.single { it.kind == AgreementTextKind.MEMBERSHIP }
                val friend = overview.kinds.single { it.kind == AgreementTextKind.FRIEND }
                membership.placeholderActive shouldBe false
                friend.placeholderActive shouldBe true
                friend.hasDraft shouldBe true
                friend.current shouldBe null
                friend.builtinVersionLabel shouldBe FriendTermsDisclaimer.VERSION
                membership.builtinVersionLabel shouldBe MembershipAgreementDisclaimer.VERSION
                summaryDto(client.publish(sha = f.sha256, kind = AgreementTextKind.FRIEND).bodyAsText()).sequenceNo shouldBe 1
            }
        }

        test(
            "the list shows versions newest first; getVersion returns the text, the built-in label returns the built-in text, an unknown label is 404",
        ) {
            withApp {
                AgreementTextTestState.publishDirect(kind = AgreementTextKind.MEMBERSHIP, label = "a.v1", body = "Eins", validFrom = today)
                AgreementTextTestState.publishDirect(kind = AgreementTextKind.MEMBERSHIP, label = "a.v2", body = "Zwei", validFrom = today)
                val list: List<AgreementTextVersionSummaryDto> =
                    json.decodeFromString(client.get("/t/versions?kind=MEMBERSHIP") { header("X-Member-Id", ADMIN_ID) }.bodyAsText())
                list.map { it.versionLabel } shouldContainExactly listOf("a.v2", "a.v1")
                val one: AgreementTextVersionDto =
                    json.decodeFromString(
                        client.get("/t/version?kind=MEMBERSHIP&label=a.v1") { header("X-Member-Id", ADMIN_ID) }.bodyAsText(),
                    )
                one.body shouldBe "Eins"
                one.summary.authorDisplayName.isNotBlank() shouldBe true
                val builtin: AgreementTextVersionDto =
                    json.decodeFromString(
                        client
                            .get(
                                "/t/version?kind=MEMBERSHIP&label=${MembershipAgreementDisclaimer.VERSION}",
                            ) { header("X-Member-Id", ADMIN_ID) }
                            .bodyAsText(),
                    )
                builtin.body shouldBe MembershipAgreementDisclaimer.TEXT
                builtin.summary.sha256 shouldBe MembershipAgreementDisclaimer.SHA256
                client.get("/t/version?kind=MEMBERSHIP&label=gibts-nicht") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        test("a published version is never changed: publishing the next one leaves every column of the older row bit-identical") {
            withApp {
                val d1 = draftDto(client.save(label = "i.v1", body = "Erste").bodyAsText())
                client.publish(sha = d1.sha256)
                val before = AgreementTextTestState.versionRows().single().let { r -> AgreementTextVersionTable.columns.map { r[it] } }
                val d2 = draftDto(client.save(label = "i.v2", body = "Zweite").bodyAsText())
                client.publish(sha = d2.sha256)
                val rows = AgreementTextTestState.versionRows()
                rows.size shouldBe 2
                val firstAfter = rows.single { it[AgreementTextVersionTable.versionLabel] == "i.v1" }
                AgreementTextVersionTable.columns.map { firstAfter[it] } shouldBe before
            }
        }

        test("the overview counts the people who have not acknowledged the current MEMBERSHIP version") {
            withApp {
                val label = "cnt.v1"
                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.MEMBERSHIP,
                    label = label,
                    body = "Zaehltext",
                    validFrom = today,
                )
                val baseline =
                    client
                        .overview()
                        .kinds
                        .single { it.kind == AgreementTextKind.MEMBERSHIP }
                        .olderAcknowledgmentCount
                val extra = fixtures.newMember(status = MemberStatus.ACTIVE)
                client
                    .overview()
                    .kinds
                    .single { it.kind == AgreementTextKind.MEMBERSHIP }
                    .olderAcknowledgmentCount shouldBe baseline!! + 1
                transaction {
                    MemberTable.update({ MemberTable.id eq extra }) { it[status] = MemberStatus.WITHDRAWN }
                }
                client
                    .overview()
                    .kinds
                    .single { it.kind == AgreementTextKind.MEMBERSHIP }
                    .olderAcknowledgmentCount shouldBe baseline
                // no count while only the placeholder exists
                AgreementTextTestState.reset()
                client
                    .overview()
                    .kinds
                    .single { it.kind == AgreementTextKind.MEMBERSHIP }
                    .olderAcknowledgmentCount shouldBe null
            }
        }

        test("audit: exactly one entry per event with the expected action and snapshot keys; the text never appears in it") {
            withApp {
                val d = draftDto(client.save(body = "Anfang $SECRET_MARKER Ende").bodyAsText())
                client.save(body = "Zweiter Stand $SECRET_MARKER")
                val d2 = draftDto(client.get("/t/draft?kind=MEMBERSHIP") { header("X-Member-Id", ADMIN_ID) }.bodyAsText())
                client.publish(sha = d2.sha256)
                d.versionLabel shouldBe d2.versionLabel

                val entries =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq network.lapis.cloud.shared.domain.AuditEntityType.AGREEMENT_TEXT }
                            .orderBy(AuditLogEntryTable.sequenceNumber)
                            .toList()
                    }
                entries.map { it[AuditLogEntryTable.action] } shouldContainExactly
                    listOf(AuditAction.CREATE, AuditAction.UPDATE, AuditAction.POST)
                entries.forEach { it[AuditLogEntryTable.actorMemberId] shouldBe Uuid.parse(ADMIN_ID) }
                val keys = setOf("event", "kind", "versionLabel", "sha256", "charCount", "validFrom", "format")
                val after = entries.map { json.parseToJsonElement(it[AuditLogEntryTable.afterSnapshot]!!).jsonObject }
                after[0].keys shouldBe keys
                after[1].keys shouldBe keys
                after[2].keys shouldBe keys + "sequenceNo"
                (after[2]["event"] as kotlinx.serialization.json.JsonPrimitive).content shouldBe "PUBLISHED"
                entries.forEach {
                    (it[AuditLogEntryTable.beforeSnapshot] ?: "") shouldNotContain SECRET_MARKER
                    (it[AuditLogEntryTable.afterSnapshot] ?: "") shouldNotContain SECRET_MARKER
                }
                // the entity id of the two draft entries is the stable draft id, the publication has the version id
                entries[0][AuditLogEntryTable.entityId] shouldBe entries[1][AuditLogEntryTable.entityId]
                (entries[2][AuditLogEntryTable.entityId] == entries[0][AuditLogEntryTable.entityId]) shouldBe false
            }
        }
    })

private fun Route.registerAgreementAdminTestRoutes() {
    fun service(call: ApplicationCall) = AgreementTextAdminService(call = call)

    fun kindOf(call: ApplicationCall) = AgreementTextKind.valueOf(call.request.queryParameters["kind"]!!)
    get("/t/overview") { call.respondText(Json.encodeToString(AgreementTextOverviewDto.serializer(), service(call).getOverview())) }
    get("/t/draft") {
        val draft = service(call).getDraft(kindOf(call)).draft
        call.respondText(if (draft == null) "null" else Json.encodeToString(AgreementTextDraftDto.serializer(), draft))
    }
    post("/t/save") {
        val q = call.request.queryParameters
        val body = call.receiveText()
        val saved =
            service(call).saveDraft(
                network.lapis.cloud.shared.domain.AgreementTextDraftInput(
                    kind = kindOf(call),
                    versionLabel = q["label"]!!,
                    validFrom = LocalDate.parse(q["validFrom"]!!),
                    body = body,
                ),
            )
        call.respondText(Json.encodeToString(AgreementTextDraftDto.serializer(), saved))
    }
    post("/t/publish") {
        val summary = service(call).publishDraft(kind = kindOf(call), expectedDraftSha256 = call.request.queryParameters["sha"]!!)
        call.respondText(Json.encodeToString(AgreementTextVersionSummaryDto.serializer(), summary))
    }
    get("/t/versions") {
        call.respondText(
            Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(AgreementTextVersionSummaryDto.serializer()),
                service(call).listVersions(kindOf(call)),
            ),
        )
    }
    get("/t/version") {
        val dto = service(call).getVersion(kind = kindOf(call), versionLabel = call.request.queryParameters["label"]!!)
        call.respondText(Json.encodeToString(AgreementTextVersionDto.serializer(), dto))
    }
}

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
