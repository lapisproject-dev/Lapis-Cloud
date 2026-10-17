package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun tempo(
    audio: Double?,
    audioStart: Double? = 0.0,
    video: Double?,
    videoStart: Double? = 0.0,
) = ParticipantAudioDrift.tempoFor(
    audioMediaSeconds = audio,
    audioStartSeconds = audioStart,
    videoMediaSeconds = video,
    videoStartSeconds = videoStart,
)

/** V1.9.154 -- the one decision about the egress DTX drift. */
class ParticipantAudioDriftTest :
    FunSpec({
        test("ELB values: audio 500.0 from 0, video 498.333 from 0 gives 1.003345") {
            tempo(audio = 500.0, video = 498.333).shouldNotBeNull() shouldBe (1.003345 plusOrMinus 1e-5)
        }

        test("real start pattern: the video starts 1.5 s later and is shorter by that amount -- same factor") {
            tempo(audio = 500.0, video = 496.833, videoStart = 1.5).shouldNotBeNull() shouldBe (1.003345 plusOrMinus 1e-5)
        }

        test("an offset without drift (video starts 0.4 s later, audio 0.4 s longer) is no drift") {
            tempo(audio = 240.0, video = 239.6, videoStart = 0.4).shouldBeNull()
        }

        test("absolute threshold at wallSpan 100: 0.249 s no, 0.251 s yes") {
            tempo(audio = 100.249, video = 100.0).shouldBeNull()
            tempo(audio = 100.251, video = 100.0).shouldNotBeNull()
        }

        test("relative threshold at wallSpan 600: 0.299 s no, 0.301 s yes") {
            tempo(audio = 600.299, video = 600.0).shouldBeNull()
            tempo(audio = 600.301, video = 600.0).shouldNotBeNull()
        }

        test("upper bound: exactly 2 percent yes, 2.01 percent no") {
            tempo(audio = 102.0, video = 100.0).shouldNotBeNull() shouldBe (1.02 plusOrMinus 1e-9)
            tempo(audio = 102.01, video = 100.0).shouldBeNull()
        }

        test("audio shorter than the picture: never corrected") {
            tempo(audio = 498.0, video = 500.0).shouldBeNull()
        }

        test("streams or wall span under 30 s: no correction") {
            tempo(audio = 29.0, video = 28.0).shouldBeNull()
            tempo(audio = 31.0, video = 29.5).shouldBeNull()
            tempo(audio = 31.0, video = 31.0, videoStart = -5.0).shouldBeNull()
        }

        test("missing or non-finite values: no correction") {
            tempo(audio = 500.0, video = 498.0, audioStart = null).shouldBeNull()
            tempo(audio = 500.0, video = 498.0, videoStart = null).shouldBeNull()
            tempo(audio = null, video = 498.0).shouldBeNull()
            tempo(audio = 500.0, video = null).shouldBeNull()
            tempo(audio = Double.NaN, video = 498.0).shouldBeNull()
            tempo(audio = Double.POSITIVE_INFINITY, video = 498.0).shouldBeNull()
            tempo(audio = 500.0, video = Double.NEGATIVE_INFINITY).shouldBeNull()
            tempo(audio = 0.0, video = 0.0).shouldBeNull()
        }

        test("property over a grid: every result lies in (1, 1.02]") {
            var hits = 0
            for (video in listOf(30.0, 60.0, 100.0, 498.0, 1800.0)) {
                for (startShift in listOf(0.0, 0.4, 1.7)) {
                    var excess = -1.0
                    while (excess < video * 0.03) {
                        val r = tempo(audio = video + startShift + excess, video = video, videoStart = startShift)
                        if (r != null) {
                            hits++
                            (r > 1.0 && r <= 1.02) shouldBe true
                        }
                        excess += 0.05
                    }
                }
            }
            (hits > 0) shouldBe true
        }

        test("effectiveTempo: driftTempo wins, otherwise the AudioTempo rule") {
            val base =
                RecordingComposeAudioInput(
                    file = java.io.File("a"),
                    offsetSeconds = 0.0,
                    mediaDurationSeconds = 99.0,
                    wallDurationSeconds = 100.0,
                )
            base.effectiveTempo() shouldBe AudioTempo.ratioFor(mediaSeconds = 99.0, wallSeconds = 100.0)
            base.copy(driftTempo = 1.003).effectiveTempo() shouldBe 1.003
            base.copy(wallDurationSeconds = null).effectiveTempo().shouldBeNull()
        }
    })
