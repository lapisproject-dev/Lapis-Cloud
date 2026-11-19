package network.lapis.cloud.server.carpool.pull

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.carpool.CarpoolOriginSignatures
import network.lapis.cloud.shared.domain.CarpoolTeaser

/** V1.9.314 W4 -- the signed statement of the origin: exact bytes, determinism, tamper detection, strict validation. */
class CarpoolOriginSignaturesTest :
    FunSpec({
        val origin = OriginServer(host = "origin.beispiel.de")
        val other = OriginServer(host = "other.beispiel.de")
        val id = "a".repeat(64)
        val teaser = CarpoolTeaser(teaserId = id, departureDate = LocalDate(2026, 11, 24), fromPostcodeArea = "38", toPostcodeArea = "10")

        test("the statement is exactly the documented bytes (golden)") {
            val expected =
                "lapis-carpool-teaser-origin/v1\n" + id + "\norigin.beispiel.de\n2026-11-24\n38\n10\n2"
            String(
                CarpoolOriginSignatures.statementBytes(teaser = teaser, originHost = "origin.beispiel.de", maxHops = 2),
                Charsets.UTF_8,
            ) shouldBe
                expected
            CarpoolOriginSignatures.PURPOSE shouldBe "lapis-carpool-teaser-origin/v1"
        }

        test("signing is deterministic (the ETag stays stable) and verifies against the matching public key only") {
            val a = CarpoolOriginSignatures.sign(teaser = teaser, originHost = "origin.beispiel.de", maxHops = 2, key = origin.signing)
            val b = CarpoolOriginSignatures.sign(teaser = teaser, originHost = "origin.beispiel.de", maxHops = 2, key = origin.signing)
            a shouldBe b
            CarpoolOriginSignatures.verify(
                teaser = teaser,
                originHost = "origin.beispiel.de",
                maxHops = 2,
                signatureB64 = a,
                publicKeyPem = origin.keyPair.publicKeyPem,
            ) shouldBe
                true
            CarpoolOriginSignatures.verify(
                teaser = teaser,
                originHost = "origin.beispiel.de",
                maxHops = 2,
                signatureB64 = a,
                publicKeyPem = other.keyPair.publicKeyPem,
            ) shouldBe
                false
        }

        test("a rotated key under the SAME key id never gets an old signature back from the cache") {
            val rotated = OriginServer(host = "origin.beispiel.de")
            val first = CarpoolOriginSignatures.sign(teaser = teaser, originHost = "origin.beispiel.de", maxHops = 2, key = origin.signing)
            val second =
                CarpoolOriginSignatures.sign(
                    teaser = teaser,
                    originHost = "origin.beispiel.de",
                    maxHops = 2,
                    key = rotated.signing,
                )
            (first == second) shouldBe false
            CarpoolOriginSignatures.verify(
                teaser = teaser,
                originHost = "origin.beispiel.de",
                maxHops = 2,
                signatureB64 = second,
                publicKeyPem = rotated.keyPair.publicKeyPem,
            ) shouldBe
                true
            CarpoolOriginSignatures.verify(
                teaser = teaser,
                originHost = "origin.beispiel.de",
                maxHops = 2,
                signatureB64 = second,
                publicKeyPem = origin.keyPair.publicKeyPem,
            ) shouldBe
                false
        }

        test("every signed field is bound: id, host, day, both areas and the hop limit") {
            val sig = CarpoolOriginSignatures.sign(teaser = teaser, originHost = "origin.beispiel.de", maxHops = 2, key = origin.signing)
            val pub = origin.keyPair.publicKeyPem

            fun ok(
                t: CarpoolTeaser = teaser,
                host: String = "origin.beispiel.de",
                hops: Int = 2,
            ) = CarpoolOriginSignatures.verify(teaser = t, originHost = host, maxHops = hops, signatureB64 = sig, publicKeyPem = pub)
            ok() shouldBe true
            ok(t = teaser.copy(teaserId = "b".repeat(64))) shouldBe false
            ok(host = "relay.beispiel.de") shouldBe false
            ok(t = teaser.copy(departureDate = LocalDate(2026, 11, 25))) shouldBe false
            ok(t = teaser.copy(fromPostcodeArea = "39")) shouldBe false
            ok(t = teaser.copy(toPostcodeArea = "11")) shouldBe false
            ok(hops = 3) shouldBe false
        }

        test("a field that is not canonical is refused before anything is signed or checked (no line feed can sneak in)") {
            shouldThrow<IllegalArgumentException> {
                CarpoolOriginSignatures.statementBytes(
                    teaser = teaser.copy(teaserId = "A".repeat(64)),
                    originHost = "origin.beispiel.de",
                    maxHops = 2,
                )
            }
            shouldThrow<IllegalArgumentException> {
                CarpoolOriginSignatures.statementBytes(teaser = teaser, originHost = "Origin.Beispiel.de", maxHops = 2)
            }
            shouldThrow<IllegalArgumentException> {
                CarpoolOriginSignatures.statementBytes(teaser = teaser, originHost = "origin.beispiel.de\n2026", maxHops = 2)
            }
            shouldThrow<IllegalArgumentException> {
                CarpoolOriginSignatures.statementBytes(teaser = teaser, originHost = "origin.beispiel.de", maxHops = 4)
            }
            shouldThrow<IllegalArgumentException> {
                CarpoolOriginSignatures.statementBytes(teaser = teaser, originHost = "origin.beispiel.de", maxHops = 0)
            }
            CarpoolOriginSignatures.verify(
                teaser = teaser,
                originHost = "origin.beispiel.de\n",
                maxHops = 2,
                signatureB64 = "AAAA",
                publicKeyPem = origin.keyPair.publicKeyPem,
            ) shouldBe
                false
        }

        test("verify never throws on garbage: bad base64, an over-long value, a broken key") {
            val pub = origin.keyPair.publicKeyPem
            CarpoolOriginSignatures.verify(
                teaser = teaser,
                originHost = "origin.beispiel.de",
                maxHops = 2,
                signatureB64 = "***",
                publicKeyPem = pub,
            ) shouldBe
                false
            CarpoolOriginSignatures.verify(
                teaser = teaser,
                originHost = "origin.beispiel.de",
                maxHops = 2,
                signatureB64 = "A".repeat(2000),
                publicKeyPem = pub,
            ) shouldBe
                false
            CarpoolOriginSignatures.verify(
                teaser = teaser,
                originHost = "origin.beispiel.de",
                maxHops = 2,
                signatureB64 = "AAAA",
                publicKeyPem = "not a key",
            ) shouldBe
                false
        }

        test(
            "a signature over the statement is not a valid HTTP signature and the other way round (domain separation by the purpose prefix)",
        ) {
            // HTTP signing strings of this server always start with "(request-target): "; the statement starts with the purpose.
            String(CarpoolOriginSignatures.statementBytes(teaser = teaser, originHost = "origin.beispiel.de", maxHops = 2), Charsets.UTF_8)
                .startsWith("(request-target)") shouldBe false
        }
    })
