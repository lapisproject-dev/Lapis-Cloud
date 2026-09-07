package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.shared.domain.AccountingExportProvider

/**
 * Review finding fix (Welle V1.4.5.4 "sevDesk-Live-Anbindung", MINOR Testabdeckung).
 *
 * [AccountingExportServiceTest] already pins that `sha256For(LEXOFFICE) != sha256For(SEVDESK)`,
 * but that alone does not specify WHY they differ, nor does it catch a regression where the
 * `%s`-placeholder in [ZeroVatExportDisclaimer]'s `TEXT_TEMPLATE` is accidentally dropped while
 * reformulating the surrounding prose (`String.format` would not throw for a missing `%s` -- it
 * throws only for an EXTRA/malformed conversion -- the text would simply stop naming a provider,
 * which the two-hashes-differ assertion alone would not surface as anything more specific than
 * "still red for some reason"). This test pins the actual rendered content instead.
 */
class ZeroVatExportDisclaimerTest :
    FunSpec({
        test("textFor(LEXOFFICE) names Lexware Office") {
            ZeroVatExportDisclaimer.textFor(AccountingExportProvider.LEXOFFICE) shouldContain "Lexware Office"
        }

        test("textFor(SEVDESK) names sevDesk") {
            ZeroVatExportDisclaimer.textFor(AccountingExportProvider.SEVDESK) shouldContain "sevDesk"
        }

        test("textFor(LEXOFFICE) does not name sevDesk, and vice versa -- the placeholder was actually substituted") {
            ZeroVatExportDisclaimer.textFor(AccountingExportProvider.LEXOFFICE) shouldNotContain "sevDesk"
            ZeroVatExportDisclaimer.textFor(AccountingExportProvider.SEVDESK) shouldNotContain "Lexware Office"
        }

        test("neither rendered text leaves the format placeholder unsubstituted") {
            AccountingExportProvider.entries.forEach { provider ->
                ZeroVatExportDisclaimer.textFor(provider) shouldNotContain "%s"
            }
        }
    })
