package network.lapis.cloud.server.dashboard

import network.lapis.cloud.server.dashboard.sources.AiDraftSignals
import network.lapis.cloud.server.dashboard.sources.ApplicationSignals
import network.lapis.cloud.server.dashboard.sources.AuctionSignals
import network.lapis.cloud.server.dashboard.sources.ConsensusSignals
import network.lapis.cloud.server.dashboard.sources.ContributionSignals
import network.lapis.cloud.server.dashboard.sources.CrowdfundingSignals
import network.lapis.cloud.server.dashboard.sources.ElectionSignals
import network.lapis.cloud.server.dashboard.sources.EventSignals
import network.lapis.cloud.server.dashboard.sources.MeetingSignals
import network.lapis.cloud.server.dashboard.sources.MotionVoteSignals
import network.lapis.cloud.server.dashboard.sources.PollSignals
import network.lapis.cloud.server.dashboard.sources.TenderSignals
import network.lapis.cloud.server.dashboard.sources.TravelExpenseSignals
import network.lapis.cloud.server.dashboard.sources.TreasurySignals

/** V1.9.224 -- every signal source, in a fixed order. A new module is added here and nowhere else (see `docs/features/dashboard.adoc`). */
internal object DashboardSources {
    val all: List<DashboardSignalSource> =
        listOf(
            AuctionSignals,
            TenderSignals,
            ElectionSignals,
            ConsensusSignals,
            PollSignals,
            MotionVoteSignals,
            MeetingSignals,
            CrowdfundingSignals,
            EventSignals,
            ContributionSignals,
            TravelExpenseSignals,
            AiDraftSignals,
            ApplicationSignals,
            TreasurySignals,
        )
}
