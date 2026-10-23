package network.lapis.cloud.server.db

import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Outcome of the safety checks in front of the staging top-up (V1.9.197). */
internal sealed interface StagingTopUpDecision {
    /** [StagingSeedConfig.decide] is not `Enabled` (this includes `Refused`, where `seedIfEmpty` has already failed fast). */
    data object EnvDisabled : StagingTopUpDecision

    /** The database does not look like the fictitious staging/demo instance. [reason] never contains personal data. */
    data class NotStagingInstance(
        val reason: String,
    ) : StagingTopUpDecision

    data object Eligible : StagingTopUpDecision
}

/**
 * The two independent guards of [StagingSeedTopUp]. Unlike the initial seed, the top-up deliberately works on a NON-empty
 * `member` table, so the "member table empty" lock does not exist here. It is replaced by an instance check that only the
 * seeded Testverein passes: the exact placeholder organization name AND the seeded first member (fixed id, display name,
 * `@staging.invalid` address). A real instance has neither the seed UUID nor an address on the never-resolvable domain.
 */
internal object StagingSeedTopUpGuard {
    const val ANNA_DISPLAY_NAME: String = "Anna Musterfrau"

    /** Pure env gate: `Enabled` -> null (go on), everything else -> [StagingTopUpDecision.EnvDisabled]. */
    fun envGate(env: (String) -> String?): StagingTopUpDecision? =
        when (StagingSeedConfig.decide(env)) {
            is StagingSeedDecision.Enabled -> null
            is StagingSeedDecision.Disabled, is StagingSeedDecision.Refused -> StagingTopUpDecision.EnvDisabled
        }

    /** Reads only. Must be the first thing a top-up transaction does. Exact comparisons: no trim, no ignore-case on the name. */
    fun JdbcTransaction.instanceCheck(): StagingTopUpDecision {
        val orgName =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq StagingSeedData.organizationSettingsId }
                .singleOrNull()
                ?.get(OrganizationSettingsTable.name)
        if (orgName != StagingSeedData.ORGANIZATION_NAME) {
            return StagingTopUpDecision.NotStagingInstance(reason = "organization name is not the staging placeholder")
        }
        val anna =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq SeedIds.member(1) }
                .singleOrNull()
                ?: return StagingTopUpDecision.NotStagingInstance(reason = "seed member 1 does not exist")
        if (anna[MemberTable.displayName] != ANNA_DISPLAY_NAME) {
            return StagingTopUpDecision.NotStagingInstance(reason = "seed member 1 has a different display name")
        }
        if (!anna[MemberTable.email].lowercase().endsWith("@${StagingSeedData.EMAIL_DOMAIN}")) {
            return StagingTopUpDecision.NotStagingInstance(reason = "seed member 1 is not on the staging e-mail domain")
        }
        return StagingTopUpDecision.Eligible
    }
}
