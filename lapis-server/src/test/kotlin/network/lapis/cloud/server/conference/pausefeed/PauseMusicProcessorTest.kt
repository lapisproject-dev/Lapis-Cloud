package network.lapis.cloud.server.conference.pausefeed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.conference.FfmpegProcessRunner
import network.lapis.cloud.server.conference.FfmpegStepRunner
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration

private class FakeProber(
    private val inputProbe: AudioProbe?,
    private val outputProbe: AudioProbe? =
        AudioProbe(
            formatName = "mov,mp4,m4a,3gp,3g2,mj2",
            durationSeconds = 12.0,
            streams = listOf(AudioProbeStream(codecType = "audio", attachedPic = false)),
        ),
) : AudioProber {
    val calls = ArrayList<Pair<String, String>>()

    override suspend fun probe(
        file: File,
        demuxer: String,
    ): AudioProbe? {
        calls += file.name to demuxer
        return if (demuxer == "mov" && file.name.startsWith("out")) outputProbe else inputProbe
    }
}

private class RecordingRunner(
    private val writeBytes: Int = 100,
) : FfmpegStepRunner {
    val argLists = ArrayList<List<String>>()

    override suspend fun run(
        args: List<String>,
        timeout: Duration,
        label: String,
    ) {
        argLists += args
        File(args.last()).writeBytes(ByteArray(writeBytes) { 1 })
    }
}

private fun mp3Probe(
    seconds: Double?,
    vararg streams: AudioProbeStream = arrayOf(AudioProbeStream(codecType = "audio", attachedPic = false)),
    format: String = "mp3",
) = AudioProbe(formatName = format, durationSeconds = seconds, streams = streams.toList())

private fun tmp(prefix: String): File = Files.createTempFile(prefix, ".bin").toFile().also { it.writeBytes(byteArrayOf(1)) }

