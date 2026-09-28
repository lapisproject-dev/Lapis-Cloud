package network.lapis.cloud.server.events.series

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import net.fortuna.ical4j.model.Recur
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Welle V1.4.35 "Wiederkehrende Veranstaltungen" -- a deliberate classpath/runtime smoke test, not
 * a `RecurrenceExpander` behaviour test (that's `RecurrenceExpanderTest`). Its only job is to fail
 * loudly here, in a two-second unit test, instead of failing in production if either of the two
 * things `lapis-server/build.gradle.kts`'s `libs.ical4j { exclude(...) }` block promises ever stop
 * holding:
 *
 * 1. `Recur` must be usable WITHOUT Groovy or jparsec on the classpath (both excluded as unused
 *    transitive dependencies of ical4j's `ContentBuilder` DSL / filter-expression features) --
 *    verified here by asserting neither is `Class.forName`-loadable, then exercising `Recur`
 *    regardless.
 * 2. ical4j's timezone-update mechanism must be disabled (`Application.kt` sets
 *    `net.fortuna.ical4j.timezone.update.enabled=false` as a system property before `main()` does
 *    anything else) -- this test sets the same properties directly (it never runs through
 *    `Application.main()`) so it also exercises the "does `Recur` still work with the update
 *    mechanism off" half of that guarantee.
 */
class Ical4jClasspathSmokeTest :
    FunSpec({
        test("Groovy is NOT on the test classpath") {
            classExists("groovy.lang.GroovyObject") shouldBe false
            classExists("org.codehaus.groovy.runtime.InvokerHelper") shouldBe false
        }

        test("jparsec is NOT on the test classpath") {
            classExists("org.jparsec.Parser") shouldBe false
        }

        test("Recur<ZonedDateTime> expands a rule without Groovy/jparsec and with timezone updates disabled") {
            System.setProperty("net.fortuna.ical4j.timezone.update.enabled", "false")
            System.setProperty("net.fortuna.ical4j.recur.maxincrementcount", "1000")

            val zone = ZoneId.of("Europe/Berlin")
            val seed = ZonedDateTime.of(2026, 10, 6, 19, 0, 0, 0, zone)
            val recur = Recur<ZonedDateTime>("FREQ=WEEKLY;INTERVAL=1;BYDAY=TU;COUNT=5")

            val dates = recur.getDates(seed, seed, seed.plusMonths(24), 6)

            dates shouldHaveSize 5
        }
    })

private fun classExists(fqcn: String): Boolean =
    try {
        Class.forName(fqcn)
        true
    } catch (e: ClassNotFoundException) {
        false
    }
