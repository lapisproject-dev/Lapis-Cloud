package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * Welle V1.9.298 -- source-scan tripwire: inside the video-conference, event-online and encounter-space files "guest of the room" (consent dialog,
 * the room's `allowFederationGuests` release, the short guest token, the guest cap, the guest lobby) is `MemberStatusSets.CONFERENCE_GUEST`
 * (GUEST, FRIEND), never `MemberStatusSets.NON_MEMBER`: since V1.9.298 `NON_MEMBER` also holds DONOR, who signs in but is no guest of a room.
 * Using `NON_MEMBER` there would silently turn a donor into a guest (or, the other way round, a guest check into a member check).
 */
class ConferenceGuestSetTripwireTest :
    FunSpec({
        val scoped =
            Regex(
                """network/lapis/cloud/server/(conference/.*|encounter/.*|events/EventOnline.*|rpc/Conference.*|rpc/EncounterSpace.*|rpc/MembershipGuards)\.kt""",
            )
        val usage = Regex("""\bMemberStatusSets\.NON_MEMBER\b|(?<![A-Za-z0-9_.])NON_MEMBER\b""")

        fun relative(file: File): String = file.relativeTo(SourceScan.mainRoot()).path.replace(File.separatorChar, '/')

        test("the conference, event-online and encounter files never use NON_MEMBER (they use CONFERENCE_GUEST)") {
            val files = SourceScan.mainFiles().filter { scoped.matches(relative(it)) }
            (files.size > 10) shouldBe true // the scanner sees the scope
            val offenders = files.filter { usage.containsMatchIn(SourceScan.blank(it.readText())) }.map { relative(it) }
            offenders shouldBe emptyList()
        }

        test("the scope is not empty and does use CONFERENCE_GUEST") {
            val files = SourceScan.mainFiles().filter { scoped.matches(relative(it)) }
            files.any { it.readText().contains("MemberStatusSets.CONFERENCE_GUEST") } shouldBe true
        }
    })
