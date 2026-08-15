package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Pure tests of [PasswordHasher] — no DB access. Covers the happy path, the two documented
 * hardenings ("72-byte truncation", "Timing-safe 'no such account' handling"), and tamper cases.
 */
class PasswordHasherTest :
    FunSpec({
        test("hash() then verify() round-trips for the same password") {
            val hash = PasswordHasher.hash("a-perfectly-fine-password")
            PasswordHasher.verify(rawPassword = "a-perfectly-fine-password", storedHash = hash) shouldBe true
        }

        test("verify() rejects a wrong password against a real hash") {
            val hash = PasswordHasher.hash("correct-password-123")
            PasswordHasher.verify(rawPassword = "wrong-password-123", storedHash = hash) shouldBe false
        }

        test("verify() rejects any password when storedHash is null (no such account / never set)") {
            PasswordHasher.verify(rawPassword = "anything-at-all", storedHash = null) shouldBe false
        }

        test("hash() is randomized -- two hashes of the same password never collide (fresh salt every call)") {
            val first = PasswordHasher.hash("same-password-both-times")
            val second = PasswordHasher.hash("same-password-both-times")
            first shouldNotBe second
            // Both must still independently verify against the original password.
            PasswordHasher.verify(rawPassword = "same-password-both-times", storedHash = first) shouldBe true
            PasswordHasher.verify(rawPassword = "same-password-both-times", storedHash = second) shouldBe true
        }

        test("a password well beyond bcrypt's raw 72-byte limit is fully honored via SHA-256 pre-hashing") {
            // 200 ASCII chars -- far beyond bcrypt's native 72-byte truncation point. Two passwords
            // that only differ AFTER byte 72 of the raw UTF-8 input must still be distinguishable,
            // which only holds if the full password (not just the first 72 bytes) feeds the hash.
            val base = "x".repeat(100)
            val passwordA = base + "A".repeat(100)
            val passwordB = base + "B".repeat(100)
            val hashA = PasswordHasher.hash(passwordA)
            PasswordHasher.verify(rawPassword = passwordA, storedHash = hashA) shouldBe true
            PasswordHasher.verify(rawPassword = passwordB, storedHash = hashA) shouldBe false
        }

        test("a password containing an embedded NUL codepoint is fully honored, not silently truncated at it") {
            // Raw bcrypt/Blowfish stops at the first NUL byte -- preHash's SHA-256 digest does not,
            // so two passwords differing only after an embedded NUL codepoint must still be
            // distinguishable. Constructed via Char(0) rather than a literal control character in
            // source text.
            val nulChar = Char(0)
            val passwordA = "prefix" + nulChar + "suffix-A"
            val passwordB = "prefix" + nulChar + "suffix-B"
            val hash = PasswordHasher.hash(passwordA)
            PasswordHasher.verify(rawPassword = passwordA, storedHash = hash) shouldBe true
            PasswordHasher.verify(rawPassword = passwordB, storedHash = hash) shouldBe false
        }
    })
