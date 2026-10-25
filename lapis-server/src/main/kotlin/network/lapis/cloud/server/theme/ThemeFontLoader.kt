package network.lapis.cloud.server.theme

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class LoadedFont(
    val fileName: String,
    val bytes: ByteArray,
    val sha256Hex: String,
) {
    val hash12: String get() = sha256Hex.take(12)
}

/**
 * The only code that reads font files, and only at startup: every file is read through NOFOLLOW, bounded, magic-checked and held
 * in memory; requests never touch the disk. Any problem drops ALL fonts (no partial success within the fonts). Does not log --
 * the CLI shares this code and must keep stdout clean.
 */
object ThemeFontLoader {
    data class Outcome(
        val fonts: Map<String, LoadedFont>,
        val findings: List<ThemeFinding>,
    )

    fun load(
        realThemeDir: Path,
        spec: ThemeSpec,
    ): Outcome {
        val names = listOfNotNull(spec.body, spec.display).flatMap { r -> r.files.map { it.file } }.distinct()
        if (names.isEmpty()) return Outcome(fonts = emptyMap(), findings = emptyList())
        val fonts = LinkedHashMap<String, LoadedFont>()
        var total = 0L
        try {
            for (name in names) {
                val path = "fonts:$name"
                if (!ThemeParser.isPlainFontFileName(
                        name,
                    )
                ) {
                    return dropped(code = ThemeErrorCode.THEME_FONT_PATH, path = path, message = "not a plain file name")
                }
                val file = realThemeDir.resolve(name)
                val attrs =
                    try {
                        Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: java.nio.file.NoSuchFileException) {
                        return dropped(
                            code = ThemeErrorCode.THEME_FONT_MISSING,
                            path = path,
                            message = "font file not found in the theme directory",
                        )
                    }
                if (attrs.isSymbolicLink) {
                    return dropped(
                        code = ThemeErrorCode.THEME_FONT_SYMLINK,
                        path = path,
                        message = "font files must not be symbolic links",
                    )
                }
                if (!attrs.isRegularFile) {
                    return dropped(
                        code = ThemeErrorCode.THEME_FONT_MISSING,
                        path = path,
                        message = "not a regular file",
                    )
                }
                if (!file.toRealPath().startsWith(realThemeDir)) {
                    return dropped(code = ThemeErrorCode.THEME_FONT_PATH, path = path, message = "resolves outside the theme directory")
                }
                val bytes =
                    readBounded(file = file, max = ThemeTokens.MAX_FONT_BYTES)
                        ?: return dropped(
                            code = ThemeErrorCode.THEME_FONT_TOO_LARGE,
                            path = path,
                            message = "larger than ${ThemeTokens.MAX_FONT_BYTES} bytes",
                        )
                if (bytes.size < 4 || !bytes.copyOfRange(0, 4).contentEquals(ThemeTokens.WOFF2_MAGIC)) {
                    return dropped(code = ThemeErrorCode.THEME_FONT_MAGIC, path = path, message = "not a WOFF2 file (magic bytes)")
                }
                total += bytes.size
                if (total > ThemeTokens.MAX_FONTS_TOTAL_BYTES) {
                    return dropped(
                        code = ThemeErrorCode.THEME_FONT_TOO_LARGE,
                        path = "fonts",
                        message = "fonts together exceed ${ThemeTokens.MAX_FONTS_TOTAL_BYTES} bytes",
                    )
                }
                fonts[name] = LoadedFont(fileName = name, bytes = bytes, sha256Hex = sha256Hex(bytes))
            }
            licenseFinding(realThemeDir)?.let { return Outcome(fonts = emptyMap(), findings = listOf(it)) }
        } catch (_: IOException) {
            return dropped(code = ThemeErrorCode.THEME_IO, path = "fonts", message = "font files could not be read")
        } catch (_: SecurityException) {
            return dropped(code = ThemeErrorCode.THEME_IO, path = "fonts", message = "font files could not be read")
        }
        return Outcome(fonts = fonts, findings = emptyList())
    }

    private fun licenseFinding(dir: Path): ThemeFinding? {
        var candidate: Path? = null
        Files.newDirectoryStream(dir).use { stream ->
            var count = 0
            for (entry in stream) {
                if (++count > ThemeTokens.MAX_DIRECTORY_ENTRIES) break
                if (ThemeTokens.LICENSE_REGEX.matches(entry.fileName.toString())) {
                    candidate = entry
                    break
                }
            }
        }
        val missing =
            fontFinding(
                code = ThemeErrorCode.THEME_FONT_LICENSE_MISSING,
                path = "license",
                message = "no licence file (LICENSE, OFL, COPYING) next to the fonts",
            )
        val file = candidate ?: return missing
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attrs.isSymbolicLink || !attrs.isRegularFile) return missing
        if (readBounded(file = file, max = ThemeTokens.MAX_LICENSE_BYTES) == null) {
            return fontFinding(
                code = ThemeErrorCode.THEME_FONT_LICENSE_MISSING,
                path = "license",
                message = "licence file larger than ${ThemeTokens.MAX_LICENSE_BYTES} bytes",
            )
        }
        return null
    }

    /** Reads at most [max] bytes; `null` when the file is longer. The length is taken from what was actually read (TOCTOU-safe). */
    internal fun readBounded(
        file: Path,
        max: Int,
    ): ByteArray? =
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(max + 1)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            if (buffer.position() > max) null else buffer.array().copyOf(buffer.position())
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fontFinding(
        code: ThemeErrorCode,
        path: String,
        message: String,
    ) = ThemeFinding(code = code, path = path, message = message, severity = Severity.ERROR, action = ThemeAction.FONTS_DROPPED)

    private fun dropped(
        code: ThemeErrorCode,
        path: String,
        message: String,
    ) = Outcome(fonts = emptyMap(), findings = listOf(fontFinding(code = code, path = path, message = message)))
}
