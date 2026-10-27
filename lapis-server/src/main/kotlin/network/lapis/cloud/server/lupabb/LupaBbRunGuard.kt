package network.lapis.cloud.server.lupabb

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.server.db.generated.LupaBbSyncConfigTable
import network.lapis.cloud.shared.rpc.LupaBbCredentialsMissingException
import network.lapis.cloud.shared.rpc.LupaBbSyncBusyException
import network.lapis.cloud.shared.rpc.LupaBbSyncRateLimitedException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

internal enum class LupaBbRunKind { FETCH, APPLY }

/**
 * V1.9.226 -- the compare-and-set guard against concurrent and too frequent runs, kept on the single `lupabb_sync_config` row (so it works across
 * several server processes). A claim stamps `running_since` and a random `run_claim`; it is released only by the holder of that claim (fencing:
 * a claim that went stale and was taken over cannot be released by its old owner). A claim older than [STALE_AFTER_MINUTES] counts as free.
 * A FETCH additionally needs the last fetch to be at least [MIN_FETCH_INTERVAL_SECONDS] ago.
 *
 * Each call is its own short transaction: the guard is never held inside the transaction of a run, and never together with HTTP.
 */
internal object LupaBbRunGuard {
    const val STALE_AFTER_MINUTES = 10L
    const val MIN_FETCH_INTERVAL_SECONDS = 60L

    fun staleBefore(now: LocalDateTime): LocalDateTime = now.toJavaLocalDateTime().minusMinutes(STALE_AFTER_MINUTES).toKotlinLocalDateTime()

    fun claim(
        kind: LupaBbRunKind,
        now: LocalDateTime,
    ): Uuid {
        val claim = Uuid.random()
        val stale = staleBefore(now)
        val fetchLimit = now.toJavaLocalDateTime().minusSeconds(MIN_FETCH_INTERVAL_SECONDS).toKotlinLocalDateTime()
        val updated =
            transaction {
                LupaBbSyncConfigTable.update({
                    var condition: Op<Boolean> =
                        (LupaBbSyncConfigTable.id eq LupaBbCredentialStore.CONFIG_ID) and
                            (LupaBbSyncConfigTable.runningSince.isNull() or (LupaBbSyncConfigTable.runningSince less stale))
                    if (kind == LupaBbRunKind.FETCH) {
                        condition = condition and
                            (
                                LupaBbSyncConfigTable.lastFetchStartedAt.isNull() or
                                    (LupaBbSyncConfigTable.lastFetchStartedAt less fetchLimit)
                            )
                    }
                    condition
                }) {
                    it[runningSince] = now
                    it[runClaim] = claim
                    if (kind == LupaBbRunKind.FETCH) it[lastFetchStartedAt] = now
                }
            }
        if (updated > 0) return claim
        // diagnose why: no row, a running claim, or a fetch too soon after the last one
        val row =
            transaction {
                LupaBbSyncConfigTable
                    .selectAll()
                    .where { LupaBbSyncConfigTable.id eq LupaBbCredentialStore.CONFIG_ID }
                    .singleOrNull()
            }
        if (row == null) throw LupaBbCredentialsMissingException()
        val running = row[LupaBbSyncConfigTable.runningSince]
        if (running != null && running >= stale) throw LupaBbSyncBusyException()
        throw LupaBbSyncRateLimitedException()
    }

    /** Releases [claim] if it is still the current one. Never throws into the caller's `finally`. */
    fun release(claim: Uuid) {
        try {
            transaction {
                LupaBbSyncConfigTable.update({
                    (LupaBbSyncConfigTable.id eq LupaBbCredentialStore.CONFIG_ID) and (LupaBbSyncConfigTable.runClaim eq claim)
                }) {
                    it[runningSince] = null
                    it[runClaim] = null
                }
            }
        } catch (_: Exception) {
            // the claim goes stale after STALE_AFTER_MINUTES by itself; a failing release must not hide the result of the run
        }
    }

    /** `true` while a non-stale claim exists. */
    fun isRunning(now: LocalDateTime): Boolean =
        transaction {
            LupaBbSyncConfigTable
                .selectAll()
                .where { LupaBbSyncConfigTable.id eq LupaBbCredentialStore.CONFIG_ID }
                .singleOrNull()
                ?.get(LupaBbSyncConfigTable.runningSince)
                ?.let { it >= staleBefore(now) } == true
        }
}
