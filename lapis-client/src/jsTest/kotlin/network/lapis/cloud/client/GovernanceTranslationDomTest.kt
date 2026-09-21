package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionDto
import network.lapis.cloud.shared.domain.ResolutionStatus
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * V1.4.31 audit fix M4 (the rest): raw German words handed to `gettext` as ARGUMENTS ("erreicht" / "nicht erreicht" in the quorum line) and a
 * hand-built German plural ("1 offenen Änderungsantrag" / "%1 offene Änderungsanträge") reached every language as German. Both now go
 * through the catalog -- the plural as two whole sentences, so each language can decline it.
 */
class GovernanceTranslationDomTest {
    private fun resolution(quorumMet: Boolean) =
        ResolutionDto(
            id = "r1",
            meetingId = "m1",
            agendaItemId = null,
            number = "B-1",
            title = "Haushalt",
            text = "Der Haushalt wird beschlossen.",
            votesYes = 5,
            votesNo = 1,
            votesAbstain = 0,
            quorumMet = quorumMet,
            status = ResolutionStatus.ADOPTED,
            decidedAt = LocalDateTime(2026, 9, 1, 10, 0),
            recordedById = "u1",
            recordedByDisplayName = "Erika",
        )

    private fun motion(id: String) =
        MotionDto(
            id = id,
            targetCommitteeId = "c1",
            targetCommitteeName = "Vorstand",
            targetCommitteeType = CommitteeType.EXECUTIVE_BOARD,
            title = "Änderung $id",
            rationale = "",
            text = "",
            submitterMemberId = "u1",
            submitterDisplayName = "Erika",
            status = MotionStatus.SUBMITTED,
            submittedAt = LocalDateTime(2026, 9, 1, 10, 0),
            reviewedById = null,
            reviewedByDisplayName = null,
            reviewedAt = null,
            reviewNote = null,
            meetingId = null,
            agendaItemId = null,
            resolutionId = null,
            amendsMotionId = "main",
        )

    @Test
    fun quorumWords_areTranslated_inTheResolutionRow() {
        withTranslations(mapOf("erreicht" to "reached", "nicht erreicht" to "not reached")) {
            withMountedRoot("gov-quorum") { root, element ->
                renderResolutionRow(root, resolution(quorumMet = true))
                renderResolutionRow(root, resolution(quorumMet = false))
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Quorum reached") && text.contains("Quorum not reached"), text)
                assertTrue(!text.contains("nicht erreicht") && !text.contains("erreicht ·"), "no German quorum word left: $text")
            }
        }
    }

    @Test
    fun amendmentWarning_isAWholeSentence_singularAndPlural_eachTranslated() {
        val translations =
            mapOf(
                "Dieser Antrag hat 1 offenen Änderungsantrag, der zuerst entschieden werden muss:" to "ONE-AMENDMENT",
                "Dieser Antrag hat %1 offene Änderungsanträge, die zuerst entschieden werden müssen:" to "MANY-AMENDMENTS %1",
            )
        withTranslations(translations) {
            listOf(1 to "ONE-AMENDMENT", 3 to "MANY-AMENDMENTS 3").forEach { (count, expected) ->
                withMountedRoot("gov-amendments-$count") { root, element ->
                    renderResolutionSection(
                        panel = root,
                        motion = motion("main"),
                        pendingAmendments = (1..count).map { motion("a$it") },
                        canManage = true,
                        activeVote = null,
                        onSelectMotion = {},
                        onChanged = {},
                    )
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains(expected), "expected '$expected' in: $text")
                    assertTrue(
                        !text.contains("offene Änderungsanträge") && !text.contains("offenen Änderungsantrag"),
                        "no German plural left: $text",
                    )
                }
            }
        }
    }
}
