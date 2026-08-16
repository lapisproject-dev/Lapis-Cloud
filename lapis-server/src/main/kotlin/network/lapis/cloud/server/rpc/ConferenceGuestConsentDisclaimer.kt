package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * The versioned, hashed DSGVO consent disclosure a non-member conference participant --
 * `MemberStatus.GUEST` (federated, has a home server) OR `MemberStatus.FRIEND` (self-registered,
 * NO home server, unverified display name) -- must be shown -- and echo back verbatim (via
 * [matches]) -- before [ConferenceService.joinRoom] will admit them to a room whose
 * `allowFederationGuests` opt-in is set. [TEXT] is deliberately ONE shared wording honest for BOTH
 * cases (see [DETAIL] KDoc below) rather than two caller-conditional variants -- simpler, and the
 * server already exposes [network.lapis.cloud.shared.domain.ConferenceGuestJoinInfoDto
 * .callerIsGuest]/`.callerIsNonMember` if a future wave wants to branch the client rendering
 * instead. See [IConferenceService]
 * [network.lapis.cloud.shared.rpc.IConferenceService] KDoc "Federated guest join" for the full
 * authorization matrix this backs, and [network.lapis.cloud.shared.domain.ConferenceGuestJoinInfoDto]
 * KDoc for why this disclaimer's version/sha256 must travel as DATA (a pre-join read call), never as
 * a thrown exception message -- kilua-rpc never transmits exception messages to the browser.
 *
 * Same immutability/versioning contract [AuctionComplianceDisclaimer] establishes: [VERSION]/[TEXT]/
 * [SHA256] are `const`/`val` (immutable at runtime) -- a future wording change requires a NEW
 * [VERSION] string, never an in-place edit of [TEXT] under the same version (that would silently
 * invalidate the audit trail's claim that a given guest saw a given version's exact wording).
 *
 * ## Two-layer disclosure (Wave 5 design review D7) -- structurally drift-proof
 *
 * The client renders TWO layers: layer 1 is [HEADLINE] + [KEY_POINTS] (exactly two short, load-
 * bearing statements, rendered above the fold, unscrollable-past), layer 2 is the full [TEXT] in a
 * scroll box beneath it, always present, never hidden behind a "Details anzeigen" link. [TEXT] is
 * COMPOSED from [HEADLINE]/[KEY_POINTS]/[DETAIL] (`HEADLINE + "\n\n" + KEY_POINTS.joined + "\n\n" +
 * DETAIL`) so drift between the short client-rendered summary and the long hashed text is
 * structurally impossible -- [SHA256] stays `sha256Hex("$VERSION\n$TEXT")`, byte-for-byte the same
 * shape [AuctionComplianceDisclaimer]/[MembershipAgreementDisclaimer] already establish, never a
 * hash over anything else. A unit test asserts each [KEY_POINTS] entry appears verbatim in [TEXT].
 *
 * ## The organization name is NOT part of the hashed text (design review D15, endorsed)
 *
 * [TEXT] stays a compile-time constant so [SHA256] remains a deployment-independent, test-pinnable
 * `val`. The concrete `organizationName` (from `organization_settings.name`) travels as a SEPARATE,
 * unhashed field on [network.lapis.cloud.shared.domain.ConferenceGuestJoinInfoDto], rendered by the
 * client directly ABOVE [HEADLINE] (layer 1's own org line) -- see that DTO's own KDoc. Interpolating
 * the org name into [TEXT] and hashing at call time was considered and rejected: it would make
 * [SHA256] a function rather than a `val`, break the "a wording change requires a new VERSION" audit
 * invariant, and silently invalidate every historical acknowledgment on an org rename.
 */
object ConferenceGuestConsentDisclaimer {
    /**
     * V0.11.0 FRIEND wave -- bumped from `"2026-08-10.v1"`. The wording change below (HEADLINE no
     * longer claims "eines anderen Servers", DETAIL's home-server sentence is no longer a blanket
     * claim, and a new FRIEND-unverified-name sentence was added) is a real, hash-changing edit --
     * see class KDoc "immutability/versioning contract".
     */
    const val VERSION: String = "2026-08-16.v1"

    /**
     * Layer 1, line 1 -- see class KDoc "Two-layer disclosure". Deliberately does NOT say "eines
     * anderen Servers" (unlike the pre-V0.11.0 wording) -- that was true for a federated GUEST but
     * false for a FRIEND, who has no other server at all. [DETAIL] carries the case-specific detail.
     */
    val HEADLINE: String = "Sie treten dieser Besprechung als Gast bei."

    /**
     * Layer 1, exactly two entries -- the two facts this whole wave exists to convey. See class
     * KDoc "Two-layer disclosure". A unit test pins `KEY_POINTS.size == 2`.
     */
    val KEY_POINTS: List<String> =
        listOf(
            """
            Aufzeichnung und Livestream sind möglich. Die Moderation kann diese Besprechung
            jederzeit aufzeichnen oder live auf externe Plattformen übertragen. Ihr Bild, Ihr Ton
            und eine geteilte Bildschirmfreigabe werden dann mit erfasst. Beginnt eine Aufzeichnung
            oder Übertragung, wird das allen Anwesenden sichtbar angezeigt -- Sie können die
            Besprechung dann jederzeit verlassen.
            """.trimIndent(),
            """
            Es gilt die Datenschutzerklärung dieses Servers. Für diese Besprechung gilt
            ausschließlich die Datenschutzerklärung der oben genannten Organisation. Die
            Datenschutzerklärung Ihres eigenen Heimservers findet hier keine Anwendung. Auskunft,
            Berichtigung und Löschung richten Sie deshalb an diese Organisation.
            """.trimIndent(),
        )

    /**
     * Layer 2 remainder -- see class KDoc "Two-layer disclosure". The "Sichtbare Gast-Markierung"
     * paragraph (V0.11.0 FRIEND wave) is written to stay honest for BOTH admission paths this
     * disclaimer now covers, without needing a caller-conditional variant: a federated
     * `MemberStatus.GUEST` has a home server, whose address is disclosed; a self-registered
     * `MemberStatus.FRIEND` has none -- but unlike a GUEST (identity asserted by a trusted OIDC
     * home server), a FRIEND's display name was typed in at self-registration and verified by
     * nobody, which is disclosed instead. Pinned by
     * `ConferenceGuestConsentDisclaimerTest`'s "does not overclaim a home server for every guest"
     * / "discloses FRIEND's display name is unverified" tests.
     */
    private val DETAIL: String =
        """
        Ihre Kontrolle: Sie können Kamera und Mikrofon jederzeit ausschalten und die Besprechung
        jederzeit verlassen. Ohne Kamera wird lediglich Ihr Anzeigename dargestellt.

        Sichtbare Gast-Markierung: Ihr Gaststatus ist für alle übrigen Teilnehmenden dieser
        Besprechung sichtbar. Treten Sie über einen anderen (föderierten) Server bei, ist zusätzlich
        dessen Adresse -- Ihr Heimserver -- für alle übrigen Teilnehmenden sichtbar. Treten Sie
        stattdessen mit einem selbst registrierten Freund-Konto bei, entfällt diese
        Heimserver-Angabe -- dafür ist Ihr Anzeigename in diesem Fall NICHT verifiziert: er wurde bei
        der Registrierung frei gewählt und von niemandem geprüft.

        Dieser Hinweis stellt keine Rechtsberatung dar. Ein reales Deployment sollte diesen Text
        unter einer neuen Version durch die eigene, rechtlich geprüfte Fassung ersetzen.
        """.trimIndent()

    /** See class KDoc "Two-layer disclosure" -- composed, never hand-duplicated. */
    val TEXT: String = HEADLINE + "\n\n" + KEY_POINTS.joinToString("\n\n") + "\n\n" + DETAIL

    /**
     * `SHA-256` over `"$VERSION\n$TEXT"` -- a fresh [MessageDigest] instance PER CALL (thread-safe,
     * see the codebase's standing security checklist "Kryptografie: MessageDigest neue Instanz pro
     * Aufruf"), computed once at class-init time since [VERSION]/[TEXT] are themselves immutable.
     */
    val SHA256: String = sha256Hex("$VERSION\n$TEXT")

    /**
     * `true` iff [version] equals [VERSION] AND [sha256] equals [SHA256] (case-insensitive hex
     * comparison, constant-time via [MessageDigest.isEqual] so a malformed/tampered hash cannot be
     * distinguished from a correct-length-wrong-value one by timing). A malformed (non-hex, wrong
     * length) [sha256] is treated as a non-match, never thrown.
     */
    fun matches(
        version: String,
        sha256: String,
    ): Boolean {
        if (version != VERSION) return false
        val provided = runCatching { hexToBytes(sha256) }.getOrNull() ?: return false
        val expected = hexToBytes(SHA256)
        return MessageDigest.isEqual(provided, expected)
    }

    private fun sha256Hex(input: String): String {
        val digestBytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digestBytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(hex.length / 2) { i ->
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "invalid hex character in '$hex'" }
            ((high shl 4) + low).toByte()
        }
    }
}
