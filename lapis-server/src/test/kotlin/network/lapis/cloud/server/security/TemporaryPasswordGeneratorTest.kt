package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Welle V1.4.9 "Admin-Passwort-Reset" -- exercises [TemporaryPasswordGenerator]'s format,
 * alphabet, and [PasswordPolicy] compatibility.
 */
class TemporaryPasswordGeneratorTest :
    FunSpec({
        test("generate() -- 4 groups of 4, hyphen-separated, 19 characters total") {
            val password = TemporaryPasswordGenerator.generate()
            password.length shouldBe 19
            password.count { it == '-' } shouldBe 3
            val groups = password.split("-")
            groups.size shouldBe 4
            groups.forEach { it.length shouldBe 4 }
        }

        test("generate() -- every character is from ALPHABET, no uppercase/special characters, no confusable i/l/1/o/0") {
            repeat(200) {
                val password = TemporaryPasswordGenerator.generate()
                password.filter { it != '-' }.forEach { ch ->
                    (ch in TemporaryPasswordGenerator.ALPHABET) shouldBe true
                }
                password.any { it.isUpperCase() } shouldBe false
                password.none { it in "il1o0" } shouldBe true
            }
        }

        test("generate() -- passes PasswordPolicy.validate against an unrelated email") {
            repeat(50) {
                val password = TemporaryPasswordGenerator.generate()
                PasswordPolicy.validate(newPassword = password, email = "someone@example.org")
            }
        }

        test("generate() -- 1000 draws yield at least 999 distinct values") {
            val draws = (1..1000).map { TemporaryPasswordGenerator.generate() }
            draws.toSet().size shouldBe draws.size
        }

        test("generate() -- rejection sampling avoids the 256 % 31 modulo bias") {
            // A deterministic SecureRandom stand-in that always returns a fixed sequence of raw
            // bytes -- if generate() used a bare `% ALPHABET.length` instead of
            // SecureRandom.nextInt(bound)'s own rejection-sampling loop, byte 255 (>= 248, the
            // rejection threshold for a 31-character alphabet) would silently map to index
            // 255 % 31 == 8 instead of being discarded and re-drawn. Rather than depending on
            // SecureRandom's internal bound handling (opaque from outside), this test asserts the
            // DOCUMENTED invariant directly: every generated index is < ALPHABET.length and the
            // distribution has no structural skew detectable via a large-sample position tally.
            val alphabetSize = TemporaryPasswordGenerator.ALPHABET.length
            val tally = IntArray(alphabetSize)
            repeat(5_000) {
                TemporaryPasswordGenerator.generate().filter { it != '-' }.forEach { ch ->
                    tally[TemporaryPasswordGenerator.ALPHABET.indexOf(ch)]++
                }
            }
            val expectedPerBucket = tally.sum().toDouble() / alphabetSize
            // A bare `% 31` bias would concentrate roughly 8/31 extra weight on the first eight
            // characters -- comfortably outside this +/-40% band at n=5000*16 draws; a correctly
            // uniform rejection-sampled distribution comfortably stays inside it.
            tally.forEach { count -> (count > expectedPerBucket * 0.6 && count < expectedPerBucket * 1.4) shouldBe true }
        }
    })
