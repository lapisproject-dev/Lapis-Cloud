package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.rpc.AgreementTextAdminService
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextDraftInput
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.AgreementTextDraftChangedException
import network.lapis.cloud.shared.rpc.AgreementTextVersionLabelTakenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.134 -- the publication of an agreement text against a real PostgreSQL (and, as a compatibility smoke test, H2): two admins publishing the
 * SAME draft at the same time produce exactly one new version and one `DraftChanged` (the draft row lock plus the optimistic hash check), two
 * different kinds never block each other, and no deadlock is reported. The callers authenticate with a real session (the `X-Member-Id` fallback
 * is H2-only).
 */
abstract class AgreementTextPublishConcurrencyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val adminId = Uuid.random()

        beforeSpec {
            db.activate()
            // `seedIfEmpty` seeds only into a database WITHOUT members: this spec's own member must not be the reason the seed never happens.
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
            transaction {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Vertragstext-Admin"
                    it[email] = "agreement-admin-$adminId@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[role] = AccountRole.ADMIN
                }
            }
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterEach { AgreementTextTestState.reset() }
        afterSpec {
            AgreementTextTestState.reset()
            transaction {
                SessionTable.deleteWhere { SessionTable.memberId eq adminId }
                AccountTable.deleteWhere { memberId eq adminId }
                MemberTable.deleteWhere { id eq adminId }
            }
            db.deactivate()
        }

        suspend fun HttpClient.save(
            token: String,
            kind: AgreementTextKind,
            label: String,
            body: String,
        ): String =
            post("/t/save?kind=${kind.name}&label=$label&validFrom=${OrganizationTimeZone.today()}") {
                header("Authorization", "Bearer $token")
                setBody(body)
            }.bodyAsText()

        suspend fun HttpClient.publish(
            token: String,
            kind: AgreementTextKind,
            sha: String,
        ): String = post("/t/publish?kind=${kind.name}&sha=$sha") { header("Authorization", "Bearer $token") }.bodyAsText()

        fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) {
                        exception<AgreementTextDraftChangedException> {
                            call,
                            _,
                            ->
                            call.respondText("DraftChanged", status = HttpStatusCode.Conflict)
                        }
                        exception<AgreementTextVersionLabelTakenException> {
                            call,
                            _,
                            ->
                            call.respondText("LabelTaken", status = HttpStatusCode.Conflict)
                        }
                    }
                    routing {
                        post("/t/save") {
                            val q = call.request.queryParameters
                            val body = call.receiveText()
                            val saved =
                                service(call).saveDraft(
                                    AgreementTextDraftInput(
                                        kind = AgreementTextKind.valueOf(q["kind"]!!),
                                        versionLabel = q["label"]!!,
                                        validFrom = LocalDate.parse(q["validFrom"]!!),
                                        body = body,
                                    ),
                                )
                            call.respondText(saved.sha256)
                        }
                        post("/t/publish") {
                            val q = call.request.queryParameters
                            val published =
                                service(
                                    call,
                                ).publishDraft(kind = AgreementTextKind.valueOf(q["kind"]!!), expectedDraftSha256 = q["sha"]!!)
                            call.respondText("OK:${published.sequenceNo}")
                        }
                    }
                }
                block()
            }

        test("two admins publishing the same draft at once create exactly one version; the other gets DraftChanged") {
            val token = SessionStore.createSession(adminId).rawToken
            withApp {
                val sha = client.save(token, AgreementTextKind.MEMBERSHIP, "race.v1", "Wettlauf um den Entwurf")
                val results =
                    coroutineScope {
                        (1..2).map { async(Dispatchers.IO) { client.publish(token, AgreementTextKind.MEMBERSHIP, sha) } }.awaitAll()
                    }
                results.count { it == "OK:1" } shouldBe 1
                results.count { it == "DraftChanged" } shouldBe 1
                AgreementTextTestState.versionRows().size shouldBe 1
                AgreementTextTestState.draftRows().size shouldBe 0
            }
        }

        test("the two kinds publish in parallel without blocking each other and each gets sequence number 1") {
            val token = SessionStore.createSession(adminId).rawToken
            withApp {
                val m = client.save(token, AgreementTextKind.MEMBERSHIP, "par.v1", "Mitglieder")
                val f = client.save(token, AgreementTextKind.FRIEND, "par.v1", "Freunde")
                val results =
                    coroutineScope {
                        listOf(
                            async(Dispatchers.IO) { client.publish(token, AgreementTextKind.MEMBERSHIP, m) },
                            async(Dispatchers.IO) { client.publish(token, AgreementTextKind.FRIEND, f) },
                        ).awaitAll()
                    }
                results shouldBe listOf("OK:1", "OK:1")
                AgreementTextTestState.versionRows().size shouldBe 2
            }
        }
    })

private fun service(call: ApplicationCall) = AgreementTextAdminService(call = call)
