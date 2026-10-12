package network.lapis.cloud.server.rpc

import dev.kilua.rpc.AbstractServiceException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.MemberNationalityTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.FakePeerNotificationMailer
import network.lapis.cloud.server.member.PeerFixture
import network.lapis.cloud.server.member.configuredSmtp
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberDto
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.121 -- the write paths of the country / nationality selection lists through a real `MemberService`:
 * validation (after authorization), the legacy-text rule, peer protection, the value-free audit entry and the effective
 * values in the DTOs. H2 only (the lock / CAS behaviour on PostgreSQL is in [MemberCountryPostgresTest]).
 */
class MemberCountryRpcTest :
    FunSpec({
        val fx = PeerFixture()
        val mailer = FakePeerNotificationMailer()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.isolateAdmins() }
        afterTest { fx.restoreAdmins() }

        fun memberService(call: ApplicationCall): MemberService =
            MemberService(
                call = call,
                passwordResetMailer = FakePasswordResetMailer(),
                adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                smtpConfigState = configuredSmtp(),
                adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                memberCardIssueRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(maxRequests = 1000),
                peerNotificationMailer = mailer,
            )

        fun describe(dto: MemberDto): String =
            "OK:${dto.country}|${dto.countryCode}|${dto.countryLegacy}|${dto.nationality}|${dto.nationalityCodes.joinToString(
                ",",
            )}|${dto.nationalityLegacy}"

        fun Application.setup() {
            install(StatusPages) {
                exception<AbstractServiceException> {
                    call,
                    cause,
                    ->
                    call.respondText(cause::class.simpleName!!, status = HttpStatusCode.Conflict)
                }
            }
            routing {
                post("/t/country/{id}") {
                    val dto =
                        memberService(
                            call,
                        ).updateMemberCountry(memberId = call.parameters["id"]!!, countryCode = call.request.queryParameters["code"])
                    call.respondText(describe(dto))
                }
                post("/t/nat/{id}") {
                    val q = call.request.queryParameters
                    val codes = q["codes"].orEmpty().split(',').filter { it.isNotEmpty() }
                    val dto =
                        memberService(
                            call,
                        ).updateMemberNationalities(
                            memberId = call.parameters["id"]!!,
                            dateOfBirth = q["dob"]?.let(LocalDate::parse),
                            nationalityCodes = codes,
                        )
                    call.respondText(describe(dto))
                }
                post("/t/street/{id}") {
                    val q = call.request.queryParameters
                    val dto =
                        memberService(
                            call,
                        ).updateMemberStreetAddress(
                            memberId = call.parameters["id"]!!,
                            street = q["street"],
                            postalCode = q["zip"],
                            city = q["city"],
                        )
                    call.respondText(describe(dto))
                }
                post("/t/legacyaddr/{id}") {
                    val q = call.request.queryParameters
                    val dto =
                        memberService(
                            call,
                        ).updateMemberAddress(
                            memberId = call.parameters["id"]!!,
                            street = q["street"],
                            postalCode = q["zip"],
                            city = q["city"],
                            country = q["country"],
                        )
                    call.respondText(describe(dto))
                }
                post("/t/legacygwg/{id}") {
                    val q = call.request.queryParameters
                    val dto =
                        memberService(
                            call,
                        ).updateMemberBeneficialOwnerData(
                            memberId = call.parameters["id"]!!,
                            dateOfBirth = null,
                            nationality = q["nationality"],
                        )
                    call.respondText(describe(dto))
                }
                post("/t/read/{id}") {
                    val d = memberService(call).getMemberAddressForAdministration(call.parameters["id"]!!)
                    call.respondText(
                        "OK:${d.protectedTarget}|${d.country}|${d.countryCode}|${d.countryLegacy}|${d.nationality}|" +
                            "${d.nationalityCodes.joinToString(",")}|${d.nationalityLegacy}",
                    )
                }
            }
        }

        suspend fun HttpClient.act(
            path: String,
            actor: Uuid,
        ): Pair<HttpStatusCode, String> {
            val r = post(path) { header("X-Member-Id", actor.toString()) }
            return r.status to r.bodyAsText()
        }

        fun row(id: Uuid) = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }

        fun codesOf(id: Uuid): List<String> =
            transaction {
                MemberNationalityTable
                    .selectAll()
                    .where { MemberNationalityTable.memberId eq id }
                    .map { it[MemberNationalityTable.countryCode] }
                    .sorted()
            }

        fun withLegacy(
            role: AccountRole = AccountRole.MEMBER,
            country: String? = "Polen-Altwert",
            nationality: String? = "polnisch-Altwert",
        ): Uuid {
            val id = fx.member(role = role)
            transaction {
                MemberTable.update({ MemberTable.id eq id }) {
                    it[MemberTable.country] = country
                    it[MemberTable.nationality] = nationality
                }
            }
            return id
        }

        test("choosing a country sets the code, clears the legacy text and the DTO carries the effective name") {
            testApplication {
                application { setup() }
                val m = withLegacy()
                client.act("/t/country/$m?code=pl", m) shouldBe (HttpStatusCode.OK to "OK:Polen|PL|null|polnisch-Altwert||polnisch-Altwert")
                row(m)[MemberTable.countryCode] shouldBe "PL"
                row(m)[MemberTable.country] shouldBe null
            }
        }

        test("clearing the code leaves the legacy text alone; Kosovo is selectable") {
            testApplication {
                application { setup() }
                val m = withLegacy(country = null)
                client.act("/t/country/$m?code=XK", m).second shouldContain "OK:Kosovo|XK"
                client.act("/t/country/$m", m) shouldBe (HttpStatusCode.OK to "OK:null|null|null|polnisch-Altwert||polnisch-Altwert")
                row(m)[MemberTable.countryCode] shouldBe null
                val legacy = withLegacy(country = "Altland")
                client.act("/t/country/$legacy", legacy).second shouldContain "OK:Altland|null|Altland"
            }
        }

        test("invalid country values are rejected value-free and nothing is written: unknown, internal XS/ZZ, wrong shape") {
            testApplication {
                application { setup() }
                val m = withLegacy()
                listOf("QQ", "XS", "ZZ", "DEU", "D", "1A").forEach { bad ->
                    client.act("/t/country/$m?code=$bad", m) shouldBe (HttpStatusCode.Conflict to "ConflictException")
                }
                row(m)[MemberTable.countryCode] shouldBe null
                row(m)[MemberTable.country] shouldBe "Polen-Altwert"
            }
        }

        test("authorization comes before validation, and no member-existence oracle: a stranger gets Forbidden for everything") {
            testApplication {
                application { setup() }
                val subject = withLegacy()
                val stranger = fx.member()
                client.act("/t/country/$subject?code=QQ", stranger) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                client.act("/t/nat/$subject?codes=QQ", stranger) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                client.act("/t/country/${Uuid.random()}?code=QQ", stranger) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                client.act("/t/country/${Uuid.random()}?code=PL", stranger) shouldBe (HttpStatusCode.Conflict to "ForbiddenException")
                row(subject)[MemberTable.countryCode] shouldBe null
                // the privileged caller learns about a missing member only after the value passed validation
                val board = fx.member(role = AccountRole.BOARD)
                client.act("/t/country/${Uuid.random()}?code=PL", board) shouldBe (HttpStatusCode.Conflict to "NotFoundException")
                client.act("/t/country/$subject?code=PL", board).first shouldBe HttpStatusCode.OK
            }
        }

        test("nationalities: sorted, deduplicated, legacy text cleared, effective German text, at most 10") {
            testApplication {
                application { setup() }
                val m = withLegacy()
                client.act("/t/nat/$m?codes=pl,DE,PL", m) shouldBe
                    (HttpStatusCode.OK to "OK:Polen-Altwert|null|Polen-Altwert|Deutschland, Polen|DE,PL|null")
                codesOf(m) shouldBe listOf("DE", "PL")
                row(m)[MemberTable.nationality] shouldBe null
                val ten = "AD,AE,AF,AG,AI,AL,AM,AO,AQ,AR"
                client.act("/t/nat/$m?codes=$ten", m).first shouldBe HttpStatusCode.OK
                codesOf(m) shouldHaveSize 10
                client.act("/t/nat/$m?codes=$ten,AT", m) shouldBe (HttpStatusCode.Conflict to "ConflictException")
                codesOf(m) shouldHaveSize 10
            }
        }

        test("XS stateless and ZZ unclear stand alone; choosing one replaces the list, empty clears the codes only") {
            testApplication {
                application { setup() }
                val m = withLegacy(nationality = "Altnat")
                client.act("/t/nat/$m?codes=XS,DE", m) shouldBe (HttpStatusCode.Conflict to "ConflictException")
                client.act("/t/nat/$m?codes=ZZ,XS", m) shouldBe (HttpStatusCode.Conflict to "ConflictException")
                codesOf(m).shouldBeEmpty()
                row(m)[MemberTable.nationality] shouldBe "Altnat"
                client.act("/t/nat/$m?codes=DE,PL", m).first shouldBe HttpStatusCode.OK
                client.act("/t/nat/$m?codes=XS", m).second shouldContain "|staatenlos|XS|"
                codesOf(m) shouldBe listOf("XS")
                client.act("/t/nat/$m?codes=ZZ", m).second shouldContain "|ungeklärt|ZZ|"
                client.act("/t/nat/$m", m).first shouldBe HttpStatusCode.OK
                codesOf(m).shouldBeEmpty()
                // empty list does not resurrect the legacy text that a code selection had already cleared
                row(m)[MemberTable.nationality] shouldBe null
            }
        }

        test("the date of birth rides along and keeps its plausibility rule; a rejected request changes nothing") {
            testApplication {
                application { setup() }
                val m = withLegacy()
                client.act("/t/nat/$m?codes=DE&dob=2999-01-01", m) shouldBe (HttpStatusCode.Conflict to "ConflictException")
                codesOf(m).shouldBeEmpty()
                client.act("/t/nat/$m?codes=DE&dob=1980-02-03", m).first shouldBe HttpStatusCode.OK
                row(m)[MemberTable.dateOfBirth] shouldBe LocalDate(1980, 2, 3)
                codesOf(m) shouldBe listOf("DE")
            }
        }

        test("the street address RPC leaves country and code untouched") {
            testApplication {
                application { setup() }
                val m = withLegacy()
                client.act("/t/country/$m?code=PL", m).first shouldBe HttpStatusCode.OK
                client.act("/t/street/$m?street=Weg+1&zip=38100&city=Bonn", m).second shouldContain "OK:Polen|PL"
                row(m)[MemberTable.street] shouldBe "Weg 1"
                row(m)[MemberTable.countryCode] shouldBe "PL"
            }
        }

        test("peer protection: BOARD cannot write an ADMIN's country or nationality; an ADMIN can and the target is told") {
            testApplication {
                mailer.protectedDataNotices.clear()
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val admin = withLegacy(role = AccountRole.ADMIN)
                client.act("/t/country/$admin?code=PL", board) shouldBe (HttpStatusCode.Conflict to "PeerProtectionDeniedException")
                client.act("/t/nat/$admin?codes=PL", board) shouldBe (HttpStatusCode.Conflict to "PeerProtectionDeniedException")
                row(admin)[MemberTable.countryCode] shouldBe null
                codesOf(admin).shouldBeEmpty()
                val otherAdmin = fx.admin(displayName = "Schreibender Admin")
                client.act("/t/country/$admin?code=PL", otherAdmin).first shouldBe HttpStatusCode.OK
                client.act("/t/nat/$admin?codes=PL", otherAdmin).first shouldBe HttpStatusCode.OK
                mailer.protectedDataNotices.map { it.actorName }.distinct() shouldBe listOf("Schreibender Admin")
                mailer.protectedDataNotices shouldHaveSize 2
            }
        }

        test("the audit entry is value-free (no code, no name appears in any audit column)") {
            testApplication {
                application { setup() }
                val m = withLegacy()
                client.act("/t/country/$m?code=PL", m).first shouldBe HttpStatusCode.OK
                client.act("/t/nat/$m?codes=GE,DE", m).first shouldBe HttpStatusCode.OK
                val audit = fx.auditJsonFor(m).joinToString("\n")
                audit shouldContain "ADDRESS_UPDATED"
                audit shouldContain "BENEFICIAL_OWNER_DATA_UPDATED"
                listOf("PL", "GE", "Polen", "Georgien", "Deutschland", "\"DE\"").forEach { audit shouldNotContain it }
            }
        }

        test("administrative read: effective values plus codes and legacy; an ADMIN's data is withheld from BOARD") {
            testApplication {
                application { setup() }
                val board = fx.member(role = AccountRole.BOARD)
                val withCode = fx.member()
                client.act("/t/country/$withCode?code=PL", withCode).first shouldBe HttpStatusCode.OK
                client.act("/t/nat/$withCode?codes=PL,DE", withCode).first shouldBe HttpStatusCode.OK
                client.act("/t/read/$withCode", board) shouldBe
                    (HttpStatusCode.OK to "OK:false|Polen|PL|null|Deutschland, Polen|DE,PL|null")
                val legacyOnly = withLegacy()
                client.act("/t/read/$legacyOnly", board) shouldBe
                    (HttpStatusCode.OK to "OK:false|Polen-Altwert|null|Polen-Altwert|polnisch-Altwert||polnisch-Altwert")
                val admin = withLegacy(role = AccountRole.ADMIN)
                client.act("/t/country/$admin?code=DE", fx.admin()).first shouldBe HttpStatusCode.OK
                client.act("/t/read/$admin", board) shouldBe (HttpStatusCode.OK to "OK:true|null|null|null|null||null")
            }
        }

        test("legacy RPCs (cached browser tabs): the effective name sent back keeps the code; other text becomes legacy and drops it") {
            testApplication {
                application { setup() }
                val m = fx.member()
                client.act("/t/country/$m?code=PL", m).first shouldBe HttpStatusCode.OK
                client.act("/t/nat/$m?codes=DE,PL", m).first shouldBe HttpStatusCode.OK
                // an old tab saves its form unchanged: the effective names come back
                client.act("/t/legacyaddr/$m?country=Polen", m).first shouldBe HttpStatusCode.OK
                client.act("/t/legacygwg/$m?nationality=${"Deutschland, Polen".replace(" ", "+")}", m).first shouldBe HttpStatusCode.OK
                row(m)[MemberTable.countryCode] shouldBe "PL"
                row(m)[MemberTable.country] shouldBe null
                codesOf(m) shouldBe listOf("DE", "PL")
                // an old tab types something else
                client.act("/t/legacyaddr/$m?country=Atlantis", m).second shouldContain "OK:Atlantis|null|Atlantis"
                client.act("/t/legacygwg/$m?nationality=Atlanter", m).second shouldContain "|Atlanter|"
                row(m)[MemberTable.countryCode] shouldBe null
                row(m)[MemberTable.country] shouldBe "Atlantis"
                codesOf(m).shouldBeEmpty()
                // clearing the field clears everything
                client.act("/t/legacyaddr/$m", m).first shouldBe HttpStatusCode.OK
                row(m)[MemberTable.country] shouldBe null
            }
        }
    })
