package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RegionalChapterRequiredException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- [requireRegionalChapterBeforeActivation]
 * / [revokeActiveRegionalChapterOfficerGrant] coverage via [MemberService.updateMemberStatus]
 * (the transition-based entry point; [RegistrationService.approveApplication]/
 * `.createMemberDirect` share the exact same helper, see that function's own KDoc).
 */
class RegionalChapterActivationRuleTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdChapterIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                AuditLogEntryTable.deleteWhere { actorMemberId inList createdMemberIds }
                RegionalChapterOfficerTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.update({ MemberTable.id inList createdMemberIds }) { it[regionalChapterId] = null }
                RegionalChapterTable.deleteWhere { id inList createdChapterIds }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun createMember(
            status: MemberStatus,
            role: AccountRole = AccountRole.MEMBER,
            chapterId: Uuid? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Activation-Rule-Test"
                    it[email] = "rcar-${Uuid.random()}@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    it[regionalChapterId] = chapterId
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

        fun StatusPagesConfig.installExceptionHandlers() {
            exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
            exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            exception<RegionalChapterRequiredException> { call, cause ->
                call.respondText(cause.message, status = HttpStatusCode.Conflict)
            }
        }

        // [RegionalChapterEnforcementConfig] defaults OFF (see that class's own KDoc "Review-fix
        // reasoning") -- [enforcementEnabled] lets each test explicitly opt into the STRICT
        // behavior [requireRegionalChapterBeforeActivation]'s own logic implements, via the exact
        // same `env` injection seam [RegionalChapterEnforcementConfig.load] documents.
        fun testRoutes(enforcementEnabled: Boolean): io.ktor.server.application.Application.() -> Unit =
            {
                install(StatusPages) { installExceptionHandlers() }
                routing {
                    post("/test/status/{id}") {
                        val service =
                            MemberService(
                                call = call,
                                friendVerificationMailer = FakeFriendVerificationMailer(),
                                memberCoreDataFriendMailRateLimiter = FederationInboxRateLimiter(),
                                memberCoreDataFriendMailActorRateLimiter = FederationInboxRateLimiter(),
                                passwordResetMailer = FakePasswordResetMailer(),
                                adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                                smtpConfigState = SmtpConfigState.NotConfigured,
                                adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                                adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                                adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                                memberCardIssueRateLimiter = FederationInboxRateLimiter(),
                                memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(),
                                regionalChapterEnforcementConfig =
                                    RegionalChapterEnforcementConfig.load { name ->
                                        if (name == RegionalChapterEnforcementConfig.ENV_ENABLED && enforcementEnabled) "true" else null
                                    },
                            )
                        val newStatus = MemberStatus.valueOf(call.request.queryParameters["status"]!!)
                        val dto =
                            service.updateMemberStatus(
                                memberId = call.parameters["id"]!!,
                                newStatus = newStatus,
                                reason = "Testgrund fuer Statuswechsel",
                                dateOfDeath = null,
                            )
                        call.respondText(dto.status.name)
                    }
                }
            }

        // WITHDRAWN, not APPLICATION -- MemberStatusTransitions.allowedTargets(APPLICATION) is
        // deliberately empty (applications have their own dedicated approve/reject workflow, see
        // that object's KDoc); ACTIVE is only reachable via updateMemberStatus from
        // MemberStatusTransitions.ADMINISTRATIVELY_MANAGED (ACTIVE/WITHDRAWN/DONOR/DECEASED).
        test("no chapters exist: WITHDRAWN -> ACTIVE unaffected (regression to V1.9.12)") {
            testApplication {
                application(testRoutes(enforcementEnabled = true))
                val admin = createMember(status = MemberStatus.ACTIVE, role = AccountRole.ADMIN)
                val reactivating = createMember(status = MemberStatus.WITHDRAWN)

                val response =
                    client.post("/test/status/$reactivating?status=ACTIVE") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("enforcement enabled, a chapter exists: reactivating a member without one is rejected, then succeeds once assigned") {
            testApplication {
                application(testRoutes(enforcementEnabled = true))
                val admin = createMember(status = MemberStatus.ACTIVE, role = AccountRole.ADMIN)
                val chapterId = Uuid.random()
                transaction {
                    RegionalChapterTable.insert {
                        it[id] = chapterId
                        it[name] = "ActivationRule-${Uuid.random()}"
                        it[nameKey] = "activationrule-$chapterId"
                        it[createdAt] =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime()
                    }
                }
                createdChapterIds += chapterId
                val reactivating = createMember(status = MemberStatus.WITHDRAWN)

                val rejected =
                    client.post("/test/status/$reactivating?status=ACTIVE") { header("X-Member-Id", admin.toString()) }
                rejected.status shouldBe HttpStatusCode.Conflict

                transaction {
                    MemberTable.update({ MemberTable.id eq reactivating }) { it[regionalChapterId] = chapterId }
                }
                val approved =
                    client.post("/test/status/$reactivating?status=ACTIVE") { header("X-Member-Id", admin.toString()) }
                approved.status shouldBe HttpStatusCode.OK
            }
        }

        // Review-fix regression guard: this is the EXACT scenario the finding describes as
        // production-breaking (the first `regional_chapter` row ever created immediately blocking
        // every activation the still-picker-less client cannot satisfy) -- with the DEFAULT
        // (enforcement disabled) config, it must NOT happen.
        test("enforcement disabled (default): a chapter exists, reactivating a member without one still succeeds") {
            testApplication {
                application(testRoutes(enforcementEnabled = false))
                val admin = createMember(status = MemberStatus.ACTIVE, role = AccountRole.ADMIN)
                val chapterId = Uuid.random()
                transaction {
                    RegionalChapterTable.insert {
                        it[id] = chapterId
                        it[name] = "ActivationRuleDisabled-${Uuid.random()}"
                        it[nameKey] = "activationruledisabled-$chapterId"
                        it[createdAt] =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime()
                    }
                }
                createdChapterIds += chapterId
                val reactivating = createMember(status = MemberStatus.WITHDRAWN)

                val response =
                    client.post("/test/status/$reactivating?status=ACTIVE") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("leaving ACTIVE auto-revokes an active regional-chapter-officer grant") {
            testApplication {
                application(testRoutes(enforcementEnabled = true))
                val admin = createMember(status = MemberStatus.ACTIVE, role = AccountRole.ADMIN)
                val chapterId = Uuid.random()
                transaction {
                    RegionalChapterTable.insert {
                        it[id] = chapterId
                        it[name] = "AutoRevoke-${Uuid.random()}"
                        it[nameKey] = "autorevoke-$chapterId"
                        it[createdAt] =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime()
                    }
                }
                createdChapterIds += chapterId
                val officer = createMember(status = MemberStatus.ACTIVE, chapterId = chapterId)
                val grantId = Uuid.random()
                transaction {
                    RegionalChapterOfficerTable.insert {
                        it[id] = grantId
                        it[memberId] = officer
                        it[regionalChapterId] = chapterId
                        it[grantedAt] =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime()
                        it[grantedByMemberId] = admin
                        it[revokedAt] = null
                        it[activeForMemberId] = officer
                    }
                }

                client.post("/test/status/$officer?status=WITHDRAWN") { header("X-Member-Id", admin.toString()) }

                val revokedAt =
                    transaction {
                        RegionalChapterOfficerTable
                            .selectAll()
                            .where { RegionalChapterOfficerTable.id eq grantId }
                            .single()[RegionalChapterOfficerTable.revokedAt]
                    }
                (revokedAt != null) shouldBe true

                // Review-fix regression guard (MEDIUM audit gap): the revoke this auto-revoke path
                // performs must leave the SAME audit trail an ADMIN-initiated
                // `RegionalChapterService.revokeOfficer`/`assignMemberToChapter` revoke does --
                // before the fix, a grant ended via a status transition vanished from the audit
                // trail with no counterpart to its own CREATE entry.
                val auditEntry =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.REGIONAL_CHAPTER_OFFICER) and
                                    (AuditLogEntryTable.entityId eq grantId) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.singleOrNull()
                    }
                auditEntry.shouldNotBeNull()
                auditEntry[AuditLogEntryTable.actorMemberId] shouldBe admin
                auditEntry[AuditLogEntryTable.afterSnapshot] shouldBe null
            }
        }
    })
