package network.lapis.cloud.server.testdb

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import java.io.File

/**
 * Welle V1.9.37 -- the Postgres lane's class set is selected by NAME (`*Postgres*`, see the
 * `postgresTest` task in `lapis-server/build.gradle.kts`) AND by the `@Tags("Postgres")` annotation.
 * If the two ever disagree, a lane spec silently stops running (named wrong) or a Postgres-only spec
 * runs in the H2 `test` task (untagged). This guard keeps both in step.
 */
class PostgresLaneNamingTest :
    FunSpec({
        fun testClasses(): List<Class<*>> {
            val root =
                File(
                    PostgresTestSupport::class.java.protectionDomain.codeSource.location
                        .toURI(),
                )
            return root
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" && !it.name.contains('$') }
                .map {
                    it
                        .relativeTo(root)
                        .path
                        .removeSuffix(".class")
                        .replace(File.separatorChar, '.')
                }.mapNotNull { runCatching { Class.forName(it, false, PostgresTestSupport::class.java.classLoader) }.getOrNull() }
                .toList()
        }

        test("every @Tags(\"Postgres\") spec has 'Postgres' in its class name, and carries the EnabledIf condition") {
            val laneSpecs =
                testClasses().filter {
                    Spec::class.java.isAssignableFrom(it) &&
                        it.getAnnotation(Tags::class.java)?.values?.contains("Postgres") == true
                }
            laneSpecs.size shouldBeGreaterThanOrEqual 10 // the classpath scan must not silently run empty
            laneSpecs.filterNot { it.simpleName.contains("Postgres") }.map { "tagged but not named *Postgres*: ${it.name}" }.shouldBeEmpty()
            laneSpecs
                .filter {
                    it.getAnnotation(
                        EnabledIf::class.java,
                    ) == null
                }.map { "no @EnabledIf(PostgresConfigured): ${it.name}" }
                .shouldBeEmpty()
        }

        test("no concrete spec named *Postgres* escapes the lane tag (it would run in the H2 test task)") {
            val untagged =
                testClasses()
                    .filter {
                        Spec::class.java.isAssignableFrom(it) &&
                            !java.lang.reflect.Modifier
                                .isAbstract(it.modifiers)
                    }.filter { it.simpleName.contains("Postgres") }
                    .filter { it.getAnnotation(Tags::class.java)?.values?.contains("Postgres") != true }
                    // Pre-existing, DB-free spec that merely has the product name in its name (SQL-shape test, no connection).
                    // PostgresTestSupportUrlValidationTest is DB-free by design and must run in the normal task, too.
                    .filterNot {
                        it.simpleName == "PostgresFullTextRetrieverSqlTest" ||
                            it.simpleName == "PostgresTestSupportUrlValidationTest" ||
                            it.simpleName == "PostgresLaneNamingTest"
                    }.map { it.name }
            untagged.shouldBeEmpty()
        }
    })
