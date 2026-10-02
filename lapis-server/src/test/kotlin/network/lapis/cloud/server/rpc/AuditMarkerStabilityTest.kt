package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.AuditMarkers

/**
 * The marker strings are persisted in the audit hash chain and matched by the client display, so
 * their literal values are pinned. `MemberService` must use the shared constants (one source).
 */
class AuditMarkerStabilityTest :
    FunSpec({
        test("the four markers keep their literal values") {
            AuditMarkers.MEMBER_ADDRESS_UPDATED shouldBe "ADDRESS_UPDATED"
            AuditMarkers.MEMBER_BENEFICIAL_OWNER_UPDATED shouldBe "BENEFICIAL_OWNER_DATA_UPDATED"
            AuditMarkers.MEMBER_ADDRESS_READ shouldBe "ADDRESS_READ"
            AuditMarkers.EVENT_REFUND_MARKED shouldBe "EVENT_REFUND_MARKED"
        }

        test("the server-side aliases point at the shared constants") {
            MEMBER_ADDRESS_AUDIT_UPDATED shouldBe AuditMarkers.MEMBER_ADDRESS_UPDATED
            MEMBER_BENEFICIAL_OWNER_AUDIT_UPDATED shouldBe AuditMarkers.MEMBER_BENEFICIAL_OWNER_UPDATED
            MEMBER_ADDRESS_AUDIT_READ shouldBe AuditMarkers.MEMBER_ADDRESS_READ
        }
    })
