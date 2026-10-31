package network.lapis.cloud.server.activism

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismTransitions
import network.lapis.cloud.shared.domain.ActivismViewerRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class ActivismUnitTest :
    FunSpec({
        fun viewer(
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.ACTIVE,
        ) = CurrentMember(memberId = Uuid.random(), role = role, status = status)

        test("policy matrix: role x status x responsible") {
            val hidden = setOf(ActivismStatus.IDEA, ActivismStatus.AWAITING_REVIEW, ActivismStatus.WITHDRAWN)
            for (status in ActivismStatus.entries) {
                val plain = ActivismAccessPolicy.resolve(viewer = viewer(), status = status, responsible = false)
                if (status in hidden) plain.shouldBeNull() else plain shouldBe ActivismViewerRole.MEMBER
                ActivismAccessPolicy.resolve(viewer = viewer(), status = status, responsible = true) shouldBe ActivismViewerRole.RESPONSIBLE
                // TREASURER is not a manager
                val treasurer = ActivismAccessPolicy.resolve(viewer = viewer(AccountRole.TREASURER), status = status, responsible = false)
                if (status in hidden) treasurer.shouldBeNull() else treasurer shouldBe ActivismViewerRole.MEMBER
                for (role in listOf(AccountRole.BOARD, AccountRole.ADMIN)) {
                    ActivismAccessPolicy.resolve(viewer = viewer(role), status = status, responsible = false) shouldBe
                        ActivismViewerRole.MANAGER
                    ActivismAccessPolicy.resolve(viewer = viewer(role), status = status, responsible = true) shouldBe
                        ActivismViewerRole.MANAGER
                }
                for (outsider in listOf(MemberStatus.FRIEND, MemberStatus.GUEST, MemberStatus.APPLICATION, MemberStatus.WITHDRAWN)) {
                    ActivismAccessPolicy
                        .resolve(
                            viewer = viewer(AccountRole.ADMIN, outsider),
                            status = status,
                            responsible = true,
                        ).shouldBeNull()
                }
            }
        }

        test("transitions: the whole matrix for the review switch off and on") {
            val core = listOf(ActivismStatus.PLANNED, ActivismStatus.RUNNING, ActivismStatus.COMPLETED)
            for (a in core) {
                for (b in core) {
                    if (a == b) continue
                    ActivismTransitions.allowed(from = a, to = b, role = ActivismViewerRole.MEMBER, reviewRequired = false) shouldBe false
                    val exitsCompleted = a == ActivismStatus.COMPLETED
                    ActivismTransitions.allowed(from = a, to = b, role = ActivismViewerRole.RESPONSIBLE, reviewRequired = false) shouldBe
                        !exitsCompleted
                    ActivismTransitions.allowed(from = a, to = b, role = ActivismViewerRole.MANAGER, reviewRequired = false) shouldBe true
                }
            }
            val idea = ActivismStatus.IDEA
            ActivismTransitions.allowed(
                from = idea,
                to = ActivismStatus.PLANNED,
                role = ActivismViewerRole.MANAGER,
                reviewRequired = false,
            ) shouldBe
                true
            ActivismTransitions.allowed(
                from = idea,
                to = ActivismStatus.PLANNED,
                role = ActivismViewerRole.MANAGER,
                reviewRequired = true,
            ) shouldBe
                false
            ActivismTransitions.allowed(
                from = idea,
                to = ActivismStatus.PLANNED,
                role = ActivismViewerRole.RESPONSIBLE,
                reviewRequired = false,
            ) shouldBe
                false
            ActivismTransitions.allowed(
                from = idea,
                to = ActivismStatus.AWAITING_REVIEW,
                role = ActivismViewerRole.RESPONSIBLE,
                reviewRequired = true,
            ) shouldBe
                true
            ActivismTransitions.allowed(
                from = idea,
                to = ActivismStatus.AWAITING_REVIEW,
                role = ActivismViewerRole.RESPONSIBLE,
                reviewRequired = false,
            ) shouldBe
                false
            ActivismTransitions.allowed(
                from = idea,
                to = ActivismStatus.AWAITING_REVIEW,
                role = ActivismViewerRole.MEMBER,
                reviewRequired = true,
            ) shouldBe
                false
            ActivismTransitions.allowed(
                from = ActivismStatus.AWAITING_REVIEW,
                to = ActivismStatus.PLANNED,
                role = ActivismViewerRole.RESPONSIBLE,
                reviewRequired = true,
            ) shouldBe
                false
            ActivismTransitions.allowed(
                from = ActivismStatus.AWAITING_REVIEW,
                to = ActivismStatus.PLANNED,
                role = ActivismViewerRole.MANAGER,
                reviewRequired = true,
            ) shouldBe
                true
            ActivismTransitions.allowed(
                from = ActivismStatus.AWAITING_REVIEW,
                to = idea,
                role = ActivismViewerRole.MANAGER,
                reviewRequired = true,
            ) shouldBe
                true
            for (s in ActivismStatus.entries.filter { it != ActivismStatus.WITHDRAWN }) {
                ActivismTransitions.allowed(
                    from = s,
                    to = ActivismStatus.WITHDRAWN,
                    role = ActivismViewerRole.MANAGER,
                    reviewRequired = false,
                ) shouldBe
                    true
                ActivismTransitions.allowed(
                    from = s,
                    to = ActivismStatus.WITHDRAWN,
                    role = ActivismViewerRole.RESPONSIBLE,
                    reviewRequired = false,
                ) shouldBe
                    false
            }
            ActivismTransitions.allowed(
                from = ActivismStatus.WITHDRAWN,
                to = idea,
                role = ActivismViewerRole.MANAGER,
                reviewRequired = false,
            ) shouldBe
                true
            ActivismTransitions.allowed(
                from = ActivismStatus.WITHDRAWN,
                to = ActivismStatus.PLANNED,
                role = ActivismViewerRole.MANAGER,
                reviewRequired = false,
            ) shouldBe
                false
            ActivismTransitions.allowed(
                from = ActivismStatus.PLANNED,
                to = ActivismStatus.PLANNED,
                role = ActivismViewerRole.MANAGER,
                reviewRequired = false,
            ) shouldBe
                false
            ActivismTransitions.acceptsParticipation(ActivismStatus.RUNNING) shouldBe true
            ActivismStatus.entries.filter { it != ActivismStatus.RUNNING }.none { ActivismTransitions.acceptsParticipation(it) } shouldBe
                true
        }

        test("text cleaning: NFC, control and bidi characters, limits in code points, blank = null") {
            ActivismText.clean(raw = "  Hallo ‮\u0000 Welt  ", maxChars = 50) shouldBe "Hallo Welt"
            ActivismText.clean(raw = "   ", maxChars = 50).shouldBeNull()
            ActivismText.clean(raw = "<b>fett</b>", maxChars = 50) shouldBe "<b>fett</b>"
            ActivismText.clean(raw = "é", maxChars = 5) shouldBe "é"
            ActivismText.clean(raw = "😀".repeat(5), maxChars = 5) shouldBe "😀".repeat(5)
            shouldThrow<BadRequestException> { ActivismText.clean(raw = "😀".repeat(6), maxChars = 5) }
            ActivismText.cleanMultiline(raw = "a\r\nb\n\n\n\nc", maxChars = 50) shouldBe "a\nb\n\nc"
            shouldThrow<BadRequestException> { ActivismText.require(raw = null, maxChars = 5) }
            shouldThrow<BadRequestException> { ActivismText.requireBetween(raw = "kurz", min = 10, max = 100) }
            ActivismText.requireBetween(raw = "lang genug", min = 10, max = 100) shouldBe "lang genug"
        }

        test("rate limiter: per member and per kind, the window slides") {
            val limiter = ActivismRateLimiter(participation = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes))
            val a = Uuid.random()
            val b = Uuid.random()
            limiter.allowParticipation(a) shouldBe true
            limiter.allowParticipation(a) shouldBe true
            limiter.allowParticipation(a) shouldBe false
            limiter.allowParticipation(b) shouldBe true
            // another kind is independent
            limiter.allowReport(a) shouldBe true
            limiter.allowSearch(a) shouldBe true
        }
    })
