package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberSelectionDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException

private fun memberService(call: io.ktor.server.application.ApplicationCall) =
    MemberService(
        call = call,
        passwordResetMailer = FakePasswordResetMailer(),
        adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
        smtpConfigState = SmtpConfigState.NotConfigured,
        adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
        adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
        adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
        memberCardIssueRateLimiter = FederationInboxRateLimiter(),
        memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(),
    )

/** V1.9.36 -- [MemberService.listMembersForSelection]: role gate before any DB access, all statuses, anonymized excluded. */
class MemberSelectionTest :
    FunSpec({
        val members = ThrowawayMembers()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { members.cleanup() }

        test("BOARD and ADMIN get every status, anonymized members are excluded, order is displayName then id") {
            val board = members.create(email = "v1936-sel-board@example.test", displayName = "Zz Sel Board", role = AccountRole.BOARD)
            val admin = members.create(email = "v1936-sel-admin@example.test", displayName = "Zz Sel Admin", role = AccountRole.ADMIN)
            val withdrawn =
                members.create(
                    email = "v1936-sel-w@example.test",
                    displayName = "Zz Sel Withdrawn",
                    status = MemberStatus.WITHDRAWN,
                )
            val deceased =
                members.create(
                    email = "v1936-sel-d@example.test",
                    displayName = "Zz Sel Deceased",
                    status = MemberStatus.DECEASED,
                )
            val anon = members.create(email = "v1936-sel-a@example.test", displayName = "Zz Sel Anon", anonymized = true)
            withServiceCaller {
                listOf(board, admin).forEach { who ->
                    val list = call(caller = who) { memberService(it).listMembersForSelection() }.getOrThrow()
                    val ids = list.map { it.id }
                    ids shouldContain withdrawn.toString()
                    ids shouldContain deceased.toString()
                    ids shouldNotContain anon.toString()
                    list.first { it.id == withdrawn.toString() }.status shouldBe MemberStatus.WITHDRAWN
                    list.map { it.displayName to it.id } shouldBe
                        list.sortedWith(compareBy({ it.displayName }, { it.id })).map { it.displayName to it.id }
                }
            }
        }

        test("the DTO carries only id, displayName and status") {
            MemberSelectionDto::class
                .members
                .map { it.name }
                .filter { it in setOf("id", "displayName", "status", "email", "iban", "dateOfBirth") }
                .toSet() shouldBe setOf("id", "displayName", "status")
        }

        test("MEMBER and TREASURER are rejected") {
            val member = members.create(email = "v1936-sel-m@example.test", role = AccountRole.MEMBER)
            val treasurer = members.create(email = "v1936-sel-t@example.test", role = AccountRole.TREASURER)
            withServiceCaller {
                listOf(member, treasurer).forEach { who ->
                    call(
                        caller = who,
                    ) { memberService(it).listMembersForSelection() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
            }
        }

        test("the cap constant is 5000") {
            MAX_MEMBER_SELECTION shouldBe 5000
        }
    })
