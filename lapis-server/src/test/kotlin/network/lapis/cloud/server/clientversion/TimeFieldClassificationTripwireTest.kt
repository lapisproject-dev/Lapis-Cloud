package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * V1.9.38 "Einheitliche Zeitzonen" -- the classification tripwire. Every temporal DTO property (lapis-shared) and every temporal
 * Exposed column (lapis-server) must be classified in `time-fields.tsv` as A (system timestamp, UTC), B (wall-clock typed in
 * by a person, organization zone) or D (calendar date), so a NEW temporal field cannot be added without deciding which clock
 * and which display rule it follows. Budget 0: a failure lists the missing lines in registry format.
 */
class TimeFieldClassificationTripwireTest :
    FunSpec({
        val allowed = setOf("A", "B", "D", "A?", "B?", "D?")
        val declared = TimeFieldRegistry.declaredInSources()
        val registry = TimeFieldRegistry.registry()
        val byQualifier = registry.associateBy { it.qualifier }

        test("TF1: the scan finds the fields (guard against a regex that silently matches nothing)") {
            (declared.size > 700) shouldBe true
            declared.map { it.qualifier } shouldContain "dto:EventDto.startsAt"
            declared.map { it.qualifier } shouldContain "col:PollTable.closesAt"
        }

        test("TF2: every temporal field is classified") {
            val missing = declared.map { it.qualifier }.filter { it !in byQualifier }.distinct()
            withClue(
                "Unclassified temporal fields -- add 'qualifier<TAB>class<TAB>reason' lines to time-fields.tsv:\n${missing.joinToString(
                    "\n",
                )}",
            ) {
                missing.shouldBeEmpty()
            }
        }

        test("TF3: no orphan registry entry") {
            val known = declared.map { it.qualifier }.toSet()
            val orphans = registry.map { it.qualifier }.filter { it !in known }
            withClue(
                "Registry entries without a declaration (renamed/removed field?):\n${orphans.joinToString("\n")}",
            ) { orphans.shouldBeEmpty() }
        }

        test("TF4: classes are valid, reasons are not empty, qualifiers are unique") {
            registry.filter { it.cls !in allowed }.map { it.qualifier }.shouldBeEmpty()
            registry.filter { it.reason.isBlank() }.map { it.qualifier }.shouldBeEmpty()
            registry
                .map { it.qualifier }
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .shouldBeEmpty()
        }

        test("TF5: LocalDate is class D, LocalTime is class B, LocalDateTime is A or B") {
            val wrong =
                declared.mapNotNull { d ->
                    val cls = byQualifier[d.qualifier]?.cls?.trimEnd('?') ?: return@mapNotNull null
                    val ok =
                        when (d.type) {
                            "LocalDate" -> cls == "D"
                            "LocalTime" -> cls == "B"
                            else -> cls == "A" || cls == "B"
                        }
                    if (ok) null else "${d.qualifier} is ${d.type} but classified $cls"
                }
            wrong.shouldBeEmpty()
        }

        test("TF6 (self-test): the DTO scanner attributes fields to the nearest class and skips comments") {
            val text =
                """
                /** val ignored: LocalDateTime */
                data class A(
                    val x: LocalDateTime,
                    val y: LocalDate?,
                    val z: kotlinx.datetime.LocalTime,
                )
                // val alsoIgnored: LocalDate
                class B(val w: LocalDateTime?)
                """.trimIndent()
            TimeFieldRegistry.scanDtoFields(text).map { it.qualifier to it.type } shouldBe
                listOf(
                    "dto:A.x" to "LocalDateTime",
                    "dto:A.y" to "LocalDate",
                    "dto:A.z" to "LocalTime",
                    "dto:B.w" to "LocalDateTime",
                )
        }

        test("TF7 (self-test): the column scanner reads typed and untyped Exposed columns") {
            val text =
                """
                object FooTable : Table("foo") {
                    val a: Column<LocalDateTime> = datetime("a")
                    val b = date("b")
                    val c: Column<LocalTime?> = time("c").nullable()
                }
                """.trimIndent()
            TimeFieldRegistry.scanColumns(text).map { it.qualifier to it.type } shouldBe
                listOf("col:FooTable.a" to "LocalDateTime", "col:FooTable.b" to "LocalDate", "col:FooTable.c" to "LocalTime")
        }
    })
