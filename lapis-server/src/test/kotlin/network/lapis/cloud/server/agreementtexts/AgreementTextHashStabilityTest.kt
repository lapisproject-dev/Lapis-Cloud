package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextKind

/**
 * V1.9.196 -- the format flag is NOT part of the consent hash. A version published before V112 (plain) keeps its hash, the same label and body as
 * MARKDOWN hash identically, an acknowledgment recorded earlier still matches the current text, and the format cannot be used to forge a match.
 * (`AgreementTextBuiltinPinTest` pins the built-in placeholders and stays green unchanged.)
 */
class AgreementTextHashStabilityTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach { AgreementTextTestState.reset() }
        afterSpec { AgreementTextTestState.reset() }

        test("the hash is SHA-256 over label, line feed and the stored source body -- the formula is pinned") {
            val expected =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest("v1\nHallo".toByteArray(Charsets.UTF_8))
                    .joinToString("") { b -> "%02x".format(b) }
            AgreementTextHash.of(versionLabel = "v1", body = "Hallo") shouldBe expected
        }

        test(
            "the same label and body hash identically as a plain and as a markdown version, and the resolver serves both with their format",
        ) {
            val plain = AgreementTextTestState.publishDirect(kind = AgreementTextKind.FRIEND, label = "gleich.v1", body = "**Text**")
            AgreementTextTestState.reset()
            val markdown =
                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.FRIEND,
                    label = "gleich.v1",
                    body = "**Text**",
                    format = AgreementTextFormat.MARKDOWN,
                )
            markdown shouldBe plain
            val current = AgreementTextResolver.current(kind = AgreementTextKind.FRIEND)
            current.format shouldBe AgreementTextFormat.MARKDOWN
            current.sha256 shouldBe plain
            current.body shouldBe "**Text**"
        }

        test("an acknowledgment recorded for a plain version still matches the current text (matchesCurrent never looks at the format)") {
            val sha = AgreementTextTestState.publishDirect(kind = AgreementTextKind.MEMBERSHIP, label = "bestand.v1", body = "Bestandstext")
            AgreementTextResolver.matchesCurrent(kind = AgreementTextKind.MEMBERSHIP, version = "bestand.v1", sha256 = sha) shouldBe true
            AgreementTextResolver.current(kind = AgreementTextKind.MEMBERSHIP).format shouldBe AgreementTextFormat.PLAIN
            AgreementTextResolver.matchesCurrent(
                kind = AgreementTextKind.MEMBERSHIP,
                version = "bestand.v1",
                sha256 = "0".repeat(64),
            ) shouldBe
                false
        }

        test("the built-in placeholders are always plain") {
            AgreementTextKind.entries.forEach { kind ->
                AgreementTextResolver.current(kind = kind).format shouldBe AgreementTextFormat.PLAIN
                AgreementTextResolver.current(kind = kind).placeholder shouldBe true
            }
        }
    })
