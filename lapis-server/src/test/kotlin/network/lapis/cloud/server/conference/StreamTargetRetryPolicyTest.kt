package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.ConferenceStreamFailureKind
import network.lapis.cloud.shared.domain.ConferenceStreamRetryLimits
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** V1.9.275 -- the pure rules of the automatic retry: classification, window, back-off, limits and the choice among several `stream_results`. */
class StreamTargetRetryPolicyTest :
    FunSpec({
        val zone = ServerClock.zone
        val t0 = LocalDateTime(2026, 11, 3, 12, 0, 0)

        fun at(seconds: Int): LocalDateTime = (t0.toInstant(zone) + seconds.seconds).toLocalDateTime(zone)

        fun next(
            kind: ConferenceStreamFailureKind,
            attempt: Int,
            window: LocalDateTime? = t0,
            issued: LocalDateTime? = null,
            detected: LocalDateTime,
        ) = StreamTargetRetryPolicy.nextAttemptAt(
            kind = kind,
            attemptSoFar = attempt,
            windowStartedAt = window,
            lastIssuedAt = issued,
            detectedAt = detected,
            zone = zone,
        )

        fun pick(
            candidates: List<LiveKitStreamInfo>,
            issued: LocalDateTime?,
        ) = StreamTargetRetryPolicy.pickResult(candidates = candidates, issuedAt = issued, zone = zone)

        fun nanos(seconds: Int): String = (t0.toInstant(zone).toEpochMilliseconds() * 1_000_000L + seconds * 1_000_000_000L).toString()

        test("classify: a refusal wins over a connect word, timeouts and resolution errors are told apart") {
            val p = StreamTargetRetryPolicy
            p.classify("Failed to connect: 403 Forbidden") shouldBe ConferenceStreamFailureKind.REJECTED
            p.classify("401 unauthorized") shouldBe ConferenceStreamFailureKind.REJECTED
            p.classify("Forbidden") shouldBe ConferenceStreamFailureKind.REJECTED
            p.classify("operation timed out") shouldBe ConferenceStreamFailureKind.TIMEOUT
            p.classify("Timeout") shouldBe ConferenceStreamFailureKind.TIMEOUT
            p.classify("Connection refused") shouldBe ConferenceStreamFailureKind.CONNECTION
            p.classify("Error resolving host") shouldBe ConferenceStreamFailureKind.CONNECTION
            p.classify("some other thing") shouldBe ConferenceStreamFailureKind.UNKNOWN
            p.classify("") shouldBe ConferenceStreamFailureKind.UNKNOWN
        }

        test("reasonText returns one of four fixed texts, never anything from the input") {
            ConferenceStreamFailureKind.entries
                .map { StreamTargetRetryPolicy.reasonText(it) }
                .toSet()
                .size shouldBe 4
            StreamTargetRetryPolicy.reasonText(ConferenceStreamFailureKind.REJECTED) shouldBe FAILURE_REJECTED
            StreamTargetRetryPolicy.reasonText(ConferenceStreamFailureKind.UNKNOWN) shouldBe FAILURE_GENERIC
        }

        test("window of 45 seconds: 44 retries, 46 does not; no anchor never retries") {
            val p = StreamTargetRetryPolicy
            val k = ConferenceStreamFailureKind.CONNECTION
            next(kind = k, attempt = 0, detected = at(44)) shouldBe at(49)
            next(kind = k, attempt = 0, detected = at(46)) shouldBe null
            next(kind = k, attempt = 0, window = null, detected = at(1)) shouldBe null
        }

        test("the last issued attempt is the anchor when there is one (an old window start does not count)") {
            val k = ConferenceStreamFailureKind.TIMEOUT
            StreamTargetRetryPolicy.nextAttemptAt(
                kind = k,
                attemptSoFar = 1,
                windowStartedAt = t0,
                lastIssuedAt = at(100),
                detectedAt = at(120),
                zone = zone,
            ) shouldBe
                at(135)
        }

        test("back-off is 5, 15, 30 seconds and there are at most three attempts") {
            val k = ConferenceStreamFailureKind.UNKNOWN
            next(kind = k, attempt = 0, detected = at(1)) shouldBe at(6)
            next(kind = k, attempt = 1, detected = at(1)) shouldBe at(16)
            next(kind = k, attempt = 2, detected = at(1)) shouldBe at(31)
            next(kind = k, attempt = 3, detected = at(1)) shouldBe null
        }

        test("a rejected key is never retried") {
            next(kind = ConferenceStreamFailureKind.REJECTED, attempt = 0, detected = at(1)) shouldBe null
        }

        test("pickResult: ACTIVE wins; without an issue the latest timestamp wins; one entry is the former behaviour") {
            val old = LiveKitStreamInfo(url = "u", status = "FAILED", startedAtEpochNanos = nanos(1), endedAtEpochNanos = nanos(2))
            val newer = LiveKitStreamInfo(url = "u", status = "FAILED", startedAtEpochNanos = nanos(10), endedAtEpochNanos = nanos(12))
            val active = LiveKitStreamInfo(url = "u", status = "ACTIVE", startedAtEpochNanos = nanos(5))
            pick(candidates = listOf(old), issued = null) shouldBe old
            pick(candidates = listOf(old, newer), issued = null) shouldBe newer
            pick(candidates = listOf(old, active, newer), issued = null) shouldBe active
            pick(candidates = emptyList(), issued = null) shouldBe null
        }

        test("pickResult with an issue: a FAILED entry that ended before the issue is stale, a later one is fresh") {
            val old = LiveKitStreamInfo(url = "u", status = "FAILED", startedAtEpochNanos = nanos(1), endedAtEpochNanos = nanos(2))
            val fresh = LiveKitStreamInfo(url = "u", status = "FAILED", startedAtEpochNanos = nanos(21), endedAtEpochNanos = nanos(22))
            pick(candidates = listOf(old), issued = at(10)) shouldBe null
            pick(candidates = listOf(old, fresh), issued = at(10)) shouldBe fresh
        }

        test("the SQL CHECK of V141 repeats the shared limit") {
            val sql =
                File("src/main/resources/db/migration/V141__stream_target_auto_retry.sql")
                    .let {
                        if (it.exists()) {
                            it
                        } else {
                            File(
                                "lapis-server/src/main/resources/db/migration/V141__stream_target_auto_retry.sql",
                            )
                        }
                    }.readText()
            sql.contains("auto_retry_attempt BETWEEN 0 AND ${ConferenceStreamRetryLimits.MAX_AUTO_ATTEMPTS}") shouldBe true
            StreamTargetRetryPolicy.MAX_ATTEMPTS shouldBe ConferenceStreamRetryLimits.MAX_AUTO_ATTEMPTS
        }
    })
