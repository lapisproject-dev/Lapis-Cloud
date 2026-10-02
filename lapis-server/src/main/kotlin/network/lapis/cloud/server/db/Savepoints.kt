package network.lapis.cloud.server.db

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Runs [block] under a JDBC savepoint inside the CURRENT Exposed transaction. If [block] throws, only
 * the work since the savepoint is rolled back and the exception is rethrown -- the surrounding
 * transaction stays usable.
 *
 * Why this exists (Welle V1.9.37, found by the Postgres test lane): on PostgreSQL a failed statement
 * (e.g. a `23505` unique violation) aborts the WHOLE transaction -- every later statement fails with
 * `25P02` ("current transaction is aborted") and even a `COMMIT` silently turns into a `ROLLBACK`.
 * H2 (the rest of the suite) carries on after a failed statement, so any code that catches a
 * constraint violation and then keeps using the same transaction was only ever correct on H2. Wrap
 * the statement that may violate the constraint in this function; the catch then runs on a healthy
 * transaction. Same idiom `PaymentReferenceAllocator` and `WebhookEventPublisher` established inline.
 *
 * Do NOT use a non-local `return` out of [block] (it would skip the release); keep early exits outside.
 */
internal inline fun <T> withSavepoint(
    name: String,
    block: () -> T,
): T {
    val connection = TransactionManager.current().connection
    val savepoint = connection.setSavepoint(name)
    val result =
        try {
            block()
        } catch (e: Throwable) {
            runCatching { connection.rollback(savepoint) }
            throw e
        }
    connection.releaseSavepoint(savepoint)
    return result
}
