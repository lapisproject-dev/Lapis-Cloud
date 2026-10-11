package network.lapis.cloud.server.db

import org.jetbrains.exposed.v1.core.LikePattern

/**
 * `%`/`_` in raw search text are LIKE metacharacters -- without escaping, a single `%` turns every search
 * into a full-table match on all rows (not a SQL-injection vector, Exposed parameterizes the value, but a
 * correctness/DoS sleeve at scale). Uses Exposed's own [LikePattern.ofLiteral] (dialect-aware escaping)
 * rather than a hand-rolled replace chain, then wraps the escaped literal in unescaped `%` wildcards for a
 * "contains" match. V1.9.101: the single shared copy (formerly private duplicates in `MemberService` and
 * `MemberFamilyService`), now also used by the committee-candidate search.
 */
internal fun containsLikePattern(term: String): LikePattern {
    val escaped = LikePattern.ofLiteral(term)
    return LikePattern("%${escaped.pattern}%", escaped.escapeChar)
}
