package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import network.lapis.cloud.shared.domain.PartgFindingDto
import network.lapis.cloud.shared.domain.PartgLedgerProposalAppliedSnapshot
import network.lapis.cloud.shared.domain.PartgMappingAuditSnapshot
import network.lapis.cloud.shared.domain.PartgModeAuditSnapshot
import network.lapis.cloud.shared.domain.RegionalChapterTreasurerSnapshot

/** V1.9.289 (R4a) -- the new audit snapshots and the new hints carry counts, ids and codes only: no amount, no balance, no name. */
class PartgLedgerAuditPrivacyTest :
    FunSpec({
        val forbidden = Regex("(?i)cents|amount|balance|saldo|name|text|street|city")

        fun fields(serializer: KSerializer<*>): List<String> =
            (0 until serializer.descriptor.elementsCount).map {
                serializer.descriptor.getElementName(it)
            }

        test("no audit snapshot of the wave has a field for an amount, a balance or a name") {
            listOf(
                PartgMappingAuditSnapshot.serializer(),
                PartgModeAuditSnapshot.serializer(),
                PartgLedgerProposalAppliedSnapshot.serializer(),
                RegionalChapterTreasurerSnapshot.serializer(),
            ).forEach { serializer ->
                fields(serializer).filter { forbidden.containsMatchIn(it) } shouldBe emptyList()
            }
        }

        test("the finding carries a count, never an amount") {
            fields(PartgFindingDto.serializer()).filter { forbidden.containsMatchIn(it) } shouldBe emptyList()
            fields(PartgFindingDto.serializer()).contains("count") shouldBe true
        }
    })
