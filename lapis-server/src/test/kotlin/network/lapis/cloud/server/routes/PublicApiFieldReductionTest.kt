package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * V1.3.1 "API-Fundament, lesend" -- field reduction at the RAW JSON level (not just "the Kotlin DTO
 * type has fewer fields"), because a raw-string check is the only thing that would catch an
 * accidental `@Serializable` field leak that a type-level test could miss (e.g. a forgotten
 * `@Transient`). Especially `/members`: id + displayName ONLY, permanently -- see `docs/api/public-api-v1.adoc`.
 */
class PublicApiFieldReductionTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    routing {
                        registerPublicApiRoutes(preAuthRateLimiter = generousLimiter(), postAuthRateLimiter = generousLimiter())
                    }
                }
                block()
            }
        }

        fun issueKey(): String = ApiKeyStore.issue(label = "Field Reduction Test", createdByMemberId = ADMIN_ID).rawKey

        test("/api/v1/members never leaks email, status, role, or address -- id+displayName only") {
            testApp {
                val id = Uuid.random()
                val secretEmail = "field-reduction-secret-${Uuid.random()}@example.org"
                transaction {
                    MemberTable.insert {
                        it[MemberTable.id] = id
                        it[displayName] = "Field Reduction Fixture"
                        it[email] = secretEmail
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2026, 1, 1)
                        it[street] = "Geheimstraße 1"
                        it[membershipTierId] = null
                    }
                }
                try {
                    val body = client.get("/api/v1/members?limit=1000") { header("Authorization", "Bearer ${issueKey()}") }.bodyAsText()
                    body shouldContain "Field Reduction Fixture"
                    body shouldNotContain secretEmail
                    body shouldNotContain "Geheimstraße"
                    body shouldNotContain "\"status\""
                    body shouldNotContain "\"role\""
                    body shouldNotContain "\"street\""
                    body shouldNotContain "\"email\""
                } finally {
                    // Must not leak into other Spec classes' exact-count assertions against
                    // DevSeedData's fixed demo roster -- see PublicApiRoutesTest's own cleanup KDoc.
                    transaction { MemberTable.deleteWhere { MemberTable.id eq id } }
                }
            }
        }

        test("/api/v1/committees never leaks createdAt") {
            testApp {
                val id = Uuid.random()
                transaction {
                    CommitteeTable.insert {
                        it[CommitteeTable.id] = id
                        it[name] = "Field Reduction Committee ${Uuid.random()}"
                        it[type] = CommitteeType.WORKING_GROUP
                        it[description] = "Fixture"
                        it[active] = true
                        it[quorumPercent] = 50
                        it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    }
                }
                val body =
                    client
                        .get(
                            "/api/v1/committees?activeOnly=false&limit=1000",
                        ) { header("Authorization", "Bearer ${issueKey()}") }
                        .bodyAsText()
                body shouldNotContain "\"createdAt\""
            }
        }

        test("/api/v1/motions never leaks rationale, reviewNote, or submitterMemberId") {
            testApp {
                val committeeId = Uuid.random()
                transaction {
                    CommitteeTable.insert {
                        it[CommitteeTable.id] = committeeId
                        it[name] = "Motion Field Reduction Committee ${Uuid.random()}"
                        it[type] = CommitteeType.WORKING_GROUP
                        it[description] = "Fixture"
                        it[active] = true
                        it[quorumPercent] = 50
                        it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    }
                    MotionTable.insert {
                        it[id] = Uuid.random()
                        it[targetCommitteeId] = committeeId
                        it[title] = "Field Reduction Motion"
                        it[rationale] = "SECRET RATIONALE TEXT"
                        it[text] = "Motion body text"
                        it[submitterMemberId] = ADMIN_ID
                        it[status] = MotionStatus.SCHEDULED
                        it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                        it[reviewedBy] = null
                        it[reviewedAt] = null
                        it[reviewNote] = "SECRET REVIEW NOTE"
                        it[meetingId] = null
                        it[agendaItemId] = null
                        it[resolutionId] = null
                        it[withdrawnAt] = null
                        it[amendsMotionId] = null
                        it[currentText] = null
                    }
                }
                val body =
                    client
                        .get(
                            "/api/v1/motions?targetCommitteeId=$committeeId",
                        ) { header("Authorization", "Bearer ${issueKey()}") }
                        .bodyAsText()
                body shouldContain "Field Reduction Motion"
                body shouldNotContain "SECRET RATIONALE TEXT"
                body shouldNotContain "SECRET REVIEW NOTE"
                body shouldNotContain "\"submitterMemberId\""
                body shouldNotContain "\"rationale\""
                body shouldNotContain "\"reviewNote\""
            }
        }
    })
