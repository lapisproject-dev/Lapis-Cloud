package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.shared.conference.CellRect
import network.lapis.cloud.shared.conference.GridShape
import java.util.Locale
import io.kotest.matchers.collections.shouldContain as listShouldContain

/** V1.9.109 -- the PURE `ffmpeg` argument construction of one segment. Runs under a German default locale on purpose. */
class SegmentArgumentBuilderTest :
    FunSpec({
        val canvas = RecordingCanvas(width = 1280, height = 720)
        val fps = 30
        val previousLocale = Locale.getDefault()

        beforeSpec { Locale.setDefault(Locale.GERMANY) }
        afterSpec { Locale.setDefault(previousLocale) }

        fun seg(
            start: Long = 0,
            end: Long = 900,
            share: String? = null,
            slots: Map<String, Int> = mapOf("a" to 0),
            shape: GridShape = GridShape(cols = 1, rows = 1),
        ) = EdlSegment(index = 0, startFrame = start, endFrame = end, screenShareTrackId = share, cameraSlots = slots, shape = shape)

        fun sources(vararg ids: String) =
            ids.associateWith { SegmentVideoSource(trackId = it, path = "/work/$it.mkv", offsetSeconds = 0.5) }

        fun graphOf(args: List<String>) = args[args.indexOf("-filter_complex") + 1]

        /** The value after the first [flag] that comes at or after index [from]. */
        fun valueAfter(
            args: List<String>,
            flag: String,
            from: Int = 0,
        ) = args[args.withIndex().first { it.index >= from && it.value == flag }.index + 1]

        test("video: seek BEFORE the input, a constant frame count, identical encoder parameters, no B-frames, MP4 segment") {
            val args =
                SegmentArgumentBuilder.buildVideoSegment(
                    seg = seg(start = 3600, end = 4500),
                    inputs = sources("a"),
                    canvas = canvas,
                    fps = fps,
                    outVideo = "/work/seg.mp4",
                )
            val ssIndex = args.indexOf("-ss")
            val inputIndex = args.indexOf("/work/a.mkv")
            (ssIndex in 0 until inputIndex) shouldBe true
            args[ssIndex + 1] shouldBe "119.500000" // segment start 120 s minus the track offset 0.5 s
            // V1.9.155: the input is bounded right after -ss, before -i: 30 s of segment + 0.5 s tail
            args[ssIndex + 2] shouldBe "-t"
            args[ssIndex + 3] shouldBe "30.500000"
            args[args.indexOf("-frames:v") + 1] shouldBe "900"
            args[args.indexOf("-bf") + 1] shouldBe "0"
            args[args.indexOf("-g") + 1] shouldBe "60"
            args[args.indexOf("-c:v") + 1] shouldBe "libx264"
            args[args.indexOf("-crf") + 1] shouldBe "23"
            valueAfter(args, "-f", from = args.indexOf("-pix_fmt")) shouldBe "mp4"
            args.last() shouldBe "/work/seg.mp4"
            args listShouldContain "-an"
        }

        test("video: the locale never leaks into a number (German locale, no comma anywhere in the options or the graph)") {
            val args =
                SegmentArgumentBuilder.buildVideoSegment(
                    seg = seg(start = 4, end = 40),
                    inputs = sources("a"),
                    canvas = canvas,
                    fps = fps,
                    outVideo = "/o.mp4",
                )
            val graph = graphOf(args)
            Regex("""\d,\d""").containsMatchIn(args.joinToString(" ").replace(graph, "")) shouldBe false
            Regex("""\d,\d""").containsMatchIn(graph) shouldBe false
        }

        test("video: a track that starts later than the segment is padded in front instead of seeking before zero") {
            val args =
                SegmentArgumentBuilder.buildVideoSegment(
                    seg = seg(start = 0, end = 30),
                    inputs = mapOf("a" to SegmentVideoSource(trackId = "a", path = "/work/a.mkv", offsetSeconds = 0.5)),
                    canvas = canvas,
                    fps = fps,
                    outVideo = "/o.mp4",
                )
            args[args.indexOf("-ss") + 1] shouldBe "0.000000"
            graphOf(args) shouldContain "tpad=start_duration=0.500000:start_mode=clone"
        }

        test("video: with a tpad lead the input is shortened by the lead (tpad supplies those seconds itself)") {
            val args =
                SegmentArgumentBuilder.buildVideoSegment(
                    seg = seg(start = 0, end = 900),
                    inputs = mapOf("a" to SegmentVideoSource(trackId = "a", path = "/work/a.mkv", offsetSeconds = 0.5)),
                    canvas = canvas,
                    fps = fps,
                    outVideo = "/o.mp4",
                )
            // 30 s segment - 0.5 s lead + 0.5 s tail
            args[args.indexOf("-t") + 1] shouldBe "30.000000"
        }

        test("video: every input of a six-camera segment is bounded, -t sits between -ss and its own -i") {
            val ids = listOf("a", "b", "c", "d", "e", "f")
            val args =
                SegmentArgumentBuilder.buildVideoSegment(
                    seg =
                        seg(
                            start = 180,
                            end = 360,
                            slots = ids.withIndex().associate { it.value to it.index },
                            shape = GridShape(cols = 3, rows = 2),
                        ),
                    inputs = sources(*ids.toTypedArray()),
                    canvas = canvas,
                    fps = fps,
                    outVideo = "/o.mp4",
                )
            args.count { it == "-t" } shouldBe 6
            for (id in ids) {
                val i = args.indexOf("/work/$id.mkv")
                args[i - 1] shouldBe "-i"
                args[i - 3] shouldBe "-t"
                args[i - 5] shouldBe "-ss"
                args[i - 2] shouldBe "6.500000"
            }
        }

        test("video: a lead longer than the segment never makes -t negative (floor of one frame plus the tail)") {
            val args =
                SegmentArgumentBuilder.buildVideoSegment(
                    seg = seg(start = 0, end = 15),
                    inputs = mapOf("a" to SegmentVideoSource(trackId = "a", path = "/work/a.mkv", offsetSeconds = 5.0)),
                    canvas = canvas,
                    fps = fps,
                    outVideo = "/o.mp4",
                )
            args[args.indexOf("-t") + 1] shouldBe "0.533333" // 1/30 + 0.5
        }

        test("video: gallery cells come from the shared grid; every picture is put on a constant frame rate and fitted into its cell") {
            val args =
                SegmentArgumentBuilder.buildVideoSegment(
                    seg = seg(slots = mapOf("a" to 0, "b" to 1), shape = GridShape(cols = 2, rows = 1)),
                    inputs = sources("a", "b"),
                    canvas = canvas,
                    fps = fps,
                    outVideo = "/o.mp4",
                )
            val graph = graphOf(args)
            graph shouldContain "fps=fps=30:start_time=0"
            graph shouldContain "scale=640:360:force_original_aspect_ratio=decrease"
            graph shouldContain "overlay=x=0:y=180:eof_action=repeat"
            graph shouldContain "overlay=x=640:y=180:eof_action=repeat"
            graph shouldContain "[vout]"
        }

        test("video: presentation layout - the share above, the cameras in a row below it") {
            val cells =
                SegmentArgumentBuilder.cells(
                    seg = seg(share = "s", slots = mapOf("a" to 0, "b" to 1), shape = GridShape(cols = 2, rows = 1)),
                    canvas = canvas,
                )
            val strip = (720 * 0.28).toInt() and 1.inv()
            cells.getValue("s") shouldBe CellRect(x = 0, y = 0, width = 1280, height = 720 - strip)
            cells.getValue("a").y shouldBe 720 - strip
            cells.getValue("b").y shouldBe 720 - strip
            (cells.getValue("a").x < cells.getValue("b").x) shouldBe true
            cells.keys.first() shouldBe "s"
        }

        test("video: a share without cameras fills the whole canvas") {
            val cells =
                SegmentArgumentBuilder.cells(
                    seg = seg(share = "s", slots = emptyMap(), shape = GridShape(cols = 1, rows = 1)),
                    canvas = canvas,
                )
            cells.getValue("s") shouldBe CellRect(x = 0, y = 0, width = 1280, height = 720)
        }

        test("video: an empty segment is just the black canvas") {
            val args =
                SegmentArgumentBuilder.buildVideoSegment(
                    seg = seg(slots = emptyMap()),
                    inputs = emptyMap(),
                    canvas = canvas,
                    fps = fps,
                    outVideo = "/o.mp4",
                )
            graphOf(args) shouldBe "[0:v]null[vout]"
        }

        test("video: an unknown track is refused") {
            runCatching {
                SegmentArgumentBuilder.buildVideoSegment(
                    seg = seg(slots = mapOf("zzz" to 0)),
                    inputs = sources("a"),
                    canvas = canvas,
                    fps = fps,
                    outVideo = "/o.mp4",
                )
            }.isFailure shouldBe true
        }

        fun audioSource(
            ranges: List<TimeRange>,
            tempo: Double? = null,
            offset: Double = 0.0,
        ) = SegmentAudioSource(path = "/work/m.mka", offsetSeconds = offset, wallRanges = ranges, tempo = tempo)

        test("audio: sample-exact length - atrim and apad count samples, 48000 / 30 fps = 1600 samples per frame") {
            val args =
                SegmentArgumentBuilder.buildAudioSegment(
                    seg = seg(0, 900),
                    sources = listOf(audioSource(listOf(TimeRange(startSeconds = 0.0, endSeconds = 100.0)))),
                    fps = fps,
                    outWav = "/w.wav",
                )
            val graph = graphOf(args)
            graph shouldContain "apad=whole_len=1440000"
            graph shouldContain "atrim=end_sample=1440000"
            SegmentArgumentBuilder.sampleCount(frames = 900, fps = 30) shouldBe 1_440_000L
            SegmentArgumentBuilder.sampleCount(frames = 1, fps = 30) shouldBe 1600L
        }

        test(
            "audio: the input is seeked to the media position of the segment, the delay is in samples, the mix is not normalised and limited",
        ) {
            val args =
                SegmentArgumentBuilder.buildAudioSegment(
                    seg = seg(3600, 4500),
                    sources = listOf(audioSource(listOf(TimeRange(startSeconds = 121.0, endSeconds = 400.0)), offset = 2.0)),
                    fps = fps,
                    outWav = "/w.wav",
                )
            args[args.indexOf("-ss") + 1] shouldBe "119.000000" // wall 121 - offset 2
            args[args.indexOf("-t") + 1] shouldBe "29.000000" // up to the segment end at wall 150
            val graph = graphOf(args)
            graph shouldContain "adelay=48000S:all=1" // 1 s into the segment
            graph shouldContain "amix=inputs=1:normalize=0:duration=longest"
            graph shouldContain "alimiter=limit=0.891:attack=0.1:level=0"
            graph shouldContain "aformat=sample_rates=48000:channel_layouts=stereo"
            args.last() shouldBe "/w.wav"
            args listShouldContain "pcm_s16le"
        }

        test("audio: a drift tempo above 1 seeks and cuts in media time (multiplied) and filters with atempo") {
            val args =
                SegmentArgumentBuilder.buildAudioSegment(
                    seg = seg(13800, 14400),
                    sources = listOf(audioSource(listOf(TimeRange(startSeconds = 460.0, endSeconds = 480.0)), tempo = 1.00335)),
                    fps = fps,
                    outWav = "/w.wav",
                )
            args[args.indexOf("-ss") + 1] shouldBe "461.541000"
            args[args.indexOf("-t") + 1] shouldBe "20.067000"
            graphOf(args) shouldContain "atempo=1.00335000"
        }

        test("audio: one input per data range that overlaps the segment, none for a range outside it") {
            val ranges =
                listOf(
                    TimeRange(startSeconds = 0.0, endSeconds = 10.0),
                    TimeRange(startSeconds = 20.0, endSeconds = 25.0),
                    TimeRange(startSeconds = 100.0, endSeconds = 110.0),
                )
            val args =
                SegmentArgumentBuilder.buildAudioSegment(
                    seg = seg(0, 900),
                    sources = listOf(audioSource(ranges)),
                    fps = fps,
                    outWav = "/w.wav",
                )
            args.count { it == "-i" } shouldBe 2
            graphOf(args) shouldContain "amix=inputs=2"
        }

        test("audio: no data in the segment - silence from anullsrc") {
            val args =
                SegmentArgumentBuilder.buildAudioSegment(
                    seg = seg(0, 900),
                    sources = listOf(audioSource(listOf(TimeRange(startSeconds = 100.0, endSeconds = 110.0)))),
                    fps = fps,
                    outWav = "/w.wav",
                )
            args listShouldContain "anullsrc=r=48000:cl=stereo"
            graphOf(args) shouldBe "[0:a]atrim=end_sample=1440000[aout]"
        }

        test("audio: atempo only when the ratio is set, followed by a rebuilt sample timeline") {
            val with =
                graphOf(
                    SegmentArgumentBuilder.buildAudioSegment(
                        seg = seg(0, 900),
                        sources = listOf(audioSource(listOf(TimeRange(startSeconds = 0.0, endSeconds = 50.0)), tempo = 0.995)),
                        fps = fps,
                        outWav = "/w.wav",
                    ),
                )
            with shouldContain "atempo=0.99500000,asetpts=N/SR/TB"
            val without =
                graphOf(
                    SegmentArgumentBuilder.buildAudioSegment(
                        seg = seg(0, 900),
                        sources = listOf(audioSource(listOf(TimeRange(startSeconds = 0.0, endSeconds = 50.0)))),
                        fps = fps,
                        outWav = "/w.wav",
                    ),
                )
            without shouldNotContain "atempo"
        }

        test("audio: with atempo the media position and length are scaled by the ratio") {
            val args =
                SegmentArgumentBuilder.buildAudioSegment(
                    seg = seg(3000, 3300),
                    sources = listOf(audioSource(listOf(TimeRange(startSeconds = 0.0, endSeconds = 400.0)), tempo = 0.99)),
                    fps = fps,
                    outWav = "/w.wav",
                )
            args[args.indexOf("-ss") + 1] shouldBe "99.000000" // wall 100 -> media 99
            args[args.indexOf("-t") + 1] shouldBe "9.900000" // 10 s of wall time -> 9.9 s of media
        }

        test("AudioTempo: the correction window") {
            // 360 s media against 360.4 s reported (0.4 s EOS tail): ratio ~1 -> no correction
            AudioTempo.ratioFor(mediaSeconds = 360.0, wallSeconds = 360.4) shouldBe null
            // 0.5 % short: ratio 0.995 -> corrected
            AudioTempo.ratioFor(mediaSeconds = 358.2, wallSeconds = 360.4)!!.let { (it > 0.9949 && it < 0.9951) shouldBe true }
            // more than 5 % short: not drift, left alone
            AudioTempo.ratioFor(mediaSeconds = 300.0, wallSeconds = 360.4) shouldBe null
            // a 1 s difference is not enough even when the ratio fits
            AudioTempo.ratioFor(mediaSeconds = 30.0, wallSeconds = 30.9) shouldBe null
            // ratio fits but the difference is below one second (short track)
            AudioTempo.ratioFor(mediaSeconds = 50.0, wallSeconds = 50.9) shouldBe null
            AudioTempo.ratioFor(mediaSeconds = null, wallSeconds = 100.0) shouldBe null
            AudioTempo.ratioFor(mediaSeconds = 100.0, wallSeconds = null) shouldBe null
            AudioTempo.ratioFor(mediaSeconds = 0.0, wallSeconds = 100.0) shouldBe null
        }

        test("concat list: generated names and frame-exact durations only") {
            val text = SegmentArgumentBuilder.buildConcatList(entries = listOf("seg-00000.mp4" to 3600L, "seg-00001.mp4" to 1L), fps = fps)
            text shouldBe "file seg-00000.mp4\nduration 120.000000\nfile seg-00001.mp4\nduration 0.033333\n"
        }

        test("concat list: a name with a path, a quote or a space is refused") {
            for (bad in listOf("/etc/passwd", "../x.mp4", "a b.mp4", "a'b.mp4", "dir/x.mp4", "")) {
                runCatching { SegmentArgumentBuilder.buildConcatList(entries = listOf(bad to 1L), fps = fps) }.isFailure shouldBe true
            }
        }

        test("final mux: -safe 1, both lists, video copied, audio encoded once, mp4 forced for the .part output") {
            val args = SegmentArgumentBuilder.buildFinalMux(videoList = "/w/video.txt", audioList = "/w/audio.txt", out = "/o/x.mp4.part")
            args.count { it == "-safe" } shouldBe 2
            args[args.indexOf("-c:v") + 1] shouldBe "copy"
            args[args.indexOf("-c:a") + 1] shouldBe "aac"
            args[args.indexOf("-movflags") + 1] shouldBe "+faststart"
            valueAfter(args, "-f", from = args.indexOf("-movflags")) shouldBe "mp4"
            args.last() shouldBe "/o/x.mp4.part"
        }

        test("V1.9.115: the speaker layout places the large tile on top and the strip below, all inside the canvas") {
            val speakerSeg =
                EdlSegment(
                    index = 0,
                    startFrame = 0,
                    endFrame = 90,
                    screenShareTrackId = null,
                    cameraSlots = emptyMap(),
                    shape = GridShape(cols = 1, rows = 1),
                    speakerTrackId = "a",
                    stripTrackIds = listOf("b", "c"),
                )
            val cells = SegmentArgumentBuilder.cells(seg = speakerSeg, canvas = canvas)
            cells.keys.toList() shouldBe listOf("a", "b", "c")
            cells.getValue("a") shouldBe CellRect(x = 128, y = 0, width = 1024, height = 576)
            cells.getValue("b").y shouldBe 576
            cells.getValue("b").width shouldBe 256
            val args =
                SegmentArgumentBuilder.buildVideoSegment(
                    seg = speakerSeg,
                    inputs = sources("a", "b", "c"),
                    canvas = canvas,
                    fps = fps,
                    outVideo = "/work/seg.mp4",
                )
            graphOf(args) shouldContain "scale=1024:576"
            graphOf(args) shouldContain "overlay=x=128:y=0"
            graphOf(args) shouldContain "scale=256:144"
        }
    })
