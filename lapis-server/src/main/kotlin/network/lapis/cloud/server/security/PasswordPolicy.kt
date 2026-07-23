package network.lapis.cloud.server.security

import network.lapis.cloud.shared.rpc.WeakPasswordException

/**
 * Minimal password-strength gate for [network.lapis.cloud.server.rpc.AuthService.changePassword]
 * (V0.7.1 Authentifizierung). Deliberately simple — length bounds plus an "isn't just your own
 * email" check — not a full entropy estimator (e.g. zxcvbn); a later wave can tighten this without
 * touching any call site, since every check funnels through [validate].
 */
object PasswordPolicy {
    /** Below this, a password is rejected as too weak to be worth bcrypt-hashing at all. */
    const val MIN_LENGTH: Int = 12

    /** Above this, a password is rejected — bounds both bcrypt's per-call CPU cost and the request body size (DoS). */
    const val MAX_LENGTH: Int = 128

    /** Throws [WeakPasswordException] if [newPassword] is too short/long, or equals [email] (case-insensitive). */
    fun validate(
        newPassword: String,
        email: String,
    ) {
        if (newPassword.length < MIN_LENGTH) {
            throw WeakPasswordException("Password must be at least $MIN_LENGTH characters long")
        }
        if (newPassword.length > MAX_LENGTH) {
            throw WeakPasswordException("Password must be at most $MAX_LENGTH characters long")
        }
        if (newPassword.equals(email, ignoreCase = true)) {
            throw WeakPasswordException("Password must not be the same as your email address")
        }
    }
}
