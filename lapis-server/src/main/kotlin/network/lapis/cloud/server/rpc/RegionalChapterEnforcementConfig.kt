package network.lapis.cloud.server.rpc

/**
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- operator configuration of the hard
 * "every member becoming ACTIVE must already have a regional chapter assigned once at least one
 * exists" rule (see [requireRegionalChapterBeforeActivation]/[RegistrationService
 * .requireValidRegionalChapterSelection]). **Default OFF**, same posture
 * [network.lapis.cloud.server.keycloak.KeycloakConfig] KDoc documents for its own optional feature.
 *
 * **Still Default OFF as of Welle V1.9.14, deliberately.** V1.9.13 shipped backend-only, when
 * enforcing unconditionally would have immediately rejected every self-registration/admin-create/
 * apply-for-membership call the then-unmodified client made (a `RegionalChapterRequiredException`
 * the client had no picker to satisfy). V1.9.14 added the chapter picker to `RegistrationScreen`/
 * `MemberAdministrationScreen` AND the `RegionalChaptersScreen` admin UI to actually create
 * chapters through -- but the default stays OFF: whether every active member MUST be assigned a
 * chapter is an operator policy decision, not something this wave should flip silently just
 * because the picker now exists (a betreiber running chapters purely informally, with some members
 * intentionally unassigned, is a legitimate choice). Read via env-var lookups injected as a
 * `(String) -> String?` function (`load`'s `env` parameter), same testability reasoning
 * [network.lapis.cloud.server.conference.ConferenceConfig] KDoc gives for its own constructor-
 * parameter-default idiom (`System.getenv` cannot be mutated per-JVM-test-run). An operator flips
 * `LAPIS_REGIONAL_CHAPTER_ENFORCEMENT_ENABLED=true` when they decide the assignment should become
 * mandatory -- the rule's OWN logic ([requireRegionalChapterBeforeActivation]) and its full test
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
