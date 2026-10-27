package network.lapis.cloud.server.lupabb

import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus

/**
 * V1.9.226 -- the mapping from the twelve LupaBB member status labels to the Lapis member status. The table is documented in
 * `docs/features/lupabb-sync.adoc` and pinned by `LupaBbStatusMappingTest` (all twelve labels plus an unknown one).
 *
 * | LupaBB | Lapis |
 * |---|---|
 * | Mitglied, Neumitglied | ACTIVE |
 * | in Aufnahme | APPLICATION |
 * | Foerderer | FRIEND |
 * | Spender, Nur Zahler | DONOR |
 * | verstorben | DECEASED |
 * | Widerrufen, Gekuendigt, Ausgeschlossen | WITHDRAWN (Lapis has no EXCLUDED status) |
 * | Ablehnung | REJECTED |
 * | Storniert, anything else | skipped |
 */
internal sealed interface LupaBbStatusMapping {
    data class Mapped(
        val status: MemberStatus,
    ) : LupaBbStatusMapping

    data class Skip(
        val reason: LupaBbRowReasonCode,
    ) : LupaBbStatusMapping

    companion object {
        @Suppress("CyclomaticComplexMethod")
        fun map(label: String): LupaBbStatusMapping =
            when (label.trim()) {
                "Mitglied", "Neumitglied" -> Mapped(MemberStatus.ACTIVE)
                "in Aufnahme" -> Mapped(MemberStatus.APPLICATION)
                "Förderer" -> Mapped(MemberStatus.FRIEND)
                "Spender", "Nur Zahler" -> Mapped(MemberStatus.DONOR)
                "verstorben" -> Mapped(MemberStatus.DECEASED)
                "Widerrufen", "Gek\u00fcndigt", "Ausgeschlossen" -> Mapped(MemberStatus.WITHDRAWN)
                "Ablehnung" -> Mapped(MemberStatus.REJECTED)
                "Storniert" -> Skip(LupaBbRowReasonCode.CANCELLED_STATUS)
                else -> Skip(LupaBbRowReasonCode.UNKNOWN_STATUS)
            }
    }
}
