package network.lapis.cloud.server.conference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.util.Locale

/**
 * [FfmpegArgumentBuilder.build] -- pure, no ffmpeg binary or process ever involved (see that
 * object's own KDoc "the testability lever the Wave 2 plan's own §5 'Composition' calls out"). Runs
 * entirely against [java.io.File]s that need not exist -- the builder never touches disk.
 */
class FfmpegArgumentBuilderTest :
    FunSpec({
        fun args(spec: RecordingComposeSpec) = FfmpegArgumentBuilder.build(spec = spec, outputPath = "/tmp/out.mp4").joinToString(" ")

        test("throws for an empty videoInputs list -- composition needs at least one video") {
            shouldThrow<IllegalArgumentException> {
                FfmpegArgumentBuilder.build(
                    spec = RecordingComposeSpec(videoInputs = emptyList(), audioInputs = emptyList(), outputDurationSeconds = 10.0),
                    outputPath = "/tmp/out.mp4",
                )
            }
        }

        test(
            "gallery layout (no screen share): black canvas base, per-input setpts+overlay with enable gating, output codec flags, no xstack",
        ) {
            val spec =
                RecordingComposeSpec(
                    videoInputs =
                        listOf(
                            RecordingComposeVideoInput(file = File("cam1.mp4"), offsetSeconds = 0.0, isScreenShare = false),
                            RecordingComposeVideoInput(file = File("cam2.mp4"), offsetSeconds = 5.0, isScreenShare = false),
                        ),
                    audioInputs = emptyList(),
                    outputDurationSeconds = 42.0,
                )
            val joined = args(spec)

            joined shouldContain "color=black:size=1280x720:rate=30"
            joined shouldContain "-t 42.000"
            joined shouldContain "setpts=PTS-STARTPTS+0.000/TB"
            joined shouldContain "setpts=PTS-STARTPTS+5.000/TB"
            joined shouldContain "enable='gte(t,0.000)'"
            joined shouldContain "enable='gte(t,5.000)'"
            joined shouldContain "[vout]"
            joined shouldContain "-map [vout] -map [aout]"
            joined shouldContain "-c:v libx264"
            joined shouldContain "-crf 23"
            joined shouldContain "-pix_fmt yuv420p"
            joined shouldContain "-c:a aac"
            joined shouldContain "-movflags +faststart"
            joined shouldContain "-shortest"
            joined shouldNotContain "xstack"
        }

        test("zero audio inputs -> silent anullsrc fallback, mapped directly to [aout] via anull, no amix") {
            val spec =
                RecordingComposeSpec(
                    videoInputs = listOf(RecordingComposeVideoInput(file = File("cam1.mp4"), offsetSeconds = 0.0, isScreenShare = false)),
                    audioInputs = emptyList(),
                    outputDurationSeconds = 10.0,
                )
            val joined = args(spec)

            joined shouldContain "anullsrc=r=48000:cl=stereo"
            joined shouldContain "anull[aout]"
            joined shouldNotContain "amix"
        }

        test("non-zero audio inputs -> asetpts+aresample per input with correct offsets, then amix, no anullsrc") {
            val spec =
                RecordingComposeSpec(
                    videoInputs = listOf(RecordingComposeVideoInput(file = File("cam1.mp4"), offsetSeconds = 0.0, isScreenShare = false)),
                    audioInputs =
                        listOf(
                            RecordingComposeAudioInput(file = File("mic1.ogg"), offsetSeconds = 0.0),
                            RecordingComposeAudioInput(file = File("mic2.ogg"), offsetSeconds = 2.5),
                        ),
                    outputDurationSeconds = 10.0,
                )
            val joined = args(spec)

            // Same absolute PTS rebase as the video chain (see FfmpegArgumentBuilder's own bug-fix
            // comment) -- both must zero their own input's PTS then add the offset, or a non-zero
            // start PTS in either chain alone becomes an uncorrected constant A/V gap.
            joined shouldContain "asetpts=PTS-STARTPTS+0.000/TB,aresample=async=1:first_pts=0"
            joined shouldContain "asetpts=PTS-STARTPTS+2.500/TB,aresample=async=1:first_pts=0"
            joined shouldContain "amix=inputs=2"
            joined shouldNotContain "anullsrc"
            joined shouldNotContain "adelay"
        }

        test(
            "screen share present -> presentation layout: screen share cell starts at (0,0), camera strip cell(s) start at y = shareHeight",
        ) {
            val spec =
                RecordingComposeSpec(
                    videoInputs =
                        listOf(
                            RecordingComposeVideoInput(file = File("cam1.mp4"), offsetSeconds = 0.0, isScreenShare = false),
                            RecordingComposeVideoInput(file = File("share.mp4"), offsetSeconds = 0.0, isScreenShare = true),
                        ),
                    audioInputs = emptyList(),
                    outputDurationSeconds = 10.0,
                    outputWidth = 1280,
                    outputHeight = 720,
                )
            val joined = args(spec)

            // Screen share (input index 2, second video input) full-width at the top.
            joined shouldContain "overlay=x=0:y=0"
            // stripHeight = (720 * 0.28).toInt() = 201 (Int truncation); shareHeight = 720 - 201 = 519.
            joined shouldContain "overlay=x=0:y=519"
        }

        test("no screen share -> gallery grid, both cells start at y=0 side by side for a 2-input row") {
            val spec =
                RecordingComposeSpec(
                    videoInputs =
                        listOf(
                            RecordingComposeVideoInput(file = File("cam1.mp4"), offsetSeconds = 0.0, isScreenShare = false),
                            RecordingComposeVideoInput(file = File("cam2.mp4"), offsetSeconds = 0.0, isScreenShare = false),
                        ),
                    audioInputs = emptyList(),
                    outputDurationSeconds = 10.0,
                    outputWidth = 1280,
                    outputHeight = 720,
                )
            val builtArgs = FfmpegArgumentBuilder.build(spec = spec, outputPath = "/tmp/out.mp4")

            // 2 inputs -> ceil(sqrt(2))=2 columns, 1 row -> cell width 640, both at y=0.
            builtArgs shouldContain "-filter_complex"
            val filterGraph = builtArgs[builtArgs.indexOf("-filter_complex") + 1]
            filterGraph shouldContain "overlay=x=0:y=0"
            filterGraph shouldContain "overlay=x=640:y=0"
        }

        test("offset formatting always uses Locale.ROOT (period decimal separator), regardless of the JVM default locale") {
            val previousDefault = Locale.getDefault()
            try {
                Locale.setDefault(Locale.GERMANY) // comma decimal separator by default
                val spec =
                    RecordingComposeSpec(
                        videoInputs =
                            listOf(
                                RecordingComposeVideoInput(file = File("cam1.mp4"), offsetSeconds = 1.5, isScreenShare = false),
                            ),
                        audioInputs = emptyList(),
                        outputDurationSeconds = 12.25,
                    )
                val joined = args(spec)

                joined shouldContain "1.500"
                joined shouldContain "12.250"
                joined shouldNotContain "1,500"
                joined shouldNotContain "12,250"
            } finally {
                Locale.setDefault(previousDefault)
            }
        }

        test("a negative offsetSeconds is clamped to zero, never emitted as a negative ffmpeg filter argument") {
            val spec =
                RecordingComposeSpec(
                    videoInputs = listOf(RecordingComposeVideoInput(file = File("cam1.mp4"), offsetSeconds = -3.0, isScreenShare = false)),
                    audioInputs = emptyList(),
                    outputDurationSeconds = 10.0,
                )
            val joined = args(spec)

            joined shouldContain "setpts=PTS-STARTPTS+0.000/TB"
            joined shouldNotContain "-3.000"
        }
    })
