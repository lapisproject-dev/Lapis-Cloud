package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.AuctionOfferNoticeDto
import network.lapis.cloud.shared.domain.AuctionOfferNoticeKind
import network.lapis.cloud.shared.rpc.AuctionOfferNoticeMismatchException

/**
 * V1.9.323 -- the versioned, hashed notices a person is shown (and echoes back) when he makes a price proposal or a counter-offer. Same
 * mechanism as [AuctionFiatSellerDisclaimer]: SHA-256 over the UTF-8 bytes of `"$VERSION\n$TEXT"` ([FiatDisclaimerHashing]), a fresh `MessageDigest`
 * per call, a constant-time comparison, a malformed hash is a non-match and never an exception.
 *
 * **Why the text lives here and not in gettext.** The hash is meant to prove WHICH wording the person saw. A translated catalogue entry can be
 * edited without notice, a client could translate it differently from what the server hashes, and a sanitizer would change the bytes. So the
 * client fetches the text from [AuctionService.getAuctionOfferNotice] and shows exactly that string. Two languages exist ("de", "en"); any other
 * UI language gets the English text (the person then sees English and `lang` = `en` is what is stored).
 *
 * **Changing a text means a NEW [VERSION]**, never an in-place edit: the stored hashes of earlier offers must keep proving what was shown then.
 * `AuctionOfferNoticeScenarios` pins the hash of every text of version 1 as a literal so an edit without a version change fails the build.
 * Texts are single-line NFC strings; do not reformat them (no `trimIndent`).
 *
 * **Not legally reviewed.** The texts say that an offer binds nobody until it is accepted; that statement has not been checked by a lawyer.
 */
internal object AuctionOfferNotice {
    const val VERSION: String = "2026-11-24.v1"

    /** The languages a notice exists in. Everything else resolves to [FALLBACK_LANG]. */
    val LANGS: Set<String> = setOf("de", "en")
    const val FALLBACK_LANG: String = "en"

    /** Upper bounds of the echoed fields: longer input is a mismatch before any hashing or database work. */
    const val MAX_VERSION_LENGTH: Int = 32
    const val MAX_LANG_LENGTH: Int = 5
    const val SHA256_HEX_LENGTH: Int = 64

    private val TEXTS: Map<Pair<AuctionOfferNoticeKind, String>, String> =
        mapOf(
            (AuctionOfferNoticeKind.BUYER_OFFER to "de") to
                "Ein Preisvorschlag bindet Sie erst, wenn der Verkäufer ihn annimmt. Er gilt höchstens bis zum Auktionsende. " +
                "Ihre Gebote und der Sofortkauf bleiben davon unberührt.",
            (AuctionOfferNoticeKind.BUYER_OFFER to "en") to
                "A price suggestion only binds you once the seller accepts it. It is valid until the end of the auction at the latest. " +
                "Your bids and the buy-now option are not affected.",
            (AuctionOfferNoticeKind.SELLER_COUNTER to "de") to
                "Ein Gegenvorschlag bindet Sie erst, wenn der Käufer ihn annimmt. Es ist nur ein Gegenvorschlag möglich.",
            (AuctionOfferNoticeKind.SELLER_COUNTER to "en") to
                "A counter-offer only binds you once the buyer accepts it. Only one counter-offer is possible.",
        )

    private val HASHES: Map<Pair<AuctionOfferNoticeKind, String>, String> =
        TEXTS.mapValues { (_, text) -> FiatDisclaimerHashing.sha256Hex(version = VERSION, text = text) }

    /** "de" or "en" for a requested UI language; the requested value may be anything (it is only compared, never stored as given). */
    fun resolveLang(requested: String): String = if (requested in LANGS) requested else FALLBACK_LANG

    fun text(
        kind: AuctionOfferNoticeKind,
        lang: String,
    ): String? = TEXTS[kind to lang]

    fun sha256(
        kind: AuctionOfferNoticeKind,
        lang: String,
    ): String? = HASHES[kind to lang]

    fun dto(
        kind: AuctionOfferNoticeKind,
        requestedLang: String,
    ): AuctionOfferNoticeDto {
        val lang = resolveLang(requestedLang)
        return AuctionOfferNoticeDto(
            kind = kind,
            version = VERSION,
            lang = lang,
            text = TEXTS.getValue(kind to lang),
            sha256 = HASHES.getValue(kind to lang),
        )
    }

    /**
     * `true` only if [version], [lang] and [sha256] are exactly those of the current notice of [kind]. Cheap checks first (length, whitelist,
     * lowercase hex); the hash itself is compared in constant time. Never throws.
     */
    fun matches(
        kind: AuctionOfferNoticeKind,
        version: String,
        lang: String,
        sha256: String,
    ): Boolean {
        if (version.length > MAX_VERSION_LENGTH || lang.length > MAX_LANG_LENGTH || sha256.length != SHA256_HEX_LENGTH) return false
        if (lang !in LANGS) return false
        if (!sha256.all { it in '0'..'9' || it in 'a'..'f' }) return false
        val expected = HASHES[kind to lang] ?: return false
        return FiatDisclaimerHashing.matches(expectedVersion = VERSION, expectedSha256 = expected, version = version, sha256 = sha256)
    }

    /** [matches] or [AuctionOfferNoticeMismatchException]. Runs before any counter, lock or insert. */
    fun require(
        kind: AuctionOfferNoticeKind,
        version: String,
        lang: String,
        sha256: String,
    ) {
        if (!matches(kind = kind, version = version, lang = lang, sha256 = sha256)) throw AuctionOfferNoticeMismatchException()
    }
}