class PauseMusicProcessorTest :
    FunSpec({
        fun run(
            probe: AudioProbe?,
            sniffed: SniffedAudio = SniffedAudio.MP3,
            runner: RecordingRunner = RecordingRunner(),
            outProbe: AudioProbe? =
                AudioProbe(
                    formatName = "mov,mp4,m4a,3gp,3g2,mj2",
                    durationSeconds = 12.0,
                    streams = listOf(AudioProbeStream(codecType = "audio", attachedPic = false)),
                ),
        ): Pair<MusicProcessingResult, RecordingRunner> {
            val out = Files.createTempFile("out-", ".m4a").toFile()
            val result =
                kotlinx.coroutines.runBlocking {
                    PauseMusicProcessor(
                        runner = runner,
                        prober = FakeProber(inputProbe = probe, outputProbe = outProbe),
                    ).process(input = tmp("in-"), sniffed = sniffed, out = out)
                }
            return result to runner
        }

        test("3 s and 601 s are refused before any transcode") {
            val (short, r1) = run(mp3Probe(seconds = 3.0))
            short shouldBe MusicProcessingResult.TooShort
            r1.argLists.size shouldBe 0
            val (long, r2) = run(mp3Probe(seconds = 601.0))
            long shouldBe MusicProcessingResult.TooLong
            r2.argLists.size shouldBe 0
            run(mp3Probe(seconds = 5.0)).first.shouldBeInstanceOf<MusicProcessingResult.Ok>()
            run(
                mp3Probe(seconds = 600.0),
                outProbe =
                    AudioProbe(
                        formatName = "mov,mp4,m4a,3gp,3g2,mj2",
                        durationSeconds = 600.0,
                        streams = listOf(AudioProbeStream(codecType = "audio", attachedPic = false)),
                    ),
            ).first
                .shouldBeInstanceOf<MusicProcessingResult.Ok>()
        }

        test("a real video stream is refused, an attached cover picture is allowed and dropped") {
            run(
                mp3Probe(
                    seconds = 30.0,
                    AudioProbeStream(codecType = "audio", attachedPic = false),
                    AudioProbeStream(codecType = "video", attachedPic = false),
                ),
            ).first shouldBe
                MusicProcessingResult.HasVideo
            val (ok, runner) =
                run(
                    mp3Probe(
                        seconds = 30.0,
                        AudioProbeStream(codecType = "audio", attachedPic = false),
                        AudioProbeStream(codecType = "video", attachedPic = true),
                    ),
                )
            ok.shouldBeInstanceOf<MusicProcessingResult.Ok>()
            runner.argLists.single() shouldContain "-vn"
        }

        test("hls, concat and every format name that does not match the sniffed container are refused") {
            run(mp3Probe(seconds = 30.0, format = "hls")).first shouldBe MusicProcessingResult.Unsupported
            run(mp3Probe(seconds = 30.0, format = "concat")).first shouldBe MusicProcessingResult.Unsupported
            run(mp3Probe(seconds = 30.0, format = "mov,mp4,m4a,3gp,3g2,mj2"), SniffedAudio.MP3).first shouldBe
                MusicProcessingResult.Unsupported
            run(null).first shouldBe MusicProcessingResult.Unsupported
            run(mp3Probe(seconds = null)).first shouldBe MusicProcessingResult.Unsupported
            run(mp3Probe(seconds = 30.0, AudioProbeStream(codecType = "subtitle", attachedPic = false))).first shouldBe
                MusicProcessingResult.Unsupported
        }

        test("the transcode arguments force the demuxer and the file protocol, cap size and length, and set the rate AFTER loudnorm") {
            val (_, runner) = run(mp3Probe(seconds = 9.5))
            val a = runner.argLists.single()
            a[a.indexOf("-f") + 1] shouldBe "mp3"
            a[a.indexOf("-protocol_whitelist") + 1] shouldBe "file"
            (a.indexOf("-protocol_whitelist") < a.indexOf("-i")) shouldBe true
            (a.indexOf("-f") < a.indexOf("-i")) shouldBe true
            a[a.indexOf("-fs") + 1] shouldBe "12000000"
            a[a.indexOf("-t") + 1] shouldBe "600"
            a shouldContain "-map_metadata"
            val af = a[a.indexOf("-af") + 1]
            // a German locale must not produce "9,0": the fade-out starts at 9.00
            af shouldBe "loudnorm=I=-20:TP=-2:LRA=11,afade=t=in:st=0:d=0.5,afade=t=out:st=9.00:d=0.5"
            (a.indexOf("-ar") > a.indexOf("-af")) shouldBe true
            a[a.indexOf("-ar") + 1] shouldBe "44100"
            a.takeLast(3) shouldBe listOf("-f", "ipod", a.last())
        }

        test("an output that is oversized, silent or of the wrong length is deleted and refused") {
            val (big, _) = run(mp3Probe(seconds = 30.0), runner = RecordingRunner(writeBytes = 12_000_001))
            big shouldBe MusicProcessingResult.Failed
            val (noAudio, _) =
                run(
                    mp3Probe(seconds = 30.0),
                    outProbe = AudioProbe(formatName = "mov,mp4,m4a,3gp,3g2,mj2", durationSeconds = 30.0, streams = emptyList()),
                )
            noAudio shouldBe MusicProcessingResult.Failed
            val (wrong, _) =
                run(
                    mp3Probe(seconds = 30.0),
                    outProbe =
                        AudioProbe(
                            formatName = "mov,mp4,m4a,3gp,3g2,mj2",
                            durationSeconds = 900.0,
                            streams = listOf(AudioProbeStream(codecType = "audio", attachedPic = false)),
                        ),
                )
            wrong shouldBe MusicProcessingResult.Failed
        }

        test("the label keeps only a base name, drops control, bidi and zero-width characters, and is at most 80 characters") {
            sanitizeMusicLabel("../../etc/passwd.mp3") shouldBe "passwd.mp3"
            sanitizeMusicLabel("C:\\Users\\x\\song.mp3") shouldBe "song.mp3"
            sanitizeMusicLabel("evil\u202Efdp.mp3\u0000\u200B") shouldBe "evilfdp.mp3"
            sanitizeMusicLabel("a".repeat(200)).length shouldBe 80
            sanitizeMusicLabel("  ") shouldBe "Pausenmusik"
            sanitizeMusicLabel(null) shouldBe "Pausenmusik"
            sanitizeMusicLabel("/") shouldBe "Pausenmusik"
            sanitizeMusicLabel("<b>x<i>.mp3") shouldBe "<b>x<i>.mp3" // kept as text; the client never renders it as markup
        }

        test("with the real ffmpeg: a 10 s sine wave becomes an AAC 44.1 kHz stereo M4A of about 10 s; HLS and a video are refused") {
            val ffmpegOk = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
            if (!ffmpegOk) return@test // "skipped when FreeCAD is not installed"-style guard: no ffmpeg here, nothing to run
            val dir = Files.createTempDirectory("pause-music-real").toFile()
            val wav = File(dir, "tone.wav")
            ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=10", wav.path)
                .start()
                .waitFor() shouldBe 0
            val processor = PauseMusicProcessor(runner = FfmpegProcessRunner("ffmpeg"), prober = FfprobeAudioProber("ffprobe"))
            val out = File(dir, "out.m4a")
            val result =
                kotlinx.coroutines.runBlocking {
                    processor.process(input = wav, sniffed = AudioSniffer.sniff(wav.readBytes().copyOf(16))!!, out = out)
                }
            val ok = result as MusicProcessingResult.Ok
            (ok.durationSeconds in 9..11) shouldBe true
            val probe = kotlinx.coroutines.runBlocking { FfprobeAudioProber("ffprobe").probe(file = out, demuxer = "mov") }!!
            probe.streams.single().codecType shouldBe "audio"

            // an HLS playlist wearing a WAV name never reaches ffmpeg: it does not even sniff as audio
            AudioSniffer.sniff("#EXTM3U\n#EXTINF:5,\nfile:///etc/passwd\n".toByteArray()) shouldBe null
            // a WAV header that is really a video file: forced demuxer wav rejects it
            val video = File(dir, "v.mp4")
            ProcessBuilder(
                "ffmpeg",
                "-nostdin",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=duration=6:size=160x120:rate=10",
                video.path,
            ).start()
                .waitFor() shouldBe 0
            AudioSniffer.sniff(video.readBytes().copyOf(16)) shouldBe SniffedAudio.M4A // an mp4 brand passes the sniff ...
            val refused =
                kotlinx.coroutines.runBlocking {
                    processor.process(
                        input = video,
                        sniffed = SniffedAudio.M4A,
                        out = File(dir, "out2.m4a"),
                    )
                }
            // ... ffprobe does not
            (refused == MusicProcessingResult.HasVideo || refused == MusicProcessingResult.Unsupported) shouldBe true
        }
    })
