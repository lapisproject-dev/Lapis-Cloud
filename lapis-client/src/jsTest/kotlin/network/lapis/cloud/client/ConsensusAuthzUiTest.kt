package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.28: the pure gates and formatters of the consensus screens -- who is offered what in which phase, and the number formats. */
class ConsensusAuthzUiTest {
    private fun statusOf(status: SystemicConsensusStatus) = consensus(status = status)

    @Test
    fun addOption_needsCollectionAndTheServerFlag() {
        assertTrue(canAddOption(statusOf(SystemicConsensusStatus.COLLECTION), skParticipation(canPropose = true)))
        assertFalse(canAddOption(statusOf(SystemicConsensusStatus.COLLECTION), skParticipation(canPropose = false)))
        SystemicConsensusStatus.entries.filter { it != SystemicConsensusStatus.COLLECTION }.forEach {
            assertFalse(canAddOption(statusOf(it), skParticipation(canPropose = true)), "no option may be added in $it")
        }
    }

    @Test
    fun removeOption_ownOptionOrManager_neverTheStatusQuoOption_onlyInCollection() {
        val c = statusOf(SystemicConsensusStatus.COLLECTION)
        val own = skOption("o-x", "Meins", 3, createdById = "m-1")
        val foreign = skOption("o-y", "Fremd", 4, createdById = "m-2")
        val statusQuo = skOption("o-sq", "x", 0, statusQuo = true, createdById = "m-1")
        val plain = skParticipation(canManage = false)
        val manager = skParticipation(canManage = true)
        assertTrue(canRemoveOption(c, own, "m-1", plain))
        assertFalse(canRemoveOption(c, foreign, "m-1", plain))
        assertTrue(canRemoveOption(c, foreign, "m-1", manager))
        assertFalse(canRemoveOption(c, statusQuo, "m-1", manager), "the status quo option is permanent, even for its 'creator'")
        assertFalse(canRemoveOption(statusOf(SystemicConsensusStatus.RATING), own, "m-1", manager))
    }

    @Test
    fun editRationale_followsTheRemoveRule() {
        val c = statusOf(SystemicConsensusStatus.COLLECTION)
        val own = skOption("o-x", "Meins", 3, createdById = "m-1")
        val foreign = skOption("o-y", "Fremd", 4, createdById = "m-2")
        val statusQuo = skOption("o-sq", "x", 0, statusQuo = true, createdById = "m-1")
        val plain = skParticipation(canManage = false)
        val manager = skParticipation(canManage = true)
        assertTrue(canEditRationale(c, own, "m-1", plain))
        assertFalse(canEditRationale(c, foreign, "m-1", plain))
        assertTrue(canEditRationale(c, foreign, "m-1", manager))
        assertFalse(canEditRationale(c, statusQuo, "m-1", manager), "never for the status quo option")
        SystemicConsensusStatus.entries.filter { it != SystemicConsensusStatus.COLLECTION }.forEach {
            assertFalse(canEditRationale(statusOf(it), own, "m-1", manager), "no rationale edit in $it")
        }
    }

    @Test
    fun freeze_isDisabledWithoutOptions_hiddenForNonManagers_andOnlyInCollection() {
        val manager = skParticipation(canManage = true)
        assertEquals(Gate.Enabled, canFreeze(statusOf(SystemicConsensusStatus.COLLECTION), manager))
        assertTrue(canFreeze(consensus(options = emptyList()), manager) is Gate.Disabled)
        assertEquals(Gate.Hidden, canFreeze(statusOf(SystemicConsensusStatus.COLLECTION), skParticipation(canManage = false)))
        assertEquals(Gate.Hidden, canFreeze(statusOf(SystemicConsensusStatus.RATING), manager))
    }

    @Test
    fun closeEvaluateAbort_followTheStatus() {
        val manager = skParticipation(canManage = true)
        val plain = skParticipation(canManage = false)
        assertTrue(canCloseRating(statusOf(SystemicConsensusStatus.RATING), manager))
        assertFalse(canCloseRating(statusOf(SystemicConsensusStatus.RATING), plain))
        assertFalse(canCloseRating(statusOf(SystemicConsensusStatus.CLOSED), manager))
        assertTrue(canEvaluate(statusOf(SystemicConsensusStatus.CLOSED), manager))
        assertFalse(canEvaluate(statusOf(SystemicConsensusStatus.RATING), manager))
        SystemicConsensusStatus.entries.forEach {
            val offered = it != SystemicConsensusStatus.EVALUATED && it != SystemicConsensusStatus.ABORTED
            assertEquals(offered, canAbortConsensus(statusOf(it), manager), "abort in $it")
            assertFalse(canAbortConsensus(statusOf(it), plain))
        }
    }

    @Test
    fun booth_onlyWhileRatingAndWhenTheServerSaysCanRate() {
        assertTrue(canEnterConsensusBooth(statusOf(SystemicConsensusStatus.RATING), skParticipation(canRate = true)))
        assertFalse(canEnterConsensusBooth(statusOf(SystemicConsensusStatus.RATING), skParticipation(canRate = false)))
        assertFalse(canEnterConsensusBooth(statusOf(SystemicConsensusStatus.CLOSED), skParticipation(canRate = true)))
    }

