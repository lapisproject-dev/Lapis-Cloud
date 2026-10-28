package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.routes.registerDocumentRoutes
import network.lapis.cloud.server.routes.registerMeetingProtocolRoutes
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ProtocolConfirmOutcome
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.240 -- the HTTP side of the finalised Protokoll: the PDF download route (401 without a session, one 404 for everything the caller must
 * not get, headers, 429) and the evidence guard of the document upload route (a Protokoll document gets no uploaded version).
 */
class ProtocolRoutesTest :
    FunSpec({
        val fx = SessionFixture()
        val storage = newStorageRoot()
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            fx.cleanup()
            storage.deleteRecursively()
        }

        fun multipart() =
            MultiPartFormDataContent(
                formData {
                    append(
                        "file",
                        "tampered".toByteArray(Charsets.UTF_8),
                        Headers.build {
                            append(HttpHeaders.ContentType, "text/plain")
                            append(HttpHeaders.ContentDisposition, "filename=\"x.txt\"")
                        },
                    )
                },
            )

        test(
            "download: 401 without a session, 404 for stranger and bad ids, headers for a participant; upload onto the Protokoll document is a 409",
        ) {
            val s = fx.scene()
            val f = fx.finalOps(storage)
            s.openAndWrite(final = f)
            val version = s.finaliseAs(final = f, who = s.lead).version!!
            f.confirm(current = s.minutes, meetingId = s.meetingId.toString(), versionId = version.versionId).outcome shouldBe
                ProtocolConfirmOutcome.FULLY_CONFIRMED
            val documentId =
                transaction {
                    MeetingTable.selectAll().where { MeetingTable.id eq s.meetingId }.single()[MeetingTable.protocolDocumentId]!!
                }
            val versionsBefore =
                transaction { DocumentVersionTable.selectAll().where { DocumentVersionTable.documentId eq documentId }.count() }
            val stranger = fx.newMember()
            val admin = fx.newMember(role = AccountRole.ADMIN)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        registerMeetingProtocolRoutes(storage)
                        registerDocumentRoutes(storage)
                    }
                }
                val url = "/api/meeting-protocol/${version.versionId}/pdf"
                client.get(url).status shouldBe HttpStatusCode.Unauthorized
                client.get(url) { header("X-Member-Id", stranger.memberId.toString()) }.status shouldBe HttpStatusCode.NotFound
                client
                    .get(
                        "/api/meeting-protocol/not-a-uuid/pdf",
                    ) { header("X-Member-Id", s.participant.memberId.toString()) }
                    .status shouldBe
                    HttpStatusCode.NotFound
                val ok = client.get(url) { header("X-Member-Id", s.participant.memberId.toString()) }
                ok.status shouldBe HttpStatusCode.OK
                ok.headers["X-Content-Type-Options"] shouldBe "nosniff"
                (ok.headers[HttpHeaders.ContentDisposition]!!.contains("attachment")) shouldBe true
                (ok.headers[HttpHeaders.ContentDisposition]!!.contains("Protokoll_F1.pdf")) shouldBe true
                // Evidence: not even an ADMIN can add a version to the Protokoll document.
                val upload =
                    client.post("/api/documents/$documentId/versions") {
                        header("X-Member-Id", admin.memberId.toString())
                        setBody(multipart())
                    }
                upload.status shouldBe HttpStatusCode.Conflict
            }
            transaction { DocumentVersionTable.selectAll().where { DocumentVersionTable.documentId eq documentId }.count() } shouldBe
                versionsBefore
        }

        test("download: the 61st request of one member within a minute is a 429") {
            val someone = fx.newMember()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { registerMeetingProtocolRoutes(storage) }
                }
                val url = "/api/meeting-protocol/00000000-0000-0000-0000-000000000001/pdf"
                repeat(
                    60,
                ) { client.get(url) { header("X-Member-Id", someone.memberId.toString()) }.status shouldBe HttpStatusCode.NotFound }
                client.get(url) { header("X-Member-Id", someone.memberId.toString()) }.status shouldBe HttpStatusCode.TooManyRequests
            }
        }
    })
