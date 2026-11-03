package network.lapis.cloud.server.config

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Arrays
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * V1.9.266 -- thrown at startup when a `<NAME>_FILE` secret cannot be used. The message is `"<VAR>: <reason>"` ONLY: never a
 * path, never a value, never a cause (an `IOException` message carries the path). Callers must NOT catch this and treat it as
 * "not configured" -- a broken secret file has to stop the start (fail closed).
 */
class SecretFileException internal constructor(
    val variable: String,
    val reason: Reason,
) : RuntimeException("$variable: ${reason.text}") {
    enum class Reason(
        val text: String,
    ) {
        AMBIGUOUS("both the variable and its _FILE variant are set"),
        NOT_ABSOLUTE("path is not absolute"),
        INVALID_PATH("path is invalid"),
        MISSING("file does not exist or is not readable"),
        NOT_REGULAR("target is not a regular file"),
        EMPTY("file is empty"),
        TOO_LARGE("file exceeds 65536 bytes"),
        NOT_UTF8("file is not valid UTF-8"),
        BOM("file starts with a byte order mark"),
        NUL("file contains a NUL byte"),
        CHANGED("file changed while it was read"),
    }
}

/**
 * V1.9.266 -- the single gateway for EVERY environment read of the server. For the names in [SECRET_NAMES] a `<NAME>_FILE`
 * variable (path of a file holding the value, e.g. a Docker/Compose/Swarm/Kubernetes secret) is honoured as an alternative to
 * `<NAME>`. Rules: see `docs/architecture/secret-files.adoc`. Rotation = restart (no watcher, no reload).
 */
object SecretEnv {
    const val FILE_SUFFIX = "_FILE"
    const val MAX_SECRET_FILE_BYTES = 64 * 1024

    val SECRET_NAMES: Set<String> =
        setOf(
            "LAPIS_AI_API_KEY",
            "LAPIS_BOOTSTRAP_ADMIN_PASSWORD",
            "LAPIS_DB_PASSWORD",
            "LAPIS_KEYCLOAK_CLIENT_SECRET",
            "LAPIS_LETTERXPRESS_APIKEY",
            "LAPIS_LIVEKIT_API_KEY",
            "LAPIS_LIVEKIT_API_SECRET",
            "LAPIS_MAILING_TRACKING_KEY",
            "LAPIS_ORACLE_ALPHAVANTAGE_KEY",
            "LAPIS_ORACLE_GOLDAPI_KEY",
            "LAPIS_ORACLE_METALPRICEAPI_KEY",
            "LAPIS_PAYPAL_CLIENT_SECRET",
            "LAPIS_SECRET_ENCRYPTION_KEY",
            "LAPIS_SMTP_PASSWORD",
            "LAPIS_STAGING_SEED_PASSWORD",
            "LAPIS_STRIPE_SECRET_KEY",
            "LAPIS_STRIPE_WEBHOOK_SIGNING_SECRET",
            "LAPIS_TURN_SHARED_SECRET",
        )

    private val cache = ConcurrentHashMap<String, Optional<String>>()

    /** Production gateway for every environment read. Secrets: resolved once and cached. Other names: live `System.getenv`. */
    fun get(name: String): String? {
        if (name !in SECRET_NAMES) return System.getenv(name)
        return cache
            .computeIfAbsent(name) {
                Optional.ofNullable(resolve(name = name, raw = System::getenv))
            }.orElse(null)
    }

    /** Raw process environment, for consumers that need a whole map (no secret among them: DB session timeouts). */
    fun rawSnapshot(): Map<String, String> = System.getenv()

    /** Test/composition seam: the same rules over an arbitrary raw environment, with a per-instance memo. */
    fun resolving(raw: (String) -> String?): (String) -> String? {
        val memo = ConcurrentHashMap<String, Optional<String>>()
        return { name ->
            if (name !in SECRET_NAMES) {
                raw(name)
            } else {
                memo.computeIfAbsent(name) { Optional.ofNullable(resolve(name = name, raw = raw)) }.orElse(null)
            }
        }
    }

    /** Eagerly resolves all [SECRET_NAMES]; the first failure throws [SecretFileException]. First call in every `main()`. */
    fun preflight() {
        SECRET_NAMES.forEach { get(it) }
    }