    @Test
    fun reopen_isPrimaryAboveTheWarnThreshold_secondaryBelow_neverForBindingOrWithoutRoundsLeft() {
        val evaluated = statusOf(SystemicConsensusStatus.EVALUATED)
        val manager = skParticipation(canManage = true)
        val hot = skResult(winner = "o-a", groupConflictWarning = true)
        val ok = skResult(winner = "o-a", groupConflictWarning = false)
        assertEquals(ReopenOffer.Primary, canReopen(evaluated, manager, hot))
        assertEquals(ReopenOffer.Secondary, canReopen(evaluated, manager, ok), "no warning from the server is no urgency")
        val withheldCalm = skResult(winner = "o-a", figuresWithheld = true, groupConflictWarning = false)
        assertEquals(
            ReopenOffer.Secondary,
            canReopen(evaluated, manager, withheldCalm),
            "a withheld result has no figures, the server's own booleans decide (an empty list must not read as no winner)",
        )
        assertEquals(
            ReopenOffer.Primary,
            canReopen(evaluated, manager, skResult(winner = "o-a", figuresWithheld = true, groupConflictWarning = true)),
        )
        assertEquals(
            ReopenOffer.Primary,
            canReopen(evaluated, manager, skResult(winner = null, figuresWithheld = true)),
            "withheld without a winner still calls for a revote",
        )
        assertEquals(
            ReopenOffer.Primary,
            canReopen(evaluated, manager, skResult(winner = null)),
            "a tie without a winner calls for a revote",
        )
        assertEquals(ReopenOffer.Hidden, canReopen(evaluated.copy(bindingness = SystemicConsensusBindingness.BINDING), manager, hot))
        assertEquals(ReopenOffer.Hidden, canReopen(evaluated.copy(round = 3, maxRounds = 3), manager, hot))
        assertEquals(ReopenOffer.Hidden, canReopen(evaluated, skParticipation(canManage = false), hot))
        listOf(
            SystemicConsensusStatus.COLLECTION,
            SystemicConsensusStatus.RATING,
            SystemicConsensusStatus.CLOSED,
            SystemicConsensusStatus.ABORTED,
        ).forEach { assertEquals(ReopenOffer.Hidden, canReopen(statusOf(it), manager, hot), "no revote from $it") }
    }

    @Test
    fun openForMotion_scheduledManagerAndNothingElseDeciding() {
        val motion = motionDto()
        val base = { consensuses: List<network.lapis.cloud.shared.domain.SystemicConsensusDto> ->
            canOpenConsensusForMotion(motion, consensuses, emptyList(), null, canManage = true)
        }
        assertTrue(base(emptyList()))
        assertTrue(base(listOf(statusOf(SystemicConsensusStatus.ABORTED))), "an aborted consensus does not block a new one")
        assertFalse(base(listOf(statusOf(SystemicConsensusStatus.EVALUATED))))
        assertFalse(canOpenConsensusForMotion(motion, emptyList(), emptyList(), null, canManage = false))
        assertFalse(
            canOpenConsensusForMotion(motion.copy(status = MotionStatus.RESOLVED), emptyList(), emptyList(), null, canManage = true),
        )
        assertFalse(
            canOpenConsensusForMotion(
                motion,
                emptyList(),
                listOf(election(status = network.lapis.cloud.shared.domain.ElectionStatus.OPEN)),
                null,
                true,
            ),
        )
    }

    @Test
    fun groupConflictWords_atTheBoundaries() {
        assertEquals("Tragfähiger Konsens", groupConflictWord(0.19))
        assertEquals("Mit Bedenken", groupConflictWord(0.2))
        assertEquals("Mit Bedenken", groupConflictWord(0.5))
        assertEquals("Warnsignal", groupConflictWord(0.51))
        assertEquals("Warnsignal", groupConflictWord(0.3, viable = 0.1, warn = 0.2), "the consensus' own thresholds win")
    }

    @Test
    fun resistanceFormat_oneDecimal_withTheLanguagesSeparator() {
        assertEquals("2,4", formatDecimal(2.4, 1, "de"))
        assertEquals("2.4", formatDecimal(2.4, 1, "en"))
        assertEquals("0,05", formatDecimal(0.05, 2, "de"))
        assertEquals("10,0", formatDecimal(10.0, 1, "de"))
        assertEquals("Ø 2,4 von 10", formatResistance(2.4, 10, "de"))
    }

    @Test
    fun roundLabel_isOneRoundForABindingConsensus() {
        assertEquals("Eine Runde (Beschluss)", consensusRoundLabel(consensus(bindingness = SystemicConsensusBindingness.BINDING)))
        assertEquals("Runde 2 von 3", consensusRoundLabel(consensus(round = 2)))
    }

    @Test
    fun statusQuoOption_isTranslatedByItsFlag_andOtherTextIsSanitized() {
        assertEquals(
            "Alles bleibt wie bisher (Passivlösung)",
            consensusOptionText(skOption("o", SK_SERVER_STATUS_QUO_LABEL, 0, statusQuo = true)),
        )
        val forged = "###KvI18nS###Hallo"
        assertFalse(consensusOptionText(skOption("o", forged, 1)).contains("###KvI18nS###"))
    }
}
