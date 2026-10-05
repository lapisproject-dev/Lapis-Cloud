package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.SQLException

class DbFailuresTest :
    FunSpec({
        test("sqlState classification") {
            DbSqlStates.classify("55P03") shouldBe DbFailureKind.LOCK_TIMEOUT
            DbSqlStates.classify("HYT00") shouldBe DbFailureKind.LOCK_TIMEOUT
            DbSqlStates.classify("57014") shouldBe DbFailureKind.STATEMENT_TIMEOUT
            DbSqlStates.classify("25P03") shouldBe DbFailureKind.IDLE_IN_TX_TIMEOUT
            DbSqlStates.classify("40P01") shouldBe DbFailureKind.DEADLOCK
            DbSqlStates.classify("40001") shouldBe DbFailureKind.SERIALIZATION
            DbSqlStates.classify("23505") shouldBe null
            DbSqlStates.classify(null) shouldBe null
        }

        test("the failure kind is found through the cause chain") {
            val root = SQLException("canceling statement due to lock timeout", "55P03")
            RuntimeException("wrapper", IllegalStateException("inner", root)).dbFailureKind() shouldBe DbFailureKind.LOCK_TIMEOUT
        }

        test("the failure kind is found through nextException") {
            val first = SQLException("batch", "00000")
            first.nextException = SQLException("canceled", "57014")
            first.dbFailureKind() shouldBe DbFailureKind.STATEMENT_TIMEOUT
        }

        test("isUniqueViolation matches only 23505") {
            SQLException("dup", "23505").isUniqueViolation() shouldBe true
            RuntimeException(SQLException("dup", "23505")).isUniqueViolation() shouldBe true
            SQLException("timeout", "55P03").isUniqueViolation() shouldBe false
            RuntimeException("nothing").isUniqueViolation() shouldBe false
        }

        test("a unique violation is not a busy failure and vice versa") {
            SQLException("dup", "23505").dbFailureKind() shouldBe null
            SQLException("timeout", "55P03").isUniqueViolation() shouldBe false
        }

        test("cause cycles terminate") {
            val a = RuntimeException("a")
            val b = RuntimeException("b", a)
            a.initCause(b)
            a.dbFailureKind() shouldBe null
            a.isUniqueViolation() shouldBe false
        }

        test("very deep chains terminate and stop at the depth cap") {
            var t: Throwable = SQLException("deep", "55P03")
            repeat(40) { t = RuntimeException("w$it", t) }
            t.dbFailureKind() shouldBe null
        }
    })
