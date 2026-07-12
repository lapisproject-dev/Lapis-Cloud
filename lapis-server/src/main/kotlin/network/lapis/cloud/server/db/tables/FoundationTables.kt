package network.lapis.cloud.server.db.tables

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date

/**
 * Foundation stub — see [network.lapis.cloud.shared.domain.MemberStatus] KDoc and
 * docs/architecture/domain-model.adoc. Hand-written (the `uml-to-exposed` MDA pipeline,
 * ADR-0016, does not exist for this repository yet — see CLAUDE.md "Vorab-Befund"); schema
 * itself is owned by the Flyway migrations under `src/main/resources/db/migration`, not by
 * `SchemaUtils.create`.
 */
object MemberTable : Table("member") {
    val id = uuid("id")
    val displayName = varchar("display_name", 200)
    val email = varchar("email", 320).uniqueIndex()
    val status = enumerationByName<MemberStatus>("status", 20)
    val joinedAt = date("joined_at")

    // Added by the V2 migration (forward reference — membership_tier is created in V2, not
    // V1). Nullable: a member without an assigned tier is skipped by
    // generateContributionsForPeriod. See MembershipTierTable in ContributionTables.kt.
    val membershipTierId = uuid("membership_tier_id").references(MembershipTierTable.id).nullable()

    override val primaryKey = PrimaryKey(id)
}

object AccountTable : Table("account") {
    val id = uuid("id")
    val memberId = uuid("member_id").references(MemberTable.id).uniqueIndex()
    val passwordHash = varchar("password_hash", 200).nullable()
    val oidcSubject = varchar("oidc_subject", 200).nullable()
    val role = enumerationByName<AccountRole>("role", 20)

    override val primaryKey = PrimaryKey(id)
}
