package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.AuctionOfferNoticeKind
import java.text.Normalizer

/**
 * V1.9.323 -- the versioned, hashed notices of price offers, without a database: the PINNED hashes of version 1 (so an edit of a text without a new
 * version breaks the build), NFC, the German wording that used to be the gettext message, and every way an echo can be wrong.
 */
class AuctionOfferNoticeTest :
    FunSpec({
        val buyerDe = AuctionOfferNoticeKind.BUYER_OFFER to "de"
        val buyerEn = AuctionOfferNoticeKind.BUYER_OFFER to "en"
        val counterDe = AuctionOfferNoticeKind.SELLER_COUNTER to "de"
        val counterEn = AuctionOfferNoticeKind.SELLER_COUNTER to "en"

        // PINNED: SHA-256 of "2026-11-24.v1\n<text>". Changing a text WITHOUT a new VERSION fails here; with a new VERSION, replace these literals
        // together with the version (the old hashes then live on only in the stored rows).
        val pinned =
            mapOf(
                buyerDe to "a22e9d2d1d1668435137a36e2a57818ab9cfe022fe0e4187ea2150e6edbef718",
                buyerEn to "602860e854cab04f6cfd38c2368e719f754f3bec1c4e35138cdb9213c80e4ba1",
                counterDe to "3a248f164b1e7736a7c589dca61ccf7a7ea682cea112c104e8784bae9c3560c5",
                counterEn to "2c6e7b7e0c67c032c31fc41098e91032d7780bf9209499c98afacbf356d1c62e",
            )

        test("version 1 is pinned: every text hashes to its literal") {
            AuctionOfferNotice.VERSION shouldBe "2026-11-24.v1"
            pinned.forEach { (key, hash) ->
                AuctionOfferNotice.sha256(kind = key.first, lang = key.second) shouldBe hash
            }
        }

        test("the German texts are the former gettext messages, word for word") {
            AuctionOfferNotice.text(kind = buyerDe.first, lang = "de") shouldBe
                "Ein Preisvorschlag bindet Sie erst, wenn der Verkäufer ihn annimmt. Er gilt höchstens bis zum Auktionsende. " +
                "Ihre Gebote und der Sofortkauf bleiben davon unberührt."
            AuctionOfferNotice.text(kind = counterDe.first, lang = "de") shouldBe
                "Ein Gegenvorschlag bindet Sie erst, wenn der Käufer ihn annimmt. Es ist nur ein Gegenvorschlag möglich."
        }

        test("every text is a single-line NFC string") {
            listOf(buyerDe, buyerEn, counterDe, counterEn).forEach { (kind, lang) ->
                val text = AuctionOfferNotice.text(kind = kind, lang = lang)!!
                Normalizer.isNormalized(text, Normalizer.Form.NFC) shouldBe true
                text.contains('\n') shouldBe false
                text.trim() shouldBe text
            }
        }

        test("no text contains a control character or an i18n marker: the client sanitizer leaves every text byte for byte unchanged") {
            listOf(buyerDe, buyerEn, counterDe, counterEn).forEach { (kind, lang) ->
                val text = AuctionOfferNotice.text(kind = kind, lang = lang)!!
                text.none { it.isISOControl() } shouldBe true
                text.contains("###") shouldBe false
            }
        }

        test("a language other than de/en resolves to English, and the DTO carries the English text and hash") {
            listOf("fr", "es", "", "de-DE", "EN", "xx").forEach { requested ->
                AuctionOfferNotice.resolveLang(requested) shouldBe "en"
            }
            val dto = AuctionOfferNotice.dto(kind = AuctionOfferNoticeKind.BUYER_OFFER, requestedLang = "fr")
            dto.lang shouldBe "en"
            dto.text shouldBe AuctionOfferNotice.text(kind = AuctionOfferNoticeKind.BUYER_OFFER, lang = "en")
            dto.sha256 shouldBe pinned.getValue(buyerEn)
        }

        test("the current echo matches, in both languages and both kinds") {
            pinned.forEach { (key, hash) ->
                AuctionOfferNotice.matches(
                    kind = key.first,
                    version = AuctionOfferNotice.VERSION,
                    lang = key.second,
                    sha256 = hash,
                ) shouldBe
                    true
            }
        }

        test("every wrong echo is a non-match and never throws") {
            val v = AuctionOfferNotice.VERSION
            val good = pinned.getValue(buyerDe)
            val kind = AuctionOfferNoticeKind.BUYER_OFFER
            // wrong hash, upper-case hex, wrong lengths, non-hex, empty
            listOf(
                good.replaceRange(0, 1, if (good[0] == 'a') "b" else "a"),
                good.uppercase(),
                good.dropLast(1),
                good + "0",
                "g".repeat(64),
                "",
                " ".repeat(64),
            ).forEach { AuctionOfferNotice.matches(kind = kind, version = v, lang = "de", sha256 = it) shouldBe false }
            // unknown / old / future / overlong version
            listOf("", "x", "2026-11-23.v1", "2026-11-25.v2", "2026-11-24.v1 ", "v".repeat(33), "v".repeat(10_000)).forEach {
                AuctionOfferNotice.matches(kind = kind, version = it, lang = "de", sha256 = good) shouldBe false
            }
            // unknown language
            listOf("fr", "", "de-DE", "DE", "deutsch", "xxxxxx", "d".repeat(10_000)).forEach {
                AuctionOfferNotice.matches(kind = kind, version = v, lang = it, sha256 = good) shouldBe false
            }
            // the valid hash of the OTHER language and of the OTHER kind
            AuctionOfferNotice.matches(kind = kind, version = v, lang = "de", sha256 = pinned.getValue(buyerEn)) shouldBe false
            AuctionOfferNotice.matches(kind = kind, version = v, lang = "en", sha256 = pinned.getValue(buyerDe)) shouldBe false
            AuctionOfferNotice.matches(kind = kind, version = v, lang = "de", sha256 = pinned.getValue(counterDe)) shouldBe false
            AuctionOfferNotice.matches(kind = AuctionOfferNoticeKind.SELLER_COUNTER, version = v, lang = "de", sha256 = good) shouldBe false
        }
    })
