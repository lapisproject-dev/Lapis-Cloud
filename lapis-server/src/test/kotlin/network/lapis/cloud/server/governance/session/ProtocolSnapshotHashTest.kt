package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingProtocolDraftDto
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.ProtocolAgendaItemDto
import network.lapis.cloud.shared.domain.ProtocolAttendanceDto
import network.lapis.cloud.shared.domain.ProtocolDraftPhase
import network.lapis.cloud.shared.domain.ProtocolHeaderDto
import java.security.MessageDigest

/** V1.9.240 -- the canonical JSON and its hash are deterministic and independent of everything that is not content. */
class ProtocolSnapshotHashTest :
    FunSpec({
        fun draft(
            body: String,
            serverNow: LocalDateTime = LocalDateTime(2030, 1, 1, 0, 0),
            version: Long = 1,
        ) = MeetingProtocolDraftDto(
            meetingId = "00000000-0000-0000-0000-000000000001",
            phase = ProtocolDraftPhase.DRAFT,
            editable = true,
            role = MeetingSessionRole.LEAD,
            header =
                ProtocolHeaderDto(
                    committeeName = "Vorstand",
                    committeeType = CommitteeType.EXECUTIVE_BOARD,
                    title = "Sitzung",
                    scheduledAt = LocalDateTime(2030, 5, 4, 19, 0),
                    format = MeetingFormat.ONLINE,
                    location = null,
                    openedAt = LocalDateTime(2030, 5, 4, 17, 0),
                    endedAt = null,
                    chairName = "A",
                    minuteTakerName = "B",
                ),
            attendance = ProtocolAttendanceDto(),
            quorum = null,
            items =
                listOf(
                    ProtocolAgendaItemDto(
                        agendaItemId = "00000000-0000-0000-0000-000000000002",
                        position = 1,
                        title = "TOP",
                        body = body,
                        version = version,
                        updatedAt = LocalDateTime(2030, 5, 4, 18, 0),
                    ),
                ),
            taskCount = 0,
            serverNow = serverNow,
        )

        fun snapshot(d: MeetingProtocolDraftDto) =
            ProtocolSnapshots.build(
                draft = d,
                versionNo = 1,
                correctionOfVersionNo = null,
                correctionNote = null,
                minutesRequired = true,
                taskKeys = emptyMap(),
            )

        fun json(d: MeetingProtocolDraftDto) = ProtocolSnapshots.canonicalJson(snapshot(d))

        test("the same content gives byte-identical JSON and the same hash; the hash is sha256 of the fixed prefix plus the JSON") {
            val a = json(draft("Text"))
            val b = json(draft("Text"))
            a shouldBe b
            val expected =
                MessageDigest.getInstance("SHA-256").digest(("lapis-protocol-v1\n" + a).toByteArray(Charsets.UTF_8)).joinToString("") {
                    "%02x".format(it.toInt() and 0xFF)
                }
            ProtocolSnapshots.sha256(a) shouldBe expected
        }

        test("serverNow, the block version and the update stamp are not content: they do not change the hash") {
            val base = ProtocolSnapshots.sha256(json(draft("Text")))
            ProtocolSnapshots.sha256(json(draft("Text", serverNow = LocalDateTime(2031, 1, 1, 0, 0), version = 9))) shouldBe base
            ProtocolSnapshots.sha256(json(draft("Anderer Text"))) shouldNotContain base
        }

        test("a decomposed (NFD) text hashes like the composed (NFC) one") {
            val nfc = "Beschlüsse"
            val nfd = java.text.Normalizer.normalize(nfc, java.text.Normalizer.Form.NFD)
            (nfc == nfd) shouldBe false
            ProtocolSnapshots.sha256(json(draft(nfd))) shouldBe ProtocolSnapshots.sha256(json(draft(nfc)))
        }

        test("the JSON round-trips and carries no member id field") {
            val j = json(draft("Text"))
            ProtocolSnapshots.parse(j).items[0].body shouldBe "Text"
            j shouldNotContain "MemberId"
            j shouldNotContain "serverNow"
        }
    })
