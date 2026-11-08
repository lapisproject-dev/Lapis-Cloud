package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class RecordingSingleParticipantFilterTest :
    FunSpec({
        fun video(
            trackId: String,
            share: Boolean = false,
        ) = RecordingComposeVideoInput(file = File("$trackId.mkv"), offsetSeconds = 0.0, isScreenShare = share, trackId = trackId)

        val identities = mapOf("a-cam" to "anna", "b-cam" to "ben", "b-cam2" to "ben", "share" to "ben")

        test("other people's cameras drop out; the chosen person's cameras (also after a re-entry) and every screen share stay") {
            val inputs = listOf(video("a-cam"), video("b-cam"), video("b-cam2"), video("share", share = true))
            filterForSingleParticipant(videoInputs = inputs, identityByTrackId = identities, identity = "ben").map { it.trackId } shouldBe
                listOf("b-cam", "b-cam2", "share")
            filterForSingleParticipant(videoInputs = inputs, identityByTrackId = identities, identity = "anna").map { it.trackId } shouldBe
                listOf("a-cam", "share")
        }

        test("no camera of the chosen person leaves only the screen shares, possibly none") {
            val inputs = listOf(video("a-cam"), video("share", share = true))
            filterForSingleParticipant(videoInputs = inputs, identityByTrackId = identities, identity = "carl").map { it.trackId } shouldBe
                listOf("share")
            filterForSingleParticipant(videoInputs = listOf(video("a-cam")), identityByTrackId = identities, identity = "carl") shouldBe
                emptyList()
        }

        test("an input whose track is unknown is dropped") {
            filterForSingleParticipant(videoInputs = listOf(video("ghost")), identityByTrackId = identities, identity = "ben") shouldBe
                emptyList()
        }
    })
