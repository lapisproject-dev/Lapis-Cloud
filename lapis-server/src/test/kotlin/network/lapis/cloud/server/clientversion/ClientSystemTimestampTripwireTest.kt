package network.lapis.cloud.server.clientversion

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V1.9.38 -- class A (system timestamp, UTC) and class B (wall-clock typed in) take different display paths, and a field must take
 * the one its class in `time-fields.tsv` says:
 *  - a class-A field shown through the PLAIN formatters (`formatDateTime`, `dateTimeSpan`, `dateTimeColumn`, `dateTimeToken`, ...)
 *    would show UTC to a member in Germany -> must use the `formatSystem*`/`systemDateTime*`/`systemTimestamp*` variant;
 *  - a class-B field shown through a `system*` variant would be shifted by the zone offset a second time -> must use the plain one.
 *
 * The check works on the bare field NAME (the client reads DTO properties as `x.<name>`), so a name that is class A in one DTO and
 * class B in another (`endsAt`, `closedAt`, `occurredAt`) cannot be decided by name alone; those are decided per file in
 * [AMBIGUOUS_DECISIONS], each with its reason. Names that mix only A with D (`startedAt`, `expiresAt`, ...) are decided by the
 * compiler: a plain `formatDateTime` call cannot take a `LocalDate`, so such a name in that call is the `LocalDateTime` one.
 * Budget 0.
 */
private val CLIENT_MAIN =
    File("../lapis-client/src/jsMain/kotlin").let { if (it.exists()) it else File("lapis-client/src/jsMain/kotlin") }

private val PLAIN_FAMILY =
    "formatDateTime|formatTimestamp|dateTimeSpan|timestampSpan|dateTimeColumn|timestampColumn|dateTimeToken|timestampToken"
private val SYSTEM_FAMILY =
    "formatSystemDateTime|formatSystemTimestamp|formatSystemDateTimeWithZone|systemDateTimeSpan|systemTimestampSpan|" +
        "systemDateTimeColumn|systemTimestampColumn|systemDateTimeToken|systemTimestampToken"

private val CALL = Regex("""\b($PLAIN_FAMILY|$SYSTEM_FAMILY)\s*(?:<[^>]*>)?\s*\(""")

/** `File.kt:field` -> the class that call site must follow. Reason in the comment. */
private val AMBIGUOUS_DECISIONS: Map<String, String> =
    mapOf(
        // AuctionDto.endsAt = now + duration, stamped by the server (A); Event/EventVolunteerShift endsAt are typed in (B).
        "AuctionCard.kt:endsAt" to "A",
        // CrmInteractionDto.occurredAt is typed in (B); every other DTO's occurredAt is a server stamp (A).
        "CrmContactsScreen.kt:occurredAt" to "B",
        "AuditLogScreen.kt:occurredAt" to "A",
        "DsgvoRightsScreen.kt:occurredAt" to "A",
        "WebhookDeliveryLogPanel.kt:occurredAt" to "A",
    )

private fun codeOf(file: File): String =
    file
        .readLines()
        .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
        .joinToString("\n")

/** The text of the call's parenthesised arguments plus an immediately following trailing lambda (`dateTimeColumn(title = ..) { it.createdAt }`). */
private fun callBody(
    text: String,
    openParen: Int,
): String {
    var depth = 0
    var i = openParen
    var inString = false
    while (i < text.length) {
        val c = text[i]
        when {
            inString ->
                if (c == '\\') {
                    i++
                } else if (c == '"') {
                    inString = false
                }
            c == '"' -> inString = true
            c == '(' -> depth++
            c == ')' -> {
                depth--
                if (depth == 0) break
            }
        }
        i++
    }
    val args = text.substring(openParen, minOf(i + 1, text.length))
    var j = i + 1
    while (j < text.length && text[j].isWhitespace()) j++
    if (j >= text.length || text[j] != '{') return args
    var braces = 0
    var k = j
    inString = false
    while (k < text.length) {
        val c = text[k]
        when {
            inString ->
                if (c == '\\') {
                    k++
                } else if (c == '"') {
                    inString = false
                }
            c == '"' -> inString = true
            c == '{' -> braces++
            c == '}' -> {
                braces--
                if (braces == 0) break
            }
        }
        k++
    }
    return args + text.substring(j, minOf(k + 1, text.length))
}

private val FIELD_ACCESS = Regex("""\.(\w+)""")

private data class Finding(
    val file: String,
    val family: String,
    val field: String,
)

