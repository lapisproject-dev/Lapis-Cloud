package network.lapis.cloud.server.partner

import network.lapis.cloud.server.federation.FederationConfig
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.text.Collator
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One selectable home server on the guest sign-in page. [name] is ForeignText-cleaned (<=100 cp) or null; [host] canonical punycode. */
internal data class PartnerLoginChoice(
    val host: String,
    val name: String?,
) {
    /** "Name (host)" with the name ellipsized to 60 code points; the host is NEVER shortened. Host alone when there is no name. */
    val label: String
        get() =
            if (name == null) {
                host
            } else {
                "${ForeignText.ellipsize(text = name, maxChars = PartnerLoginChoices.MAX_LABEL_NAME_CODEPOINTS)} ($host)"
            }
}

internal data class PartnerLoginFilterResult(
    /** At most [PartnerLoginChoices.MAX_CHOICES]. */
    val shown: List<PartnerLoginChoice>,
    /** Counted BEFORE the cap. */
    val totalMatches: Int,
    val capped: Boolean,
    /** The normalised q, or null. */
    val query: String?,
)

/**
 * V1.9.258 -- the READ side of the partner list for the guest sign-in page: which partner servers a visitor may pick as home server.
 * Everything that decides visibility is [PartnerEventDisplay.isVisible] (the same rules as the public event pages). The page offers
 * nothing the free-text field would not accept: every host passed [PartnerHost.normalizeHostOnly]. Cached for [CACHE_TTL] per
 * [PartnerDisplayEpoch]; a POSTed choice is additionally checked against a FRESH `listHidden` read.
 */
internal object PartnerLoginChoices {
    const val MAX_CHOICES = 200
    const val MAX_QUERY_CODEPOINTS = 100
    const val MAX_LABEL_NAME_CODEPOINTS = 60
    private const val MAX_NAME_CODEPOINTS = 100
    private const val MAX_RAW_QUERY_CHARS = 400
    private const val MAX_RAW_CHOICE_CHARS = 253
    val CACHE_TTL: Duration = 30.seconds

    private class Memo(
        val epoch: Long,
        val expiresAt: Instant,
        val list: List<PartnerLoginChoice>,
    )

    private val memo = AtomicReference<Memo?>(null)

