package network.lapis.cloud.server.security

import java.security.SecureRandom

/**
 * Welle V1.4.9 "Admin-Passwort-Reset" -- generates a password an operator can DICTATE over the
 * PHONE. That is the only transport this feature has at all for the value itself (the
 * accompanying security notice to the member deliberately carries no password, see
 * `network.lapis.cloud.server.mail.MailTemplates.passwordResetByAdmin` KDoc), so this is a
 * functional requirement, not cosmetics: `xK3$9mQz#p2R` is unusable over the phone.
 *
 * Format: [GROUP_COUNT] groups of [GROUP_SIZE] characters, joined with `"-"` (e.g.
 * `k7rq-ta9m-wx3f-hn6d`). [ALPHABET] contains neither uppercase letters nor special characters,
 * nor the confusable pairs i/l/1 and o/0. 19 characters including separators -- well above
 * [PasswordPolicy.MIN_LENGTH] (12), roughly 79 bits of entropy (31^16). [PasswordPolicy] imposes
 * no character-class requirement, so this format passes it unchanged.
 *
 * **Rejection sampling, not a bare `% ALPHABET.length`**: 256 % 31 == 8, so a naive modulo would
 * measurably favor the alphabet's first eight characters. [SecureRandom.nextInt] itself already
 * performs rejection sampling internally for a bound that is not a power of two, so this class
 * simply always calls [SecureRandom.nextInt] with the alphabet's length as the bound -- documented
 * explicitly here so a future edit does not "simplify" this into a raw `nextInt() % ALPHABET.length`
 * (a genuine, if smaller than 256, bias) or a raw byte-modulo (the 256 % 31 bias above).
 */
object TemporaryPasswordGenerator {
    /** 31 characters -- no uppercase, no special characters, no i/l/1/o/0 confusable pairs. */
    const val ALPHABET: String = "abcdefghjkmnpqrstuvwxyz23456789"
    const val GROUP_COUNT: Int = 4
    const val GROUP_SIZE: Int = 4

    /**
     * [random] defaults to a fresh [SecureRandom] per call -- cheap relative to the bcrypt hashing
     * this value immediately feeds into, and this call site is a rare, deliberate ADMIN action, not
     * a hot path. The parameter exists only so a deterministic test can drive a specific byte
     * sequence through [SecureRandom.nextInt]'s own rejection-sampling loop.
     */
    fun generate(random: SecureRandom = SecureRandom()): String =
        (0 until GROUP_COUNT)
            .joinToString(separator = "-") {
                (0 until GROUP_SIZE)
                    .map { ALPHABET[random.nextInt(ALPHABET.length)] }
                    .joinToString(separator = "")
            }
}
