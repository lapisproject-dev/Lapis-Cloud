package network.lapis.cloud.server.db.tables

import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionStatus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime

object MembershipTierTable : Table("membership_tier") {
    val id = uuid("id")
    val name = varchar("name", 100)
    val description = varchar("description", 1000)
    val contributionAmount = decimal("contribution_amount", 12, 2)
    val billingInterval = enumerationByName<BillingInterval>("billing_interval", 20)
    val active = bool("active")

    override val primaryKey = PrimaryKey(id)
}

object ContributionTable : Table("contribution") {
    val id = uuid("id")
    val memberId = uuid("member_id").references(MemberTable.id)
    val membershipTierId = uuid("membership_tier_id").references(MembershipTierTable.id)
    val periodStart = date("period_start")
    val periodEnd = date("period_end")
    val amountDue = decimal("amount_due", 12, 2)
    val status = enumerationByName<ContributionStatus>("status", 20)
    val paidAt = datetime("paid_at").nullable()
    val paidAmount = decimal("paid_amount", 12, 2).nullable()
    val note = varchar("note", 1000).nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}
