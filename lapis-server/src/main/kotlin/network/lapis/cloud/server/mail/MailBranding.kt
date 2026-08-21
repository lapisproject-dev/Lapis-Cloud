package network.lapis.cloud.server.mail

import network.lapis.cloud.server.federation.FederationConfig

/**
 * Die white-label-Absenderidentität, die in JEDE gerenderte Mail einfließt (V1.2.3 Design-Review,
 * Konflikt 1: "Lapis Cloud" ist ein Produktname, den kein PdV-Mitglied je gehört hat -- eine Mail
 * von parteidervernunft.de, die sich "Lapis Cloud" nennt, liest sich wie Phishing). Im
 * [SmtpConfigState.Configured]-Fall kommt [fromDisplayName] aus dem PFLICHT-Env-Wert
 * LAPIS_SMTP_FROM_NAME, ist also immer betreiber-gewaehlt.
 */
data class MailBranding(
    val fromDisplayName: String,
    val replyTo: String?,
    val publicBaseUrl: String = FederationConfig.publicBaseUrl,
) {
    companion object {
        /**
         * Nur fuer [SmtpConfigState.NotConfigured] -- dieser Wert erreicht NIE einen echten
         * Posteingang, weil in diesem Zustand ausschliesslich [NoOpMailTransport] laeuft (loggt
         * Betreff, sendet nichts). Sobald SMTP konfiguriert ist, ist LAPIS_SMTP_FROM_NAME Pflicht.
         */
        fun notConfigured(): MailBranding = MailBranding(fromDisplayName = "Lapis Cloud", replyTo = null)
    }
}
