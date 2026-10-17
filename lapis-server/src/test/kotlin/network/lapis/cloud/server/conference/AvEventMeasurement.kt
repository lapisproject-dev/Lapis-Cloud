package network.lapis.cloud.server.conference

import java.io.File

/** V1.9.154 -- the flash/beep measurement shared by the Participant integration tests (moved out of [RecordingParticipantSyncIntegrationTest]). */
internal object AvEventMeasurement {
    /** A flash frame's mean luma is at least this far above BOTH neighbours. */
    private const val SPIKE_LUMA = 8.0

    /** Times of the white flash frames (`signalstats` YAVG spike). */
    fun flashes(
        ffmpeg: String,
        video: File,
        workDir: File,
        totalSeconds: Double,
    ): List<Double> {
        val stats = File(workDir, "${video.nameWithoutExtension}-yavg.txt")
        FfmpegForTests.run(
            ffmpeg = ffmpeg,
            args =
                listOf(
                    "-nostats",
                    "-hide_banner",
                    "-i",
                    video.absolutePath,
                    "-vf",
                    "signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=${stats.absolutePath}",
                    "-an",
                    "-f",
                    "null",
                    "-",
                ),
            timeoutSeconds = 600 + totalSeconds.toLong(),
        )
        val times = ArrayList<Double>()
        val luma = ArrayList<Double>()
        var pendingTime: Double? = null
        stats.forEachLine { line ->
            Regex("pts_time:([0-9.]+)").find(line)?.let { pendingTime = it.groupValues[1].toDouble() }
            Regex("YAVG=([0-9.]+)").find(line)?.let {
                times += pendingTime ?: 0.0
                luma += it.groupValues[1].toDouble()
            }
        }
        val flashes = ArrayList<Double>()
        for (i in 1 until luma.size - 1) {
            if (luma[i] - maxOf(luma[i - 1], luma[i + 1]) > SPIKE_LUMA) flashes += times[i]
        }
        return flashes
    }

    /** Times of the ends of the silences (= the ends of the beeps) before the last 0.25 s (`silencedetect`). */
    fun beeps(
        ffmpeg: String,
        video: File,
        totalSeconds: Double,
    ): List<Double> {
        val out =
            FfmpegForTests.run(
                ffmpeg = ffmpeg,
                args =
                    listOf(
                        "-nostats",
                        "-hide_banner",
                        "-i",
                        video.absolutePath,
                        "-vn",
                        "-af",
                        "silencedetect=n=-35dB:d=0.02",
                        "-f",
                        "null",
                        "-",
                    ),
                timeoutSeconds = 600 + totalSeconds.toLong(),
            )
        return Regex("silence_end: ([0-9.]+)")
            .findAll(out)
            .map { it.groupValues[1].toDouble() }
            .filter { it < totalSeconds - 0.25 }
            .toList()
    }
}
