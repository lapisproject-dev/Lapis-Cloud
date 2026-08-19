package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SepaComplianceAcknowledgmentTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Security Round 1 (2026-08-19, SHOULD-3) regression coverage for
 * [sepaDisclaimerIsCurrentlyAcknowledged]. Direct [SepaComplianceAcknowledgmentTable] inserts for
 * setup, same "own freshly created fixtures, direct table inserts" house style
 * [ContributionPostingBridgeTest] establishes -- no HTTP/RPC layer involved since this helper is a
 * plain function, not an [ISepaService][network.lapis.cloud.shared.rpc.ISepaService] method (see
 * its own KDoc for why: no current call site gates real behaviour on it yet).
 */
class SepaServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        // sepaDisclaimerIsCurrentlyAcknowledged() deliberately has no member scoping -- it reads the
        // single, org-wide latest acknowledgment row, mirroring the org-wide singleton gate it backs
        // (same shape as OrganizationSettings). [PaymentComplianceGateTest] writes to this SAME table
        // (and cleans up after itself via its own afterTest, same "delete all rows" idiom) -- clearing
        // here too, before each test, keeps this Spec's assertions deterministic regardless of
        // cross-Spec test execution order.
        beforeTest {
            transaction {
                SepaComplianceAcknowledgmentTable.deleteWhere {
                    SepaComplianceAcknowledgmentTable.id eq SepaComplianceAcknowledgmentTable.id
                }
            }
        }

        afterSpec {
            if (createdMemberIds.isEmpty()) return@afterSpec
            transaction {
                SepaComplianceAcknowledgmentTable.deleteWhere {
                    SepaComplianceAcknowledgmentTable.acknowledgedByMemberId inList createdMemberIds
                }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "SepaService Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.ADMIN
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertAcknowledgment(
            memberId: Uuid,
            version: String,
            acknowledgedAt: LocalDateTime,
        ) {
            transaction {
                SepaComplianceAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[acknowledgedByMemberId] = memberId
                    it[SepaComplianceAcknowledgmentTable.acknowledgedAt] = acknowledgedAt
                    it[disclaimerVersion] = version
                    it[disclaimerSha256] = "0".repeat(64)
                }
            }
        }

        test("only acknowledgment on record matches the CURRENT SepaComplianceDisclaimer.VERSION -> currently acknowledged") {
            val member = createTestMember("sepa-disclaimer-current-${Uuid.random()}@example.org")
            insertAcknowledgment(
                memberId = member,
                version = SepaComplianceDisclaimer.VERSION,
                acknowledgedAt = LocalDateTime(2026, 8, 19, 12, 0),
            )

            sepaDisclaimerIsCurrentlyAcknowledged() shouldBe true
        }

        test("latest acknowledgment is NOT the current version -> NOT currently acknowledged (Security Round 1, SHOULD-3)") {
            val member = createTestMember("sepa-disclaimer-stale-${Uuid.random()}@example.org")
            // Insert an up-to-date ack FIRST, then a strictly-more-recent row whose version does NOT
            // match SepaComplianceDisclaimer.VERSION -- simulates an ADMIN having acknowledged an
            // older/different wording than what is CURRENTLY in force. The MOST RECENT row must be
            // the one this function consults, proving it looks at the latest, not "any".
            insertAcknowledgment(
                memberId = member,
                version = SepaComplianceDisclaimer.VERSION,
                acknowledgedAt = LocalDateTime(2026, 8, 19, 12, 0),
            )
            insertAcknowledgment(
                memberId = member,
                version = "2020-01-01.v0-not-the-current-version",
                acknowledgedAt = LocalDateTime(2026, 8, 19, 12, 1),
            )

            sepaDisclaimerIsCurrentlyAcknowledged() shouldBe false
        }
    })
