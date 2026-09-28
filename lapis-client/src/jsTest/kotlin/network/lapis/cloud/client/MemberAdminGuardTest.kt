package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.RegionalChapterInUseException
import network.lapis.cloud.shared.rpc.RegionalChapterLimitReachedException
import network.lapis.cloud.shared.rpc.RegionalChapterNameTakenException
import network.lapis.cloud.shared.rpc.RegionalChapterOfficerIneligibleException
import network.lapis.cloud.shared.rpc.RegionalChapterRequiredException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review fix (Welle V1.9.14, MAJOR test-coverage finding): [regionalChapterGuarded] and
 * [memberAdminGuarded] had NO tests at all before this file -- the CHANGELOG openly disclosed the
 * gap ("vollständige DOM-Testabdeckung ... nicht umgesetzt"), which is exactly the kind of
 * regression this file now catches: e.g. someone reordering the `catch` branches in either
 * function so [RegionalChapterRequiredException] falls through to the generic toast instead of
 * [regionalChapterGuarded]'s own branch would previously have stayed green.
 *
 * These are plain, DOM-free unit tests: both guard functions are ordinary `suspend` functions
 * taking a `block: suspend () -> T` lambda, so a test can hand them a block that throws the exact
 * exception type directly -- no need to simulate Kilua RPC's wire protocol (see
 * `RegistrationScreenFieldErrorDomTest.kt` for the one place that DOES need the real wire, because
 * the catch chain under test lives OUTSIDE any guard function). What each test asserts:
 * - the return value (`null` = "failed, already handled" by this codebase's universal convention)
 * - whether the field-error callback ([RegionalChapterNameTakenException]'s `onNameTaken`,
 *   [RegionalChapterRequiredException]'s `onChapterRejected`) fired -- this is the "which exception
 *   gives a field error" half of the review finding.
 * - that [kotlinx.coroutines.CancellationException] is rethrown, never swallowed (both guards have
 *   a dedicated `catch (e: CancellationException) { throw e }` branch ahead of everything else).
 *
 * The "which exception gives a toast" half cannot be asserted here without a mounted
 * `ToastContainer` (`notifyError` is a silent no-op against a `null` container outside a DOM test,
 * see `Notify.kt`) -- covered instead by asserting the ABSENCE of a callback/thrown exception for
 * every toast-only branch, which is the only externally observable difference a caller sees.
 */
class MemberAdminGuardTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).promise { block() }

    // ── regionalChapterGuarded -- success / cancellation ──────────────────────────────────────

    @Test
    fun success_returnsTheBlocksResult(): Promise<Unit> =
        test {
            val result = regionalChapterGuarded { "ok" }
            assertEquals("ok", result)
        }

    @Test
    fun cancellationException_isRethrown_neverSwallowed(): Promise<Unit> =
        test {
            var caught = false
            try {
                regionalChapterGuarded { throw kotlinx.coroutines.CancellationException("cancelled") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                caught = true
            }
            assertTrue(caught, "a CancellationException must propagate, never be treated as a failure")
        }

    // ── regionalChapterGuarded -- the five chapter-specific branches ──────────────────────────

    @Test
    fun nameTaken_withCallback_firesTheCallback_showsNoToastCallback_returnsNull(): Promise<Unit> =
        test {
            var fired = false
            val result =
                regionalChapterGuarded(onNameTaken = { fired = true }) {
                    throw RegionalChapterNameTakenException()
                }
            assertNull(result)
            assertTrue(fired, "onNameTaken must fire when provided")
        }

    @Test
    fun nameTaken_withoutCallback_doesNotThrow_returnsNull(): Promise<Unit> =
        test {
            // No onNameTaken given -- the branch falls back to a toast (see class KDoc: not
            // observable without a mounted ToastContainer), the only externally visible contract is
            // "does not throw, returns null".
            val result = regionalChapterGuarded { throw RegionalChapterNameTakenException() }
            assertNull(result)
        }

    @Test
    fun inUse_returnsNull(): Promise<Unit> =
        test {
            assertNull(regionalChapterGuarded { throw RegionalChapterInUseException() })
        }

    @Test
    fun limitReached_returnsNull(): Promise<Unit> =
        test {
            assertNull(regionalChapterGuarded { throw RegionalChapterLimitReachedException() })
        }

    @Test
    fun officerIneligible_returnsNull(): Promise<Unit> =
        test {
            assertNull(regionalChapterGuarded { throw RegionalChapterOfficerIneligibleException() })
        }

    @Test
    fun chapterRequired_withCallback_firesTheCallback_returnsNull(): Promise<Unit> =
        test {
            var fired = false
            val result =
                regionalChapterGuarded(onChapterRejected = { fired = true }) {
                    throw RegionalChapterRequiredException()
                }
            assertNull(result)
            assertTrue(fired, "onChapterRejected must fire when provided")
        }

    @Test
    fun chapterRequired_withoutCallback_doesNotThrow_returnsNull(): Promise<Unit> =
        test {
            val result = regionalChapterGuarded { throw RegionalChapterRequiredException() }
            assertNull(result)
        }

    @Test
    fun onNameTaken_neverFiresForAnUnrelatedException(): Promise<Unit> =
        test {
            var fired = false
            val result = regionalChapterGuarded(onNameTaken = { fired = true }) { throw RegionalChapterRequiredException() }
            assertNull(result)
            assertFalse(fired, "onNameTaken must only fire for RegionalChapterNameTakenException")
        }

    @Test
    fun onChapterRejected_neverFiresForAnUnrelatedException(): Promise<Unit> =
        test {
            var fired = false
            val result = regionalChapterGuarded(onChapterRejected = { fired = true }) { throw RegionalChapterNameTakenException() }
            assertNull(result)
            assertFalse(fired, "onChapterRejected must only fire for RegionalChapterRequiredException")
        }

    // ── regionalChapterGuarded -- falls through to the SAME shared handler memberAdminGuarded uses ──

    @Test
    fun everyOtherException_fallsThroughToTheSharedHandler_returnsNull(): Promise<Unit> =
        test {
            assertNull(regionalChapterGuarded { throw ConflictException("x") })
            assertNull(regionalChapterGuarded { throw BadRequestException("x") })
            assertNull(regionalChapterGuarded { throw ForbiddenException() })
        }

    // ── memberAdminGuarded -- its own RegionalChapterRequiredException branch (V1.9.14 addition) ──

    @Test
    fun memberAdminGuarded_success_returnsTheBlocksResult(): Promise<Unit> =
        test {
            assertEquals(42, memberAdminGuarded { 42 })
        }

    @Test
    fun memberAdminGuarded_cancellationException_isRethrown(): Promise<Unit> =
        test {
            var caught = false
            try {
                memberAdminGuarded { throw kotlinx.coroutines.CancellationException("cancelled") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                caught = true
            }
            assertTrue(caught)
        }

    @Test
    fun memberAdminGuarded_chapterRequired_showsTheGenericToast_returnsNull(): Promise<Unit> =
        test {
            // memberAdminGuarded has no onChapterRejected parameter at all -- every caller through
            // THIS guard (updateMemberStatus et al.) gets the same generic toast regionalChapterGuarded
            // shows when no onChapterRejected was given. Only externally observable contract here:
            // does not throw, returns null.
            assertNull(memberAdminGuarded { throw RegionalChapterRequiredException() })
        }

    @Test
    fun memberAdminGuarded_everyOtherException_fallsThroughToTheSharedHandler_returnsNull(): Promise<Unit> =
        test {
            assertNull(memberAdminGuarded { throw ConflictException("x") })
            assertNull(memberAdminGuarded { throw BadRequestException("x") })
            assertNull(memberAdminGuarded { throw ForbiddenException() })
        }

    @Test
    fun memberAdminGuarded_unauthenticated_clearsTheSessionAndReturnsNull(): Promise<Unit> =
        test {
            // Delegated to `guarded` (via `handleMemberAdminFailure`'s `is UnauthenticatedException ->
            // guarded<T> { throw e }` branch): `guarded` clears AppState.session and would navigate to
            // login (a no-op here -- no `initRouting()` was called in this unit test, see `navigateTo`
            // KDoc). Reset the session afterwards so this test cannot affect any other test's ordering.
            try {
                assertNull(memberAdminGuarded { throw UnauthenticatedException() })
                assertNull(AppState.session, "guarded's sessionExpired() must clear AppState.session")
            } finally {
                AppState.setSession(null)
            }
        }
}
