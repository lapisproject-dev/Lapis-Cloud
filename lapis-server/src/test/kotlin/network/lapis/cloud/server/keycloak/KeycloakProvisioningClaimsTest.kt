package network.lapis.cloud.server.keycloak

import com.nimbusds.jwt.JWTClaimsSet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.keycloak.KeycloakProvisioningClaims.GroupCheck
import network.lapis.cloud.server.keycloak.KeycloakProvisioningClaims.NameResult

private fun claims(vararg pairs: Pair<String, Any?>): JWTClaimsSet {
    val builder = JWTClaimsSet.Builder()
    pairs.forEach { (k, v) -> builder.claim(k, v) }
    return builder.build()
}

/** Welle V1.9.73 -- the pure claim evaluation: group matching, name derivation and sanitizing. */
class KeycloakProvisioningClaimsTest :
    FunSpec({
        fun group(
            claim: Any?,
            required: String = "apolda",
            name: String = "groups",
        ) = KeycloakProvisioningClaims.checkGroup(claims = claims(name to claim), claimName = name, requiredGroup = required)

        test("a string claim matches the group, with and without the leading slash") {
            group("apolda") shouldBe GroupCheck.MATCH
            group("/apolda") shouldBe GroupCheck.MATCH
            group("apolda", required = "/apolda") shouldBe GroupCheck.MATCH
            group("/apolda", required = "/apolda") shouldBe GroupCheck.MATCH
        }

        test("an array claim matches when one string element is the group") {
            group(listOf("other", "/apolda")) shouldBe GroupCheck.MATCH
            group(listOf("apolda")) shouldBe GroupCheck.MATCH
        }

        test("a sub-group path is NOT the group") {
            group("/parent/apolda") shouldBe GroupCheck.NO_MATCH
            group(listOf("/parent/apolda", "apolda-extra")) shouldBe GroupCheck.NO_MATCH
        }

        test("the comparison is case-sensitive and exact") {
            group("Apolda") shouldBe GroupCheck.NO_MATCH
            group("apolda ") shouldBe GroupCheck.NO_MATCH
            group("apold") shouldBe GroupCheck.NO_MATCH
        }

        test("a missing claim is MISSING, also for another claim name") {
            KeycloakProvisioningClaims.checkGroup(
                claims = claims("email" to "a@b.de"),
                claimName = "groups",
                requiredGroup = "apolda",
            ) shouldBe
                GroupCheck.MISSING
            KeycloakProvisioningClaims.checkGroup(
                claims = claims("groups" to listOf("apolda")),
                claimName = "roles",
                requiredGroup = "apolda",
            ) shouldBe
                GroupCheck.MISSING
        }

        test("a number, an object or a boolean is WRONG_TYPE") {
            group(5) shouldBe GroupCheck.WRONG_TYPE
            group(true) shouldBe GroupCheck.WRONG_TYPE
            group(mapOf("apolda" to true)) shouldBe GroupCheck.WRONG_TYPE
        }

        test("an empty list or empty string is NO_MATCH, and non-string array elements are ignored") {
            group(emptyList<String>()) shouldBe GroupCheck.NO_MATCH
            group("") shouldBe GroupCheck.NO_MATCH
            group(listOf(1, 2, mapOf("apolda" to 1))) shouldBe GroupCheck.NO_MATCH
            group(listOf(1, "apolda")) shouldBe GroupCheck.MATCH
        }

        test("groupMatches never matches an empty required group") {
            KeycloakProvisioningClaims.groupMatches(value = "", requiredGroup = "") shouldBe false
            KeycloakProvisioningClaims.groupMatches(value = "/", requiredGroup = "/") shouldBe false
        }

        test("the name comes from the name claim") {
            KeycloakProvisioningClaims.displayName(claims("name" to "Erika Muster", "given_name" to "X", "family_name" to "Y")) shouldBe
                NameResult.Ok("Erika Muster")
        }

        test("the name falls back to given_name + family_name, and one part alone is accepted") {
            KeycloakProvisioningClaims.displayName(claims("given_name" to "Erika", "family_name" to "Muster")) shouldBe
                NameResult.Ok("Erika Muster")
            KeycloakProvisioningClaims.displayName(claims("given_name" to "Erika")) shouldBe NameResult.Ok("Erika")
            KeycloakProvisioningClaims.displayName(claims("family_name" to "Muster")) shouldBe NameResult.Ok("Muster")
            KeycloakProvisioningClaims.displayName(claims("name" to "   ", "given_name" to "Erika")) shouldBe NameResult.Ok("Erika")
        }

        test("no usable name is Missing: absent, blank, only controls, or a non-string claim") {
            KeycloakProvisioningClaims.displayName(claims()) shouldBe NameResult.Missing
            KeycloakProvisioningClaims.displayName(claims("name" to "  \t ")) shouldBe NameResult.Missing
            KeycloakProvisioningClaims.displayName(claims("name" to "\u0000\u0007​")) shouldBe NameResult.Missing
            KeycloakProvisioningClaims.displayName(claims("name" to 42, "given_name" to listOf("x"))) shouldBe NameResult.Missing
        }

        test("sanitizeName turns line breaks into a space and removes controls and bidi overrides") {
            KeycloakProvisioningClaims.sanitizeName("Erika\r\nMuster") shouldBe "Erika Muster"
            KeycloakProvisioningClaims.sanitizeName("Erika Muster Zwei") shouldBe "Erika Muster Zwei"
            KeycloakProvisioningClaims.sanitizeName("Er\u0000ika‮Mu⁦ster⁩") shouldBe "ErikaMuster"
            KeycloakProvisioningClaims.sanitizeName("  Erika    Muster  ") shouldBe "Erika Muster"
        }

        test("sanitizeName cuts to 200 UTF-16 units and never splits a surrogate pair") {
            KeycloakProvisioningClaims.sanitizeName("a".repeat(250))!!.length shouldBe 200
            // 199 BMP characters + one emoji (2 units): the pair would straddle the limit and is dropped whole.
            val straddling = "b".repeat(199) + "😀" + "tail"
            val cut = KeycloakProvisioningClaims.sanitizeName(straddling)!!
            cut shouldBe "b".repeat(199)
            // 198 + emoji = exactly 200 units: kept intact.
            val fitting = "c".repeat(198) + "😀" + "tail"
            val kept = KeycloakProvisioningClaims.sanitizeName(fitting)!!
            kept.length shouldBe 200
            Character.isLowSurrogate(kept.last()) shouldBe true
        }

        test("sanitizeName returns null for null and for nothing left") {
            KeycloakProvisioningClaims.sanitizeName(null) shouldBe null
            KeycloakProvisioningClaims.sanitizeName("") shouldBe null
            KeycloakProvisioningClaims.sanitizeName("​‍") shouldBe null
        }
    })
