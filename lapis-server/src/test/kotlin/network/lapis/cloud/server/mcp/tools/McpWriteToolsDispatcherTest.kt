package network.lapis.cloud.server.mcp.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import network.lapis.cloud.server.ai.retrieval.KnowledgeRetrievers
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.McpPostDraftTable
import network.lapis.cloud.server.db.generated.McpToolCallAuditTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.server.mcp.auth.McpScopes
import network.lapis.cloud.server.mcp.ratelimit.McpToolCallRateLimiter
import network.lapis.cloud.server.social.PostDraftStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.McpPostDraftStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.8.2 "MCP-Server: Schreibwerkzeuge" -- dispatcher-level coverage for the write-scope
 * gate and `create_post_draft`'s draft-limit rejection. Deliberately NOT end-to-end
 * (`testApplication`) for the same reason [McpToolDispatcherLtrForbiddenTest] gives: only
 * [network.lapis.cloud.shared.domain.MemberStatusSets.ORGANIZATION_MEMBER] members can ever obtain
 * a real MCP token over HTTP, and these tests need to construct [McpPrincipal] directly to control
 * `canWrite`.
 *
 * **NOT covered here** (review fix, V1.8.2 wave 2 -- a prior version of this KDoc claimed the
 * `tools/list` catalog filtering by write scope as covered in THIS file, which was false: that
 * filtering lives in `routes.McpRoutes`' `private fun toolsListResult`, unreachable from a
 * dispatcher-only unit test): see [network.lapis.cloud.server.mcp.McpConformanceTest]'s two
 * `tools/list` tests (5 entries for a read-only principal, all 7 for a write-capable one) instead.
 */
class McpWriteToolsDispatcherTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                McpPostDraftTable.deleteWhere { McpPostDraftTable.memberId inList createdMemberIds }
                McpToolCallAuditTable.deleteWhere { McpToolCallAuditTable.memberId inList createdMemberIds }
                SocialPostTable.deleteWhere { SocialPostTable.authorMemberId inList createdMemberIds }
                EventRegistrationTable.deleteWhere { EventRegistrationTable.eventId inList createdEventIds }
                EventTable.deleteWhere { EventTable.id inList createdEventIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createTestMember(status: MemberStatus = MemberStatus.ACTIVE): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "MCP Write-Tools Testmitglied"
                    it[email] = "mcp-write-tools-$id@example.org"
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

        fun createEvent(
            createdBy: Uuid,
            feeAmount: BigDecimal,
        ): Uuid {
            val id = Uuid.random()
            val startsAt = LocalDateTime(2030, 1, 1, 18, 0)
            val endsAt = LocalDateTime(2030, 1, 1, 22, 0)
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "mcp-write-tools-test-$id"
                    it[title] = "MCP-Write-Tools-Test-Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[EventTable.startsAt] = startsAt
                    it[EventTable.endsAt] = endsAt
                    it[capacity] = null
                    it[EventTable.feeAmount] = feeAmount
                    it[feeCurrency] = "EUR"
                    it[status] = EventStatus.PUBLISHED
                    it[visibility] = EventVisibility.PUBLIC
                    it[registrationClosesAt] = null
                    it[EventTable.createdAt] = DbClock.nowLocalDateTime()
                    it[EventTable.createdBy] = createdBy
                    it[cancelledAt] = null
                    it[roomId] = null
                }
            }
            createdEventIds += id
            return id
        }

        /** Free (feeAmount=ZERO), uncapped, far-future PUBLISHED event -- registration always CONFIRMED, no checkout gateway needed. */
        fun createFreeEvent(createdBy: Uuid): Uuid = createEvent(createdBy = createdBy, feeAmount = BigDecimal.ZERO)

        /** Welle V1.8.2b -- a fee-bearing PUBLISHED event, for the "no payment through MCP" tests. */
        fun createPaidEvent(createdBy: Uuid): Uuid = createEvent(createdBy = createdBy, feeAmount = BigDecimal("12.50"))

        fun freshDispatcher(writeEnabled: Boolean = true): McpToolDispatcher =
            McpToolDispatcher(
                rateLimiter = McpToolCallRateLimiter(perTokenPerMinute = 1_000, perMemberPerHour = 1_000, perServerPerDay = 1_000),
                toolTimeoutMs = 5_000,
                maxResponseBytes = 65_536,
                retriever = KnowledgeRetrievers.forCurrentDatabase(),
                registrationSubmission =
                    EventRegistrationSubmission(
                        checkoutGateways = emptyMap(),
                        baseUrl = "https://mcp-write-tools-test.invalid",
                        mailDispatcher = MailDispatcher(transport = NoOpMailTransport()),
                    ),
                // Welle V1.8.2b -- this whole spec is about the writing tools actually succeeding,
                // so the operator switch defaults ON here; the dedicated McpWriteSwitchTest covers
                // the OFF case.
                writeEnabled = writeEnabled,
            )

        test("McpToolCatalog: TOOLS has 7 entries, 2 writing, 5 reading") {
            McpToolCatalog.TOOLS.size shouldBe 7
            McpToolCatalog.TOOLS.count { it.writing } shouldBe 2
            McpToolCatalog.TOOLS.count { !it.writing } shouldBe 5
            McpToolCatalog.TOOLS.map { it.name }.toSet() shouldBe
                setOf(
                    "get_my_contribution_status",
                    "get_my_ltr_balance",
                    "search_statute",
                    "list_upcoming_events",
                    "get_my_ballots",
                    "register_for_event",
                    "create_post_draft",
                )
        }

        test(
            "create_post_draft: a read-only principal (canWrite=false) is rejected as Forbidden, NOT audited " +
                "(no DB write -- see McpToolDispatcher KDoc \"Write-scope Forbidden review fix\"), and creates NO draft row",
        ) {
            val memberId = createTestMember()
            val principal =
                McpPrincipal(
                    memberId = memberId,
                    tokenId = Uuid.random(),
                    scope = McpScopes.MEMBER_READ,
                    canWrite = false,
                    connectionLabel = "Test Agent",
                )

            val result =
                freshDispatcher().dispatch(
                    name = "create_post_draft",
                    arguments =
                        buildJsonObject {
                            put("content", JsonPrimitive("Hallo Welt"))
                            put("visibility", JsonPrimitive("PUBLIC"))
                        },
                    principal = principal,
                )

            result.shouldBeInstanceOf<McpToolCallResult.Forbidden>()
            transaction {
                McpPostDraftTable.selectAll().where { McpPostDraftTable.memberId eq memberId }.count()
            } shouldBe 0L
            // Welle V1.8.2 wave 2 review fix: this early write-scope rejection never reaches a
            // tool, so -- exactly like a rate-limited call -- it must not cost a DB write either.
            // The first implementation of this check audited it unconditionally BEFORE the rate
            // limiter ran, an unbounded-INSERT DoS vector for a read-only/leaked token hammering a
            // writing tool.
            val auditedCount =
                transaction {
                    McpToolCallAuditTable.selectAll().where { McpToolCallAuditTable.memberId eq memberId }.count()
                }
            auditedCount shouldBe 0L
        }

        test("create_post_draft: a write-scoped principal creates exactly one mcp_post_draft row and NO social_post row") {
            val memberId = createTestMember()
            val principal =
                McpPrincipal(
                    memberId = memberId,
                    tokenId = Uuid.random(),
                    scope = "mcp:member_read mcp:member_write",
                    canWrite = true,
                    connectionLabel = "Test Agent",
                )

            val result =
                freshDispatcher().dispatch(
                    name = "create_post_draft",
                    arguments =
                        buildJsonObject {
                            put("content", JsonPrimitive("Hallo Welt"))
                            put("visibility", JsonPrimitive("PUBLIC"))
                        },
                    principal = principal,
                )

            result.shouldBeInstanceOf<McpToolCallResult.Success>()
            transaction {
                McpPostDraftTable.selectAll().where { McpPostDraftTable.memberId eq memberId }.count() shouldBe 1L
                SocialPostTable.selectAll().where { SocialPostTable.authorMemberId eq memberId }.count() shouldBe 0L
            }
        }

        test("create_post_draft: the 11th open draft is rejected as ToolError(draft_limit_reached), still only 10 rows") {
            val memberId = createTestMember()
            repeat(PostDraftStore.MAX_OPEN_DRAFTS_PER_MEMBER) {
                PostDraftStore.createDraft(
                    memberId = memberId,
                    tokenId = Uuid.random(),
                    agentLabel = "Test Agent",
                    content = "Entwurf $it",
                    visibility = SocialPostVisibility.PUBLIC,
                )
            }
            val principal =
                McpPrincipal(
                    memberId = memberId,
                    tokenId = Uuid.random(),
                    scope = "mcp:member_read mcp:member_write",
                    canWrite = true,
                    connectionLabel = "Test Agent",
                )

            val result =
                freshDispatcher().dispatch(
                    name = "create_post_draft",
                    arguments =
                        buildJsonObject {
                            put("content", JsonPrimitive("Der elfte Entwurf"))
                            put("visibility", JsonPrimitive("PUBLIC"))
                        },
                    principal = principal,
                )

            val toolError = result.shouldBeInstanceOf<McpToolCallResult.ToolError>()
            toolError.code shouldBe "draft_limit_reached"
            transaction {
                McpPostDraftTable
                    .selectAll()
                    .where {
                        (McpPostDraftTable.memberId eq memberId) and (McpPostDraftTable.status eq McpPostDraftStatus.OPEN)
                    }.count()
            } shouldBe PostDraftStore.MAX_OPEN_DRAFTS_PER_MEMBER.toLong()
        }

        test("register_for_event: a read-only principal (canWrite=false) is rejected as Forbidden before the tool ever runs") {
            val memberId = createTestMember()
            val principal =
                McpPrincipal(
                    memberId = memberId,
                    tokenId = Uuid.random(),
                    scope = McpScopes.MEMBER_READ,
                    canWrite = false,
                    connectionLabel = "Test Agent",
                )

            val result =
                freshDispatcher().dispatch(
                    name = "register_for_event",
                    arguments =
                        buildJsonObject {
                            put("eventId", JsonPrimitive(Uuid.random().toString()))
                        },
                    principal = principal,
                )

            result.shouldBeInstanceOf<McpToolCallResult.Forbidden>()
        }

        // Review fix (V1.8.2 wave 2): before this test, `register_for_event` was NEVER exercised
        // to a successful outcome anywhere in this codebase's test suite -- only its Forbidden
        // (write-scope gate) rejection had coverage. The tool's own KDoc headlines idempotency
        // ("Idempotent by construction") as its main selling point over a plain registration RPC
        // call, yet nothing pinned it.
        test("register_for_event: a write-scoped principal registers for a free event, CONFIRMED, exactly one row") {
            val memberId = createTestMember()
            val organizerId = createTestMember()
            val eventId = createFreeEvent(createdBy = organizerId)
            val principal =
                McpPrincipal(
                    memberId = memberId,
                    tokenId = Uuid.random(),
                    scope = "mcp:member_read mcp:member_write",
                    canWrite = true,
                    connectionLabel = "Test Agent",
                )

            val result =
                freshDispatcher().dispatch(
                    name = "register_for_event",
                    arguments = buildJsonObject { put("eventId", JsonPrimitive(eventId.toString())) },
                    principal = principal,
                )

            val success = result.shouldBeInstanceOf<McpToolCallResult.Success>()
            val content = success.content
            check(content is JsonObject)
            content["registered"]?.jsonPrimitive?.content shouldBe "true"
            content["alreadyRegistered"]?.jsonPrimitive?.content shouldBe "false"
            content["status"]?.jsonPrimitive?.content shouldBe "CONFIRMED"

            transaction {
                EventRegistrationTable
                    .selectAll()
                    .where { (EventRegistrationTable.eventId eq eventId) and (EventRegistrationTable.memberId eq memberId) }
                    .count()
            } shouldBe 1L
        }

        test(
            "register_for_event: a SECOND call for the same event returns alreadyRegistered=true with the " +
                "existing status, and creates NO second event_registration row -- the tool's headline idempotency promise",
        ) {
            val memberId = createTestMember()
            val organizerId = createTestMember()
            val eventId = createFreeEvent(createdBy = organizerId)
            val principal =
                McpPrincipal(
                    memberId = memberId,
                    tokenId = Uuid.random(),
                    scope = "mcp:member_read mcp:member_write",
                    canWrite = true,
                    connectionLabel = "Test Agent",
                )
            val dispatcher = freshDispatcher()

            val first =
                dispatcher.dispatch(
                    name = "register_for_event",
                    arguments = buildJsonObject { put("eventId", JsonPrimitive(eventId.toString())) },
                    principal = principal,
                )
            first.shouldBeInstanceOf<McpToolCallResult.Success>()

            val second =
                dispatcher.dispatch(
                    name = "register_for_event",
                    arguments = buildJsonObject { put("eventId", JsonPrimitive(eventId.toString())) },
                    principal = principal,
                )
            val secondSuccess = second.shouldBeInstanceOf<McpToolCallResult.Success>()
            val content = secondSuccess.content
            check(content is JsonObject)
            content["registered"]?.jsonPrimitive?.content shouldBe "true"
            content["alreadyRegistered"]?.jsonPrimitive?.content shouldBe "true"
            content["status"]?.jsonPrimitive?.content shouldBe "CONFIRMED"
            content["waitlistPosition"].shouldBeNull()

            transaction {
                EventRegistrationTable
                    .selectAll()
                    .where { (EventRegistrationTable.eventId eq eventId) and (EventRegistrationTable.memberId eq memberId) }
                    .count()
            } shouldBe 1L
            transaction {
                EventRegistrationTable
                    .selectAll()
                    .where { (EventRegistrationTable.eventId eq eventId) and (EventRegistrationTable.memberId eq memberId) }
                    .single()[EventRegistrationTable.status]
            } shouldBe EventRegistrationStatus.CONFIRMED
        }

        // Welle V1.8.2b -- "No payment through MCP" (RegisterForEventTool KDoc): a fee-bearing
        // event is rejected UP FRONT as a typed ToolError, never a PaymentRequired/paymentUrl
        // outcome, and creates NO event_registration row at all (the rejection happens before
        // EventRegistrationSubmission.submit is ever called).
        test("register_for_event: a fee-bearing event is rejected as ToolError(event_requires_payment), no registration row created") {
            val memberId = createTestMember()
            val organizerId = createTestMember()
            val eventId = createPaidEvent(createdBy = organizerId)
            val principal =
                McpPrincipal(
                    memberId = memberId,
                    tokenId = Uuid.random(),
                    scope = "mcp:member_read mcp:member_write",
                    canWrite = true,
                    connectionLabel = "Test Agent",
                )

            val result =
                freshDispatcher().dispatch(
                    name = "register_for_event",
                    arguments = buildJsonObject { put("eventId", JsonPrimitive(eventId.toString())) },
                    principal = principal,
                )

            val toolError = result.shouldBeInstanceOf<McpToolCallResult.ToolError>()
            toolError.code shouldBe "event_requires_payment"
            transaction {
                EventRegistrationTable
                    .selectAll()
                    .where { (EventRegistrationTable.eventId eq eventId) and (EventRegistrationTable.memberId eq memberId) }
                    .count()
            } shouldBe 0L
        }

        // No existence oracle: a non-existent eventId must produce the SAME generic Forbidden as
        // any other "cannot register" outcome, never the event_requires_payment ToolError -- an
        // agent must not be able to tell "does not exist" apart from "requires payment".
        test("register_for_event: a non-existent eventId is rejected as the generic Forbidden, NOT event_requires_payment") {
            val memberId = createTestMember()
            val principal =
                McpPrincipal(
                    memberId = memberId,
                    tokenId = Uuid.random(),
                    scope = "mcp:member_read mcp:member_write",
                    canWrite = true,
                    connectionLabel = "Test Agent",
                )

            val result =
                freshDispatcher().dispatch(
                    name = "register_for_event",
                    arguments = buildJsonObject { put("eventId", JsonPrimitive(Uuid.random().toString())) },
                    principal = principal,
                )

            result.shouldBeInstanceOf<McpToolCallResult.Forbidden>()
        }

        // Welle V1.8.2b -- the actual enforcement point (McpToolDispatcher.dispatch), independent
        // of the tools/list advertising covered by McpConformanceTest's tools/list tests.
        test("writeEnabled=false: a write-scoped principal is still rejected as Forbidden for a writing tool") {
            val memberId = createTestMember()
            val principal =
                McpPrincipal(
                    memberId = memberId,
                    tokenId = Uuid.random(),
                    scope = "mcp:member_read mcp:member_write",
                    canWrite = true,
                    connectionLabel = "Test Agent",
                )

            val result =
                freshDispatcher(writeEnabled = false).dispatch(
                    name = "create_post_draft",
                    arguments =
                        buildJsonObject {
                            put("content", JsonPrimitive("Hallo Welt"))
                            put("visibility", JsonPrimitive("PUBLIC"))
                        },
                    principal = principal,
                )

            result.shouldBeInstanceOf<McpToolCallResult.Forbidden>()
            transaction {
                McpPostDraftTable.selectAll().where { McpPostDraftTable.memberId eq memberId }.count()
            } shouldBe 0L
        }

        // Welle V1.8.2 wave 3 security fix -- see McpToolDispatcher KDoc "Write-scope
        // rate-limit-bypass security fix". Before this fix, the write-gate Forbidden rejection
        // returned BEFORE `checkAndRecord` ever ran, so a read-only (or leaked) token hammering a
        // writing tool never touched any of the three global rate-limit windows -- an unbounded-
        // request DoS vector. With the fix, the SAME early rejection is now bounded by those windows
        // exactly like a real call would be.
        test(
            "write-gate Forbidden still counts against the three global rate-limit windows: a read-only " +
                "principal's SECOND call against a writing tool is RateLimited, not Forbidden again",
        ) {
            val memberId = createTestMember()
            val principal =
                McpPrincipal(
                    memberId = memberId,
                    tokenId = Uuid.random(),
                    scope = McpScopes.MEMBER_READ,
                    canWrite = false,
                    connectionLabel = "Test Agent",
                )
            val dispatcher =
                McpToolDispatcher(
                    rateLimiter = McpToolCallRateLimiter(perTokenPerMinute = 1, perMemberPerHour = 1_000, perServerPerDay = 1_000),
                    toolTimeoutMs = 5_000,
                    maxResponseBytes = 65_536,
                    retriever = KnowledgeRetrievers.forCurrentDatabase(),
                    registrationSubmission =
                        EventRegistrationSubmission(
                            checkoutGateways = emptyMap(),
                            baseUrl = "https://mcp-write-tools-test.invalid",
                            mailDispatcher = MailDispatcher(transport = NoOpMailTransport()),
                        ),
                    writeEnabled = true,
                )
            val args =
                buildJsonObject {
                    put("content", JsonPrimitive("Hallo Welt"))
                    put("visibility", JsonPrimitive("PUBLIC"))
                }

            val first = dispatcher.dispatch(name = "create_post_draft", arguments = args, principal = principal)
            first.shouldBeInstanceOf<McpToolCallResult.Forbidden>()

            val second = dispatcher.dispatch(name = "create_post_draft", arguments = args, principal = principal)
            second.shouldBeInstanceOf<McpToolCallResult.RateLimited>()

            transaction {
                McpPostDraftTable.selectAll().where { McpPostDraftTable.memberId eq memberId }.count()
            } shouldBe 0L
            transaction {
                McpToolCallAuditTable.selectAll().where { McpToolCallAuditTable.memberId eq memberId }.count()
            } shouldBe 0L
        }
    })
