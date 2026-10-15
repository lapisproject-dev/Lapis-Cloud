package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.agreementtexts.AgreementTextHash
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AgreementTextDraftTable
import network.lapis.cloud.server.db.generated.AgreementTextVersionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.RegistrationInput
import network.lapis.cloud.shared.rpc.AgreementTextOutdatedException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.134 -- what the PUBLIC registration endpoints show: the built-in placeholder until a version is published, then the highest
 * `sequence_no` -- never the draft -- and a refusal when the stored hash no longer matches the stored text.
 */
class AgreementTextPublicFetchTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach { AgreementTextTestState.reset() }
        afterSpec { AgreementTextTestState.reset() }

        fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ServiceBusyException> { call, _ -> call.respondText("Busy", status = HttpStatusCode.ServiceUnavailable) }
                        exception<AgreementTextOutdatedException> {
                            call,
                            _,
                            ->
                            call.respondText("Outdated", status = HttpStatusCode.Conflict)
                        }
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerPublicFetchRoutes() }
                }
                block()
            }

        test("without any published version both documents are the built-in placeholders, with their own label and hash") {
            withApp {
                client.get("/t/membership").bodyAsText() shouldBe
                    "${MembershipAgreementDisclaimer.VERSION}|${MembershipAgreementDisclaimer.SHA256}|${MembershipAgreementDisclaimer.TEXT}"
                client.get("/t/friend").bodyAsText() shouldBe
                    "${FriendTermsDisclaimer.VERSION}|${FriendTermsDisclaimer.SHA256}|${FriendTermsDisclaimer.TEXT}"
            }
        }

        test("a published version replaces the placeholder for its kind only; the draft is never shown") {
            val sha =
                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.MEMBERSHIP,
                    label = "pub.v1",
                    body = "Veroeffentlichter Text",
                )
            transaction {
                AgreementTextDraftTable.insert {
                    it[kind] = AgreementTextKind.MEMBERSHIP.name
                    it[id] = Uuid.random()
                    it[versionLabel] = "pub.v2"
                    it[validFrom] = kotlinx.datetime.LocalDate(2026, 10, 2)
                    it[body] = "Nur ein Entwurf"
                    it[sha256] = "c".repeat(64)
                    it[charCount] = 15
                    it[updatedBy] = Uuid.parse("00000000-0000-0000-0000-000000000001")
                    it[updatedAt] =
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                }
            }
            withApp {
                client.get("/t/membership").bodyAsText() shouldBe "pub.v1|$sha|Veroeffentlichter Text"
                client.get("/t/friend").bodyAsText().startsWith(FriendTermsDisclaimer.VERSION) shouldBe true
            }
        }

        test("the highest sequence number wins") {
            AgreementTextTestState.publishDirect(kind = AgreementTextKind.FRIEND, label = "f.v1", body = "Eins")
            val sha = AgreementTextTestState.publishDirect(kind = AgreementTextKind.FRIEND, label = "f.v2", body = "Zwei")
            withApp { client.get("/t/friend").bodyAsText() shouldBe "f.v2|$sha|Zwei" }
        }

        test("a stored hash that does not match the stored text is refused: no text is shown and no registration is accepted") {
            AgreementTextTestState.publishDirect(kind = AgreementTextKind.MEMBERSHIP, label = "tamper.v1", body = "Original")
            transaction {
                AgreementTextVersionTable.update({ AgreementTextVersionTable.kind eq "MEMBERSHIP" }) { it[body] = "Verfaelscht" }
            }
            withApp {
                client.get("/t/membership").status shouldBe HttpStatusCode.ServiceUnavailable
                val response =
                    client.post(
                        "/t/register?agreementVersion=tamper.v1&agreementSha256=" +
                            AgreementTextHash.of(versionLabel = "tamper.v1", body = "Original"),
                    )
                response.status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }
    })

private fun Route.registerPublicFetchRoutes() {
    fun service(call: ApplicationCall) =
        RegistrationService(
            call = call,
            registrationRateLimiter = LoginRateLimiter(),
            friendRegistrationRateLimiter = LoginRateLimiter(),
            friendSignupIpRateLimiter = FederationInboxRateLimiter(),
            friendVerificationMailer = FakeFriendVerificationMailer(),
            keycloakConfig = KeycloakConfig.load(env = { null }),
        )
    get("/t/membership") {
        val dto = service(call).getMembershipAgreement()
        call.respondText("${dto.version}|${dto.sha256}|${dto.text}")
    }
    get("/t/friend") {
        val dto = service(call).getFriendTerms()
        call.respondText("${dto.version}|${dto.sha256}|${dto.text}")
    }
    post("/t/register") {
        val q = call.request.queryParameters
        service(call).registerApplication(
            RegistrationInput(
                displayName = "Never Created",
                email = "never-created-${Uuid.random()}@example.org",
                password = "a-genuinely-strong-password-1",
                agreementVersion = q["agreementVersion"]!!,
                agreementSha256 = q["agreementSha256"]!!,
            ),
        )
        call.respondText("OK")
    }
}
