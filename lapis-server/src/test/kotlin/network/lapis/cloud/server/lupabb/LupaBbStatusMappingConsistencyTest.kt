package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.bootstrap.SourceStatusMapping
import network.lapis.cloud.server.bootstrap.mapSourceStatus
import network.lapis.cloud.shared.domain.MemberStatus

/** V1.9.299 -- the CSV import and the LupaBB sync agree on Förderer and Spender (both DONOR). */
class LupaBbStatusMappingConsistencyTest :
    FunSpec({
        test("Förderer maps to DONOR in the CSV import in every spelling, like in the sync") {
            for (label in listOf("Förderer", "förderer", "foerderer")) {
                mapSourceStatus(label) shouldBe SourceStatusMapping.Importable(MemberStatus.DONOR)
            }
            LupaBbStatusMapping.map("Förderer") shouldBe LupaBbStatusMapping.Mapped(status = MemberStatus.DONOR)
        }

        test("Spender maps to DONOR in the CSV import like in the sync") {
            mapSourceStatus("Spender") shouldBe SourceStatusMapping.Importable(MemberStatus.DONOR)
            LupaBbStatusMapping.map("Spender") shouldBe LupaBbStatusMapping.Mapped(status = MemberStatus.DONOR)
        }
    })
