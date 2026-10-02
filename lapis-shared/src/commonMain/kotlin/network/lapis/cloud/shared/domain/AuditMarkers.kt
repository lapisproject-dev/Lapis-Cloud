package network.lapis.cloud.shared.domain

/**
 * Value-free audit markers written as `afterSnapshot` (never a field value). The literal values
 * are load-bearing: they are persisted in the audit hash chain and the client matches on them to
 * translate the protocol display.
 */
object AuditMarkers {
    /** V1.9.33, unchanged value. */
    const val MEMBER_ADDRESS_UPDATED = "ADDRESS_UPDATED"

    /** V1.9.33, unchanged value. */
    const val MEMBER_BENEFICIAL_OWNER_UPDATED = "BENEFICIAL_OWNER_DATA_UPDATED"

    /** V1.9.35: a BOARD/ADMIN read the address / GwG data of another member. */
    const val MEMBER_ADDRESS_READ = "ADDRESS_READ"

    /** V1.9.35: the board recorded an event refund that was paid outside Lapis Cloud. */
    const val EVENT_REFUND_MARKED = "EVENT_REFUND_MARKED"
}
