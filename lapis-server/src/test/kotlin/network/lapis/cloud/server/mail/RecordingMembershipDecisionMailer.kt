package network.lapis.cloud.server.mail

/** Test double for [MembershipDecisionMailer]: records every call, can refuse or throw, and runs a hook at send time. */
class RecordingMembershipDecisionMailer(
    override val deliveryConfigured: Boolean = true,
    var accepts: Boolean = true,
    var throwOnSend: Boolean = false,
    /** Runs at the moment of every send -- lets a test observe the database state a send happens in. */
    var onSend: () -> Unit = {},
) : MembershipDecisionMailer {
    data class Sent(
        val email: String,
        val displayName: String,
        val approved: Boolean,
        val variant: RejectionVariant?,
        val language: String,
    )

    val sent = mutableListOf<Sent>()

    private fun record(entry: Sent): Boolean {
        onSend()
        if (throwOnSend) error("boom for ${entry.email}")
        sent += entry
        return accepts
    }

    override fun sendApproved(
        email: String,
        displayName: String,
        language: String,
    ): Boolean = record(Sent(email = email, displayName = displayName, approved = true, variant = null, language = language))

    override fun sendRejected(
        email: String,
        displayName: String,
        variant: RejectionVariant,
        language: String,
    ): Boolean = record(Sent(email = email, displayName = displayName, approved = false, variant = variant, language = language))
}