    internal fun resetForTest() = cache.clear()

    internal fun resolve(
        name: String,
        raw: (String) -> String?,
        reader: SecretFileReader = SecretFileReader(),
    ): String? {
        val fileVarName = name + FILE_SUFFIX
        val fileVar = raw(fileVarName)?.takeUnless { it.isBlank() }
        val direct = raw(name)
        if (fileVar == null) return direct
        if (!direct.isNullOrBlank()) throw SecretFileException(variable = fileVarName, reason = SecretFileException.Reason.AMBIGUOUS)
        val value = reader.read(variable = fileVarName, rawPath = fileVar)
        logger.info { "Secret $name read from file ($fileVarName)" }
        return value
    }
}

/** Reads one secret file. Throws only [SecretFileException] (variable + reason, no path, no cause). */
internal class SecretFileReader(
    /** Test hook: runs after the file was opened and read, before the second attribute check. */
    private val afterReadHook: () -> Unit = {},
) {
    fun read(
        variable: String,
        rawPath: String,
    ): String {
        fun fail(reason: SecretFileException.Reason): Nothing = throw SecretFileException(variable = variable, reason = reason)

        val path: Path =
            try {
                Path.of(rawPath)
            } catch (_: InvalidPathException) {
                fail(SecretFileException.Reason.INVALID_PATH)
            }
        if (!path.isAbsolute) fail(SecretFileException.Reason.NOT_ABSOLUTE)

        val real: Path =
            try {
                path.toRealPath()
            } catch (_: IOException) {
                fail(SecretFileException.Reason.MISSING)
            } catch (_: SecurityException) {
                fail(SecretFileException.Reason.MISSING)
            }

        val pre: BasicFileAttributes =
            try {
                Files.readAttributes(real, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                fail(SecretFileException.Reason.MISSING)
            }
        if (!pre.isRegularFile) fail(SecretFileException.Reason.NOT_REGULAR)
        if (pre.size() > SecretEnv.MAX_SECRET_FILE_BYTES) fail(SecretFileException.Reason.TOO_LARGE)

        val bytes = ByteArray(SecretEnv.MAX_SECRET_FILE_BYTES + 1)
        var length = 0
        try {
            FileChannel.open(real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) {
                    if (ch.read(buf) < 0) break
                }
                length = buf.position()
            }
        } catch (_: IOException) {
            Arrays.fill(bytes, 0)
            fail(SecretFileException.Reason.MISSING)
        }
        try {
            afterReadHook()
            if (length > SecretEnv.MAX_SECRET_FILE_BYTES) fail(SecretFileException.Reason.TOO_LARGE)

            val post: BasicFileAttributes =
                try {
                    Files.readAttributes(real, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: IOException) {
                    fail(SecretFileException.Reason.CHANGED)
                }
            val keyChanged = pre.fileKey() != null && post.fileKey() != null && pre.fileKey() != post.fileKey()
            if (keyChanged || pre.lastModifiedTime() != post.lastModifiedTime() || post.size() != length.toLong()) {
                fail(SecretFileException.Reason.CHANGED)
            }

            if (length == 0) fail(SecretFileException.Reason.EMPTY)
            for (i in 0 until length) if (bytes[i] == 0.toByte()) fail(SecretFileException.Reason.NUL)
            if (length >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                fail(SecretFileException.Reason.BOM)
            }

            val chars =
                try {
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, length))
                } catch (_: CharacterCodingException) {
                    fail(SecretFileException.Reason.NOT_UTF8)
                }
            try {
                var text = chars.toString()
                text =
                    when {
                        text.endsWith("\r\n") -> text.dropLast(2)
                        text.endsWith("\n") -> text.dropLast(1)
                        else -> text
                    }
                if (text.isEmpty()) fail(SecretFileException.Reason.EMPTY)
                return text
            } finally {
                if (chars.hasArray()) Arrays.fill(chars.array(), '\u0000')
            }
        } finally {
            // Best effort only: the resulting String itself stays in memory (documented).
            Arrays.fill(bytes, 0)
        }
    }
}
