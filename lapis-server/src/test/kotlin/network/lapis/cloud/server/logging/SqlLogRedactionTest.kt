package network.lapis.cloud.server.logging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.shouldNotContainNumber
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.SQLException
import java.util.UUID

class SqlLogRedactionTest :
    FunSpec({
        // Private in-memory H2: unique name, never shared with another spec or JVM fork.
        val database =
            Database.connect(
                "jdbc:h2:mem:logredaction-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        val table = createUniqueTable(database)
        val failure = provokeUniqueViolation(table = table, database = database)

        fun assertClean(text: String?) {
            val lower = (text ?: "").lowercase()
            FORBIDDEN_IN_LOG.forEach { lower shouldNotContain it }
            text.orEmpty() shouldNotContainNumber TEST_PHONE_DIGITS
        }

        test("the raw exception really carries the personal data (guards the test itself)") {
            failure.toString().lowercase() shouldContain "mustermann"
        }

        test("a real ExposedSQLException message is redacted, SQLSTATE and class are kept") {
            assertClean(SqlLogRedaction.redactMessage(message = failure.message, t = failure))
            val summary = SqlLogRedaction.summarize(failure).shouldNotBeNull()
            summary.sqlState shouldBe "23505"
            summary.classes shouldContain failure.javaClass.name
        }

        test("a wrapper RuntimeException(cause) whose message is cause.toString() is redacted too") {
            val wrapper = RuntimeException(failure)
            wrapper.message.orEmpty().lowercase() shouldContain "mustermann"
            assertClean(SqlLogRedaction.redactMessage(message = wrapper.message, t = wrapper))
        }

        test("a doubly wrapped suppressed exception is covered") {
            val outer = IllegalStateException("outer")
            outer.addSuppressed(RuntimeException("wrapped", RuntimeException(failure)))
            assertClean(SqlLogRedaction.redactMessage(message = failure.message, t = outer))
        }

        test("a SQLException nextException chain is covered") {
            val first = SQLException("first", "23505")
            first.nextException = SQLException("ERROR: duplicate key value\n  Detail: Key (email)=($TEST_EMAIL) already exists.", "23505")
            val message = first.nextException.message
            assertClean(SqlLogRedaction.redactMessage(message = message, t = first))
        }

        test("a cause cycle terminates") {
            val a = RuntimeException("a")
            val b = RuntimeException("b", a)
            a.initCause(b)
            SqlLogRedaction.summarize(a).shouldBeNull()
            SqlLogRedaction.sensitiveFragments(a) shouldBe emptySet()
        }

        test("a message WITHOUT a throwable is cut at the SQL markers") {
            val message =
                "Could not save: ERROR: duplicate key value violates unique constraint \"u\"\n" +
                    "  Detail: Key (email)=($TEST_EMAIL) already exists."
            val redacted = SqlLogRedaction.redactMessage(message = message, t = null).shouldNotBeNull()
            assertClean(redacted)
            redacted shouldContain "Could not save"
            redacted shouldContain SqlLogRedaction.REDACTED
        }

        test("an Exposed SQL: [..] tail without a throwable is cut") {
            val redacted = SqlLogRedaction.redactMessage(message = "failed. SQL: [INSERT INTO m (email) VALUES ('$TEST_EMAIL')]", t = null)
            assertClean(redacted)
        }

        test("a non-SQL exception and an ordinary message stay unchanged") {
            val t = IllegalArgumentException("member 42 not found")
            SqlLogRedaction.redactMessage(message = t.message, t = t) shouldBe "member 42 not found"
            SqlLogRedaction.redactMessage(message = "plain message", t = null) shouldBe "plain message"
            SqlLogRedaction.summarize(t).shouldBeNull()
            SqlLogRedaction.isSqlBearing(t) shouldBe false
        }

        test("a null message stays null") {
            SqlLogRedaction.redactMessage(message = null, t = null).shouldBeNull()
        }
    })
