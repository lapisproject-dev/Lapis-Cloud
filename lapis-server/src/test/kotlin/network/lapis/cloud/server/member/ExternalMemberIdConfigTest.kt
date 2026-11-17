package network.lapis.cloud.server.member

import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** V156 -- startup rules of the external member id configuration; reason codes and variable names only, never a value. */
class ExternalMemberIdConfigTest :
    FunSpec({
        val logger = KotlinLogging.logger {}

        fun load(vararg pairs: Pair<String, String>) = ExternalMemberIdConfig.load { pairs.toMap()[it] }

        fun verify(
            config: ExternalMemberIdConfig,
            keycloak: Boolean = true,
        ) = config.verifyAndLog(logger = logger, keycloakEnabled = keycloak)

        test("nothing set: the feature is off and the policy is the legacy one") {
            val c = load()
            c.enabled shouldBe false
            c.source shouldBe null
            c.votingPolicy() shouldBe VotingRightPolicy.LEGACY
            verify(c)
        }

        test("source only, source plus claim, and the voting requirement are accepted") {
            verify(load(ExternalMemberIdConfig.ENV_SOURCE to "Wabeo"))
            val c =
                load(
                    ExternalMemberIdConfig.ENV_SOURCE to "Wabeo",
                    ExternalMemberIdConfig.ENV_CLAIM to "wabeo_id",
                    ExternalMemberIdConfig.ENV_REQUIRE_FOR_VOTING to " TRUE ",
                )
            verify(c)
            c.enabled shouldBe true
            c.claim shouldBe "wabeo_id"
            c.votingPolicy() shouldBe VotingRightPolicy(requireExternalMemberId = true)
        }

        test("a claim without a source refuses the start") {
            shouldThrow<IllegalStateException> { verify(load(ExternalMemberIdConfig.ENV_CLAIM to "wabeo_id")) }
                .message!! shouldContain "EXTERNAL_ID_CLAIM_WITHOUT_SOURCE"
        }

        test("the voting requirement without a source refuses the start") {
            shouldThrow<IllegalStateException> { verify(load(ExternalMemberIdConfig.ENV_REQUIRE_FOR_VOTING to "true")) }
                .message!! shouldContain "EXTERNAL_ID_REQUIRE_WITHOUT_SOURCE"
        }

        test("an invalid source or claim refuses the start and the message never carries the value") {
            val badSource = "bad;source"
            shouldThrow<IllegalStateException> { verify(load(ExternalMemberIdConfig.ENV_SOURCE to badSource)) }.message!!.let {
                it shouldContain "EXTERNAL_ID_SOURCE_INVALID"
                it shouldNotContain badSource
            }
            shouldThrow<IllegalStateException> {
                verify(load(ExternalMemberIdConfig.ENV_SOURCE to "Wabeo", ExternalMemberIdConfig.ENV_CLAIM to "a.b/c"))
            }.message!! shouldContain "EXTERNAL_ID_CLAIM_INVALID"
        }

        test("an invalid requirement value refuses the start and counts as true (fail closed)") {
            val c = load(ExternalMemberIdConfig.ENV_SOURCE to "Wabeo", ExternalMemberIdConfig.ENV_REQUIRE_FOR_VOTING to "yes")
            c.requireForVoting shouldBe true
            shouldThrow<IllegalStateException> { verify(c) }.message!! shouldContain "EXTERNAL_ID_REQUIRE_INVALID"
        }

        test("a claim with Keycloak off is only a warning") {
            verify(load(ExternalMemberIdConfig.ENV_SOURCE to "Wabeo", ExternalMemberIdConfig.ENV_CLAIM to "wabeo_id"), keycloak = false)
        }
    })
