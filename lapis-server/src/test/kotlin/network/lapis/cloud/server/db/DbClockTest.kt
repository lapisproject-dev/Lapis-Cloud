package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.OidcGuestLoginEventTable
import network.lapis.cloud.shared.domain.OidcLoginEventType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Regression coverage for the 2026-07-28 timestamp-precision root-cause fix (see CHANGELOG.md
 * entry "GoBD audit-log hash-chain tamper-evidence guarantee undermined by a timestamp-precision
 * mismatch"). [DbClock.nowLocalDateTime]/[truncatedToDbPrecision] exist because every `TIMESTAMP`
 * column in this schema -- H2 in `MODE=PostgreSQL` here, real PostgreSQL in production -- silently
 * TRUNCATES (never rounds) stored values to microsecond precision, while [kotlin.time.Clock
 * .System.now] can return genuine nanosecond-precision [kotlinx.datetime.Instant]s on Linux (the
 * GitHub Actions CI runner; NOT reproducible on this codebase's macOS developer machines, whose
 * JDK wall-clock resolution never returns sub-microsecond jitter in the first place -- see the
 * CHANGELOG entry for the full account of why this went undetected locally for 7+ days).
 *
 * [OidcGuestLoginEventTable] is used here (rather than [network.lapis.cloud.server.db.generated
 * .AuditLogEntryTable], which the codebase's own [network.lapis.cloud.server.audit
 * .AuditLogImmutabilityTest]-adjacent convention reserves for [network.lapis.cloud.server.audit
 * .AuditLogRecorder] as the sole INSERT path) purely as a convenient, FK-free `TIMESTAMP` column
 * to prove the general DB round-trip mechanism -- see `AuditLogServiceTest`'s own dedicated
 * capture/hash/insert/fresh-read/recompute/compare test for the audit-log-specific proof this fix
 * was actually motivated by.
 */
class DbClockTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        test("DbClock.nowLocalDateTime() always returns a value whose nanosecond component is a multiple of 1000") {
            // Platform-independent by construction (unlike relying on this machine's own clock
            // resolution) -- this is the assertion that actually proves truncation is happening,
            // not merely assumed.
            repeat(50) {
                val captured = DbClock.nowLocalDateTime()
                (captured.nanosecond % 1000) shouldBe 0
            }
        }

        test("truncatedToDbPrecision() truncates (does not round) a nanosecond-precision LocalDateTime down to microseconds") {
            val nanosecondPrecision = LocalDateTime(2026, 7, 29, 0, 42, 18, 185_317_372)
            val truncated = nanosecondPrecision.truncatedToDbPrecision()
            truncated shouldBe LocalDateTime(2026, 7, 29, 0, 42, 18, 185_317_000)
            (truncated.nanosecond % 1000) shouldBe 0
        }

        test("truncatedToDbPrecision() is a no-op for a value already at microsecond precision") {
            val alreadyMicros = LocalDateTime(2026, 7, 29, 0, 42, 18, 185_317_000)
            alreadyMicros.truncatedToDbPrecision() shouldBe alreadyMicros
        }

        test(
            "H2 (MODE=PostgreSQL) round-trip: a truncated LocalDateTime survives INSERT + fresh SELECT byte-identical, reproducing the exact CI failure shape for an un-truncated one",
        ) {
            // The exact nanosecond-precision value quoted verbatim in the diagnosed CI failure
            // (SessionStoreTest: "expected:<...185317372> but was:<...185317>").
            val nanosecondPrecision = LocalDateTime(2026, 7, 29, 0, 42, 18, 185_317_372)
            val truncated = nanosecondPrecision.truncatedToDbPrecision()

            val untruncatedId = Uuid.random()
            val truncatedId = Uuid.random()
            transaction {
                OidcGuestLoginEventTable.insert {
                    it[id] = untruncatedId
                    it[occurredAt] = nanosecondPrecision
                    it[eventType] = OidcLoginEventType.ISSUER_TOKEN_ISSUED
                    it[memberId] = null
                    it[remoteParty] = "DbClockTest-untruncated"
                    it[reason] = null
                }
                OidcGuestLoginEventTable.insert {
                    it[id] = truncatedId
                    it[occurredAt] = truncated
                    it[eventType] = OidcLoginEventType.ISSUER_TOKEN_ISSUED
                    it[memberId] = null
                    it[remoteParty] = "DbClockTest-truncated"
                    it[reason] = null
                }
            }

            val readBackUntruncated =
                transaction {
                    OidcGuestLoginEventTable
                        .selectAll()
                        .where { OidcGuestLoginEventTable.id eq untruncatedId }
                        .single()[OidcGuestLoginEventTable.occurredAt]
                }
            val readBackTruncated =
                transaction {
                    OidcGuestLoginEventTable
                        .selectAll()
                        .where { OidcGuestLoginEventTable.id eq truncatedId }
                        .single()[OidcGuestLoginEventTable.occurredAt]
                }

            // H2 in MODE=PostgreSQL truncates the un-truncated value on write -- the read-back no
            // longer matches what was captured, exactly reproducing the diagnosed CI failure shape.
            (readBackUntruncated == nanosecondPrecision) shouldBe false
            readBackUntruncated shouldBe truncated

            // The whole point of the fix: truncating BEFORE insert makes capture-time and
            // read-back-time values byte-identical, closing the gap the bug exploited.
            readBackTruncated shouldBe truncated
        }
    })
