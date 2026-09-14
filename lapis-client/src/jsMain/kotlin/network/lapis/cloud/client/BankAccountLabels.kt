package network.lapis.cloud.client

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- pure decision logic extracted out of
 * [BankAccountsScreen]'s `FinTsStatus.ACTIVE` branch, same "pure label function, tested without
 * KVision/a DOM" idiom [BankStatementLabels.kt] already establishes (see [BankStatementLabelsTest]
 * for the sibling test file). Review fix (MEDIUM, test coverage): the ACTIVE-branch icon/text
 * selection (which icon to show, which of the three text variants) previously lived entirely
 * inline inside the KVision-building code and had no test at all -- extracting exactly the
 * `Boolean`/`Boolean -> ...` decision (never the actual translated strings, which stay in
 * [BankAccountsScreen] itself) makes it testable with plain `kotlin.test` assertions.
 */
internal enum class FinTsActiveStatusVariant {
    /** A successful fetch has happened at least once -- shown regardless of any later transient error. */
    LAST_SUCCESS,

    /** Never successfully fetched, but the poller has recorded an error code. */
    ERROR_NO_SUCCESS,

    /** Never successfully fetched, no error recorded either (freshly activated, first tick pending). */
    NO_ERROR_NO_SUCCESS,
}

/** Mirrors [BankAccountsScreen]'s own `when` branching -- `hasLastSuccess` wins over `hasPersistentError`. */
internal fun finTsActiveStatusVariant(
    hasLastSuccess: Boolean,
    hasPersistentError: Boolean,
): FinTsActiveStatusVariant =
    when {
        hasLastSuccess -> FinTsActiveStatusVariant.LAST_SUCCESS
        hasPersistentError -> FinTsActiveStatusVariant.ERROR_NO_SUCCESS
        else -> FinTsActiveStatusVariant.NO_ERROR_NO_SUCCESS
    }

/**
 * The warning triangle fires on ANY persistent error, independent of [FinTsActiveStatusVariant] --
 * see [BankAccountsScreen] KDoc "Review fix (MEDIUM)" for why an ACTIVE account that has
 * successfully fetched before but is now failing must NOT show the same green check as a healthy
 * one.
 */
internal fun finTsActiveStatusIconClass(hasPersistentError: Boolean): String =
    if (hasPersistentError) "fas fa-triangle-exclamation text-warning" else "fas fa-check text-success"
