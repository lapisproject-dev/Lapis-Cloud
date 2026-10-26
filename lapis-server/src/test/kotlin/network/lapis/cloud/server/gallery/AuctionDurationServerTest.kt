package network.lapis.cloud.server.gallery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.rpc.AuctionService
import network.lapis.cloud.shared.domain.AuctionDurationRules
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.rpc.ConflictException
import java.math.BigDecimal

/** V1.9.222 -- the duration bound moved to lapis-shared; its value did not change, and the server still refuses one hour more. */
class AuctionDurationServerTest :
    FunSpec({
        val fx = GalleryFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            fx.setAuctionEnabled(true)
        }
        afterSpec { fx.cleanUp() }

        test("the bounds are pinned: 1 hour to 2160 hours (90 days)") {
            AuctionDurationRules.MIN_HOURS shouldBe 1
            AuctionDurationRules.MAX_HOURS shouldBe 2160
        }

        test("the server rejects 2161 hours and 0 hours with a Conflict, before anything is written") {
            val seller = fx.member(label = "seller")
            galleryTestApplication(fixtures = fx) {
                for (hours in listOf(2161, 0, -5)) {
                    attempt(member = seller) {
                        AuctionService(call = it).createListing(
                            CreateAuctionListingInput(
                                title = "Zu lang",
                                description = "x",
                                startingBidLtr = BigDecimal("1.00"),
                                durationHours = hours,
                            ),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                }
            }
        }
    })
