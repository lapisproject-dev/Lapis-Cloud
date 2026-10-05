package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberAddressRules
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.33 -- hardening of `updateMemberAddress` / `updateMemberBeneficialOwnerData`:
 * normalization, length / control-character / birth-date rules, value-free audit, authorization order.
 */
class MemberAddressSelfServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
            dateOfDeath: LocalDate? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Adresse Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = if (dateOfDeath != null) MemberStatus.DECEASED else MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 2, 1)
                    it[membershipTierId] = null
                    it[MemberTable.dateOfDeath] = dateOfDeath
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun io.ktor.server.routing.Route.registerRoutes() {
            fun service(call: io.ktor.server.application.ApplicationCall) =
                MemberService(
                    call = call,
                    passwordResetMailer = FakePasswordResetMailer(),
                    adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                    smtpConfigState = SmtpConfigState.NotConfigured,
                    adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                    adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                    adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                    memberCardIssueRateLimiter = FederationInboxRateLimiter(),
                    memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(),
                )
            post("/test/address/{id}") {
                val q = call.request.queryParameters
                service(call).updateMemberAddress(
                    memberId = call.parameters["id"]!!,
                    street = q["street"],
                    postalCode = q["postalCode"],
                    city = q["city"],
                    country = q["country"],
                )
                call.respondText("ok")
            }
            post("/test/gwg/{id}") {
                val q = call.request.queryParameters
                service(call).updateMemberBeneficialOwnerData(
                    memberId = call.parameters["id"]!!,
                    dateOfBirth = q["dob"]?.let(LocalDate::parse),
                    nationality = q["nationality"],
                )
                call.respondText("ok")
            }
        }

        fun io.ktor.server.application.Application.setup() {
            install(StatusPages) { installMemberCardExceptionHandlers() }
            routing { registerRoutes() }
        }

        fun enc(v: String) = v.encodeURLParameter()

        fun row(id: Uuid) = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }

        fun auditCount(memberId: Uuid): Long =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { (AuditLogEntryTable.entityId eq memberId) and (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) }
                    .count()
            }

        test("exact length passes, length+1 is rejected for each of the five text fields") {
            testApplication {
                application { setup() }
                val m = createMember("addr-len@example.org")
                val max = MemberAddressRules
                val ok =
                    client.post {
                        url(
                            "/test/address/$m?street=${"a".repeat(
                                max.STREET_MAX,
                            )}&postalCode=${"1".repeat(
                                max.POSTAL_CODE_MAX,
                            )}&city=${"c".repeat(max.CITY_MAX)}&country=${"d".repeat(max.COUNTRY_MAX)}",
                        )
                        header("X-Member-Id", m.toString())
                    }
                ok.status shouldBe HttpStatusCode.OK
                client
                    .post {
                        url("/test/gwg/$m?nationality=${"n".repeat(max.NATIONALITY_MAX)}")
                        header("X-Member-Id", m.toString())
                    }.status shouldBe HttpStatusCode.OK

                val tooLong =
                    listOf(
                        "street=${"a".repeat(max.STREET_MAX + 1)}",
                        "postalCode=${"1".repeat(max.POSTAL_CODE_MAX + 1)}",
                        "city=${"c".repeat(max.CITY_MAX + 1)}",
                        "country=${"d".repeat(max.COUNTRY_MAX + 1)}",
                    )
                tooLong.forEach { q ->
                    client
                        .post {
                            url("/test/address/$m?$q")
                            header("X-Member-Id", m.toString())
                        }.status shouldBe HttpStatusCode.Conflict
                }
                client
                    .post {
                        url("/test/gwg/$m?nationality=${"n".repeat(max.NATIONALITY_MAX + 1)}")
                        header("X-Member-Id", m.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("control characters are rejected and nothing is written") {
            testApplication {
                application { setup() }
                val m = createMember("addr-ctrl@example.org")
                val before = auditCount(m)
                listOf("a\nb", "a\tb", "\u0000").forEach { v ->
                    client
                        .post {
                            url("/test/address/$m?city=${enc(v)}")
                            header("X-Member-Id", m.toString())
                        }.status shouldBe HttpStatusCode.Conflict
                }
                row(m)[MemberTable.city] shouldBe null
                auditCount(m) shouldBe before
            }
        }

        test("blank stores null, surrounding whitespace is trimmed") {
            testApplication {
                application { setup() }
                val m = createMember("addr-trim@example.org")
                client
                    .post {
                        url("/test/address/$m?street=${enc("  Hauptstrasse 1  ")}&city=${enc("   ")}")
                        header("X-Member-Id", m.toString())
                    }.status shouldBe HttpStatusCode.OK
                row(m)[MemberTable.street] shouldBe "Hauptstrasse 1"
                row(m)[MemberTable.city] shouldBe null
            }
        }

        test("birth date: future, before 1900 and after the date of death are rejected; 1900-01-01 passes") {
            testApplication {
                application { setup() }
                val m = createMember("addr-dob@example.org")
                val dead = createMember("addr-dob-dead@example.org", dateOfDeath = LocalDate(2020, 1, 1))
                client
                    .post {
                        url("/test/gwg/$m?dob=2999-01-01")
                        header("X-Member-Id", m.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post {
                        url("/test/gwg/$m?dob=1899-12-31")
                        header("X-Member-Id", m.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post {
                        url("/test/gwg/$dead?dob=2020-01-02")
                        header("X-Member-Id", dead.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                row(dead)[MemberTable.dateOfBirth] shouldBe null
                client
                    .post {
                        url("/test/gwg/$m?dob=1900-01-01")
                        header("X-Member-Id", m.toString())
                    }.status shouldBe HttpStatusCode.OK
                row(m)[MemberTable.dateOfBirth] shouldBe LocalDate(1900, 1, 1)
            }
        }

        test("an audit entry with a value-free marker is written, and no sent value appears in any audit column") {
            testApplication {
                application { setup() }
                val m = createMember("addr-audit@example.org")
                val street = "Geheimweg-4711"
                val city = "Vertraulichstadt"
                client
                    .post {
                        url("/test/address/$m?street=$street&postalCode=38100&city=$city&country=Wakanda")
                        header("X-Member-Id", m.toString())
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post {
                        url("/test/gwg/$m?dob=1980-02-03&nationality=Atlantean")
                        header("X-Member-Id", m.toString())
                    }.status shouldBe HttpStatusCode.OK

                val entries =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { (AuditLogEntryTable.entityId eq m) and (AuditLogEntryTable.action eq AuditAction.UPDATE) }
                            .toList()
                    }
                entries.map { it[AuditLogEntryTable.afterSnapshot] }.toSet() shouldBe
                    setOf("ADDRESS_UPDATED", "BENEFICIAL_OWNER_DATA_UPDATED")
                val sent = listOf(street, city, "38100", "Wakanda", "1980-02-03", "Atlantean")
                entries.forEach { e ->
                    val columns =
                        listOf(e[AuditLogEntryTable.beforeSnapshot], e[AuditLogEntryTable.afterSnapshot], e[AuditLogEntryTable.entryHash])
                    sent.forEach { value -> columns.any { it?.contains(value) == true } shouldBe false }
                }
            }
        }

        test("authorization: a stranger gets 403 without an audit entry or write; BOARD may edit, TREASURER may not") {
            testApplication {
                application { setup() }
                val subject = createMember("addr-subject@example.org")
                val stranger = createMember("addr-stranger@example.org")
                val board = createMember("addr-board@example.org", role = AccountRole.BOARD)
                val treasurer = createMember("addr-treasurer@example.org", role = AccountRole.TREASURER)
                val before = auditCount(subject)

                // Even an INVALID value must yield 403, not 409: Forbidden comes before input validation.
                client
                    .post {
                        url("/test/address/$subject?city=${enc("a\nb")}")
                        header("X-Member-Id", stranger.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post {
                        url("/test/gwg/$subject?dob=2999-01-01")
                        header("X-Member-Id", stranger.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post {
                        url("/test/address/$subject?city=X")
                        header("X-Member-Id", treasurer.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                row(subject)[MemberTable.city] shouldBe null
                auditCount(subject) shouldBe before

                client
                    .post {
                        url("/test/address/$subject?city=Bonn")
                        header("X-Member-Id", board.toString())
                    }.status shouldBe HttpStatusCode.OK
                row(subject)[MemberTable.city] shouldBe "Bonn"
                auditCount(subject) shouldNotBe before
            }
        }

        test("the Kotlin length constants equal the database column lengths") {
            fun len(c: org.jetbrains.exposed.v1.core.Column<String?>): Int = (c.columnType as VarCharColumnType).colLength
            len(MemberTable.street) shouldBe MemberAddressRules.STREET_MAX
            len(MemberTable.postalCode) shouldBe MemberAddressRules.POSTAL_CODE_MAX
            len(MemberTable.city) shouldBe MemberAddressRules.CITY_MAX
            len(MemberTable.country) shouldBe MemberAddressRules.COUNTRY_MAX
            len(MemberTable.nationality) shouldBe MemberAddressRules.NATIONALITY_MAX
        }
    })
