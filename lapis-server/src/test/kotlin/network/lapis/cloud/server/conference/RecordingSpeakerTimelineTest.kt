package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** V1.9.115 -- the speaker timeline built from per-person speech windows: alternation, overtalk, silence, bounds. */
class RecordingSpeakerTimelineTest :
    FunSpec({
        fun speech(
            from: Double,
            to: Double,
            db: Double = -20.0,
        ) = SpeechWindow(range = TimeRange(startSeconds = from, endSeconds = to), levelDb = db)

        test("one person speaking gives one span from the claim to the end") {
            val spans = RecordingSpeakerTimeline.build(perPerson = mapOf("A" to listOf(speech(1.0, 5.0))), totalMs = 20_000)
            spans.size shouldBe 1
            spans.single().key shouldBe "A"
            spans.single().startMs shouldBe 1_800L // 800 ms of uninterrupted speech before the claim
            spans.single().endMs shouldBe 20_000L
        }

        test("two persons speaking in turn alternate after the dwell and gap rules") {
            val spans =
                RecordingSpeakerTimeline.build(
                    perPerson = mapOf("A" to listOf(speech(0.0, 6.0)), "B" to listOf(speech(8.0, 14.0))),
                    totalMs = 20_000,
                )
            spans.map { it.key } shouldBe listOf("A", "B")
            spans[0].startMs shouldBe 800L
            spans[0].endMs shouldBe spans[1].startMs
            // B takes over after 800 ms of speech (8.8 s) and the pause of A (> 1.5 s since 6.0 s)
            spans[1].startMs shouldBe 8_800L
        }

        test("overtalk: the louder person wins when nobody holds the tile, and the one holding it keeps it") {
            val first =
                RecordingSpeakerTimeline.build(
                    perPerson = mapOf("quiet" to listOf(speech(0.0, 5.0, db = -40.0)), "loud" to listOf(speech(0.0, 5.0, db = -15.0))),
                    totalMs = 10_000,
                )
            first.map { it.key } shouldBe listOf("loud")
            val holding =
                RecordingSpeakerTimeline.build(
                    perPerson = mapOf("A" to listOf(speech(0.0, 10.0, db = -40.0)), "B" to listOf(speech(3.0, 10.0, db = -10.0))),
                    totalMs = 12_000,
                )
            holding.map { it.key } shouldBe listOf("A")
        }

        test("nobody speaking, no persons, a non-positive or over-long total give no spans") {
            RecordingSpeakerTimeline.build(perPerson = emptyMap(), totalMs = 10_000).shouldBeEmpty()
            RecordingSpeakerTimeline.build(perPerson = mapOf("A" to emptyList()), totalMs = 10_000).shouldBeEmpty()
            RecordingSpeakerTimeline.build(perPerson = mapOf("A" to listOf(speech(0.0, 5.0))), totalMs = 0).shouldBeEmpty()
            RecordingSpeakerTimeline
                .build(perPerson = mapOf("A" to listOf(speech(0.0, 5.0))), totalMs = (RecordingSpeakerTimeline.MAX_SAMPLES + 10) * 100L)
                .shouldBeEmpty()
        }

        test("windows outside the recording are ignored and spans end at the total") {
            val spans =
                RecordingSpeakerTimeline.build(
                    perPerson = mapOf("A" to listOf(speech(-5.0, 3.0), speech(50.0, 60.0))),
                    totalMs = 10_000,
                )
            spans.single().endMs shouldBe 10_000L
            spans.single().startMs shouldBe 800L
        }
    })
