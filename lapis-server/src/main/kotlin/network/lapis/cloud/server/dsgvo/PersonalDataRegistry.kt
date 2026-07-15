package network.lapis.cloud.server.dsgvo

/**
 * Single compile-time-typed source of truth for which [PersonalDataContributor] owns which
 * personal-data-bearing table — mirrors the explicit `initRpc { registerService(...) }` style in
 * `network.lapis.cloud.server.Application.module`. Export (`DsgvoService.exportManifest` / the
 * HTTP export route) and erasure (`DsgvoService.executeErasure`) both iterate [contributors]
 * inside one `transaction {}`, so a future domain area (e.g. an "events" wave) automatically
 * participates in both once it registers here.
 *
 * **This list, by itself, does not prevent rot.** Forgetting to add a new
 * [PersonalDataContributor] here compiles fine. The actual enforcement mechanism is
 * `PersonalDataCoverageTest`: it walks `information_schema` for every foreign key referencing
 * `member(id)` and asserts each `(table, column)` is inside some contributor's `coveredTables`
 * or explicitly listed in [noPersonalDataAllowlist] with a written reason. The day a later wave
 * adds e.g. `event_registration.member_id`, that test goes red until someone writes an
 * `EventPersonalData` contributor (or allowlists it) — `./gradlew clean check` enforces it, a
 * hand-maintained table list alone could not.
 */
object PersonalDataRegistry {
    val contributors: List<PersonalDataContributor> =
        listOf(
            FoundationPersonalData,
            ContributionPersonalData,
            DocumentPersonalData,
            CommunicationPersonalData,
            GovernancePersonalData,
            LtrPersonalData,
            ElectionPersonalData,
            SystemicConsensusPersonalData,
        )

    /**
     * Tables that are not covered by a [PersonalDataContributor] on purpose, each with a written
     * reason — checked by `PersonalDataCoverageTest` alongside [contributors]. `member` itself is
     * *not* listed here: it is covered by [FoundationPersonalData.coveredTables] even though it
     * is the PK side, not the FK side, of every relationship (see that object's KDoc).
     */
    val noPersonalDataAllowlist: Map<String, String> =
        mapOf(
            "membership_tier" to "Pure product definition (fee amount/interval), no personal data.",
            "document_folder" to "Pure organizational structure, no personal data.",
            "committee" to "Pure organizational structure, no personal data.",
            "vote_option" to
                "The basket (option) itself has no member FK -- only the vote_ballot rows staked " +
                "into it carry personal data (see GovernancePersonalData).",
            "election_option" to
                "The option itself has no member FK -- only the associated election_candidacy row " +
                "(one hop further, see ElectionPersonalData) carries personal data.",
            "election_ballot_selection" to
                "The selection row has no member FK of its own -- it only resolves to a member via " +
                "election_ballot (two hops further, see ElectionPersonalData), and there only on " +
                "the non-secret path.",
            "systemic_consensus_resistance" to
                "The resistance value itself has no member FK of its own -- it only resolves to a " +
                "member via systemic_consensus_ballot (two hops further, see " +
                "SystemicConsensusPersonalData), and there only on the non-secret path.",
            "erasure_request" to
                "Manages the erasure process itself and references members only by UUID. Persists " +
                "after erasure as a procedural record (see dsgvo.adoc).",
            "dsgvo_audit_log" to
                "Deliberately NOT covered by the erasure walk: references the subject only by UUID, " +
                "accountability (Art. 5(2) GDPR) is its own legal basis for retention. See " +
                "dsgvo.adoc \"Audit log data protection\".",
        )

    init {
        val owners = mutableMapOf<String, PersonalDataContributor>()
        for (contributor in contributors) {
            for (table in contributor.coveredTables) {
                val tableName = table.tableName
                val existingOwner = owners[tableName]
                check(existingOwner == null) {
                    "Table '$tableName' is covered by both '${existingOwner?.sectionKey}' and " +
                        "'${contributor.sectionKey}' PersonalDataContributor -- each table may be " +
                        "covered by exactly one contributor."
                }
                owners[tableName] = contributor
            }
        }
    }

    /** Every table name (lowercase, matching Exposed's [org.jetbrains.exposed.v1.core.Table.tableName]) any contributor owns. */
    fun coveredTableNames(): Set<String> = contributors.flatMap { it.coveredTables }.map { it.tableName }.toSet()
}
