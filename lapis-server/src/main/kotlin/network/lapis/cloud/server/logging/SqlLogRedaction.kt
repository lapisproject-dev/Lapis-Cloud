package network.lapis.cloud.server.logging

import ch.qos.logback.classic.spi.IThrowableProxy
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Removes SQL statement text and bound/personal values from log output (V1.9.65).
 *
 * Why this exists: a failed statement produces an exception whose message carries the statement AND the offending
 * values (`Detail: Key (email)=(erika.mustermann@example.org) already exists`, Exposed's `SQL: [INSERT ...]`).
 * Kilua RPC logs every non-service exception with `LOG.error(e.getMessage(), e)` (dev.kilua.rpc.RpcServiceManager), and
 * about a hundred application call sites log `logger.warn(e) { ... }`. Both put message and cause chain into the log.
 * The redaction therefore sits in the Logback layout and covers every logger.
 *
 * What stays for diagnosis: SQLSTATE, the exception class names, the constraint and table name (schema names, never
 * values) and the full stack frames. What goes: every SQLException message in the cause chain, Exposed statement text,
 * the PostgreSQL `Detail:` line.
 *
 * All functions are pure and never throw; on an internal failure they fall back to a fixed marker, never to raw text.
 */
internal object SqlLogRedaction {
    const val REDACTED = "[sql error text redacted]"
    const val REDACTION_FAILED = "[redaction failed]"

    private const val MAX_DEPTH = 16
    private const val MIN_FRAGMENT_LENGTH = 8

    private val SQL_CLASS_PREFIXES =
        listOf("java.sql.", "org.jetbrains.exposed.", "org.postgresql.", "org.h2.", "org.flywaydb.")

    private val KEY_VALUE_MARKER = Regex("""Key \([^)]*\)=\(""")

    private val FIXED_MARKERS =
        listOf(
            "org.postgresql.util.PSQLException",
            "org.h2.jdbc.JdbcSQL",
            "ExposedSQLException",
            "Detail:",
            "SQL: [",
            "Statement(s):",
        )

    data class SqlFailureSummary(
        val sqlState: String?,
        val classes: List<String>,
        val constraint: String?,
        val table: String?,
    )

    fun isSqlBearing(t: Throwable): Boolean = t is SQLException || isSqlBearingClassName(t.javaClass.name)

    fun isSqlBearingClassName(className: String): Boolean = SQL_CLASS_PREFIXES.any { className.startsWith(it) }

    /** Walks cause, suppressed and [SQLException.nextException] links; bounded depth, identity cycle guard. */
    private fun walk(root: Throwable?): List<Throwable> {
        if (root == null) return emptyList()
        val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
        val out = ArrayList<Throwable>()

        fun visit(
            t: Throwable?,
            depth: Int,
        ) {
            if (t == null || depth > MAX_DEPTH || !seen.add(t)) return
            out.add(t)
            visit(t.cause, depth + 1)
            t.suppressed.forEach { visit(it, depth + 1) }
            if (t is SQLException) visit(t.nextException, depth + 1)
        }
        visit(root, 0)
        return out
    }

    fun summarize(t: Throwable?): SqlFailureSummary? =
        runCatching {
            val chain = walk(t)
            val sqlNodes = chain.filter { isSqlBearing(it) }
            if (sqlNodes.isEmpty()) return@runCatching null
            var constraint: String? = null
            var table: String? = null
            for (node in chain.filterIsInstance<PSQLException>()) {
                val sem: ServerErrorMessage? = node.serverErrorMessage
                if (sem != null) {
                    val c: String? = sem.constraint
                    val tb: String? = sem.table
                    if (constraint == null) constraint = c
                    if (table == null) table = tb
                }
            }
            var state: String? = null
            for (node in chain.filterIsInstance<SQLException>()) {
                val st: String? = node.sqlState
                if (state == null && st != null) state = st
            }
            SqlFailureSummary(
                sqlState = state,
                classes = sqlNodes.map { it.javaClass.name }.distinct(),
                constraint = constraint,
                table = table,
            )
        }.getOrNull()

    /**
     * Text fragments that must never appear in output: the message of every SQL-bearing exception in the chain, its
     * lines and PostgreSQL's detail text, plus the message of any wrapper that embeds one of them.
     */
    fun sensitiveFragments(t: Throwable?): Set<String> =
        runCatching {
            val chain = walk(t)
            val out = LinkedHashSet<String>()
            val sqlNodes = chain.filter { isSqlBearing(it) }
            for (node in sqlNodes) {
                node.message?.let { addFragment(out = out, fragment = it) }
                node.localizedMessage?.let { addFragment(out = out, fragment = it) }
                if (node is PSQLException) {
                    // Explicit types: the driver's annotated getters would otherwise infer a checker-framework
                    // annotation type that is not on this module's compile classpath.
                    val sem: ServerErrorMessage? = node.serverErrorMessage
                    if (sem != null) {
                        val detail: String? = sem.detail
                        val hint: String? = sem.hint
                        val text: String? = sem.message
                        addFragment(out = out, fragment = detail)
                        addFragment(out = out, fragment = hint)
                        addFragment(out = out, fragment = text)
                    }
                }
            }
            // Wrapper messages (RuntimeException(cause) renders cause.toString()) that contain a SQL fragment.
            val base = out.toList()
            for (node in chain) {
                val m = node.message ?: continue
                if (base.any { m.contains(it) }) addFragment(out = out, fragment = m)
            }
            out
        }.getOrDefault(emptySet())

    private fun addFragment(
        out: MutableSet<String>,
        fragment: String?,
    ) {
        if (fragment == null) return
        if (fragment.length >= MIN_FRAGMENT_LENGTH) out.add(fragment)
        // Multi-line messages: also each line on its own, so a re-wrapped or truncated copy is caught.
        fragment
            .lineSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_FRAGMENT_LENGTH }
            .forEach { out.add(it) }
    }

    fun redactMessage(
        message: String?,
        t: Throwable?,
    ): String? = runCatching { redactWithFragments(message = message, fragments = sensitiveFragments(t)) }.getOrDefault(REDACTION_FAILED)

    fun redactWithFragments(
        message: String?,
        fragments: Set<String>,
    ): String? {
        if (message == null) return null
        return runCatching {
            var text: String = message
            for (fragment in fragments.sortedByDescending { it.length }) {
                if (text.contains(fragment)) text = text.replace(fragment, REDACTED)
            }
            truncateAtMarker(text)
        }.getOrDefault(REDACTION_FAILED)
    }

    private fun truncateAtMarker(text: String): String {
        var cut = Int.MAX_VALUE
        for (marker in FIXED_MARKERS) {
            val i = text.indexOf(marker)
            if (i in 0 until cut) cut = i
        }
        KEY_VALUE_MARKER.find(text)?.let { if (it.range.first < cut) cut = it.range.first }
        val errorIdx = text.indexOf("ERROR: ")
        if (errorIdx in 0 until cut && (text.contains("Position:") || text.contains("Detail"))) cut = errorIdx
        return if (cut == Int.MAX_VALUE) text else text.substring(0, cut) + REDACTED
    }

    /** Header text replacing the message of a SQL-bearing exception line in a stack trace. */
    fun safeHeader(t: Throwable?): String {
        val summary = summarize(t)
        val details =
            listOfNotNull(
                ((t as? SQLException)?.sqlState ?: summary?.sqlState)?.let { "sqlState=$it" },
                summary?.constraint?.let { "constraint=$it" },
            )
        return if (details.isEmpty()) "[redacted]" else "[redacted] " + details.joinToString(separator = ", ")
    }

    /** True for a proxy whose class is SQL-bearing but whose real [Throwable] is unavailable (deserialised events). */
    fun isSqlBearingProxy(proxy: IThrowableProxy): Boolean = isSqlBearingClassName(proxy.className)
}
