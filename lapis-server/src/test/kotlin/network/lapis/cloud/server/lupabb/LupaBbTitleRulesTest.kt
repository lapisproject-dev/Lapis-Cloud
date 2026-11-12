package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** V1.9.303 -- the two pure rules around the title: the legacy display name tolerance and the likely-double-entry hint. No database. */
class LupaBbTitleRulesTest :
    FunSpec({
        test("a legacy '<title> <name>' display name equals the computed one after blank normalisation") {
            isLegacyTitledDisplayName(lapisDisplayName = "Dr. Erika X", lupaTitle = "Dr.", lupaDisplayName = "Erika X") shouldBe true
            isLegacyTitledDisplayName(lapisDisplayName = "Dr.  Erika  X ", lupaTitle = " Dr. ", lupaDisplayName = "Erika X") shouldBe true
        }

        test("the tolerance is case sensitive and only against the LupaBB title") {
            isLegacyTitledDisplayName(lapisDisplayName = "dr. Erika X", lupaTitle = "Dr.", lupaDisplayName = "Erika X") shouldBe false
            isLegacyTitledDisplayName(lapisDisplayName = "Dr. Erika X", lupaTitle = "Prof. Dr.", lupaDisplayName = "Erika X") shouldBe false
        }

        test("an empty or missing LupaBB title never tolerates anything") {
            isLegacyTitledDisplayName(lapisDisplayName = "Erika X", lupaTitle = null, lupaDisplayName = "Erika X") shouldBe false
            isLegacyTitledDisplayName(lapisDisplayName = " Erika X", lupaTitle = "  ", lupaDisplayName = "Erika X") shouldBe false
        }

        test("likely duplicate: same name tokens without the title tokens") {
            isLikelyDuplicate(memberName = "Dr. Erika X", memberTitle = "Dr.", recordName = "Erika X", recordTitle = null) shouldBe true
            isLikelyDuplicate(memberName = "X Erika", memberTitle = null, recordName = "Erika X", recordTitle = null) shouldBe true
            isLikelyDuplicate(memberName = "Jemand Anderes", memberTitle = null, recordName = "Erika X", recordTitle = null) shouldBe false
        }

        test("likely duplicate: an empty token set is never a duplicate") {
            isLikelyDuplicate(memberName = "Dr.", memberTitle = "Dr.", recordName = "Prof.", recordTitle = "Prof.") shouldBe false
            isLikelyDuplicate(memberName = "", memberTitle = null, recordName = "", recordTitle = null) shouldBe false
        }
    })
