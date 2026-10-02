package network.lapis.cloud.server.clientversion

import java.io.File

/**
 * V1.9.38 -- the registry of every temporal field (`lapis-server/src/test/resources/time-fields.tsv`) and the scanners that
 * derive the field list from the sources. Shared by [TimeFieldClassificationTripwireTest] and
 * [ClientSystemTimestampTripwireTest]. Sources are read as text (the same technique as the other tripwires in this package);
 * Gradle runs server tests with `lapis-server` as the working directory.
 */
internal object TimeFieldRegistry {
    private val SHARED = File("../lapis-shared/src/commonMain").let { if (it.exists()) it else File("lapis-shared/src/commonMain") }
    private val SERVER_MAIN =
        File("src/main/kotlin").let { if (it.exists()) it else File("lapis-server/src/main/kotlin") }
    private val REGISTRY =
        File("src/test/resources/time-fields.tsv").let {
            if (it.exists()) it else File("lapis-server/src/test/resources/time-fields.tsv")
        }

    /** One temporal declaration found in the sources: [qualifier] (`dto:Class.field` / `col:Table.column`) and its Kotlin [type]. */
    data class Declaration(
        val qualifier: String,
        val type: String,
    )

    data class Entry(
        val qualifier: String,
        val cls: String,
        val reason: String,
    )

    private val CLASS_DECL = Regex("""\b(?:data\s+|sealed\s+|internal\s+|enum\s+)*class\s+(\w+)\s*(?:\(|<|:|\{)""")
    private val FIELD_DECL = Regex("""\bva[lr]\s+(\w+)\s*:\s*(?:kotlinx\.datetime\.)?(LocalDateTime|LocalDate|LocalTime)\??""")
    private val TABLE_DECL = Regex("""\bobject\s+(\w+)\s*:\s*Table\(""")
    private val COLUMN_DECL =
        Regex("""\bva[lr]\s+(\w+)\s*(?::\s*Column<(LocalDateTime|LocalDate|LocalTime)\??>)?\s*=\s*(datetime|date|time)\(""")

    private fun isComment(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** DTO properties: a `val`/`var` of a temporal type, attributed to the nearest preceding class declaration. */
    fun scanDtoFields(text: String): List<Declaration> {
        var current: String? = null
        val out = mutableListOf<Declaration>()
        for (line in text.lines()) {
            if (isComment(line)) continue
            CLASS_DECL.find(line)?.let { current = it.groupValues[1] }
            for (m in FIELD_DECL.findAll(line)) out += Declaration(qualifier = "dto:$current.${m.groupValues[1]}", type = m.groupValues[2])
        }
        return out
    }

    /** Exposed columns: `datetime(`/`date(`/`time(` columns, attributed to the nearest preceding `object X : Table(`. */
    fun scanColumns(text: String): List<Declaration> {
        var current: String? = null
        val out = mutableListOf<Declaration>()
        for (line in text.lines()) {
            if (isComment(line)) continue
            TABLE_DECL.find(line)?.let { current = it.groupValues[1] }
            for (m in COLUMN_DECL.findAll(line)) {
                val type = mapOf("datetime" to "LocalDateTime", "date" to "LocalDate", "time" to "LocalTime").getValue(m.groupValues[3])
                out += Declaration(qualifier = "col:$current.${m.groupValues[1]}", type = type)
            }
        }
        return out
    }

    private fun kotlinFiles(root: File): List<File> =
        root
            .walkTopDown()
            .filter {
                it.isFile && it.extension == "kt"
            }.sortedBy { it.path }
            .toList()

    fun declaredInSources(): List<Declaration> =
        kotlinFiles(SHARED).flatMap { scanDtoFields(it.readText()) } + kotlinFiles(SERVER_MAIN).flatMap { scanColumns(it.readText()) }

    fun parseRegistry(text: String): List<Entry> =
        text
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split('\t')
                require(parts.size == 3) { "time-fields.tsv: expected 'qualifier<TAB>class<TAB>reason', got: $line" }
                Entry(qualifier = parts[0], cls = parts[1], reason = parts[2])
            }

    fun registry(): List<Entry> = parseRegistry(REGISTRY.readText())

    /** `dto:Class.field` -> the bare field name. */
    fun fieldNameOf(qualifier: String): String = qualifier.substringAfterLast('.')

    /** Field names that are unambiguously class [cls] (every registry entry with that bare name has that class, trailing `?` ignored). */
    fun uniqueFieldNames(
        cls: String,
        kind: String,
    ): Set<String> {
        val byName =
            registry()
                .filter { it.qualifier.startsWith("$kind:") }
                .groupBy({ fieldNameOf(it.qualifier) }) { it.cls.trimEnd('?') }
        return byName.filterValues { classes -> classes.all { it == cls } }.keys
    }
}
