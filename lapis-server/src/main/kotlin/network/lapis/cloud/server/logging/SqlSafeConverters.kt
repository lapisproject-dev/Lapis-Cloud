package network.lapis.cloud.server.logging

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.classic.spi.StackTraceElementProxy
import ch.qos.logback.classic.spi.ThrowableProxy

/** Fragments for an event's throwable: precise when the real [Throwable] is present, class-name based otherwise. */
private fun fragmentsOf(proxy: IThrowableProxy?): Set<String> {
    if (proxy == null) return emptySet()
    if (proxy is ThrowableProxy) proxy.throwable?.let { return SqlLogRedaction.sensitiveFragments(it) }
    val out = LinkedHashSet<String>()
    var node: IThrowableProxy? = proxy
    var depth = 0
    while (node != null && depth++ < 16) {
        val m = node.message
        if (m != null && m.length >= 8 && SqlLogRedaction.isSqlBearingProxy(node)) out.add(m)
        node = node.cause
    }
    return out
}

/** `%safeMsg`: the formatted message with SQL text and values removed. Never throws. */
class SqlSafeMessageConverter : MessageConverter() {
    override fun convert(event: ILoggingEvent): String =
        runCatching {
            SqlLogRedaction.redactWithFragments(message = event.formattedMessage, fragments = fragmentsOf(event.throwableProxy)).orEmpty()
        }.getOrDefault(SqlLogRedaction.REDACTION_FAILED)
}

/**
 * `%safeEx`: stack-trace rendering with the messages of SQL exceptions replaced by SQLSTATE/constraint. Being a
 * [ThrowableProxyConverter] (a ThrowableHandlingConverter) it also stops PatternLayout from appending its own raw `%ex`.
 */
class SqlSafeThrowableProxyConverter : ThrowableProxyConverter() {
    override fun throwableProxyToString(tp: IThrowableProxy): String =
        runCatching { super.throwableProxyToString(RedactingThrowableProxy(delegate = tp, fragments = fragmentsOf(tp))) }
            .getOrDefault(SqlLogRedaction.REDACTION_FAILED + System.lineSeparator())
}

/** View of a throwable proxy chain with SQL-bearing messages redacted; frames are passed through untouched. */
internal class RedactingThrowableProxy(
    private val delegate: IThrowableProxy,
    private val fragments: Set<String>,
) : IThrowableProxy {
    private val sqlBearing: Boolean = SqlLogRedaction.isSqlBearingProxy(delegate)
    private val real: Throwable? = (delegate as? ThrowableProxy)?.throwable

    private fun isSqlNode(): Boolean = sqlBearing || real?.let { SqlLogRedaction.isSqlBearing(it) } == true

    private fun safeMessage(): String? =
        runCatching {
            if (isSqlNode()) {
                SqlLogRedaction.safeHeader(real)
            } else {
                SqlLogRedaction.redactWithFragments(message = delegate.message, fragments = fragments)
            }
        }.getOrDefault(SqlLogRedaction.REDACTION_FAILED)

    override fun getMessage(): String? = safeMessage()

    // ThrowableProxyUtil prints this INSTEAD of "className: message" when it is non-null, so a SQL-bearing node must
    // supply the whole redacted header line itself.
    override fun getOverridingMessage(): String? {
        val original = delegate.overridingMessage ?: return null
        return runCatching {
            if (isSqlNode()) {
                delegate.className + ": " + SqlLogRedaction.safeHeader(real)
            } else {
                SqlLogRedaction.redactWithFragments(message = original, fragments = fragments)
            }
        }.getOrDefault(SqlLogRedaction.REDACTION_FAILED)
    }

    override fun getClassName(): String = delegate.className

    override fun getStackTraceElementProxyArray(): Array<StackTraceElementProxy> = delegate.stackTraceElementProxyArray

    override fun getCommonFrames(): Int = delegate.commonFrames

    override fun getCause(): IThrowableProxy? = delegate.cause?.let { RedactingThrowableProxy(delegate = it, fragments = fragments) }

    override fun getSuppressed(): Array<IThrowableProxy> =
        delegate.suppressed.map { RedactingThrowableProxy(delegate = it, fragments = fragments) }.toTypedArray()

    override fun isCyclic(): Boolean = delegate.isCyclic
}
