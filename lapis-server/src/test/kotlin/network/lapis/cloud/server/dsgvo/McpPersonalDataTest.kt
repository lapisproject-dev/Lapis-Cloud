package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.McpMemberBlockTable
import network.lapis.cloud.server.db.generated.McpPostDraftTable
import network.lapis.cloud.server.db.generated.McpToolCallAuditTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mcp.audit.McpToolCallAuditEntry
import network.lapis.cloud.server.mcp.audit.McpToolCallAuditRecorder
import network.lapis.cloud.server.mcp.audit.McpToolCallOutcome
import network.lapis.cloud.server.mcp.optin.McpMemberBlockStore
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.social.PostDraftStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** [PersonalDataCoverageTest] only proves the two MCP tables with a member FK are covered by SOME contributor -- this file pins [McpPersonalData]'s own claims. */
class McpPersonalDataTest :
    FunSpec({
        // The H2 in-memory database is shared across every spec in this JVM test run -- see
        // McpEndToEndTest's own KDoc for why every created member MUST be torn down here.
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                McpMemberBlockTable.deleteWhere { McpMemberBlockTable.memberId inList createdMemberIds }
                McpToolCallAuditTable.deleteWhere { McpToolCallAuditTable.memberId inList createdMemberIds }
                McpPostDraftTable.deleteWhere { McpPostDraftTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun member(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "MCP DSGVO Testmitglied"
                    it[email] = "mcp-dsgvo-$id@example.invalid"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    it[passwordHash] = PasswordHasher.hash("irrelevant-password-1234")
                }
            }
            createdMemberIds += id
            return id
        }

        fun seed(): Uuid {
            val id = member()
            McpMemberBlockStore.setBlocked(memberId = id, blocked = true)
            McpToolCallAuditRecorder.record(
                entry =
                    McpToolCallAuditEntry(
                        memberId = id,
                        tokenId = Uuid.random(),
                        toolName = "get_my_contribution_status",
                        outcome = McpToolCallOutcome.OK,
                        durationMs = 12,
                    ),
            )
            PostDraftStore.createDraft(
                memberId = id,
                tokenId = Uuid.random(),
                agentLabel = "Test Agent",
                content = "Entwurf für DSGVO-Test",
                visibility = SocialPostVisibility.PUBLIC,
            )
            return id
        }

        test("the contributor is registered and covers exactly the three MCP member-FK tables (Welle V1.8.2 adds mcp_post_draft)") {
            (McpPersonalData in PersonalDataRegistry.contributors) shouldBe true
            McpPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf("mcp_member_block", "mcp_tool_call_audit", "mcp_post_draft")
        }

        test("export lists the block state, tool-call metadata and open post drafts, never a tokenId") {
            val id = seed()
            val export = transaction { McpPersonalData.exportMember(id) }
            export["blocked"]!!.jsonPrimitive.boolean shouldBe true
            val call = export["toolCalls"]!!.jsonArray.single().jsonObject
            call["toolName"]!!.jsonPrimitive.content shouldBe "get_my_contribution_status"
            call["outcome"]!!.jsonPrimitive.content shouldBe "OK"
            call.containsKey("tokenId") shouldBe false
            val draft = export["postDrafts"]!!.jsonArray.single().jsonObject
            draft["content"]!!.jsonPrimitive.content shouldBe "Entwurf für DSGVO-Test"
            draft["status"]!!.jsonPrimitive.content shouldBe "OPEN"
            draft.containsKey("tokenId") shouldBe false
        }

        test("erasure hard-deletes the block row, nulls the member reference on the audit row, and hard-deletes the draft") {
            val id = seed()
            val outcomes = transaction { McpPersonalData.eraseMember(memberId = id, mode = ErasureMode.ANONYMIZE) }
            val byTable = outcomes.associateBy { it.table }
            byTable.getValue("mcp_member_block").rowsDeleted shouldBe 1
            byTable.getValue("mcp_tool_call_audit").rowsAnonymized shouldBe 1
            byTable.getValue("mcp_post_draft").rowsDeleted shouldBe 1
            transaction {
                McpMemberBlockTable.selectAll().where { McpMemberBlockTable.memberId eq id }.count() shouldBe 0L
                McpToolCallAuditTable.selectAll().where { McpToolCallAuditTable.memberId eq id }.count() shouldBe 0L
                McpPostDraftTable.selectAll().where { McpPostDraftTable.memberId eq id }.count() shouldBe 0L
            }
        }

        test("McpMemberBlockStore: no row means not blocked (opposite polarity of AiMemberOptInStore)") {
            val id = member()
            McpMemberBlockStore.isBlocked(memberId = id) shouldBe false
        }
    })
