package network.lapis.cloud.server.payment.fints

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf". Configuration for [FinTsPoller] and both
 * [Hbci4jFinTsClient] operations. Pure string parsing, no I/O -- **deliberately no fail-fast**,
 * same posture `network.lapis.cloud.server.payment.dunning.DunningConfig`'s own KDoc documents: the
 * feature is gated by a DB column (`bank_account.fints_status`), not by an env var alone, so [load]
 * cannot know at startup whether FinTS is actually configured for any account.
 */
class FinTsConfig private constructor(
    /** `LAPIS_FINTS_POLLER_ENABLED`, default `false`. Allows the poller to be enabled on exactly ONE instance. */
    val pollerEnabled: Boolean,
    /** `LAPIS_FINTS_POLL_INTERVAL_SECONDS`, default 21600 (6h). `coerceAtLeast(900)` -- a bank is not a cronjob. */
    val pollIntervalSeconds: Long,
    /** `LAPIS_FINTS_FETCH_WINDOW_DAYS`, default 14, `coerceIn(1, 90)`. */
    val fetchWindowDays: Int,
    /** `LAPIS_FINTS_MAX_ACCOUNTS_PER_TICK`, default 20, `coerceIn(1, 100)` -- cost-/DoS-deckel per poll pass. */
    val maxAccountsPerTick: Int,
    /** `LAPIS_FINTS_DIALOG_TIMEOUT_SECONDS`, default 60, `coerceIn(10, 300)` -- hard wall-clock guard around every hbci4j dialog. */
    val dialogTimeoutSeconds: Long,
    /**
     * `LAPIS_FINTS_PASSPORT_DIR`, default `<user.dir>/fints-passports`. **Must lie OUTSIDE
     * `documentStorageRoot`** -- `network.lapis.cloud.server.backup.OrganizationExportService`
     * copies that entire tree verbatim into an UNENCRYPTED backup tarball; a FinTS userid/passport
     * blob in that tree would be a plaintext-adjacent hole in an otherwise-sealed system. See
     * `docs/architecture/bank-account.adoc` "Passport-Datei" for the full posture and
     * `FinTsPassportFileTest` for the automated guard.
     */
    val passportDir: String,
) {
    companion object {
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 21_600L
        private const val MIN_POLL_INTERVAL_SECONDS = 900L

        private const val DEFAULT_FETCH_WINDOW_DAYS = 14
        private const val MIN_FETCH_WINDOW_DAYS = 1
        private const val MAX_FETCH_WINDOW_DAYS = 90

        private const val DEFAULT_MAX_ACCOUNTS_PER_TICK = 20
        private const val MIN_MAX_ACCOUNTS_PER_TICK = 1
        private const val MAX_MAX_ACCOUNTS_PER_TICK = 100

        private const val DEFAULT_DIALOG_TIMEOUT_SECONDS = 60L
        private const val MIN_DIALOG_TIMEOUT_SECONDS = 10L
        private const val MAX_DIALOG_TIMEOUT_SECONDS = 300L

        private const val DEFAULT_PASSPORT_DIR_SUFFIX = "fints-passports"

        fun load(env: (String) -> String? = System::getenv): FinTsConfig {
            val pollerEnabled = env("LAPIS_FINTS_POLLER_ENABLED")?.trim().equals("true", ignoreCase = true)
            val pollIntervalSeconds =
                (env("LAPIS_FINTS_POLL_INTERVAL_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_SECONDS)
                    .coerceAtLeast(MIN_POLL_INTERVAL_SECONDS)
            val fetchWindowDays =
                (env("LAPIS_FINTS_FETCH_WINDOW_DAYS")?.trim()?.toIntOrNull() ?: DEFAULT_FETCH_WINDOW_DAYS)
                    .coerceIn(MIN_FETCH_WINDOW_DAYS, MAX_FETCH_WINDOW_DAYS)
            val maxAccountsPerTick =
                (env("LAPIS_FINTS_MAX_ACCOUNTS_PER_TICK")?.trim()?.toIntOrNull() ?: DEFAULT_MAX_ACCOUNTS_PER_TICK)
                    .coerceIn(MIN_MAX_ACCOUNTS_PER_TICK, MAX_MAX_ACCOUNTS_PER_TICK)
            val dialogTimeoutSeconds =
                (env("LAPIS_FINTS_DIALOG_TIMEOUT_SECONDS")?.trim()?.toLongOrNull() ?: DEFAULT_DIALOG_TIMEOUT_SECONDS)
                    .coerceIn(MIN_DIALOG_TIMEOUT_SECONDS, MAX_DIALOG_TIMEOUT_SECONDS)
            val passportDir =
                env("LAPIS_FINTS_PASSPORT_DIR")?.trim()?.takeIf { it.isNotBlank() }
                    ?: (System.getProperty("user.dir") + java.io.File.separator + DEFAULT_PASSPORT_DIR_SUFFIX)

            return FinTsConfig(
                pollerEnabled = pollerEnabled,
                pollIntervalSeconds = pollIntervalSeconds,
                fetchWindowDays = fetchWindowDays,
                maxAccountsPerTick = maxAccountsPerTick,
                dialogTimeoutSeconds = dialogTimeoutSeconds,
                passportDir = passportDir,
            )
        }
    }
}
