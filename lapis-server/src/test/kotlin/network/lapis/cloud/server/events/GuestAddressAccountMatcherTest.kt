package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.routes.EventPublicMemberFixtures
import network.lapis.cloud.server.rpc.FriendRegistrationConfig
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.uuid.Uuid

class GuestAddressAccountMatcherTest :
    FunSpec({
        val fixtures = EventPublicMemberFixtures()
        val lenient = FriendRegistrationConfig.load { null }
        val strict = FriendRegistrationConfig.load { if (it == "LAPIS_FRIEND_REQUIRE_EMAIL_VERIFICATION") "true" else null }

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fixtures.cleanup() }

        fun unique() = "matcher-${Uuid.random()}"

        test("ACTIVE, FRIEND and GUEST accounts match; the match carries the stored address") {
            for (status in listOf(MemberStatus.ACTIVE, MemberStatus.FRIEND, MemberStatus.GUEST)) {
                val local = unique()
                val id = fixtures.createMember(email = "$local@example.org", status = status)
                val match = GuestAddressAccountMatcher.selfRegistrantOrNull(normalizedEmail = "$local@example.org", friendConfig = lenient)
                match?.memberId shouldBe id
                match?.email shouldBe "$local@example.org"
            }
        }

        test("matching ignores case: the form lowercases, the account may be stored with capitals") {
            val local = unique()
            val id = fixtures.createMember(email = "$local@Example.ORG")
            val match = GuestAddressAccountMatcher.selfRegistrantOrNull(normalizedEmail = "$local@example.org", friendConfig = lenient)
            match?.memberId shouldBe id
            match?.email shouldBe "$local@Example.ORG"
        }

        test("applicants, withdrawn members, anonymised rows and rows without an account do not match") {
            val applicant = "${unique()}@example.org"
            val withdrawn = "${unique()}@example.org"
            val anonymised = "${unique()}@example.org"
            val noAccount = "${unique()}@example.org"
            fixtures.createMember(email = applicant, status = MemberStatus.APPLICATION)
            fixtures.createMember(email = withdrawn, status = MemberStatus.WITHDRAWN)
            fixtures.createMember(email = anonymised, anonymized = true)
            fixtures.createMember(email = noAccount, withAccount = false)
            for (address in listOf(applicant, withdrawn, anonymised, noAccount)) {
                GuestAddressAccountMatcher.selfRegistrantOrNull(normalizedEmail = address, friendConfig = lenient) shouldBe null
            }
        }

        test("an unknown address does not match") {
            GuestAddressAccountMatcher.selfRegistrantOrNull(normalizedEmail = "${unique()}@example.org", friendConfig = lenient) shouldBe
                null
        }

        test("a FRIEND counts without verification only while the operator does not require it") {
            val unverified = "${unique()}@example.org"
            val verified = "${unique()}@example.org"
            fixtures.createMember(email = unverified, status = MemberStatus.FRIEND)
            fixtures.createMember(email = verified, status = MemberStatus.FRIEND, emailVerified = true)
            (GuestAddressAccountMatcher.selfRegistrantOrNull(normalizedEmail = unverified, friendConfig = lenient) != null) shouldBe true
            GuestAddressAccountMatcher.selfRegistrantOrNull(normalizedEmail = unverified, friendConfig = strict) shouldBe null
            (GuestAddressAccountMatcher.selfRegistrantOrNull(normalizedEmail = verified, friendConfig = strict) != null) shouldBe true
        }

        test("the verification gate is for FRIEND only: an unverified ACTIVE member still matches when verification is required") {
            val address = "${unique()}@example.org"
            fixtures.createMember(email = address, status = MemberStatus.ACTIVE)
            (GuestAddressAccountMatcher.selfRegistrantOrNull(normalizedEmail = address, friendConfig = strict) != null) shouldBe true
        }
    })
