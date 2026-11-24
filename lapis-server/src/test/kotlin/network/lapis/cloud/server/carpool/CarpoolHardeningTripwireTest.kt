package network.lapis.cloud.server.carpool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.319 (wave 7) -- the structural rules of the hardening, as source scans (they fail when somebody adds a second writer):
 *  (v) the pending key and the moment of a key change are written only by PartnerServerStore, the hold table is touched only by PartnerDiscoveryStore;
 *  (w) only the owner's renewed consent may remove an OWN tombstone; the received-hints store may only remove pass-on rows;
 *  (x) "may pull our feed" includes "no key change pending";
 *  (y) the reset after a restore is called only by the restore;
 *  (z) a pin is set only the FIRST time (and by the probe and the administrator's accept).
 */
class CarpoolHardeningTripwireTest :
    FunSpec({
        val main = SourceScan.mainFiles()

        fun code(name: String) = SourceScan.blank(main.single { it.name == name }.readText())

        fun body(
            file: String,
            signature: String,
        ): String {
            val src = code(file)
            val start = src.indexOf(signature)
            check(start >= 0) { "$signature not found in $file" }
            val next = Regex("""\n    (?:internal |private )?fun """).find(src, start + signature.length)?.range?.first ?: src.length
            return src.substring(start, next)
        }

        test(
            "(v) only PartnerServerStore writes the pending key and the change moment; only PartnerDiscoveryStore touches the hold table",
        ) {
            val writer = Regex("""\b(signingKeyPendingId|signingKeyPendingPem|signingKeyChangedAt)\s*\]\s*=[^=]""")
            main
                .filter { writer.containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .filter { it !in setOf("PartnerServerStore.kt") }
                .shouldBeEmpty()
            main
                .filter { Regex("""\bPartnerSigningKeyHoldTable\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .filter { it !in setOf("PartnerDiscoveryStore.kt", "PartnerSigningKeyHoldTable.kt") }
                .shouldBeEmpty()
        }

        test(
            "(w) CarpoolReceivedStore never removes an own tombstone; forgetOwnGlobalTombstone is called only for the owner's renewed consent",
        ) {
            code("CarpoolReceivedStore.kt").contains("forgetOwnGlobalTombstone") shouldBe false
            code("CarpoolReceivedStore.kt").contains("forgetGlobalTombstone") shouldBe false
            main
                .filter { Regex("""\bforgetOwnGlobalTombstone\s*\(""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                .filter { it != "CarpoolFederationStore.kt" } shouldBe listOf("CarpoolService.kt")
        }

        test("(x) mayPullCarpoolFeed, the pull selection and the apply of a pull all stop on a pending key change") {
            code("CarpoolFeedRecipients.kt") shouldContain "signingKeyChangedAt == null"
            body("PartnerServerStore.kt", "fun dueForCarpoolPull(") shouldContain "signingKeyChangedAt.isNull()"
            body("CarpoolReceivedStore.kt", "fun apply(") shouldContain "signingKeyChangedAt != null"
            body("PartnerServerStore.kt", "fun pinnedSigningKeyFor(") shouldContain "signingKeyChangedAt != null"
        }

        test("(y) resetAfterRestore is called only by OrganizationRestoreService") {
            main
                .filter { Regex("""\bresetAfterRestore\s*\(""").containsMatchIn(SourceScan.blank(it.readText())) }
                .map { it.name }
                // V1.9.322: the auction exchange has its own resetAfterRestore (AuctionFederationStore), pinned by AuctionFederationTripwireTest.
                .filter { it != "CarpoolFederationStore.kt" && it != "AuctionFederationStore.kt" } shouldBe
                listOf("OrganizationRestoreService.kt")
        }

        test("(z) applyFetchSuccess assigns the pinned key only in the first-pin branch; the discovered pin comes only from decideKeyPin") {
            val assign = Regex("""\[signingPublicKeyPem\]\s*=""")
            val fetch = body("PartnerServerStore.kt", "fun applyFetchSuccess(")
            assign.findAll(fetch).count() shouldBe 1
            val firstPin = fetch.substring(fetch.indexOf("row.signingPublicKeyPem == null ->"))
            firstPin.substring(0, firstPin.indexOf("->", 40)).let { it shouldContain "signingPublicKeyPem" }
            // the probe, the apply and the administrator's accept are the only places that pin in the direct store
            assign.findAll(code("PartnerServerStore.kt")).count() shouldBe 3
            val discovered = code("PartnerDiscoveryStore.kt")
            assign.findAll(discovered).count() shouldBe 1
            body("PartnerDiscoveryStore.kt", "fun applyDiscoveredSuccess(") shouldContain "decideKeyPin("
        }
    })
