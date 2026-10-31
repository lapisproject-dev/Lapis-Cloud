package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import network.lapis.cloud.shared.domain.PartnerServerDto

/** The four states of a partner row; the [symbol] is shown WITH the text (never colour alone) and is hidden from screen readers. */
internal enum class PartnerStatusKind(
    val symbol: String,
) {
    FETCHED("●"),
    WAITING("◐"),
    PAUSED("○"),
    BLOCKED("⊘"),
}

/** V1.9.244 -- the DOM-free decisions of the partner page. */
internal object PartnerServersLogic {
    /** Blocked wins over paused, paused over the fetch result; an error or a never-fetched partner is "waiting" (the back-off is running). */
    fun statusKind(partner: PartnerServerDto): PartnerStatusKind =
        when {
            partner.blocked -> PartnerStatusKind.BLOCKED
            !partner.active -> PartnerStatusKind.PAUSED
            partner.status == PartnerFetchStatus.OK -> PartnerStatusKind.FETCHED
            else -> PartnerStatusKind.WAITING
        }

    fun statusText(partner: PartnerServerDto): String =
        when (statusKind(partner)) {
            PartnerStatusKind.BLOCKED -> gettext("Gesperrt")
            PartnerStatusKind.PAUSED -> gettext("Pausiert")
            PartnerStatusKind.FETCHED -> gettext("Abgerufen")
            PartnerStatusKind.WAITING ->
                partner.nextFetchAt?.let { gettext("Wartet bis %1", formatSystemTime(it)) } ?: gettext("Wartet auf den ersten Abruf")
        }

    /** The note under a blocked row. */
    fun blockedNote(): String = gettext("Gesperrt – wird nicht abgerufen und nicht angezeigt")

    /** The name a row shows first: the admin's own label, else the name the partner announces, else the host. Foreign text: set via `untrusted*`. */
    fun title(partner: PartnerServerDto): String = partner.displayName ?: partner.remoteName ?: partner.host

    /** `true` when the host line adds information to the title. */
    fun showsHostLine(partner: PartnerServerDto): Boolean = title(partner) != partner.host

    /** Whether the "Prüfen und speichern" button may be pressed: an address was typed. */
    fun canProbe(address: String): Boolean = address.isNotBlank()

    /** The blocklist details element is closed while there is nothing in it. */
    fun blocklistOpen(count: Int): Boolean = count > 0
}
