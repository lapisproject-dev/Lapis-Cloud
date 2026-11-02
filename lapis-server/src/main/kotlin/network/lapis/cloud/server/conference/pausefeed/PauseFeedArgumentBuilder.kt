package network.lapis.cloud.server.conference.pausefeed

import java.io.File

/**
 * The ffmpeg argument list of a pause feed, plus what must never reach a log: the plaintext target URLs and their stream keys.
 *
 * [toString] and [sanitize] never show a URL or a key. Everything ffmpeg writes (stderr) goes through [sanitize] before it is logged.
 */
internal class PauseFeedCommand(
    val args: List<String>,
    private val targetUrls: List<String>,
) {
    private val keys: List<String> = targetUrls.map { it.substringAfterLast('/') }.filter { it.length >= MIN_KEY_REPLACE_LENGTH }

    /** Replaces every plaintext URL and key, then any `rtmp(s)://...` that is left (ffmpeg may print a URL we do not know). */
    fun sanitize(line: String): String {
        var out = line
        targetUrls.forEach { out = out.replace(it, "[target]") }
        keys.forEach { out = out.replace(it, "[redacted-key]") }
        return RTMP_URL.replace(out, "rtmp://[redacted]")
    }

    override fun toString(): String = "PauseFeedCommand(args=${args.size}, targets=${targetUrls.size})"

    private companion object {
        const val MIN_KEY_REPLACE_LENGTH = 4
        val RTMP_URL = Regex("""rtmps?://\S+""", RegexOption.IGNORE_CASE)
    }
}

/**
 * V1.9.255 -- builds the ffmpeg command: ONE still picture (looped), the music looped (or silence), one H.264 + AAC encode, sent to one or
 * several RTMP(S) targets. The arguments are a list handed to `ProcessBuilder`, never a shell string.
 *
 *  - Every file input restricts the protocols to `file` (`-protocol_whitelist file`), so a crafted container can never make ffmpeg open a
 *    network location.
 *  - `-progress pipe:1` is only a sign of life for the watchdog -- it is NOT an orphan guard (ffmpeg ignores SIGPIPE; measured: it keeps
 *    sending after the reader is gone).
 *  - `-t` is the hard upper bound of one feed's life.
 *  - With several targets the `tee` muxer is used with `onfail=ignore`, so one broken target does not end the feed for the others.
 *  - rtmps targets get `tls_verify=1` and the CA file; the caller only passes rtmps targets when that file exists.
 */
internal object PauseFeedArgumentBuilder {
    const val HARD_CAP_SECONDS = 14_400

    fun build(
        imagePng: File,
        musicM4a: File?,
        targetUrls: List<String>,
        tlsVerifyArgs: List<String>,
    ): PauseFeedCommand {
        require(targetUrls.isNotEmpty()) { "a pause feed needs at least one target" }
        val tls = parseTls(tlsVerifyArgs)
        val args = ArrayList<String>()
        args += listOf("-nostdin", "-hide_banner", "-loglevel", "warning", "-progress", "pipe:1", "-stats_period", "2")
        // Input 0: the picture, a looped still at 15 fps (platforms want a moving frame rate and a key frame every two seconds).
        args += listOf("-re", "-loop", "1", "-framerate", "15", "-f", "image2", "-protocol_whitelist", "file", "-i", imagePng.path)
        // Input 1: the music (endless loop) or silence.
        if (musicM4a != null) {
            args += listOf("-re", "-stream_loop", "-1", "-f", "mov", "-protocol_whitelist", "file", "-i", musicM4a.path)
        } else {
            args += listOf("-re", "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo")
        }
        args += listOf("-map", "0:v:0", "-map", "1:a:0")
        args +=
            listOf(
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-tune",
                "stillimage",
                "-pix_fmt",
                "yuv420p",
                "-profile:v",
                "main",
                "-g",
                "30",
                "-keyint_min",
                "30",
                "-sc_threshold",
                "0",
                "-b:v",
                "800k",
                "-maxrate",
                "800k",
                "-bufsize",
                "1600k",
                "-c:a",
                "aac",
                "-b:a",
                "128k",
                "-ar",
                "44100",
                "-ac",
                "2",
                "-t",
                HARD_CAP_SECONDS.toString(),
                "-flvflags",
                "no_duration_filesize",
            )
        if (targetUrls.size == 1) {
            val url = targetUrls.single()
            if (url.startsWith("rtmps://", ignoreCase = true)) args += tls.outputArgs
            args += listOf("-f", "flv", url)
        } else {
            val slaves =
                targetUrls.joinToString("|") { url ->
                    val extra = if (url.startsWith("rtmps://", ignoreCase = true)) tls.teeOptions else ""
                    "[f=flv:onfail=ignore$extra]$url"
                }
            args += listOf("-f", "tee", slaves)
        }
        return PauseFeedCommand(args = args, targetUrls = targetUrls)
    }

    private class Tls(
        val outputArgs: List<String>,
        val teeOptions: String,
    )

    private fun parseTls(tlsVerifyArgs: List<String>): Tls {
        if (tlsVerifyArgs.isEmpty()) return Tls(outputArgs = emptyList(), teeOptions = "")
        require(
            tlsVerifyArgs.size == 4 && tlsVerifyArgs[0] == "-tls_verify" && tlsVerifyArgs[2] == "-ca_file",
        ) { "unexpected TLS arguments" }
        val verify = tlsVerifyArgs[1]
        val caFile = tlsVerifyArgs[3]
        require(verify == "1") { "TLS verification must stay on" }
        // The CA path is part of the tee option string: refuse its syntax characters.
        require(caFile.none { it in ":|[]\\'" || it.isWhitespace() }) { "CA file path contains characters that are tee syntax" }
        return Tls(outputArgs = tlsVerifyArgs, teeOptions = ":tls_verify=1:ca_file=$caFile")
    }
}
