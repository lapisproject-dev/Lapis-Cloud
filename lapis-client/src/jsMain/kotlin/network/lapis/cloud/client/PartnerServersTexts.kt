package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.PartnerAddressRejectionCode
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import network.lapis.cloud.shared.domain.PartnerProbeResultDto

/**
 * V1.9.244 -- the fixed sentences of the partner page. The server sends enum CODES only; every text a person reads is chosen here, so the page
 * never shows a server-authored string, an address or a resolver detail.
 */
internal object PartnerServersTexts {
    fun rejection(code: PartnerAddressRejectionCode): String =
        when (code) {
            PartnerAddressRejectionCode.NOT_HTTPS -> gettext("Nur https-Adressen sind erlaubt.")
            PartnerAddressRejectionCode.MALFORMED -> gettext("Das ist keine gültige Serveradresse.")
            PartnerAddressRejectionCode.PORT_NOT_ALLOWED,
            PartnerAddressRejectionCode.USERINFO,
            -> gettext("Bitte nur eine Adresse ohne Port und ohne Zugangsdaten angeben.")
            PartnerAddressRejectionCode.IP_LITERAL,
            PartnerAddressRejectionCode.LOCAL_NAME,
            -> gettext("Diese Adresse zeigt auf ein internes Netz oder ist eine IP-Adresse. Bitte den öffentlichen Servernamen angeben.")
            PartnerAddressRejectionCode.NO_DOT -> gettext("Bitte den vollständigen Servernamen angeben, zum Beispiel partner.beispiel.de.")
            PartnerAddressRejectionCode.OWN_HOST -> gettext("Das ist dieser Server selbst.")
            PartnerAddressRejectionCode.TOO_LONG -> gettext("Die Adresse ist zu lang.")
        }

    fun fetchError(code: PartnerFetchErrorCode): String =
        when (code) {
            PartnerFetchErrorCode.BLOCKED -> gettext("Dieser Host steht auf der Sperrliste.")
            PartnerFetchErrorCode.NOT_PUBLIC_ADDRESS ->
                gettext("Der Server ist nicht öffentlich erreichbar (die Adresse zeigt auf ein internes Netz).")
            PartnerFetchErrorCode.TIMEOUT -> gettext("Der Server hat nicht innerhalb von 10 Sekunden geantwortet.")
            PartnerFetchErrorCode.REDIRECT ->
                gettext(
                    "Der Server leitet weiter. Weiterleitungen werden aus Sicherheitsgründen nicht verfolgt.",
                )
            PartnerFetchErrorCode.TOO_LARGE -> gettext("Die Antwort des Servers ist größer als 512 KiB.")
            PartnerFetchErrorCode.WRONG_CONTENT_TYPE,
            PartnerFetchErrorCode.HTTP_STATUS,
            PartnerFetchErrorCode.INVALID_FEED,
            -> gettext("Der Server liefert keinen gültigen Lapis-Feed.")
            PartnerFetchErrorCode.OWN_SERVER_ID -> gettext("Das ist dieser Server selbst.")
            PartnerFetchErrorCode.SERVER_ID_CHANGED -> gettext("Der Server meldet eine andere Kennung als bisher.")
            PartnerFetchErrorCode.SERVER_ID_TAKEN -> gettext("Diese Serverkennung gehört bereits einem anderen Partner.")
            PartnerFetchErrorCode.UNREACHABLE -> gettext("Der Server ist nicht erreichbar.")
            PartnerFetchErrorCode.TLS -> gettext("Das Sicherheitszertifikat des Servers ist nicht gültig.")
            PartnerFetchErrorCode.DUPLICATE -> gettext("Dieser Server ist schon eingetragen.")
            PartnerFetchErrorCode.SERVER_ID_CONFLICT -> gettext("Kennung bereits vergeben")
        }

    /** The sentence under the form after a failed "Prüfen und speichern"; the address wins over the fetch error. */
    fun probeFailure(result: PartnerProbeResultDto): String =
        result.addressRejection?.let { rejection(it) }
            ?: result.error?.let { fetchError(it) }
            ?: gettext("Der Server liefert keinen gültigen Lapis-Feed.")

    /** The sentence after a successful save. [host] is the server-normalised host name (lower-case ASCII), [count] the number of events found. */
    fun saved(
        host: String,
        count: Int,
    ): String = gettext("Gespeichert als %1 – Veranstaltungen gefunden: %2.", host, count)
}
