package network.lapis.cloud.server.lupabb

import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus

/**
 * V1.9.226 -- the mapping from the twelve LupaBB member status labels to the Lapis member status. The table is documented in
 * `docs/features/lupabb-sync.adoc` and pinned by `LupaBbStatusMappingTest` (all twelve labels plus an unknown one).
 *
 * | LupaBB | Lapis | voting right (V1.9.298) |
 * |---|---|---|
 * | Mitglied | ACTIVE | yes |
 * | Neumitglied | ACTIVE | **no** (owner decision 2026-11-09: a "Neumitglied" is a member without voting right) |
 * | in Aufnahme | APPLICATION | not touched |
 * | Foerderer | FRIEND | not touched |
 * | Spender, Nur Zahler | DONOR | not touched |
 * | verstorben | DECEASED | not touched |
 * | Widerrufen, Gekuendigt, Ausgeschlossen | WITHDRAWN (Lapis has no EXCLUDED status) | not touched |
 * | Ablehnung | REJECTED | not touched |
 * | Storniert, anything else | skipped | - |
 *
 * "Förderer" -> FRIEND and "Spender" -> DONOR are deliberately unchanged in V1.9.298 (open point D, see `docs/features/lupabb-sync.adoc`).
 */
internal sealed interface LupaBbStatusMapping {
    data class Mapped(
        val status: MemberStatus,
        /** V1.9.298 -- the voting right the label implies; `null` = the sync does not touch the voting right for this label. */
        val votingRight: Boolean? = null,
    ) : LupaBbStatusMapping

    data class Skip(
        val reason: LupaBbRowReasonCode,
    ) : LupaBbStatusMapping

    companion object {
        @Suppress("CyclomaticComplexMethod")
        fun map(label: String): LupaBbStatusMapping =
            when (label.trim()) {
                "Mitglied" -> Mapped(status = MemberStatus.ACTIVE, votingRight = true)
                "Neumitglied" -> Mapped(status = MemberStatus.ACTIVE, votingRight = false)
                "in Aufnahme" -> Mapped(status = MemberStatus.APPLICATION)
                "Förderer" -> Mapped(status = MemberStatus.FRIEND)
                "Spender", "Nur Zahler" -> Mapped(status = MemberStatus.DONOR)
                "verstorben" -> Mapped(status = MemberStatus.DECEASED)
                "Widerrufen", "Gek\u00fcndigt", "Ausgeschlossen" -> Mapped(status = MemberStatus.WITHDRAWN)
                "Ablehnung" -> Mapped(status = MemberStatus.REJECTED)
                "Storniert" -> Skip(LupaBbRowReasonCode.CANCELLED_STATUS)
                else -> Skip(LupaBbRowReasonCode.UNKNOWN_STATUS)
            }
    }
}
