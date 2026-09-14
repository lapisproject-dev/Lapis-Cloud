package network.lapis.cloud.server.payment.fints

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * Review fix (MEDIUM): [Hbci4jFinTsClient]'s own class KDoc ("Fehler-Mapping") claims
 * [Throwable.toFinTsErrorCode]'s mapping table is tested -- before this fix it was not, and the
 * gap was exactly the bug: `runOnDedicatedThread`'s `Future.get(timeout)` (the [fetch] path)
 * wraps every exception thrown inside the submitted block in an [ExecutionException], and every
 * `is`-branch below used to be matched against the WRAPPER, never the real cause, so a bank-
 * unreachable [ConnectException] (etc.) fell through to `PROTOCOL_ERROR` instead of
 * [FinTsErrorCode.BANK_UNAVAILABLE]. This test pins the full table for both the raw exception AND
 * its [ExecutionException]-wrapped form -- the second half is the part that would have failed
 * before the fix in [Hbci4jFinTsClient].
 */
class Hbci4jFinTsClientErrorMappingTest :
    FunSpec({
        data class Case(
            val label: String,
            val throwable: Throwable,
            val expected: FinTsErrorCode,
        )

        val cases =
            listOf(
                Case("TimeoutException", TimeoutException(), FinTsErrorCode.TIMEOUT),
                Case("SocketTimeoutException", SocketTimeoutException(), FinTsErrorCode.TIMEOUT),
                Case("ConnectException", ConnectException(), FinTsErrorCode.BANK_UNAVAILABLE),
                Case("UnknownHostException", UnknownHostException(), FinTsErrorCode.BANK_UNAVAILABLE),
                Case("generic IOException", java.io.IOException("broken pipe"), FinTsErrorCode.BANK_UNAVAILABLE),
                Case("Hbci4jRawMt940FieldMissingException", Hbci4jRawMt940FieldMissingException(null), FinTsErrorCode.PROTOCOL_ERROR),
                Case("unrelated RuntimeException", RuntimeException("boom"), FinTsErrorCode.PROTOCOL_ERROR),
            )

        cases.forEach { case ->
            test("toFinTsErrorCode: raw ${case.label} maps to ${case.expected}") {
                case.throwable.toFinTsErrorCode() shouldBe case.expected
            }

            test(
                "toFinTsErrorCode: ExecutionException-wrapped ${case.label} maps to ${case.expected} " +
                    "(the runOnDedicatedThread/fetch() shape)",
            ) {
                ExecutionException(case.throwable).toFinTsErrorCode() shouldBe case.expected
            }
        }

        test("toFinTsErrorCode: an ExecutionException with no cause falls back to PROTOCOL_ERROR instead of NPE-ing") {
            ExecutionException("no cause", null).toFinTsErrorCode() shouldBe FinTsErrorCode.PROTOCOL_ERROR
        }
    })
