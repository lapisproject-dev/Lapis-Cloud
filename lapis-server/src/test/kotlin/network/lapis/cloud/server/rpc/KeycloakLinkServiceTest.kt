package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.delete
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.OidcLoginAuditRecorder
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestLoginEventTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.KeycloakLinkChange
import network.lapis.cloud.server.mail.KeycloakLinkNotificationMailer
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OidcLoginEventType
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val ISSUER = "https://keycloak.example.org/realms/lapis"

/** V1.7.2 sub-wave 2a -- same helper shape as [AuthServiceTest]'s/[KeycloakConfigTest]'s own `operational()`. */
private fun keycloakEnabledConfig(): KeycloakConfig {
    val env =
        mapOf(
            KeycloakConfig.ENV_ENABLED to "true",
            KeycloakConfig.ENV_ISSUER_URL to ISSUER,
            KeycloakConfig.ENV_CLIENT_ID to "lapis-cloud",
            KeycloakConfig.ENV_CLIENT_SECRET to "kc-very-secret-client-secret-123",
        )
    return KeycloakConfig.load { env[it] }
}

private fun keycloakDisabledConfig(): KeycloakConfig = KeycloakConfig.load { null }

/**
 * Exercises [KeycloakLinkService] -- role enforcement (ADMIN-only, every method, mirroring
 * [TrustAnchorServiceTest]'s own house style), [KeycloakLinkService.listUnlinkedMembers]'s
 * LOGIN_BLOCKED/already-linked exclusion, [KeycloakLinkService.linkMember]'s `linkedBy` (never
 * `NULL`, unlike an auto-link -- see [network.lapis.cloud.server.keycloak.KeycloakAccountLinkTable]
 * KDoc), conflict handling for a member/identity that is already linked, and
 * [KeycloakLinkService.unlinkMember]'s idempotency.
 */
class KeycloakLinkServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec { cleanUpKeycloakLinkServiceTestData(createdMemberIds) }

        fun createTestMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Keycloak-Link Testmitglied $email"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                    it[passwordHash] = "irrelevant-hash"
                }
            }
            createdMemberIds += id
            return id
        }

        fun adminId(): Uuid = createTestMember("keycloak-link-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)

        // ── listUnlinkedMembers ────────────────────────────────────────────

        test("listUnlinkedMembers(): excludes an already-linked member and a LOGIN_BLOCKED member, includes a plain unlinked ACTIVE one") {
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes() }
                }
                val admin = adminId()
                val unlinked = createTestMember("kc-link-unlinked-${Uuid.random()}@example.org")
                val alreadyLinked = createTestMember("kc-link-already-linked-${Uuid.random()}@example.org")
                val withdrawn = createTestMember("kc-link-withdrawn-${Uuid.random()}@example.org", status = MemberStatus.WITHDRAWN)
                transaction {
                    KeycloakAccountLinkTable.insert {
                        it[id] = Uuid.random()
                        it[memberId] = alreadyLinked
                        it[keycloakIssuer] = ISSUER
                        it[keycloakSubject] = "subject-${Uuid.random()}"
                        it[linkedAt] = kotlinx.datetime.LocalDateTime(2026, 1, 1, 0, 0)
                        it[linkedBy] = null
                        it[lastLoginAt] = null
                    }
                }

                val response = client.get("/test/list-unlinked") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val ids = response.bodyAsText().split(",").filter { it.isNotBlank() }
                ids shouldContain unlinked.toString()
                ids shouldNotContain alreadyLinked.toString()
                ids shouldNotContain withdrawn.toString()
            }
        }

        // ── linkMember ───────────────────────────────────────────────────

        test("linkMember(): happy path creates a row with linkedBy = the calling admin (never null)") {
            val mailer = RecordingKeycloakLinkMailer()
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes(mailer = mailer) }
                }
                val admin = adminId()
                val memberEmail = "kc-link-happy-${Uuid.random()}@example.org"
                val member = createTestMember(memberEmail)
                val subject = "subject-${Uuid.random()}"

                val response =
                    client.post("/test/link-member?memberId=$member&subject=$subject") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.OK

                val row =
                    transaction { KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq member }.single() }
                row[KeycloakAccountLinkTable.linkedBy] shouldBe admin
                row[KeycloakAccountLinkTable.keycloakIssuer] shouldBe ISSUER
                row[KeycloakAccountLinkTable.keycloakSubject] shouldBe subject

                // Review fix (MAJOR 1): the audit event itself must identify the ACTING admin, not
                // just the target member -- `linkedBy` above is the row's own record, this is the
                // separate forensic trail's record of the same fact.
                val auditEvent =
                    transaction {
                        OidcGuestLoginEventTable
                            .selectAll()
                            .where {
                                (OidcGuestLoginEventTable.memberId eq member) and
                                    (OidcGuestLoginEventTable.eventType eq OidcLoginEventType.KEYCLOAK_LINK_MANUAL)
                            }.single()
                    }
                // Security-audit fix: the audit event also records WHICH identity was linked -- as a
                // fingerprint, never the raw subject (see keycloakLinkAuditReason KDoc).
                auditEvent[OidcGuestLoginEventTable.reason] shouldBe "admin=$admin subjectSha256=${keycloakSubjectFingerprint(subject)}"
                auditEvent[OidcGuestLoginEventTable.reason]!! shouldNotContain subject

                // Security-audit fix: the target member is told about the manual link.
                mailer.sent shouldBe listOf(SentNotice(email = memberEmail, change = KeycloakLinkChange.LINKED))
            }
        }

        test("linkMember(): a LOGIN_BLOCKED target is rejected with ConflictException -- no row, no audit event, no notice") {
            val mailer = RecordingKeycloakLinkMailer()
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes(mailer = mailer) }
                }
                val admin = adminId()
                for (status in listOf(MemberStatus.WITHDRAWN, MemberStatus.DECEASED)) {
                    val member = createTestMember("kc-link-blocked-${Uuid.random()}@example.org", status = status)
                    val response =
                        client.post("/test/link-member?memberId=$member&subject=subject-${Uuid.random()}") {
                            header("X-Member-Id", admin.toString())
                        }
                    response.status shouldBe HttpStatusCode.Conflict
                    transaction {
                        KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq member }.count() shouldBe 0L
                        OidcGuestLoginEventTable.selectAll().where { OidcGuestLoginEventTable.memberId eq member }.count() shouldBe 0L
                    }
                }
                mailer.sent shouldBe emptyList()
            }
        }

        test("linkMember(): a rejected conflicting link writes no audit event and sends no notice") {
            val mailer = RecordingKeycloakLinkMailer()
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes(mailer = mailer) }
                }
                val admin = adminId()
                val firstMember = createTestMember("kc-link-noaudit-a-${Uuid.random()}@example.org")
                val secondMember = createTestMember("kc-link-noaudit-b-${Uuid.random()}@example.org")
                val subject = "subject-${Uuid.random()}"
                client.post("/test/link-member?memberId=$firstMember&subject=$subject") { header("X-Member-Id", admin.toString()) }
                mailer.sent.clear()

                client
                    .post("/test/link-member?memberId=$secondMember&subject=$subject") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                transaction {
                    OidcGuestLoginEventTable.selectAll().where { OidcGuestLoginEventTable.memberId eq secondMember }.count() shouldBe 0L
                }
                mailer.sent shouldBe emptyList()
            }
        }

        test("linkMember(): a member that already has a link is rejected with ConflictException, no second row written") {
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes() }
                }
                val admin = adminId()
                val member = createTestMember("kc-link-dup-member-${Uuid.random()}@example.org")
                val firstSubject = "subject-${Uuid.random()}"
                client.post("/test/link-member?memberId=$member&subject=$firstSubject") { header("X-Member-Id", admin.toString()) }

                val response =
                    client.post("/test/link-member?memberId=$member&subject=subject-${Uuid.random()}") {
                        header("X-Member-Id", admin.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict

                transaction {
                    KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq member }.count() shouldBe 1L
                }
            }
        }

        test("linkMember(): the SAME Keycloak identity linked to a different member is rejected with ConflictException") {
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes() }
                }
                val admin = adminId()
                val firstMember = createTestMember("kc-link-identity-a-${Uuid.random()}@example.org")
                val secondMember = createTestMember("kc-link-identity-b-${Uuid.random()}@example.org")
                val subject = "subject-${Uuid.random()}"
                client.post("/test/link-member?memberId=$firstMember&subject=$subject") { header("X-Member-Id", admin.toString()) }

                val response =
                    client.post("/test/link-member?memberId=$secondMember&subject=$subject") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.Conflict

                transaction {
                    KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq secondMember }.count() shouldBe 0L
                }
            }
        }

        test("linkMember(): an unknown memberId throws NotFoundException") {
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes() }
                }
                val admin = adminId()
                val response =
                    client.post("/test/link-member?memberId=${Uuid.random()}&subject=subject-${Uuid.random()}") {
                        header("X-Member-Id", admin.toString())
                    }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("linkMember(): a malformed memberId throws NotFoundException, not an unhandled 500") {
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes() }
                }
                val admin = adminId()
                val response =
                    client.post("/test/link-member?memberId=not-a-uuid&subject=subject-${Uuid.random()}") {
                        header("X-Member-Id", admin.toString())
                    }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("linkMember(): Keycloak mode disabled throws BadRequestException") {
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes(config = keycloakDisabledConfig()) }
                }
                val admin = adminId()
                val member = createTestMember("kc-link-disabled-${Uuid.random()}@example.org")
                val response =
                    client.post("/test/link-member?memberId=$member&subject=subject-${Uuid.random()}") {
                        header("X-Member-Id", admin.toString())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── unlinkMember ─────────────────────────────────────────────────

        test("unlinkMember(): removes an existing link") {
            val mailer = RecordingKeycloakLinkMailer()
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes(mailer = mailer) }
                }
                val admin = adminId()
                val memberEmail = "kc-link-unlink-${Uuid.random()}@example.org"
                val member = createTestMember(memberEmail)
                val subject = "subject-${Uuid.random()}"
                client.post(
                    "/test/link-member?memberId=$member&subject=$subject",
                ) { header("X-Member-Id", admin.toString()) }
                // A session the (possibly WRONG) linked identity could be holding right now.
                SessionStore.createSession(member)
                SessionStore.countActiveForMember(member) shouldBe 1
                val adminSession = SessionStore.createSession(admin)

                val response = client.delete("/test/unlink-member?memberId=$member") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.OK
                // Review fix (MINOR 6): a genuine removal reports `true`.
                response.bodyAsText() shouldBe "true"
                transaction {
                    KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq member }.count() shouldBe 0L
                }

                // Review fix (MAJOR 1): the audit event for the removal must identify the acting
                // admin -- the `keycloak_account_link` row itself is gone by now, so this is the
                // ONLY remaining record of who did it.
                val auditEvent =
                    transaction {
                        OidcGuestLoginEventTable
                            .selectAll()
                            .where {
                                (OidcGuestLoginEventTable.memberId eq member) and
                                    (OidcGuestLoginEventTable.eventType eq OidcLoginEventType.KEYCLOAK_LINK_MANUAL_REMOVED)
                            }.single()
                    }
                auditEvent[OidcGuestLoginEventTable.reason] shouldBe "admin=$admin subjectSha256=${keycloakSubjectFingerprint(subject)}"

                // Security-audit fix: the target's live sessions are ended; the acting admin's own
                // sessions are untouched (they are a different member).
                SessionStore.countActiveForMember(member) shouldBe 0
                SessionStore.countActiveForMember(admin) shouldBe 1
                adminSession.rawToken.isNotEmpty() shouldBe true
                mailer.sent shouldBe
                    listOf(
                        SentNotice(email = memberEmail, change = KeycloakLinkChange.LINKED),
                        SentNotice(email = memberEmail, change = KeycloakLinkChange.UNLINKED),
                    )
            }
        }

        test("unlinkMember(): an admin unlinking their OWN record keeps the session of the current request") {
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes() }
                }
                val admin = adminId()
                client.post(
                    "/test/link-member?memberId=$admin&subject=subject-${Uuid.random()}",
                ) { header("X-Member-Id", admin.toString()) }
                val current = SessionStore.createSession(admin)
                SessionStore.createSession(admin)
                SessionStore.countActiveForMember(admin) shouldBe 2

                val response =
                    client.delete("/test/unlink-member?memberId=$admin") {
                        header("X-Member-Id", admin.toString())
                        header("Authorization", "Bearer ${current.rawToken}")
                    }
                response.bodyAsText() shouldBe "true"
                SessionStore.countActiveForMember(admin) shouldBe 1
            }
        }

        test("unlinkMember(): idempotent -- a member with no link is a clean no-op, not NotFoundException, and reports false") {
            val mailer = RecordingKeycloakLinkMailer()
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes(mailer = mailer) }
                }
                val admin = adminId()
                val member = createTestMember("kc-link-never-linked-${Uuid.random()}@example.org")
                SessionStore.createSession(member)

                val response = client.delete("/test/unlink-member?memberId=$member") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.OK
                // Review fix (MINOR 6): nothing existed to remove -> `false`, not a misleading `true`.
                response.bodyAsText() shouldBe "false"

                val secondResponse = client.delete("/test/unlink-member?memberId=$member") { header("X-Member-Id", admin.toString()) }
                secondResponse.status shouldBe HttpStatusCode.OK
                secondResponse.bodyAsText() shouldBe "false"

                // No-op must leave no audit trail (see IKeycloakLinkService.unlinkMember KDoc
                // "deliberately diverges" / KeycloakLinkService.unlinkMember KDoc above the record() call).
                transaction {
                    OidcGuestLoginEventTable
                        .selectAll()
                        .where {
                            (OidcGuestLoginEventTable.memberId eq member) and
                                (OidcGuestLoginEventTable.eventType eq OidcLoginEventType.KEYCLOAK_LINK_MANUAL_REMOVED)
                        }.count() shouldBe 0L
                }
                // Security-audit fix: a no-op has no side effects -- no session revocation, no notice.
                SessionStore.countActiveForMember(member) shouldBe 1
                mailer.sent shouldBe emptyList()
            }
        }

        test("unlinkMember(): an unknown memberId throws NotFoundException") {
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes() }
                }
                val admin = adminId()
                val response = client.delete("/test/unlink-member?memberId=${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── Role enforcement ────────────────────────────────────────────────

        test("every method rejects a non-ADMIN caller, and an unauthenticated caller") {
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes() }
                }
                val board = createTestMember("kc-link-role-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val treasurer = createTestMember("kc-link-role-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val member = createTestMember("kc-link-role-member-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val target = createTestMember("kc-link-role-target-${Uuid.random()}@example.org")

                for (callerId in listOf(board, treasurer, member)) {
                    client.get("/test/list-unlinked") { header("X-Member-Id", callerId.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                    client
                        .post("/test/link-member?memberId=$target&subject=subject-${Uuid.random()}") {
                            header("X-Member-Id", callerId.toString())
                        }.status shouldBe HttpStatusCode.Forbidden
                    client.delete("/test/unlink-member?memberId=$target") { header("X-Member-Id", callerId.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                }
                client.get("/test/list-unlinked").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // ── Security-audit round 2: pin the premises of round 1's fixes ─────

        test("a throwing notice mailer never fails link/unlink -- change committed, audited, sessions still revoked") {
            val throwingMailer =
                object : KeycloakLinkNotificationMailer {
                    var calls = 0

                    override fun send(
                        email: String,
                        change: KeycloakLinkChange,
                        occurredAt: LocalDateTime,
                    ): DeliveryStatus {
                        calls++
                        throw IllegalStateException("SMTP down")
                    }
                }
            testApplication {
                application {
                    install(StatusPages) { installKeycloakLinkExceptionHandlers() }
                    routing { registerKeycloakLinkTestRoutes(mailer = throwingMailer) }
                }
                val admin = adminId()
                val member = createTestMember("kc-link-mailfail-${Uuid.random()}@example.org")

                client
                    .post("/test/link-member?memberId=$member&subject=subject-${Uuid.random()}") {
                        header("X-Member-Id", admin.toString())
                    }.status shouldBe HttpStatusCode.OK
                transaction {
                    KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq member }.count() shouldBe 1L
                }

                SessionStore.createSession(member)
                val unlink = client.delete("/test/unlink-member?memberId=$member") { header("X-Member-Id", admin.toString()) }
                unlink.status shouldBe HttpStatusCode.OK
                unlink.bodyAsText() shouldBe "true"
                SessionStore.countActiveForMember(member) shouldBe 0
                transaction {
                    KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq member }.count() shouldBe 0L
                    OidcGuestLoginEventTable.selectAll().where { OidcGuestLoginEventTable.memberId eq member }.count() shouldBe 2L
                }
                throwingMailer.calls shouldBe 2
            }
        }

        test("OidcLoginAuditRecorder.record joins an enclosing transaction -- a rollback also discards the audit row") {
            // The same-transaction guarantee of linkMember/unlinkMember rests on Exposed's default
            // (useNestedTransactions = false): the recorder's own transaction {} must reuse the outer
            // one instead of committing independently. Pinned here so a future DatabaseConfig change
            // that enables nested transactions cannot silently reintroduce "audit without change".
            val member = createTestMember("kc-link-audit-join-${Uuid.random()}@example.org")
            runCatching {
                transaction {
                    OidcLoginAuditRecorder.record(
                        eventType = OidcLoginEventType.KEYCLOAK_LINK_MANUAL,
                        memberId = member,
                        remoteParty = ISSUER,
                        reason = "rollback-probe",
                    )
                    error("force rollback")
                }
            }.isFailure shouldBe true
            transaction {
                OidcGuestLoginEventTable.selectAll().where { OidcGuestLoginEventTable.memberId eq member }.count() shouldBe 0L
            }
        }
    })

private fun cleanUpKeycloakLinkServiceTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        KeycloakAccountLinkTable.deleteWhere { KeycloakAccountLinkTable.memberId inList memberIds }
        SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}

private fun StatusPagesConfig.installKeycloakLinkExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

/** Shared throwaway routes for [KeycloakLinkService] -- mirrors [TrustAnchorServiceTest]'s own `registerTrustAnchorTestRoutes` style. */
private fun Route.registerKeycloakLinkTestRoutes(
    config: KeycloakConfig = keycloakEnabledConfig(),
    mailer: KeycloakLinkNotificationMailer = RecordingKeycloakLinkMailer(),
) {
    get("/test/list-unlinked") {
        val service = KeycloakLinkService(call = call, keycloakConfig = config, notificationMailer = mailer)
        call.respondText(service.listUnlinkedMembers().joinToString(",") { it.memberId })
    }
    post("/test/link-member") {
        val service = KeycloakLinkService(call = call, keycloakConfig = config, notificationMailer = mailer)
        val memberId = call.request.queryParameters["memberId"]!!
        val subject = call.request.queryParameters["subject"]!!
        service.linkMember(memberId = memberId, keycloakSubject = subject)
        call.respondText("OK")
    }
    delete("/test/unlink-member") {
        val service = KeycloakLinkService(call = call, keycloakConfig = config, notificationMailer = mailer)
        val memberId = call.request.queryParameters["memberId"]!!
        // Review fix (MINOR 6): the RPC now returns whether a row actually existed -- surfaced here
        // as the response body so `KeycloakLinkServiceTest` can assert on it.
        val removed = service.unlinkMember(memberId)
        call.respondText(removed.toString())
    }
}

private data class SentNotice(
    val email: String,
    val change: KeycloakLinkChange,
)

/** Records every notice instead of mailing it -- same fake-mailer shape the V1.4.9 admin-password tests use. */
private class RecordingKeycloakLinkMailer : KeycloakLinkNotificationMailer {
    val sent = mutableListOf<SentNotice>()

    override fun send(
        email: String,
        change: KeycloakLinkChange,
        occurredAt: LocalDateTime,
    ): DeliveryStatus {
        sent += SentNotice(email = email, change = change)
        return DeliveryStatus.SENT
    }
}