private fun scan(
    file: File,
    text: String,
    classA: Set<String>,
    classB: Set<String>,
    ambiguous: Set<String>,
    decisions: Map<String, String> = AMBIGUOUS_DECISIONS,
): List<Finding> {
    val out = mutableListOf<Finding>()
    for (m in CALL.findAll(text)) {
        val fn = m.groupValues[1]
        val isSystem = Regex(SYSTEM_FAMILY).matches(fn)
        val body = callBody(text = text, openParen = m.range.last)
        for (f in FIELD_ACCESS.findAll(body).map { it.groupValues[1] }.toSet()) {
            val required =
                when {
                    f in ambiguous -> decisions["${file.name}:$f"] ?: continue
                    f in classA -> "A"
                    f in classB -> "B"
                    else -> continue
                }
            if (required == "A" && !isSystem) out += Finding(file = file.name, family = fn, field = f)
            if (required == "B" && isSystem) out += Finding(file = file.name, family = fn, field = f)
        }
    }
    return out
}

class ClientSystemTimestampTripwireTest :
    FunSpec({
        val registry = TimeFieldRegistry.registry().filter { it.qualifier.startsWith("dto:") }
        val classesByName =
            registry
                .groupBy(
                    { TimeFieldRegistry.fieldNameOf(it.qualifier) },
                ) { it.cls.trimEnd('?') }
                .mapValues { it.value.toSet() }
        // A with D can only be the LocalDateTime one in a datetime formatter (the compiler rejects a LocalDate); B with D likewise.
        val abAmbiguous = classesByName.filterValues { "A" in it && "B" in it }.keys
        val classA = classesByName.filterValues { "A" in it && "B" !in it }.keys
        val classB = classesByName.filterValues { "B" in it && "A" !in it }.keys

        test("CS1: the ambiguous A/B names are exactly the ones this test decides per file") {
            abAmbiguous shouldBe setOf("endsAt", "closedAt", "occurredAt")
        }

        test("CS2: no class-A field goes through a plain formatter, no class-B field through a system formatter") {
            val files =
                CLIENT_MAIN.walkTopDown().filter {
                    it.isFile &&
                        it.extension == "kt" &&
                        it.name !in setOf("DateTime.kt", "OrganizationTime.kt")
                }
            val findings =
                files
                    .flatMap {
                        scan(
                            file = it,
                            text = codeOf(it),
                            classA = classA,
                            classB = classB,
                            ambiguous = abAmbiguous,
                        )
                    }.toList()
            withClue(
                "A field must follow its class (time-fields.tsv):\n" +
                    findings.joinToString("\n") { "${it.file}: ${it.family}(... ${it.field} ...)" },
            ) { findings.shouldBeEmpty() }
        }

        test("CS3: the scan is not vacuous -- it sees the formatter calls of the client") {
            val calls = CLIENT_MAIN.walkTopDown().filter { it.isFile && it.extension == "kt" }.sumOf { CALL.findAll(codeOf(it)).count() }
            (calls > 50) shouldBe true
        }

        test("CS4 (self-test): a plain formatter on an A field and a system formatter on a B field are both found") {
            val file = File("Sample.kt")
            scan(
                file = file,
                text = "x.div(formatDateTime(entry.createdAt))",
                classA = setOf("createdAt"),
                classB = setOf("startsAt"),
                ambiguous = emptySet(),
            ) shouldBe
                listOf(Finding(file = "Sample.kt", family = "formatDateTime", field = "createdAt"))
            scan(
                file = file,
                text = "x.div(formatSystemDateTime(event.startsAt))",
                classA = setOf("createdAt"),
                classB = setOf("startsAt"),
                ambiguous = emptySet(),
            ) shouldBe
                listOf(Finding(file = "Sample.kt", family = "formatSystemDateTime", field = "startsAt"))
            scan(
                file = file,
                text = "dateTimeColumn<R>(title = t) { it.createdAt }",
                classA = setOf("createdAt"),
                classB = emptySet(),
                ambiguous = emptySet(),
            ) shouldBe
                listOf(Finding(file = "Sample.kt", family = "dateTimeColumn", field = "createdAt"))
            scan(
                file = file,
                text = "x.div(formatSystemDateTime(entry.createdAt))",
                classA = setOf("createdAt"),
                classB = setOf("startsAt"),
                ambiguous = emptySet(),
            ) shouldBe
                emptyList()
            scan(
                file = file,
                text = "x.div(formatDateTime(event.startsAt))",
                classA = setOf("createdAt"),
                classB = setOf("startsAt"),
                ambiguous = emptySet(),
            ) shouldBe
                emptyList()
        }

        test("CS5 (self-test): an ambiguous name is decided by the file ledger; without a decision it is not guessed") {
            val file = File("AuctionCard.kt")
            scan(
                file = file,
                text = "formatDateTime(auction.endsAt)",
                classA = emptySet(),
                classB = emptySet(),
                ambiguous = setOf("endsAt"),
            ) shouldBe
                listOf(Finding(file = "AuctionCard.kt", family = "formatDateTime", field = "endsAt"))
            scan(
                file = File("Other.kt"),
                text = "formatDateTime(auction.endsAt)",
                classA = emptySet(),
                classB = emptySet(),
                ambiguous = setOf("endsAt"),
            ) shouldBe
                emptyList()
        }
    })
