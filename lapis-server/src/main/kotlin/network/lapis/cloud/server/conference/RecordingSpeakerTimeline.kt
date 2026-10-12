package network.lapis.cloud.server.conference

import network.lapis.cloud.server.db.generated.ConferenceRecordingSpeakerSpanTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.shared.conference.SpeakerSpan
import network.lapis.cloud.shared.conference.SpeechSample
import network.lapis.cloud.shared.conference.smoothSpeakerTimeline
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.math.ceil
import kotlin.uuid.Uuid

/**
 * V1.9.115 -- builds the SMOOTHED speaker timeline of a recording from the loudness of the recorded microphones. Pure arithmetic: the
 * input is [SpeechWindow]s per person (an opaque key, never a name), the output the spans in which one person was the large tile, using
 * the same smoothing ([smoothSpeakerTimeline]) the live view uses.
 */
object RecordingSpeakerTimeline {
    /** The grid the windows are sampled on -- the analysis window of [SpeechActivityAnalyzer]. */
    const val STEP_MS = SpeechActivityAnalyzer.WINDOW_MS.toLong()

    /** Hard upper bound of the grid (about 12 hours); a longer recording gets no timeline (and so the grid), never an unbounded array. */
    const val MAX_SAMPLES = 432_000

    /**
     * [perPerson] maps an opaque person key to that person's speech windows on the wall clock. The result is empty when nobody spoke
     * or [totalMs] is not positive or too long. Of several people speaking at one instant the loudest comes first; ties break by key.
     */
    fun build(
        perPerson: Map<String, List<SpeechWindow>>,
        totalMs: Long,
    ): List<SpeakerSpan> {
        if (totalMs <= 0L || perPerson.isEmpty()) return emptyList()
        val n = SpeechActivity.sampleCount(totalMs = totalMs, stepMs = STEP_MS)
        if (n <= 0 || n > MAX_SAMPLES) return emptyList()
        val keys = perPerson.keys.sorted()
        // levels[k][i]: loudest window of person k covering sample i, NaN = silent
        val levels = keys.map { DoubleArray(n) { Double.NaN } }
        keys.forEachIndexed { k, key ->
            for (window in perPerson.getValue(key)) {
                val from = ceil(window.range.startSeconds * 1000.0 / STEP_MS).toInt().coerceAtLeast(0)
                val to = ceil(window.range.endSeconds * 1000.0 / STEP_MS).toInt().coerceAtMost(n)
                for (i in from until to) {
                    val old = levels[k][i]
                    if (old.isNaN() || window.levelDb > old) levels[k][i] = window.levelDb
                }
            }
        }
        val samples = ArrayList<SpeechSample>(n)
        for (i in 0 until n) {
            val speaking =
                keys.indices
                    .filter { !levels[it][i].isNaN() }
                    .sortedWith(compareByDescending<Int> { levels[it][i] }.thenBy { keys[it] })
                    .map { keys[it] }
            samples += SpeechSample(atMs = i * STEP_MS, speakingByLevel = speaking)
        }
        return smoothSpeakerTimeline(samples = samples, totalMs = totalMs)
    }
}

/** A stored span: the person is identified by the track row [trackId] only (no name, no identity). */
data class StoredSpeakerSpan(
    val trackId: Uuid,
    val startMs: Long,
    val endMs: Long,
)

/**
 * V1.9.115 -- the ONLY reader and writer of `conference_recording_speaker_span` (a tripwire test fails the build when another class
 * touches the table). The timeline lives exactly as long as the raw files of its recording: [deleteFor] runs wherever they are deleted.
 * It is exposed through no API, not part of the personal-data export (see docs) and excluded from the organization backup.
 */
object RecordingSpeakerTimelineStore {
    /** The stored spans of [recordingId] in time order, or `null` when no timeline has been computed (or it was deleted). */
    fun load(recordingId: Uuid): List<StoredSpeakerSpan>? =
        transaction {
            val computed =
                ConferenceRecordingTable
                    .selectAll()
                    .where { ConferenceRecordingTable.id eq recordingId }
                    .singleOrNull()
                    ?.get(ConferenceRecordingTable.speakerTimelineComputed)
                    ?: false
            if (!computed) {
                null
            } else {
                ConferenceRecordingSpeakerSpanTable
                    .selectAll()
                    .where { ConferenceRecordingSpeakerSpanTable.recordingId eq recordingId }
                    .orderBy(ConferenceRecordingSpeakerSpanTable.startMs, SortOrder.ASC)
                    .map {
                        StoredSpeakerSpan(
                            trackId = it[ConferenceRecordingSpeakerSpanTable.trackId],
                            startMs = it[ConferenceRecordingSpeakerSpanTable.startMs],
                            endMs = it[ConferenceRecordingSpeakerSpanTable.endMs],
                        )
                    }
            }
        }

    /** Replaces the timeline of [recordingId] and marks it computed -- one transaction. Spans with a non-positive length are dropped. */
    fun replace(
        recordingId: Uuid,
        spans: List<StoredSpeakerSpan>,
    ) {
        val valid = spans.filter { it.startMs >= 0L && it.endMs > it.startMs }
        transaction {
            ConferenceRecordingSpeakerSpanTable.deleteWhere { ConferenceRecordingSpeakerSpanTable.recordingId eq recordingId }
            if (valid.isNotEmpty()) {
                ConferenceRecordingSpeakerSpanTable.batchInsert(valid) { span ->
                    this[ConferenceRecordingSpeakerSpanTable.id] = Uuid.random()
                    this[ConferenceRecordingSpeakerSpanTable.recordingId] = recordingId
                    this[ConferenceRecordingSpeakerSpanTable.trackId] = span.trackId
                    this[ConferenceRecordingSpeakerSpanTable.startMs] = span.startMs
                    this[ConferenceRecordingSpeakerSpanTable.endMs] = span.endMs
                }
            }
            ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq recordingId }) {
                it[speakerTimelineComputed] = true
            }
        }
    }

    /** Deletes the timeline of [recordingId] (rows and the computed marker). Idempotent; safe inside an open transaction. */
    fun deleteFor(recordingId: Uuid) {
        transaction {
            ConferenceRecordingSpeakerSpanTable.deleteWhere { ConferenceRecordingSpeakerSpanTable.recordingId eq recordingId }
            ConferenceRecordingTable.update({ ConferenceRecordingTable.id eq recordingId }) {
                it[speakerTimelineComputed] = false
            }
        }
    }
}
