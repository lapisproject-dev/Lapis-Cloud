package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [awaitUntil] treats a condition that throws as "not yet" (CI flake of 2026-10-16: a `buttonNamed(..)` in a condition threw while a screen
 * was re-rendering) and names the last error when the condition never holds.
 */
class AwaitUntilTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    @Test
    fun aConditionThatThrowsAtFirstIsWaitedFor(): Promise<Unit> =
        test {
            var polls = 0
            awaitUntil("holds after two throwing polls") {
                polls++
                check(polls > 2) { "element not in the document yet" }
                true
            }
            assertTrue(polls > 2, "the wait went on past the throwing polls")
        }

    @Test
    fun aConditionThatHoldsReturnsAtOnce(): Promise<Unit> =
        test {
            var polls = 0
            awaitUntil("holds at once") {
                polls++
                true
            }
            // once in the loop test and once in the final check
            assertEquals(2, polls)
        }

    @Test
    fun aConditionThatNeverHoldsFailsAndNamesTheLastError(): Promise<Unit> =
        test {
            // awaitUntilWithin takes the budget as it is (awaitUntil has a floor of 15 s); the condition throws a recognisable error
            val failure =
                runCatching {
                    awaitUntilWithin("never", budget = 100) { error("no button 'Veröffentlichen'") }
                }.exceptionOrNull()
            val text = failure?.message.orEmpty()
            assertTrue(text.contains("timeout: never"), "names the wait: $text")
            assertTrue(text.contains("last error of the condition: no button 'Veröffentlichen'"), "names the last error: $text")
        }
}
