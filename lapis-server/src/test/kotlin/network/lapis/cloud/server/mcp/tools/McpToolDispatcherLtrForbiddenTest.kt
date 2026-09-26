package network.lapis.cloud.server.mcp.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.ai.retrieval.KnowledgeRetrievers
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.McpToolCallAuditTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.server.mcp.auth.McpScopes
import network.lapis.cloud.server.mcp.ratelimit.McpToolCallRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Closes the residual gap the review flagged in [McpEndToEndTest][network.lapis.cloud.server.mcp
 * .McpEndToEndTest]: `get_my_ltr_balance`'s Forbidden path ([GetMyLtrBalanceTool]'s own KDoc
 * promise -- "an MCP-connected [member] with no LTR eligibility gets ... mapped by the dispatcher
 * to `-32000 FORBIDDEN`") was never actually run by any test.
 *
 * **Deliberately NOT an end-to-end `testApplication` test.** [network.lapis.cloud.server.mcp.auth
 * .McpTokenAuth.resolve] and `OidcRoutes.issueTokens` both gate on [network.lapis.cloud.shared
 * .domain.MemberStatusSets.ORGANIZATION_MEMBER] (`ACTIVE` only) -- a non-`ACTIVE` member can
 * never obtain an MCP access token, let alone use one, over the real HTTP path. The only member
 * status genuinely reachable through `McpTokenAuth` is therefore always
 * [network.lapis.cloud.shared.domain.MemberStatusSets.LTR_ELIGIBLE] as well, so this Forbidden
 * branch is unreachable end-to-end today and can only be pinned at the [McpToolDispatcher] layer
 * directly -- exactly where the finding's own failure scenario (a dispatcher catch-order
 * regression) lives.
 */
class McpToolDispatcherLtrForbiddenTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // McpToolCallAuditTable.memberId carries a real FK to MemberTable (unlike tokenId --
                // see that table's own KDoc "deliberately NO foreign key") -- delete the audit rows
                // this spec's own dispatch() calls wrote BEFORE the member rows themselves, or the
                // member delete below violates the FK constraint.
                McpToolCallAuditTable.deleteWhere { McpToolCallAuditTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createTestMember(status: MemberStatus): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "MCP Dispatcher Testmitglied"
                    it[email] = "mcp-dispatcher-ltr-forbidden-$id@example.org"
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

        fun freshDispatcher(): McpToolDispatcher =
            McpToolDispatcher(
                rateLimiter = McpToolCallRateLimiter(perTokenPerMinute = 1_000, perMemberPerHour = 1_000, perServerPerDay = 1_000),
                toolTimeoutMs = 5_000,
                maxResponseBytes = 65_536,
                retriever = KnowledgeRetrievers.forCurrentDatabase(),
                // Welle V1.8.2 -- this spec only ever dispatches get_my_ltr_balance (a reading
                // tool), so the write path is never actually exercised; a NoOpMailTransport-backed
                // instance satisfies the constructor without any real PSP/mail wiring.
                registrationSubmission =
                    EventRegistrationSubmission(
                        checkoutGateways = emptyMap(),
                        baseUrl = "https://mcp-dispatcher-ltr-forbidden-test.invalid",
                        mailDispatcher = MailDispatcher(transport = NoOpMailTransport()),
                    ),
            )

        test(
            "get_my_ltr_balance: a member with no LTR eligibility (APPLICATION) gets McpToolCallResult.Forbidden " +
                "from the dispatcher, not Success and not InternalError",
        ) {
            val memberId = createTestMember(MemberStatus.APPLICATION)
            val principal =
                McpPrincipal(memberId = memberId, tokenId = Uuid.random(), scope = McpScopes.MEMBER_READ, connectionLabel = "Test Agent")

            val result = freshDispatcher().dispatch(name = "get_my_ltr_balance", arguments = null, principal = principal)

            result.shouldBeInstanceOf<McpToolCallResult.Forbidden>()
        }

        test(
            "get_my_ltr_balance: a fresh APPLICATION member's Forbidden call is audited with outcome=FORBIDDEN " +
                "-- the dispatcher's normal audit path, not the separate never-audited rate-limit early-return",
        ) {
            val memberId = createTestMember(MemberStatus.APPLICATION)
            val principal =
                McpPrincipal(memberId = memberId, tokenId = Uuid.random(), scope = McpScopes.MEMBER_READ, connectionLabel = "Test Agent")

            freshDispatcher().dispatch(name = "get_my_ltr_balance", arguments = null, principal = principal)

            val auditedOutcome =
                transaction {
                    McpToolCallAuditTable
                        .selectAll()
                        .where { McpToolCallAuditTable.memberId eq memberId }
                        .single()[McpToolCallAuditTable.outcome]
                }
            auditedOutcome shouldBe "FORBIDDEN"
        }

        test("get_my_ltr_balance: an ACTIVE (LTR-eligible) member's call is NOT Forbidden -- control for the two tests above") {
            val memberId = createTestMember(MemberStatus.ACTIVE)
            val principal =
                McpPrincipal(memberId = memberId, tokenId = Uuid.random(), scope = McpScopes.MEMBER_READ, connectionLabel = "Test Agent")

            val result = freshDispatcher().dispatch(name = "get_my_ltr_balance", arguments = null, principal = principal)

            result.shouldBeInstanceOf<McpToolCallResult.Success>()
        }
    })
