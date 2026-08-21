package network.lapis.cloud.server.rpc

/**
 * V0.11.0 FRIEND self-registration -- environment configuration. Read via env-var lookups injected
 * as a `(String) -> String?` function ([load]'s `env` parameter) rather than `System.getenv` calls
 * scattered through this class -- same testability reasoning
 * [network.lapis.cloud.server.conference.ConferenceConfig] KDoc gives for its own constructor-
 * parameter-default idiom (`System.getenv` cannot be mutated per-JVM-test-run).
 */
class FriendRegistrationConfig private constructor(
    /**
     * `LAPIS_FRIEND_REQUIRE_EMAIL_VERIFICATION` -- default `false`. When `true`,
     * [network.lapis.cloud.shared.domain.MemberStatusSets.CONFERENCE_ELIGIBLE] additionally
     * requires `emailVerifiedAt != null` for a [network.lapis.cloud.shared.domain.MemberStatus
     * .FRIEND] caller. Defaults to `false` -- unrelated to whether real SMTP transport is
     * configured (V1.2.3 added one, see [network.lapis.cloud.server.mail.SmtpConfig]): flipping this
     * to `true` is a separate, later operational decision an operator makes deliberately, not
     * something a working mail transport flips automatically. See
     * [network.lapis.cloud.server.mail.FriendVerificationMailer] KDoc for the full delivery story.
     */
    val requireEmailVerification: Boolean,
    /**
     * `LAPIS_FRIEND_MAX_ACCOUNTS` -- default 500. Global cap: [RegistrationService.registerFriend]
     * refuses once `COUNT(*) WHERE status = FRIEND` reaches this value -- checked BEFORE the
     * duplicate-email check (security-audit F2 fix), so the rejection fires identically for a
     * duplicate OR a brand-new email once the cap is reached, rather than letting a filled cap turn
     * into an account-enumeration oracle (see [RegistrationService.registerFriend] body comment
     * "F2 fix"). Cheap, and it bounds the worst case that IP rotation defeats the per-IP/per-email
     * rate limiters -- see `RegistrationService` KDoc "Rate limiting" for the full defense-in-depth
     * picture (the strongest control is still `allowFederationGuests` defaulting to `false` on every
     * room).
     */
    val maxFriendAccounts: Int,
) {
    companion object {
        private const val DEFAULT_MAX_FRIEND_ACCOUNTS = 500

        fun load(env: (String) -> String? = System::getenv): FriendRegistrationConfig =
            FriendRegistrationConfig(
                requireEmailVerification =
                    env("LAPIS_FRIEND_REQUIRE_EMAIL_VERIFICATION")?.equals("true", ignoreCase = true) == true,
                maxFriendAccounts =
                    env("LAPIS_FRIEND_MAX_ACCOUNTS")?.toIntOrNull() ?: DEFAULT_MAX_FRIEND_ACCOUNTS,
            )
    }
}