    /** Pure. Full, deduped, sorted list (NOT capped -- the cap is applied by [filter] so the count is honest). */
    fun build(
        direct: List<PartnerRow>,
        discovered: List<DiscoveredLoginRow>,
        settings: RecursionSettings,
        patterns: List<String>,
        ownHost: String?,
        listHidden: Boolean,
    ): List<PartnerLoginChoice> {
        if (listHidden) return emptyList()
        val byHost = LinkedHashMap<String, PartnerLoginChoice>()
        val directHosts = direct.mapTo(HashSet()) { it.host }
        for (partner in direct) {
            if (!partner.active || PartnerBlocklistPattern.isBlocked(host = partner.host, patterns = patterns)) continue
            if (!validHost(host = partner.host, ownHost = ownHost)) continue
            val name = ForeignText.clean(raw = partner.displayName ?: partner.remoteName, maxChars = MAX_NAME_CODEPOINTS)
            byHost.putIfAbsent(partner.host, PartnerLoginChoice(host = partner.host, name = name))
        }
        val rootsById = direct.associateBy { it.id }
        val bestHops = HashMap<String, Int>()
        for (row in discovered) {
            // The direct partner wins -- also when it is inactive or blocked: the admin's decision stands.
            if (row.host in directHosts) continue
            val root = rootsById[row.rootPartnerId] ?: continue
            val visible =
                PartnerEventDisplay.isVisible(
                    hops = row.hops,
                    originHost = row.host,
                    via = row.via,
                    rootActive = root.active,
                    settings = settings,
                    patterns = patterns,
                ) &&
                    !PartnerBlocklistPattern.isBlocked(host = root.host, patterns = patterns)
            if (!visible || !validHost(host = row.host, ownHost = ownHost)) continue
            val known = bestHops[row.host]
            if (known != null && known <= row.hops) continue
            bestHops[row.host] = row.hops
            byHost[row.host] =
                PartnerLoginChoice(host = row.host, name = ForeignText.clean(raw = row.remoteName, maxChars = MAX_NAME_CODEPOINTS))
        }
        // Collator is not thread-safe: one per call, never shared.
        val collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.SECONDARY }
        return byHost.values.sortedWith { a, b ->
            val byName = collator.compare(a.name ?: a.host, b.name ?: b.host)
            if (byName != 0) byName else a.host.compareTo(b.host)
        }
    }

    /** Stored data is a cache of foreign data: re-validate each host with the very rules the free-text path applies. */
    private fun validHost(
        host: String,
        ownHost: String?,
    ): Boolean = host != ownHost && PartnerHost.normalizeHostOnly(host) == host

    /** Needs an open transaction. */
    fun loadInTx(ownHost: String?): List<PartnerLoginChoice> =
        build(
            direct = PartnerServerStore.listPartners(),
            discovered = PartnerDiscoveryStore.listForLoginChoices(),
            settings = PartnerDiscoveryStore.recursionSettings(),
            patterns = PartnerServerStore.blockPatterns(),
            ownHost = ownHost,
            listHidden = PartnerServerStore.isPartnerListHidden(),
        )

    /** Memo: key = [PartnerDisplayEpoch.current], TTL [CACHE_TTL]; opens its own transaction on a miss. */
    fun current(now: Instant = Clock.System.now()): List<PartnerLoginChoice> {
        val epoch = PartnerDisplayEpoch.current()
        memo.get()?.let { if (it.epoch == epoch && it.expiresAt > now) return it.list }
        val ownHost = PartnerHost.ownHostOf(FederationConfig.publicBaseUrl)
        val list = transaction { loadInTx(ownHost) }
        // Two concurrent rebuilds are harmless: the last one wins.
        memo.set(Memo(epoch = epoch, expiresAt = now + CACHE_TTL, list = list))
        return list
    }

    /** Pure. q trimmed, raw cut to 400 chars, ForeignText.clean(raw, 100); blank -> null. */
    fun normalizeQuery(raw: String?): String? {
        if (raw == null) return null
        return ForeignText.clean(raw = raw.trim().take(MAX_RAW_QUERY_CHARS), maxChars = MAX_QUERY_CODEPOINTS)
    }

    /** Pure. Substring match, contains(ignoreCase = true) on name and host; no Regex, no SQL. */
    fun filter(
        all: List<PartnerLoginChoice>,
        query: String?,
    ): PartnerLoginFilterResult {
        val matches =
            if (query == null) {
                all
            } else {
                all.filter { it.host.contains(query, ignoreCase = true) || it.name?.contains(query, ignoreCase = true) == true }
            }
        return PartnerLoginFilterResult(
            shown = matches.take(MAX_CHOICES),
            totalMatches = matches.size,
            capped = matches.size > MAX_CHOICES,
            query = query,
        )
    }

    /** Pure. Canonical form of a posted/queried host: trim + lowercase(Locale.ROOT); null if blank, >253 chars or any control/whitespace char. */
    fun canonicalChoiceOrNull(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_RAW_CHOICE_CHARS) return null
        if (trimmed.any { it.isISOControl() || it.isWhitespace() }) return null
        return trimmed.lowercase(Locale.ROOT)
    }

    /** Server-side acceptance of a POSTed choice: canonical form, EXACT membership in [current], plus a FRESH hidden-flag read. */
    fun acceptChoice(
        raw: String?,
        now: Instant = Clock.System.now(),
    ): String? {
        val host = canonicalChoiceOrNull(raw) ?: return null
        if (current(now).none { it.host == host }) return null
        if (transaction { PartnerServerStore.isPartnerListHidden() }) return null
        return host
    }

    internal fun resetMemoForTests() {
        memo.set(null)
    }
}
