package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.dsgvo.PartgReportPersonalData
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.PartgReportScope
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.310 (R6b) -- the auditor's invitation in the data subject export and erasure: "changed by you" (id, year, times, who) but NEVER the e-mail
 * address of the auditor (a third party); the row is retained on erasure with the reason; the address is the documented gap for third parties.
 */
class PartgReviewerPersonalDataTest :
    FunSpec({
        val fx = PartgFixtures()
        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.setParty(true) }
        afterTest { fx.cleanUp() }

        val year = 2002

        fun seed(board: Uuid): String {
            val current = fx.currentOf(memberId = board)
            val version =
                PartgVersionWriter.freeze(
                    current = current,
                    year = year,
                    scope = PartgReportScope.All,
                    comment = null,
                    now = ServerClock.now(),
                )
            val ops = PartgReviewerOperations(writeRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 60.minutes))
            val created =
                ops.create(
                    current = current,
                    year = year,
                    versionIds = listOf(version.toString()),
                    validityDays = 14,
                    email = "geheim.pruefer@example.org",
                )
            ops.revoke(current = current, invitationId = created.invitation.id)
            return created.invitation.id
        }

        test("the export lists the invitation of the member without the address of the auditor") {
            val board = fx.member()
            val id = seed(board)
            val json = transaction { PartgReportPersonalData.exportMember(board).toString() }
            json shouldContain "reviewerInvitationsByYou"
            json shouldContain id
            json shouldContain "\"createdByYou\":true"
            json shouldContain "\"revokedByYou\":true"
            listOf("geheim", "pruefer", "example.org").forEach { json shouldNotContain it }
        }

        test("erasure retains the invitation with the reason and deletes nothing") {
            val board = fx.member()
            seed(board)
            for (mode in ErasureMode.entries) {
                val outcomes = transaction { PartgReportPersonalData.eraseMember(memberId = board, mode = mode) }.associateBy { it.table }
                outcomes.getValue("partg_reviewer_invitation").rowsRetained shouldBe 1
                outcomes.getValue("partg_reviewer_invitation").retentionReason?.isNotBlank() shouldBe true
            }
        }

        test("the registry: the invitation is covered and listed as a documented gap for the address of a third party") {
            PartgReportPersonalData.coveredTables.map { it.tableName }.contains("partg_reviewer_invitation") shouldBe true
            PersonalDataRegistry.nonMemberPiiTables.contains("partg_reviewer_invitation") shouldBe true
            PersonalDataRegistry.knownUncoveredSubjectRoots.getValue("partg_reviewer_invitation") shouldContain "eraseReviewerEmail"
            listOf("partg_reviewer_session", "partg_reviewer_event", "partg_reviewer_invitation_version").forEach {
                PersonalDataRegistry.nonMemberPiiTables.contains(it) shouldBe false
            }
        }
    })
