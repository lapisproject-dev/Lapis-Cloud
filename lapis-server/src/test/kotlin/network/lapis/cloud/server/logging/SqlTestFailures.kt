package network.lapis.cloud.server.logging

import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/** Personal data the redaction tests plant in the failing statement; none of it may reach the log. */
internal const val TEST_EMAIL = "erika.mustermann@example.org"
internal const val TEST_NAME = "Mustermann"
internal const val TEST_PHONE_DIGITS = "99887766"

internal val FORBIDDEN_IN_LOG = listOf("mustermann", "@example.org", "insert into", "detail:", "key (", "values (")

/** Creates a table with a unique email column (random name, never shared) and returns its name. */
internal fun createUniqueTable(database: Database? = null): String {
    val table = "lapis_log_t_" + UUID.randomUUID().toString().replace("-", "")
    val ddl = "CREATE TABLE $table (email VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(100), phone VARCHAR(40))"
    if (database == null) transaction { exec(ddl) } else transaction(database) { exec(ddl) }
    return table
}

internal fun dropTable(
    table: String,
    database: Database? = null,
) {
    val ddl = "DROP TABLE IF EXISTS $table"
    runCatching { if (database == null) transaction { exec(ddl) } else transaction(database) { exec(ddl) } }
}

/** Provokes a REAL unique violation (twice the same email) and returns the exception the driver/Exposed produce. */
internal fun provokeUniqueViolation(
    table: String,
    database: Database? = null,
): ExposedSQLException {
    val insert = "INSERT INTO $table (email, name, phone) VALUES ('$TEST_EMAIL', '$TEST_NAME', '0170 $TEST_PHONE_DIGITS')"
    val reset = "DELETE FROM $table"
    if (database == null) transaction { exec(reset) } else transaction(database) { exec(reset) }

    fun run() {
        if (database == null) {
            transaction {
                maxAttempts = 1
                exec(insert)
            }
        } else {
            transaction(database) {
                maxAttempts = 1
                exec(insert)
            }
        }
    }
    run()
    try {
        run()
    } catch (e: ExposedSQLException) {
        return e
    }
    error("the second insert must violate the unique constraint")
}
