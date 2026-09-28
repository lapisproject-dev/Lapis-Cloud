package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Rein relativ zum echten Systemdatum berechnet (dieser Test läuft gegen den echten
 * [network.lapis.cloud.server.db.DbClock], kein Fixed-Clock-Seam in [CarpoolService]) -- ein
 * hartkodiertes Zukunftsdatum wäre nach [CarpoolValidation.MAX_FUTURE_DAYS] Tagen selbst ein
 * Validierungsfehler statt eines gültigen Testfixture-Werts.
 */
private val TEST_TODAY: LocalDate =
    Clock.System
        .now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
private val VALID_FUTURE_DATE: LocalDate = TEST_TODAY.plus(DatePeriod(days = 30))
private val VALID_FUTURE_DATE_2: LocalDate = TEST_TODAY.plus(DatePeriod(days = 31))

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- `testApplication`-Harness 1:1 `ServiceIntegrationTest`s/
 * `FriendCapabilityBoundaryTest`s Muster: eine geworfene, throwaway HTTP-Route ruft die
 * Service-Methode direkt auf, `X-Member-Id` löst [network.lapis.cloud.server.security
 * .resolveCurrentMember] auf (H2-Testmodus).
 */
class CarpoolServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdPostingIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // Testisolations-Fund: die "happy path"-Testfahrt nutzte ursprünglich die FEST
                // GESEEDETEN BOARD_ID/MEMBER_ID-Testkonten für contactAuthor/Antwort und hinterließ
                // dabei echte direct_message-Zeilen, die `ServiceIntegrationTest`s eigene exakte
                // Unread-Zähler-Assertion für dieselben Konten verfälschten -- seitdem verwendet
                // dieser Testfall zwei eigens angelegte ACTIVE-Testmitglieder (siehe dort), sodass
                // createdMemberIds allein wieder ausreicht.
                DirectMessageTable.deleteWhere { (senderId inList createdMemberIds) or (recipientId inList createdMemberIds) }
                CarpoolPostingTable.deleteWhere { id inList createdPostingIds }
                CarpoolPostingTable.deleteWhere { authorMemberId inList createdMemberIds }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun createTestMember(
            email: String,
            status: MemberStatus,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Carpool-Test Mitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun StatusPagesConfig.installExceptionHandlers() {
            exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
            exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
            exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
        }

        fun validInput(
            type: CarpoolPostingType = CarpoolPostingType.OFFER,
            departureDate: LocalDate = VALID_FUTURE_DATE,
            seatsOffered: Int? = 3,
        ) = CarpoolPostingInput(
            type = type,
            fromPlace = "Braunschweig",
            toPlace = "Hannover",
            departureDate = departureDate,
            departureTime = null,
            seatsOffered = if (type == CarpoolPostingType.OFFER) seatsOffered else null,
            notes = null,
        )

        test("happy path: OFFER + REQUEST created, both in feed, type filter works, contactAuthor -> inbox -> reply") {
            testApplication {
                application {
                    install(StatusPages) { installExceptionHandlers() }
                    routing {
                        post("/test/create") {
                            val service = CarpoolService(call)
                            val type = CarpoolPostingType.valueOf(call.request.queryParameters["type"]!!)
                            val dto = service.createPosting(validInput(type = type))
                            call.respondText(dto.id)
                        }
                        get("/test/list") {
                            val service = CarpoolService(call)
                            val type = call.request.queryParameters["type"]?.let { CarpoolPostingType.valueOf(it) }
                            val list = service.listPostings(type)
                            call.respondText(list.joinToString(",") { "${it.id}:${it.type}" })
                        }
                        post("/test/contact/{id}") {
                            val service = CarpoolService(call)
                            val dto =
                                service.contactAuthor(
                                    postingId = call.parameters["id"]!!,
                                    message = "Hallo, ist die Fahrt noch frei?",
                                )
                            call.respondText(dto.id)
                        }
                        get("/test/inbox") {
                            val service = DirectMessageService(call)
                            val inbox = service.listInbox()
                            call.respondText(inbox.joinToString(",") { "${it.id}:${it.senderId}:${it.body}" })
                        }
                        post("/test/reply/{recipientId}") {
                            val service = DirectMessageService(call)
                            val dto = service.sendDirectMessage(recipientId = call.parameters["recipientId"]!!, body = "Ja, noch frei!")
                            call.respondText(dto.id)
                        }
                    }
                }

                // Zwei frische, eigens angelegte ACTIVE-Testmitglieder statt der geteilten
                // Seed-Konten BOARD_ID/MEMBER_ID -- die Messaging-Assertionen hinterlassen echte
                // direct_message-Zeilen, und `ServiceIntegrationTest`s eigener Unread-Zähler-Test
                // prüft einen EXAKTEN Wert für genau diese Seed-Konten (Testisolations-Fund: eine
                // frühere Fassung dieses Tests nutzte BOARD_ID/MEMBER_ID direkt und brach dort die
                // exakte Zähler-Assertion in einem ganz anderen Testfall).
                val authorA = createTestMember(email = "carpool-happy-a-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)
                val authorB = createTestMember(email = "carpool-happy-b-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)

                val offerId = client.post("/test/create?type=OFFER") { header("X-Member-Id", authorA.toString()) }.bodyAsText()
                val requestId = client.post("/test/create?type=REQUEST") { header("X-Member-Id", authorB.toString()) }.bodyAsText()
                createdPostingIds += Uuid.parse(offerId)
                createdPostingIds += Uuid.parse(requestId)

                val all = client.get("/test/list") { header("X-Member-Id", authorB.toString()) }.bodyAsText()
                all shouldContain "$offerId:OFFER"
                all shouldContain "$requestId:REQUEST"

                val offersOnly = client.get("/test/list?type=OFFER") { header("X-Member-Id", authorB.toString()) }.bodyAsText()
                offersOnly shouldContain "$offerId:OFFER"
                (offersOnly.contains("$requestId:REQUEST")) shouldBe false

                // authorB contacts authorA's OFFER -> a direct_message lands in authorA's inbox.
                client.post("/test/contact/$offerId") { header("X-Member-Id", authorB.toString()) }
                val inboxA = client.get("/test/inbox") { header("X-Member-Id", authorA.toString()) }.bodyAsText()
                inboxA shouldContain "Hallo, ist die Fahrt noch frei?"

                // authorA replies -> lands in authorB's inbox.
                client.post("/test/reply/$authorB") { header("X-Member-Id", authorA.toString()) }
                val inboxB = client.get("/test/inbox") { header("X-Member-Id", authorB.toString()) }.bodyAsText()
                inboxB shouldContain "Ja, noch frei!"
            }
        }

        test("CarpoolPostingDto's raw JSON never carries an authorMemberId field") {
            testApplication {
                application {
                    install(StatusPages) { installExceptionHandlers() }
                    routing {
                        post("/test/create-raw") {
                            val service = CarpoolService(call)
                            val dto = service.createPosting(validInput())
                            call.respondText(Json.encodeToString(CarpoolPostingDto.serializer(), dto))
                        }
                    }
                }
                val body = client.post("/test/create-raw") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                body.contains("authorMemberId", ignoreCase = true) shouldBe false
                val id = Json.decodeFromString(CarpoolPostingDto.serializer(), body).id
                createdPostingIds += Uuid.parse(id)
            }
        }

        test("only the author may update or delete a posting; a non-author gets Forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installExceptionHandlers() }
                    routing {
                        post("/test/create") {
                            val service = CarpoolService(call)
                            call.respondText(service.createPosting(validInput()).id)
                        }
                        post("/test/update/{id}") {
                            val service = CarpoolService(call)
                            service.updatePosting(id = call.parameters["id"]!!, input = validInput(departureDate = VALID_FUTURE_DATE_2))
                            call.respondText("ok")
                        }
                        post("/test/delete/{id}") {
                            val service = CarpoolService(call)
                            service.deletePosting(call.parameters["id"]!!)
                            call.respondText("ok")
                        }
                    }
                }
                val id = client.post("/test/create") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdPostingIds += Uuid.parse(id)

                val updateResponse = client.post("/test/update/$id") { header("X-Member-Id", MEMBER_ID) }
                updateResponse.status shouldBe HttpStatusCode.Forbidden

                val deleteResponse = client.post("/test/delete/$id") { header("X-Member-Id", MEMBER_ID) }
                deleteResponse.status shouldBe HttpStatusCode.Forbidden

                // Der Autor selbst darf beides.
                client.post("/test/update/$id") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                client.post("/test/delete/$id") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                createdPostingIds.remove(Uuid.parse(id))
            }
        }

        test("contactAuthor rejects contacting yourself and an unknown/expired posting") {
            testApplication {
                application {
                    install(StatusPages) { installExceptionHandlers() }
                    routing {
                        post("/test/create") {
                            val service = CarpoolService(call)
                            call.respondText(service.createPosting(validInput()).id)
                        }
                        post("/test/contact/{id}") {
                            val service = CarpoolService(call)
                            service.contactAuthor(postingId = call.parameters["id"]!!, message = "Hallo!")
                            call.respondText("ok")
                        }
                    }
                }
                val id = client.post("/test/create") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdPostingIds += Uuid.parse(id)

                val selfContact = client.post("/test/contact/$id") { header("X-Member-Id", BOARD_ID) }
                selfContact.status shouldBe HttpStatusCode.Conflict

                val unknown = client.post("/test/contact/${Uuid.random()}") { header("X-Member-Id", MEMBER_ID) }
                unknown.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("validation: departure date in the past, more than 180 days out, and seatsOffered/type mismatch are all rejected") {
            testApplication {
                application {
                    install(StatusPages) { installExceptionHandlers() }
                    routing {
                        post("/test/create") {
                            val service = CarpoolService(call)
                            val params = call.request.queryParameters
                            val input =
                                CarpoolPostingInput(
                                    type = CarpoolPostingType.valueOf(params["type"] ?: "OFFER"),
                                    fromPlace = "Braunschweig",
                                    toPlace = "Hannover",
                                    departureDate = LocalDate.parse(params["date"] ?: VALID_FUTURE_DATE.toString()),
                                    departureTime = null,
                                    seatsOffered = params["seats"]?.toIntOrNull(),
                                    notes = null,
                                )
                            call.respondText(service.createPosting(input).id)
                        }
                    }
                }

                // Datum in der Vergangenheit.
                client.post("/test/create?date=2020-01-01&type=OFFER&seats=3") { header("X-Member-Id", MEMBER_ID) }.status shouldBe
                    HttpStatusCode.Conflict
                // Mehr als 180 Tage in der Zukunft.
                client.post("/test/create?date=2030-01-01&type=OFFER&seats=3") { header("X-Member-Id", MEMBER_ID) }.status shouldBe
                    HttpStatusCode.Conflict
                // REQUEST mit gesetzten seatsOffered.
                client
                    .post(
                        "/test/create?date=$VALID_FUTURE_DATE&type=REQUEST&seats=3",
                    ) { header("X-Member-Id", MEMBER_ID) }
                    .status shouldBe
                    HttpStatusCode.Conflict
                // OFFER ohne seatsOffered.
                client.post("/test/create?date=$VALID_FUTURE_DATE&type=OFFER") { header("X-Member-Id", MEMBER_ID) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("at most 10 future postings per member -- the 11th is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installExceptionHandlers() }
                    routing {
                        post("/test/create") {
                            val service = CarpoolService(call)
                            call.respondText(service.createPosting(validInput(departureDate = VALID_FUTURE_DATE)).id)
                        }
                    }
                }
                val quotaMember = createTestMember(email = "carpool-quota-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)
                repeat(10) {
                    val id = client.post("/test/create") { header("X-Member-Id", quotaMember.toString()) }.bodyAsText()
                    createdPostingIds += Uuid.parse(id)
                }
                val eleventh = client.post("/test/create") { header("X-Member-Id", quotaMember.toString()) }
                eleventh.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("contact message must be 1-2000 characters") {
            testApplication {
                application {
                    install(StatusPages) { installExceptionHandlers() }
                    routing {
                        post("/test/create") {
                            val service = CarpoolService(call)
                            call.respondText(service.createPosting(validInput()).id)
                        }
                        post("/test/contact/{id}") {
                            val service = CarpoolService(call)
                            service.contactAuthor(
                                postingId = call.parameters["id"]!!,
                                message = call.request.queryParameters["msg"].orEmpty(),
                            )
                            call.respondText("ok")
                        }
                    }
                }
                val id = client.post("/test/create") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdPostingIds += Uuid.parse(id)

                val empty = client.post("/test/contact/$id?msg=") { header("X-Member-Id", MEMBER_ID) }
                empty.status shouldBe HttpStatusCode.Conflict

                val tooLong = client.post("/test/contact/$id?msg=" + "x".repeat(2001)) { header("X-Member-Id", MEMBER_ID) }
                tooLong.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── Rollen-Gate, repräsentativ über mehrere Nicht-ACTIVE-Status ─────────────────────────

        val nonMemberStatuses =
            listOf(MemberStatus.APPLICATION, MemberStatus.GUEST, MemberStatus.FRIEND, MemberStatus.WITHDRAWN, MemberStatus.REJECTED)

        nonMemberStatuses.forEach { status ->
            test("listPostings/createPosting/contactAuthor: $status is Forbidden") {
                testApplication {
                    application {
                        install(StatusPages) { installExceptionHandlers() }
                        routing {
                            get("/test/list") {
                                CarpoolService(call).listPostings(null)
                                call.respondText("ok")
                            }
                            post("/test/create") {
                                CarpoolService(call).createPosting(validInput())
                                call.respondText("ok")
                            }
                            post("/test/contact") {
                                CarpoolService(call).contactAuthor(postingId = Uuid.random().toString(), message = "Hallo")
                                call.respondText("ok")
                            }
                        }
                    }
                    val caller = createTestMember(email = "carpool-gate-$status-${Uuid.random()}@example.org", status = status)

                    client.get("/test/list") { header("X-Member-Id", caller.toString()) }.status shouldBe HttpStatusCode.Forbidden
                    client.post("/test/create") { header("X-Member-Id", caller.toString()) }.status shouldBe HttpStatusCode.Forbidden
                    client.post("/test/contact") { header("X-Member-Id", caller.toString()) }.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        // ── sendDirectMessage-Härtung (Welle V1.9.12 Refactor, insertDirectMessage) ─────────────

        test("sendDirectMessage now rejects an empty body and a self-message (insertDirectMessage hardening)") {
            testApplication {
                application {
                    install(StatusPages) { installExceptionHandlers() }
                    routing {
                        post("/test/send/{recipientId}") {
                            val service = DirectMessageService(call)
                            service.sendDirectMessage(
                                recipientId = call.parameters["recipientId"]!!,
                                body = call.request.queryParameters["body"].orEmpty(),
                            )
                            call.respondText("ok")
                        }
                    }
                }
                val empty = client.post("/test/send/$BOARD_ID?body=") { header("X-Member-Id", MEMBER_ID) }
                empty.status shouldBe HttpStatusCode.Conflict

                val self = client.post("/test/send/$MEMBER_ID?body=Hallo") { header("X-Member-Id", MEMBER_ID) }
                self.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("sendDirectMessage rejects an anonymized/non-organization-member recipient") {
            testApplication {
                application {
                    install(StatusPages) { installExceptionHandlers() }
                    routing {
                        post("/test/send/{recipientId}") {
                            val service = DirectMessageService(call)
                            service.sendDirectMessage(recipientId = call.parameters["recipientId"]!!, body = "Hallo")
                            call.respondText("ok")
                        }
                    }
                }
                val guest = createTestMember(email = "carpool-dm-guest-${Uuid.random()}@example.org", status = MemberStatus.GUEST)
                val response = client.post("/test/send/$guest") { header("X-Member-Id", MEMBER_ID) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }
    })
