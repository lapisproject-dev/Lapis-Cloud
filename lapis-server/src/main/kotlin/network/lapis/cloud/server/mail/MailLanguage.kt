package network.lapis.cloud.server.mail

/**
 * V1.9.332 -- which language a single-language mail is written in. Precedence: the recipient's own language (when it is one of the
 * supported codes) before the instance language ([network.lapis.cloud.server.rpc.MailLanguageStore]) before `de`.
 */
internal object MailLanguage {
    fun resolve(
        recipient: String?,
        instance: String?,
    ): String =
        if (MembershipDecisionTexts.isSupported(recipient)) {
            MembershipDecisionTexts.normalize(recipient)
        } else {
            MembershipDecisionTexts.normalize(instance)
        }
}
