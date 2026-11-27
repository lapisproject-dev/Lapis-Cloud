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

    /**
     * V1.9.334 -- the language of a system mail without a recipient language (no member has one): the instance language read from [source]
     * at SEND time. Never throws; a failing or unknown source means `de`, so a mail is never lost because a setting could not be read.
     */
    fun instance(source: () -> String): String =
        resolve(recipient = null, instance = runCatching { source() }.getOrDefault(MembershipDecisionTexts.DEFAULT_LANGUAGE))

    /**
     * V1.9.335 -- the language of the event mails: the instance language at SEND time (production source: the organisation settings).
     * Used as the default of every event-mail entry point, so no production path can fall back to a hard-coded German.
     */
    fun instanceLanguage(): String =
        instance {
            network.lapis.cloud.server.rpc.MailLanguageStore
                .loadOrDefault()
        }
}
