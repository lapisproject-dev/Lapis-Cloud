package network.lapis.cloud.server.member

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.rpc.CommitteeSeatFixture
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PrivilegedActionStatus

/**
 * Welle V1.9.102 -- the four-eyes execution path (`PrivilegedActionService`, status change of an ADMIN) runs the same central
 * `applyStatusChangeLocked` and therefore ends the target's committee seats automatically: until = day of the execution, reason WITHDRAWN.
 */
class PrivilegedSuspensionEndsSeatsTest :
    FunSpec({
        val peer = PeerFixture()
        val seats = CommitteeSeatFixture()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            seats.cleanUp()
            peer.cleanUp()
        }
        beforeTest { peer.isolateAdmins() }
        afterTest { peer.restoreAdmins() }

        test("approving the suspension of an ADMIN ends the ADMIN's seats on the day of the execution") {
            val a = peer.admin()
            val b = peer.admin()
            val c = peer.admin()
            val committee = seats.committee(type = CommitteeType.WORKING_GROUP)
            val seat = seats.seat(committee = committee, member = b, role = CommitteeRole.MEMBER)
            val svc = peer.service()

            val requested =
                svc.requestSuspension(
                    actor = peer.actor(id = a),
                    targetIdRaw = b.toString(),
                    newStatus = MemberStatus.WITHDRAWN,
                    reasonRaw = "Vierer-Augen-Test mit ausreichend langem Grund",
                )
            // nothing happens before the approval
            seats.seatRow(seat)[CommitteeMembershipTable.until] shouldBe null

            svc.approve(actor = peer.actor(id = c), requestIdRaw = requested.id).status shouldBe PrivilegedActionStatus.EXECUTED

            peer.statusOf(b) shouldBe MemberStatus.WITHDRAWN
            seats.seatRow(seat)[CommitteeMembershipTable.until] shouldBe OrganizationTimeZone.dateOf(DbClock.nowLocalDateTime())
            seats.seatRow(seat)[CommitteeMembershipTable.autoEndReason] shouldBe MemberStatus.WITHDRAWN
        }
    })
