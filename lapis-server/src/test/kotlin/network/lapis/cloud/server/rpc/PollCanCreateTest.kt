package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.post
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Welle V1.9.31 -- `IPollService.canCreatePolls()`: the caller's OWN capability as a UI hint. It must agree with what
 * `createPoll` allows and must never throw ForbiddenException for an authenticated caller (it describes the caller
 * himself, so it is no existence oracle).
 */
class PollCanCreateTest :
    FunSpec({
        val data = PollTestData()
        val today: LocalDate =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { data.cleanUp() }

        suspend fun PollApp.can(member: Uuid): Boolean {
            val result = attempt(member = member) { canCreatePolls() }
            // never a refusal for an authenticated caller
            result.exceptionOrNull() shouldBe null
            return result.getOrThrow()
        }

        fun seated(
            role: CommitteeRole,
            active: Boolean = true,
            until: LocalDate? = null,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val member = data.member(label = "can-$role", status = status)
            data.seat(committeeId = data.committee(active = active), memberId = member, role = role, until = until)
            return member
        }

        test("ADMIN, BOARD and a chair of an active committee may create polls") {
            pollTestApplication {
                can(data.member(label = "admin", role = AccountRole.ADMIN)) shouldBe true
                can(data.member(label = "board", role = AccountRole.BOARD)) shouldBe true
                can(seated(CommitteeRole.CHAIR)) shouldBe true
            }
        }

        test("an expired seat, an inactive committee, a plain committee member and a plain member may not") {
            pollTestApplication {
                can(seated(CommitteeRole.CHAIR, until = today.minus(DatePeriod(days = 1)))) shouldBe false
                can(seated(CommitteeRole.CHAIR, active = false)) shouldBe false
                can(seated(CommitteeRole.MEMBER)) shouldBe false
                can(data.member(label = "plain")) shouldBe false
            }
        }

        test("FRIEND, GUEST, APPLICATION and a withdrawn former chair get false and never a refusal") {
            pollTestApplication {
                for (status in listOf(MemberStatus.FRIEND, MemberStatus.GUEST, MemberStatus.APPLICATION)) {
                    can(data.member(label = "s-$status", status = status)) shouldBe false
                }
                can(seated(CommitteeRole.CHAIR, status = MemberStatus.WITHDRAWN)) shouldBe false
            }
        }

        test("without a session the call is refused as unauthenticated") {
            var failure: Throwable? = null
            testApplication {
                application {
                    routing {
                        post("/poll-can") {
                            failure = runCatching { PollService(call = call).canCreatePolls() }.exceptionOrNull()
                            call.respondText("done")
                        }
                    }
                }
                client.post("/poll-can")
            }
            failure.shouldBeInstanceOf<UnauthenticatedException>()
        }
    })
