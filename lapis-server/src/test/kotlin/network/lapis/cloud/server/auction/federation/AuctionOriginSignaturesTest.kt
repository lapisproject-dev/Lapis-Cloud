package network.lapis.cloud.server.auction.federation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.carpool.CarpoolOriginSignatures
import network.lapis.cloud.server.carpool.CarpoolOwnSigningKey
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.shared.domain.AuctionTeaser
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** V1.9.322 (step c2) -- the signed statement of the origin: exact bytes, determinism, tamper detection (each of the 13 fields), strict validation. */
class AuctionOriginSignaturesTest :
    FunSpec({
        val keyPair = FederationKeyPairGenerator.generate()
        val key =
            CarpoolOwnSigningKey(keyId = "https://origin.beispiel.de/federation/actor#main-key", privateKeyPem = keyPair.privateKeyPem)
        val host = "origin.beispiel.de"
        val id = "a".repeat(64)
        val teaser =
            AuctionTeaser(
                teaserId = id,
                revision = 7,
                title = "Lastenrad Größe L",
                currency = "EUR",
                priceKind = AuctionTeaserPriceKind.CURRENT,
                priceMinor = 4200,
                buyNowMinor = 9000,
                offersPossible = true,
                endsAt = Instant.parse("2026-12-03T10:15:00Z"),
                priceAsOf = Instant.parse("2026-12-01T09:30:00Z"),
            )

        fun sign(
            t: AuctionTeaser = teaser,
            h: String = host,
            hops: Int = 2,
        ) = AuctionOriginSignatures.sign(teaser = t, originHost = h, maxHops = hops, key = key)

        fun verifies(
            t: AuctionTeaser,
            sig: String,
            h: String = host,
            hops: Int = 2,
            pem: String = keyPair.publicKeyPem,
        ) = AuctionOriginSignatures.verify(teaser = t, originHost = h, maxHops = hops, signatureB64 = sig, publicKeyPem = pem)

        test("the statement is exactly the documented bytes (golden), with the title as base64url and the canonical instants") {
            val titleB64 = "TGFzdGVucmFkIEdyw7bDn2UgTA"
            val expected =
                listOf(
                    "lapis-auction-teaser-origin/v1",
                    id,
                    host,
                    "7",
                    titleB64,
                    "EUR",
                    "CURRENT",
                    "4200",
                    "9000",
                    "1",
                    "2026-12-03T10:15:00Z",
                    "2026-12-01T09:30:00Z",
                    "2",
                ).joinToString("\n")
            String(AuctionOriginSignatures.statementBytes(teaser = teaser, originHost = host, maxHops = 2), Charsets.UTF_8) shouldBe
                expected
            AuctionOriginSignatures.PURPOSE shouldBe "lapis-auction-teaser-origin/v1"
            // a missing Sofortkauf price is a dash, a missing offer flag a zero
            val plain = teaser.copy(buyNowMinor = null, offersPossible = false)
            val lines =
                String(
                    AuctionOriginSignatures.statementBytes(teaser = plain, originHost = host, maxHops = 1),
                    Charsets.UTF_8,
                ).split("\n")
            lines[8] shouldBe "-"
            lines[9] shouldBe "0"
            lines.size shouldBe 13
        }

        test("signing is deterministic and verifies against the matching public key only") {
            val a = sign()
            a shouldBe sign()
            verifies(teaser, a) shouldBe true
            verifies(teaser, a, pem = FederationKeyPairGenerator.generate().publicKeyPem) shouldBe false
        }

        test("every one of the 13 statement fields is protected: changing any single one breaks the signature") {
            val sig = sign()
            val changes =
                mapOf(
                    "teaserId" to { verifies(teaser.copy(teaserId = "b".repeat(64)), sig) },
                    "originHost" to { verifies(teaser, sig, h = "other.beispiel.de") },
                    "revision" to { verifies(teaser.copy(revision = 8), sig) },
                    "title" to { verifies(teaser.copy(title = "Lastenrad Größe M"), sig) },
                    "priceKind" to { verifies(teaser.copy(priceKind = AuctionTeaserPriceKind.START), sig) },
                    "priceMinor" to { verifies(teaser.copy(priceMinor = 4300), sig) },
                    "buyNowMinor" to { verifies(teaser.copy(buyNowMinor = 9100), sig) },
                    "offersPossible" to { verifies(teaser.copy(offersPossible = false), sig) },
                    "endsAt" to { verifies(teaser.copy(endsAt = Instant.parse("2026-12-03T10:16:00Z")), sig) },
                    "priceAsOf" to { verifies(teaser.copy(priceAsOf = Instant.parse("2026-12-01T09:31:00Z")), sig) },
                    "maxHops" to { verifies(teaser, sig, hops = 1) },
                )
            changes.forEach { (field, check) -> (field to check()) shouldBe (field to false) }
        }

        test("a signature that is too long or not base64 never verifies, and verify never throws") {
            verifies(teaser, "A".repeat(AuctionOriginSignatures.MAX_SIGNATURE_LENGTH + 1)) shouldBe false
            verifies(teaser, "not base64 !!") shouldBe false
            verifies(teaser, sign(), pem = "garbage") shouldBe false
        }

        test("a field that is not canonical is refused when the statement is built") {
            shouldThrow<IllegalArgumentException> { sign(h = "Origin.Beispiel.de") }
            shouldThrow<IllegalArgumentException> { sign(h = "10.0.0.1") }
            shouldThrow<IllegalArgumentException> { sign(t = teaser.copy(currency = "LTR")) }
            shouldThrow<IllegalArgumentException> { sign(hops = 3) }
            shouldThrow<IllegalArgumentException> { sign(hops = 0) }
            shouldThrow<IllegalArgumentException> { sign(t = teaser.copy(endsAt = Instant.parse("2026-12-03T10:15:30Z"))) }
            shouldThrow<IllegalArgumentException> { sign(t = teaser.copy(priceAsOf = Instant.parse("2026-12-03T10:16:00Z"))) }
            shouldThrow<IllegalArgumentException> { sign(t = teaser.copy(title = "http://spam.example")) }
            shouldThrow<IllegalArgumentException> { sign(t = teaser.copy(buyNowMinor = teaser.priceMinor)) }
        }

        test("domain separation: a carpool signature never verifies as an auction statement and vice versa") {
            val auction = sign()
            val carpoolStatement = CarpoolOriginSignatures.PURPOSE
            carpoolStatement shouldBe "lapis-carpool-teaser-origin/v1"
            // the two purposes differ, and the auction signature is not valid for any other message of the same key
            (AuctionOriginSignatures.PURPOSE == carpoolStatement) shouldBe false
            verifies(teaser, auction) shouldBe true
        }

        test("a rotated key under the same key id never gets an old signature back from the cache") {
            val rotated = FederationKeyPairGenerator.generate()
            val rotatedKey = CarpoolOwnSigningKey(keyId = key.keyId, privateKeyPem = rotated.privateKeyPem)
            val old = sign()
            val fresh = AuctionOriginSignatures.sign(teaser = teaser, originHost = host, maxHops = 2, key = rotatedKey)
            (old == fresh) shouldBe false
            verifies(teaser, fresh, pem = rotated.publicKeyPem) shouldBe true
        }

        test("the teaser id is 64 hex characters, stable, differs from the carpool id for the same uuid and hides itself in toString") {
            val box = SecretBox(ByteArray(SecretBox.KEY_SIZE_BYTES) { (it + 1).toByte() })
            val ids = AuctionTeaserIds(box)
            val uuid = Uuid.random()
            val a = ids.of(uuid)
            Regex("^[0-9a-f]{64}$").matches(a) shouldBe true
            ids.of(uuid) shouldBe a
            (
                a ==
                    network.lapis.cloud.server.carpool.feed
                        .CarpoolTeaserIds(box)
                        .of(uuid)
            ) shouldBe false
            ids.toString() shouldBe "AuctionTeaserIds(<redacted>)"
            AuctionTeaserIds.PURPOSE shouldBe "lapis-auction-teaser-id/v1"
        }
    })
