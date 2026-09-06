package network.lapis.cloud.server.accounting.datev

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Pure tests of [DatevCharacterSet] -- no DB access anywhere in this file. */
class DatevCharacterSetTest :
    FunSpec({
        test("umlauts and sharp-s survive unchanged -- CP1252 can represent them natively") {
            val result = DatevCharacterSet.sanitize(raw = "Müller Straße äöüÄÖÜß", maxLength = 100)
            result.value shouldBe "Müller Straße äöüÄÖÜß"
            result.transliterated shouldBe false
        }

        test("typographic characters are folded to their CP1252-safe ASCII equivalent") {
            DatevCharacterSet.sanitize(raw = "Preis: 5€ – „toll“ …", maxLength = 100).value shouldBe "Preis: 5EUR - \"toll\" ..."
        }

        test("folding a typographic character marks the result as transliterated") {
            DatevCharacterSet.sanitize(raw = "5€", maxLength = 100).transliterated shouldBe true
        }

        test("accented Latin characters natively representable in CP1252 (Café, Øystein) are NOT transliterated") {
            // é and Ø are both directly encodable in windows-1252 -- only characters OUTSIDE that
            // 256-code-point set (see the "č"/"ř" test below) require any folding at all.
            val result = DatevCharacterSet.sanitize(raw = "Café Øystein", maxLength = 100)
            result.value shouldBe "Café Øystein"
            result.transliterated shouldBe false
        }

        test("a Latin character with no CP1252 representation is accent-stripped to its base letter and marked transliterated") {
            // "á" IS natively CP1252-safe (0xE1) and survives untouched; only "ř" (Czech r-caron,
            // outside CP1252) is stripped to its base letter "r".
            val result = DatevCharacterSet.sanitize(raw = "Dvořák", maxLength = 100)
            result.value shouldBe "Dvorák"
            result.transliterated shouldBe true
        }

        test("a character with no CP1252 representation is dropped, never becomes a literal question mark") {
            val result = DatevCharacterSet.sanitize(raw = "Привет", maxLength = 100)
            result.value.contains('?') shouldBe false
            result.transliterated shouldBe true
        }

        test("truncates to maxLength after folding, not before") {
            DatevCharacterSet.sanitize(raw = "Hello World", maxLength = 5).value shouldBe "Hello"
        }

        test("the fixed DATEV column-header en-dash is CP1252-safe and must never be run through sanitize") {
            DatevCharacterSet.isCp1252Safe("Beleginfo – Art 1") shouldBe true
        }

        // Review-Fund (2026-09, MAJOR): CP1252 encodes every C0 control character natively, so the
        // plain `encoder.canEncode(ch)` branch used to let "\r"/"\n"/Tab through UNCHANGED --
        // DatevBuchungsstapelWriter.render appends its own "\r\n" after every line, so an embedded
        // line break in a booking text tore one record into two malformed output lines.
        test("carriage return and line feed are each folded to a space and marked transliterated") {
            val result = DatevCharacterSet.sanitize(raw = "Zeile1\r\nZeile2", maxLength = 100)
            result.value shouldBe "Zeile1  Zeile2"
            result.transliterated shouldBe true
        }

        test("a tab character is folded to a space and marked transliterated") {
            val result = DatevCharacterSet.sanitize(raw = "Spalte1\tSpalte2", maxLength = 100)
            result.value shouldBe "Spalte1 Spalte2"
            result.transliterated shouldBe true
        }

        test("plain text with no control characters is not marked transliterated by the control-character check") {
            val result = DatevCharacterSet.sanitize(raw = "Ganz normaler Text", maxLength = 100)
            result.value shouldBe "Ganz normaler Text"
            result.transliterated shouldBe false
        }

        // Security finding fix (feature/multi-agent-pipeline-v1-4-5-2-bank-buchhaltungs-integration-d,
        // MAJOR, OWASP CSV/Formula Injection): a leading '='/'+'/'-'/'@' is the classic
        // spreadsheet-formula-injection trigger set -- see LEADING_FORMULA_TRIGGER_CHARS KDoc.
        test("a leading equals sign (DDE formula injection) is folded to a space and marked transliterated") {
            val result = DatevCharacterSet.sanitize(raw = "=cmd|'/c calc.exe'!A1", maxLength = 100)
            result.value shouldBe " cmd|'/c calc.exe'!A1"
            result.transliterated shouldBe true
        }

        test("a leading at-sign (=WEBSERVICE-style exfiltration trigger) is folded to a space") {
            val result = DatevCharacterSet.sanitize(raw = "@SUM(A1:A2)", maxLength = 100)
            result.value shouldBe " SUM(A1:A2)"
            result.transliterated shouldBe true
        }

        test("a leading plus or minus (bare-arithmetic formula trigger) is folded to a space") {
            DatevCharacterSet.sanitize(raw = "+cmd|'/c calc'!A1", maxLength = 100).value shouldBe " cmd|'/c calc'!A1"
            DatevCharacterSet.sanitize(raw = "-2+3", maxLength = 100).value shouldBe " 2+3"
        }

        test("a formula-trigger character NOT in leading position is left completely untouched") {
            // Only Excel's OWN first-character formula-sniffing is the threat model here -- a
            // hyphen/plus/at-sign anywhere else in ordinary business text (a price note, a date
            // range) must render byte-for-byte, not get mangled.
            val result = DatevCharacterSet.sanitize(raw = "10-20 Stück, Preis @ Fach A", maxLength = 100)
            result.value shouldBe "10-20 Stück, Preis @ Fach A"
            result.transliterated shouldBe false
        }

        test("a typographic en-dash that FOLDS to a leading hyphen is caught too, not only a literal leading hyphen") {
            // '–' (en-dash) is folded to '-' by TYPOGRAPHIC_MAP; if that fold lands in position 0, it
            // must still be caught by the leading-formula-trigger guard even though the RAW input
            // never contained a literal ASCII '-' at position 0.
            val result = DatevCharacterSet.sanitize(raw = "– Rabatt gewährt", maxLength = 100)
            result.value shouldBe "  Rabatt gewährt"
            result.transliterated shouldBe true
        }

        test("only the single leading character is folded, the rest of a formula-shaped string survives") {
            val result = DatevCharacterSet.sanitize(raw = "=A1+A2", maxLength = 100)
            result.value shouldBe " A1+A2"
        }
    })
