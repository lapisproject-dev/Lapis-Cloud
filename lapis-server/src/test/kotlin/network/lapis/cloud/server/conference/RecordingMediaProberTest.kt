package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/** V1.9.109 -- the streaming parser behind `probePacketRanges` and the output probe parser, without an `ffprobe` binary. */
class RecordingMediaProberTest :
    FunSpec({
        fun collect(
            vararg lines: String,
            gap: Double = 1.0,
        ): PacketRanges? {
            val collector = PacketCollector()
            lines.forEach { collector.accept(it) }
            return collector.toRanges(gap)
        }

        fun framesAt(
            from: Double,
            to: Double,
            step: Double = 1.0 / 30,
        ): List<String> {
            val lines = ArrayList<String>()
            var t = from
            while (t < to) {
                lines += "%.6f,%.6f".format(java.util.Locale.ROOT, t, step)
                t += step
            }
            return lines
        }

        test("a continuous track is one range starting at 0, relative to its first packet") {
            val ranges = collect(*framesAt(5.0, 15.0).toTypedArray())!!
            ranges.ranges.size shouldBe 1
            ranges.ranges.single().startSeconds shouldBe (0.0 plusOrMinus 1e-9)
            ranges.ranges.single().endSeconds shouldBe (10.0 plusOrMinus 0.05)
            ranges.mediaDurationSeconds shouldBe (10.0 plusOrMinus 0.05)
        }

        test("a gap of at least the threshold splits the ranges, a shorter one does not") {
            val lines = framesAt(0.0, 10.0) + framesAt(10.5, 20.0) + framesAt(25.0, 30.0)
            val ranges = collect(*lines.toTypedArray(), gap = 1.0)!!
            ranges.ranges.size shouldBe 2 // the 0.5 s hole is below the threshold, the 5 s hole is not
            ranges.ranges[0].endSeconds shouldBe (20.0 plusOrMinus 0.05)
            ranges.ranges[1].startSeconds shouldBe (25.0 plusOrMinus 0.01)
            ranges.mediaDurationSeconds shouldBe (30.0 plusOrMinus 0.05)
            collect(*lines.toTypedArray(), gap = 0.4)!!.ranges.size shouldBe 3
        }

        test("decode order does not matter: packets are sorted by presentation time") {
            val ordered = framesAt(0.0, 4.0)
            val shuffled = ordered.reversed()
            collect(*shuffled.toTypedArray())!!.ranges shouldBe collect(*ordered.toTypedArray())!!.ranges
        }

        test("garbage lines are skipped, a missing duration falls back to the packet spacing") {
            val ranges = collect("N/A", "", "warning: something", "0.000000,", "0.033333,N/A", "0.066667,0.033333")!!
            ranges.ranges.size shouldBe 1
            ranges.mediaDurationSeconds shouldBe (0.1 plusOrMinus 0.01)
        }

        test("no packet at all is no result") {
            collect() shouldBe null
            collect("N/A", "", ",") shouldBe null
        }

        test("a single packet is a (short) range") {
            val ranges = collect("3.000000,0.020000")!!
            ranges.ranges.single().startSeconds shouldBe (0.0 plusOrMinus 1e-9)
            ranges.ranges.single().endSeconds shouldBe (0.02 plusOrMinus 1e-9)
        }

        test("audio: 20 ms opus packets with a 30 s hole") {
            val lines = framesAt(-0.0065, 100.0, 0.02) + framesAt(130.0, 160.0, 0.02)
            val ranges = collect(*lines.toTypedArray(), gap = 0.5)!!
            ranges.ranges.size shouldBe 2
            ranges.ranges[1].startSeconds shouldBe (130.0065 plusOrMinus 0.001)
        }

        test("the line counter counts every line, parseable or not") {
            val collector = PacketCollector()
            collector.accept("x")
            collector.accept("1.0,0.1")
            collector.lineCount shouldBe 2
        }

        test("output probe: streams and duration") {
            val probe = FfprobeMediaProber.parseOutputProbe("codec_type=video\ncodec_type=audio\nduration=12.345000\n")
            probe.hasVideo shouldBe true
            probe.hasAudio shouldBe true
            probe.durationSeconds shouldBe 12.345
            FfprobeMediaProber.parseOutputProbe("codec_type=video\nduration=N/A\n").let {
                it.hasAudio shouldBe false
                it.durationSeconds shouldBe null
            }
            FfprobeMediaProber.parseOutputProbe("").hasVideo shouldBe false
        }

        test("the None prober knows nothing and never throws") {
            kotlinx.coroutines.runBlocking {
                RecordingMediaProber.None.probePacketRanges(
                    file = java.io.File("/nonexistent"),
                    kind = StreamKind.VIDEO,
                    gapThresholdSeconds = 1.0,
                ) shouldBe
                    null
                RecordingMediaProber.None.probeOutput(java.io.File("/nonexistent")) shouldBe null
            }
        }

        // ── V1.9.110: stream start times of a per-person file ──────────────────

        test("parseStreamStartTimes reads the first video and the first audio stream") {
            val times =
                FfprobeMediaProber.parseStreamStartTimes(
                    """{"streams":[{"codec_type":"video","start_time":"0.400000"},{"codec_type":"audio","start_time":"-0.021000"},""" +
                        """{"codec_type":"audio","start_time":"9.0"}]}""",
                )!!
            times.videoSeconds shouldBe (0.4 plusOrMinus 1e-9)
            times.audioSeconds shouldBe (-0.021 plusOrMinus 1e-9)
            times.hasVideo shouldBe true
            times.hasAudio shouldBe true
        }

        test("parseStreamStartTimes: a missing stream is reported as absent, N/A keeps the stream but has no time") {
            val onlyAudio = FfprobeMediaProber.parseStreamStartTimes("""{"streams":[{"codec_type":"audio","start_time":"0.0"}]}""")!!
            onlyAudio.hasVideo shouldBe false
            onlyAudio.videoSeconds shouldBe null
            onlyAudio.hasAudio shouldBe true

            val unknown = FfprobeMediaProber.parseStreamStartTimes("""{"streams":[{"codec_type":"video","start_time":"N/A"}]}""")!!
            unknown.hasVideo shouldBe true
            unknown.videoSeconds shouldBe null
            unknown.hasAudio shouldBe false
        }

        test("parseStreamStartTimes: unparseable output yields null, never throws") {
            FfprobeMediaProber.parseStreamStartTimes("") shouldBe null
            FfprobeMediaProber.parseStreamStartTimes("not json") shouldBe null
            FfprobeMediaProber.parseStreamStartTimes("""{"other":1}""") shouldBe null
            FfprobeMediaProber.parseStreamStartTimes("""{"streams":"x"}""") shouldBe null
        }
    })
