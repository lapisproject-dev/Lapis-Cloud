package network.lapis.cloud.server.rpc

/**
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- operator configuration of the hard
 * "every member becoming ACTIVE must already have a regional chapter assigned once at least one
 * exists" rule (see [requireRegionalChapterBeforeActivation]/[RegistrationService
 * .requireValidRegionalChapterSelection]). **Default OFF**, same posture
 * [network.lapis.cloud.server.keycloak.KeycloakConfig] KDoc documents for its own optional feature.
 *
 * **Review-fix reasoning.** This wave ships BACKEND-ONLY (no chapter picker in
 * `RegistrationScreen`/`MemberAdministrationScreen`, no chapter-management admin screen at all,
 * see `CHANGELOG.md`). With enforcement unconditionally on, the FIRST chapter an ADMIN ever creates
 * (reachable today only via a direct RPC call, e.g. from devtools or a future admin tool) would
 * immediately start rejecting every self-registration/admin-create/apply-for-membership call the
 * existing, unmodified client makes -- a `RegionalChapterRequiredException` the client has no picker
 * to satisfy and does not know how to recover from. Read via env-var lookups injected as a
 * `(String) -> String?` function (`load`'s `env` parameter), same testability reasoning
 * [network.lapis.cloud.server.conference.ConferenceConfig] KDoc gives for its own constructor-
 * parameter-default idiom (`System.getenv` cannot be mutated per-JVM-test-run). An operator flips
 * `LAPIS_REGIONAL_CHAPTER_ENFORCEMENT_ENABLED=true` once the client picker/admin UI has actually
 * shipped -- the rule's OWN logic ([requireRegionalChapterBeforeActivation]) and its full test
 * coverage stay exactly as designed and already reviewed; only its DEFAULT activation moment moves.
 */
class RegionalChapterEnforcementConfig private constructor(
    val enabled: Boolean,
) {
    companion object {
        const val ENV_ENABLED = "LAPIS_REGIONAL_CHAPTER_ENFORCEMENT_ENABLED"

        fun load(env: (String) -> String? = System::getenv): RegionalChapterEnforcementConfig =
            RegionalChapterEnforcementConfig(enabled = env(ENV_ENABLED)?.equals("true", ignoreCase = true) == true)
    }
}
