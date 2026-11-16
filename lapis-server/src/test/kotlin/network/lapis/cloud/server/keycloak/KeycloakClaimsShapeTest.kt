package network.lapis.cloud.server.keycloak

import com.nimbusds.jwt.JWTClaimsSet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.Date

private const val CANARY = "CANARY-VALUE-123"

/** Welle V1.9.285 -- pure tests of the claims-shape diagnostic: names and type words only, bounded, never a value. */
class KeycloakClaimsShapeTest :
    FunSpec({
        fun shape(
            claims: Map<String, Any?>,
            group: String? = "groups",
        ) = KeycloakClaimsShape.describe(claimMap = claims, groupClaim = group)

        fun section(
            line: String,
            key: String,
        ): String = line.substringAfter("$key=[").substringBefore("]")

        test("name states") {
            KeycloakClaimsShape.nameState(present = false, value = null) shouldBe "absent"
            KeycloakClaimsShape.nameState(present = true, value = "Erika") shouldBe "string-nonempty"
            KeycloakClaimsShape.nameState(present = true, value = "") shouldBe "string-empty"
            KeycloakClaimsShape.nameState(present = true, value = "  \t\n ") shouldBe "string-sanitizes-to-empty"
            KeycloakClaimsShape.nameState(present = true, value = "\u0007\u200B\u202E") shouldBe "string-sanitizes-to-empty"
            KeycloakClaimsShape.nameState(present = true, value = 42) shouldBe "wrong-type:number"
            KeycloakClaimsShape.nameState(present = true, value = true) shouldBe "wrong-type:boolean"
            KeycloakClaimsShape.nameState(present = true, value = listOf("x")) shouldBe "wrong-type:array"
            KeycloakClaimsShape.nameState(present = true, value = mapOf("a" to "b")) shouldBe "wrong-type:object"
            KeycloakClaimsShape.nameState(present = true, value = null) shouldBe "wrong-type:null"
        }

        test("describe reports every name state in the fixed order") {
            val line =
                shape(
                    mapOf(
                        "name" to "",
                        "given_name" to 42,
                        "family_name" to "\u200B",
                        "preferred_username" to CANARY,
                    ),
                )
            section(line, "nameClaims") shouldBe
                "name:string-empty, given_name:wrong-type:number, family_name:string-sanitizes-to-empty, " +
                "first_name:absent, last_name:absent, preferred_username:string-nonempty"
            shape(emptyMap()).let { section(it, "nameClaims") } shouldBe
                "name:absent, given_name:absent, family_name:absent, first_name:absent, last_name:absent, preferred_username:absent"
            shape(mapOf("name" to null)).let { section(it, "nameClaims") } shouldContain "name:wrong-type:null"
        }

        test("first_name and last_name states in all variants") {
            val line =
                shape(mapOf("first_name" to "Erika", "last_name" to listOf("x")))
            section(line, "nameClaims") shouldContain "first_name:string-nonempty, last_name:wrong-type:array"
            section(shape(mapOf("first_name" to "", "last_name" to "\u200B")), "nameClaims") shouldContain
                "first_name:string-empty, last_name:string-sanitizes-to-empty"
            section(shape(mapOf("first_name" to 1, "last_name" to true)), "nameClaims") shouldContain
                "first_name:wrong-type:number, last_name:wrong-type:boolean"
            shape(mapOf("first_name" to null)).let { section(it, "nameClaims") } shouldContain "first_name:wrong-type:null"
        }

        test("verifiedNameClaim section: absent, number, string, boolean, and missing entirely when null") {
            fun v(map: Map<String, Any?>) =
                KeycloakClaimsShape.describe(claimMap = map, groupClaim = null, verifiedNameClaim = "name_is_verified")
            v(emptyMap()) shouldContain "groupClaim=not-configured verifiedNameClaim=absent invalidClaimNames=0"
            v(mapOf("name_is_verified" to 1L)) shouldContain " verifiedNameClaim=number "
            v(mapOf("name_is_verified" to 1.0)) shouldContain " verifiedNameClaim=number "
            v(mapOf("name_is_verified" to "1")) shouldContain " verifiedNameClaim=string "
            v(mapOf("name_is_verified" to true)) shouldContain " verifiedNameClaim=boolean "
            shape(mapOf("name_is_verified" to 1)) shouldNotContain "verifiedNameClaim"
        }

        test("canary: neither the value of the confirmation claim nor of first_name appears") {
            val line =
                KeycloakClaimsShape.describe(
                    claimMap = mapOf("name_is_verified" to CANARY, "first_name" to CANARY, "last_name" to CANARY),
                    groupClaim = null,
                    verifiedNameClaim = "name_is_verified",
                )
            line shouldNotContain "CANARY"
            line shouldNotContain "accepted"
            line shouldNotContain "rejected"
        }

        test("name state agrees with the real display-name logic") {
            val fixtures = listOf("Erika", "", " ", "\t\n", "\u0007", "\u200B", "\u202Eabc", "a\u0000b", "x".repeat(500), "\u00E9")
            for (f in fixtures) {
                for (claimName in listOf("name", "first_name")) {
                    val ok =
                        KeycloakProvisioningClaims.displayName(JWTClaimsSet.Builder().claim(claimName, f).build()) is
                            KeycloakProvisioningClaims.NameResult.Ok
                    (KeycloakClaimsShape.nameState(present = true, value = f) == "string-nonempty") shouldBe ok
                }
            }
        }

        test("type words, including the Date-typed registered claims of a real claims set") {
            val set =
                JWTClaimsSet
                    .Builder()
                    .subject("s")
                    .audience("a")
                    .issueTime(Date())
                    .expirationTime(Date())
                    .notBeforeTime(Date())
                    .claim("flag", true)
                    .claim("dbl", 1.5)
                    .claim("obj", mapOf("k" to "v"))
                    .claim("arr", listOf("x"))
                    .build()
            val line = KeycloakClaimsShape.describe(verifiedClaims = set, groupClaim = "groups")
            val claims = section(line, "idTokenClaims")
            claims shouldBe "arr:array, aud:array, dbl:number, exp:number, flag:boolean, iat:number, nbf:number, obj:object, sub:string"
            KeycloakClaimsShape.typeWord(arrayOf("x")) shouldBe "array"
            KeycloakClaimsShape.typeWord(Object()) shouldBe "other"
            shape(mapOf("weird" to Object())) shouldContain "weird:other"
        }

        test("canary: no value, no nested key, no array element and no group name ever appears") {
            val claims =
                mapOf<String, Any?>(
                    "email" to "$CANARY@apolda.example",
                    "sub" to CANARY,
                    "name" to CANARY,
                    "groups" to listOf(CANARY, "apolda"),
                    "nested" to mapOf("CANARY-KEY-9" to CANARY),
                    "arr" to listOf(mapOf("CANARY-KEY-9" to CANARY)),
                    "preferred_username" to CANARY,
                )
            val line = shape(claims)
            line shouldNotContain "CANARY"
            line shouldNotContain "apolda"
            line shouldContain "groupClaim=array"
            line shouldContain "nested:object"
        }

        test("hostile claim names are counted, never printed, and the line is plain printable ASCII") {
            val hostile =
                listOf("a\r\nb", "\u001B[31mred", "n\u0430me", "x".repeat(65), "", "a b", "a=b", "a]b")
            val line = shape(hostile.associateWith { CANARY } + ("ok" to "v"))
            line shouldContain "invalidClaimNames=8"
            section(line, "idTokenClaims") shouldBe "ok:string"
            line.all { it.code in 0x20..0x7E } shouldBe true
        }

        test("caps: at most 40 claims and a bounded section, no truncated entry") {
            val many = (1..60).associate { "claim%02d".format(it) to "v" }
            val line = shape(many)
            section(line, "idTokenClaims").split(", ").size shouldBe KeycloakClaimsShape.MAX_CLAIMS
            line shouldContain "omittedClaims=20"

            val long = (1..40).associate { ("c%02d".format(it) + "x".repeat(60)) to "v" }
            val longLine = shape(long)
            val sec = section(longLine, "idTokenClaims")
            (sec.length <= KeycloakClaimsShape.MAX_CLAIMS_SECTION_LENGTH) shouldBe true
            sec.split(", ").all { Regex("[A-Za-z0-9_:./-]{1,64}:string").matches(it) } shouldBe true
            val shown = sec.split(", ").size
            longLine shouldContain "omittedClaims=${40 - shown}"
            (shown < 40) shouldBe true
        }

        test("order is deterministic") {
            val a = linkedMapOf<String, Any?>("b" to "x", "a" to 1, "c" to true)
            val b = linkedMapOf<String, Any?>("c" to true, "a" to 1, "b" to "x")
            shape(a) shouldBe shape(b)
            section(shape(a), "idTokenClaims") shouldBe "a:number, b:string, c:boolean"
        }

        test("group claim states") {
            shape(emptyMap(), group = null) shouldContain "groupClaim=not-configured"
            shape(emptyMap()) shouldContain "groupClaim=absent"
            shape(mapOf("groups" to "g")) shouldContain "groupClaim=string"
            shape(mapOf("groups" to listOf("g"))) shouldContain "groupClaim=array"
            shape(mapOf("groups" to 5)) shouldContain "groupClaim=number"
            shape(mapOf("groups" to 5), group = "other") shouldContain "groupClaim=absent"
        }

        test("the full example line from a WABEO-like token") {
            val set =
                JWTClaimsSet
                    .Builder()
                    .subject("s")
                    .issuer("i")
                    .audience("a")
                    .issueTime(Date())
                    .expirationTime(Date())
                    .claim("email", "e@example.org")
                    .claim("email_verified", true)
                    .claim("preferred_username", "wabeo")
                    .build()
            KeycloakClaimsShape.describe(verifiedClaims = set, groupClaim = null) shouldBe
                "idTokenClaims=[aud:array, email:string, email_verified:boolean, exp:number, iat:number, iss:string, " +
                "preferred_username:string, sub:string] nameClaims=[name:absent, given_name:absent, family_name:absent, " +
                "first_name:absent, last_name:absent, preferred_username:string-nonempty] groupClaim=not-configured invalidClaimNames=0 omittedClaims=0"
        }
    })
