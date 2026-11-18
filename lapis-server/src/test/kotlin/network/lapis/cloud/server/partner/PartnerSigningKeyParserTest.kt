package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * V1.9.312 -- the signing key a partner announces in its public feed: only a key for the partner's OWN host, over https, RSA 2048..4096 bits,
 * of a sane size, is accepted; anything else is ignored WITHOUT invalidating the feed (the events still parse).
 */
class PartnerSigningKeyParserTest :
    FunSpec({
        val pair = FederationKeyPairGenerator.generate()
        val good = PartnerFeedSigningKey(keyId = "https://$PARSER_HOST/federation/actor#main-key", publicKeyPem = pair.publicKeyPem)

        fun key(raw: PartnerFeedSigningKey?) = PartnerFeedParser.parseSigningKey(raw = raw, partnerHost = PARSER_HOST)

        fun pem(bits: Int): String {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(bits)
            val der = generator.generateKeyPair().public.encoded
            return "-----BEGIN PUBLIC KEY-----\n" +
                Base64
                    .getEncoder()
                    .encodeToString(der)
                    .chunked(64)
                    .joinToString("\n") +
                "\n-----END PUBLIC KEY-----\n"
        }

        test("a valid key for the partner's own host is accepted as announced") {
            key(good) shouldBe PinnedSigningKey(keyId = good.keyId, publicKeyPem = good.publicKeyPem)
            key(good.copy(keyId = "https://$PARSER_HOST:443/federation/actor#main-key")) shouldBe
                PinnedSigningKey(keyId = "https://$PARSER_HOST:443/federation/actor#main-key", publicKeyPem = good.publicKeyPem)
            key(null) shouldBe null
        }

        test("a key id for another host, over http, without a fragment, with user info, another port or unprintable is refused") {
            listOf(
                "https://anderer.beispiel.de/federation/actor#main-key",
                "http://$PARSER_HOST/federation/actor#main-key",
                "https://$PARSER_HOST/federation/actor",
                "https://nutzer@$PARSER_HOST/federation/actor#main-key",
                "https://$PARSER_HOST:8443/federation/actor#main-key",
                "https://$PARSER_HOST/federation/actor#main key",
                "https://$PARSER_HOST/federation/actor#main-key\"",
                "https://sub.$PARSER_HOST/federation/actor#main-key",
                "https://$PARSER_HOST.evil.example/federation/actor#main-key",
                "",
                "https://$PARSER_HOST/" + "a".repeat(600) + "#k",
            ).forEach { key(good.copy(keyId = it)) shouldBe null }
        }

        test("a broken, oversized, too small or too large key is refused") {
            key(good.copy(publicKeyPem = "-----BEGIN PUBLIC KEY-----\nnot base64 !!\n-----END PUBLIC KEY-----")) shouldBe null
            key(good.copy(publicKeyPem = "")) shouldBe null
            key(good.copy(publicKeyPem = good.publicKeyPem + "A".repeat(5000))) shouldBe null
            key(good.copy(publicKeyPem = pem(1024))) shouldBe null
            val bits4096 = pem(4096)
            key(good.copy(publicKeyPem = bits4096)) shouldBe PinnedSigningKey(keyId = good.keyId, publicKeyPem = bits4096)
        }

        test("an invalid key never invalidates the feed: the events are still parsed, the key is null") {
            val bad = ""","signingKey":{"keyId":"https://anderer.beispiel.de/x#k","publicKeyPem":"x"}"""
            val withBad =
                (String(feedJson()).dropLast(n = 1) + bad + "}").toByteArray()
            val result =
                PartnerFeedParser.parse(
                    bytes = withBad,
                    partnerHost = PARSER_HOST,
                    ownServerId = kotlin.uuid.Uuid.random(),
                    knownRemoteId = null,
                    now = PARSER_NOW,
                )
            val ok = result.shouldBeInstanceOf<PartnerParseResult.Ok>()
            ok.events shouldHaveSize 1
            ok.signingKey shouldBe null
            val withGood =
                (
                    String(feedJson()).dropLast(n = 1) +
                        ""","signingKey":{"keyId":"${good.keyId}","publicKeyPem":${jsonString(good.publicKeyPem)}}}"""
                ).toByteArray()
            val parsed =
                PartnerFeedParser.parse(
                    bytes = withGood,
                    partnerHost = PARSER_HOST,
                    ownServerId = kotlin.uuid.Uuid.random(),
                    knownRemoteId = null,
                    now = PARSER_NOW,
                )
            parsed.shouldBeInstanceOf<PartnerParseResult.Ok>().signingKey shouldBe
                PinnedSigningKey(keyId = good.keyId, publicKeyPem = good.publicKeyPem)
        }
    })
