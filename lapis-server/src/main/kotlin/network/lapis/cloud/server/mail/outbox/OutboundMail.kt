package network.lapis.cloud.server.mail.outbox

/** One system mail on its way into the durable outbox. [logRecipient] `false` keeps even the masked address out of the log. */
data class OutboundMail(
    val to: String,
    val subject: String,
    val plainTextBody: String,
    val htmlBody: String,
    val purpose: String,
    val logRecipient: Boolean = true,
) {
    // The address is personal data; the bodies can carry tokens (password-reset links). Never let a stray toString() log them.
    override fun toString(): String = "OutboundMail(purpose=$purpose)"
}
