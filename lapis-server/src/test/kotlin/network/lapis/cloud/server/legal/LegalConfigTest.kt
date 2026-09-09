package network.lapis.cloud.server.legal

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { key -> map[key] }
}

private val MANDATORY_ENV_NAMES =
    listOf(
        LegalConfig.ENV_OPERATOR_NAME,
        LegalConfig.ENV_STREET,
        LegalConfig.ENV_POSTAL_CODE,
        LegalConfig.ENV_CITY,
        LegalConfig.ENV_COUNTRY,
        LegalConfig.ENV_CONTACT_EMAIL,
        LegalConfig.ENV_REPRESENTATIVE,
    )

private val ALL_ENV_NAMES =
    MANDATORY_ENV_NAMES +
        listOf(
            LegalConfig.ENV_PHONE,
            LegalConfig.ENV_REGISTER_COURT,
            LegalConfig.ENV_REGISTER_NUMBER,
            LegalConfig.ENV_VAT_ID,
            LegalConfig.ENV_DPO_CONTACT,
            LegalConfig.ENV_MSTV_RESPONSIBLE,
            LegalConfig.ENV_SUPERVISORY_AUTHORITY,
        )

/** \u0000 (NUL), \u0007 (BEL), \u001b (ESC), \t -- a representative sample of C0 control characters.
 * Unicode escapes, not raw bytes, so this test file stays plain text (real NUL/control bytes in
 * source made this file unreviewable in `git diff` -- see Commit b676dfe, which had to remove an
 * accidental NUL byte from LegalConfig.kt production code). */
private val C0_SAMPLE = listOf('\u0000', '\u0007', '\u001b', '\t')

private fun completeEnvOf(vararg overrides: Pair<String, String>): (String) -> String? {
    val defaults =
        mapOf(
            LegalConfig.ENV_OPERATOR_NAME to "Beispielverein e. V.",
            LegalConfig.ENV_STREET to "Musterweg 1",
            LegalConfig.ENV_POSTAL_CODE to "12345",
            LegalConfig.ENV_CITY to "Musterstadt",
            LegalConfig.ENV_COUNTRY to "Deutschland",
            LegalConfig.ENV_CONTACT_EMAIL to "info@example.org",
            LegalConfig.ENV_REPRESENTATIVE to "Max Muster",
        )
    return envOf(*(defaults + overrides.toMap()).toList().toTypedArray())
}

/** Exercises [LegalConfig.load] purely through its injected `env` function -- never `System.getenv`. */
class LegalConfigTest :
    FunSpec({
        test("L1: all 14 valid -> every field filled, invalid empty, complete") {
            val config =
                completeEnvOf(
                    LegalConfig.ENV_PHONE to "+49 30 1234567",
                    LegalConfig.ENV_REGISTER_COURT to "Amtsgericht Musterstadt",
                    LegalConfig.ENV_REGISTER_NUMBER to "VR 1234",
                    LegalConfig.ENV_VAT_ID to "DE123456789",
                    LegalConfig.ENV_DPO_CONTACT to "Erika Muster, dsb@example.org",
                    LegalConfig.ENV_MSTV_RESPONSIBLE to "Max Muster",
                    LegalConfig.ENV_SUPERVISORY_AUTHORITY to "Landesbeauftragter für Datenschutz",
                ).let { LegalConfig.load(it) }
            config.operatorName shouldBe "Beispielverein e. V."
            config.street shouldBe "Musterweg 1"
            config.postalCode shouldBe "12345"
            config.city shouldBe "Musterstadt"
            config.country shouldBe "Deutschland"
            config.contactEmail shouldBe "info@example.org"
            config.representative shouldBe "Max Muster"
            config.phone shouldBe "+49 30 1234567"
            config.registerCourt shouldBe "Amtsgericht Musterstadt"
            config.registerNumber shouldBe "VR 1234"
            config.vatId shouldBe "DE123456789"
            config.dpoContact shouldBe "Erika Muster, dsb@example.org"
            config.mstvResponsible shouldBe "Max Muster"
            config.supervisoryAuthority shouldBe "Landesbeauftragter für Datenschutz"
            config.invalid.shouldBeEmpty()
            config.missingMandatory.shouldBeEmpty()
            config.isComplete shouldBe true
        }

        test("L2: nothing set -> every field null, invalid empty, missingMandatory = exactly the 7 mandatory names") {
            val config = LegalConfig.load(envOf())
            config.operatorName shouldBe null
            config.street shouldBe null
            config.postalCode shouldBe null
            config.city shouldBe null
            config.country shouldBe null
            config.contactEmail shouldBe null
            config.representative shouldBe null
            config.phone shouldBe null
            config.invalid.shouldBeEmpty()
            config.missingMandatory.shouldContainExactlyInAnyOrder(MANDATORY_ENV_NAMES)
            config.isComplete shouldBe false
        }

        test("L3: only the 7 mandatory fields set -> complete, all optional null") {
            val config = LegalConfig.load(completeEnvOf())
            config.isComplete shouldBe true
            config.phone shouldBe null
            config.registerCourt shouldBe null
            config.registerNumber shouldBe null
            config.vatId shouldBe null
            config.dpoContact shouldBe null
            config.mstvResponsible shouldBe null
            config.supervisoryAuthority shouldBe null
        }

        test("L4: street with CRLF header-injection payload -> rejected, never throws") {
            val config = LegalConfig.load(completeEnvOf(LegalConfig.ENV_STREET to "Musterweg 1\r\nX-Injected: 1"))
            config.street shouldBe null
            config.invalid shouldContain LegalConfig.ENV_STREET
            config.missingMandatory shouldContain LegalConfig.ENV_STREET
        }

        test("L5: every C0 control character in every mandatory single-line field -> rejected") {
            val singleLineMandatoryNames =
                MANDATORY_ENV_NAMES.filter { it != LegalConfig.ENV_REPRESENTATIVE && it != LegalConfig.ENV_CONTACT_EMAIL }
            C0_SAMPLE.forEach { controlChar ->
                singleLineMandatoryNames.forEach { name ->
                    val config = LegalConfig.load(completeEnvOf(name to "Bad${controlChar}Value"))
                    config.invalid shouldContain name
                }
            }
        }

        test("L6: representative with a newline (multiple named persons) -> accepted") {
            val config = LegalConfig.load(completeEnvOf(LegalConfig.ENV_REPRESENTATIVE to "Max Muster\nErika Muster"))
            config.representative shouldBe "Max Muster\nErika Muster"
            config.invalid.shouldBeEmpty()
        }

        test("L7: representative with \\r -> rejected") {
            val config = LegalConfig.load(completeEnvOf(LegalConfig.ENV_REPRESENTATIVE to "Max Muster\rErika Muster"))
            config.representative shouldBe null
            config.invalid shouldContain LegalConfig.ENV_REPRESENTATIVE
        }

        test("L8: single-line field length boundary -- 200 accepted, 201 rejected") {
            val street200 = "A".repeat(200)
            val street201 = "A".repeat(201)
            val ok = LegalConfig.load(completeEnvOf(LegalConfig.ENV_STREET to street200))
            ok.street shouldBe street200
            ok.invalid.shouldBeEmpty()
            val bad = LegalConfig.load(completeEnvOf(LegalConfig.ENV_STREET to street201))
            bad.street shouldBe null
            bad.invalid shouldContain LegalConfig.ENV_STREET
        }

        test("L9: multiline field length boundary -- 600 accepted, 601 rejected") {
            val dpo600 = "A".repeat(600)
            val dpo601 = "A".repeat(601)
            val ok = LegalConfig.load(completeEnvOf(LegalConfig.ENV_DPO_CONTACT to dpo600))
            ok.dpoContact shouldBe dpo600
            ok.invalid.shouldBeEmpty()
            val bad = LegalConfig.load(completeEnvOf(LegalConfig.ENV_DPO_CONTACT to dpo601))
            bad.dpoContact shouldBe null
            bad.invalid shouldContain LegalConfig.ENV_DPO_CONTACT
        }

        test("L10: contactEmail form validation") {
            val valid = LegalConfig.load(completeEnvOf(LegalConfig.ENV_CONTACT_EMAIL to "info@example.org"))
            valid.contactEmail shouldBe "info@example.org"
            valid.invalid.shouldBeEmpty()

            listOf("info@example", "info example.org", "a@b@c.de", "@example.org").forEach { bad ->
                val config = LegalConfig.load(completeEnvOf(LegalConfig.ENV_CONTACT_EMAIL to bad))
                config.contactEmail shouldBe null
                config.invalid shouldContain LegalConfig.ENV_CONTACT_EMAIL
                config.missingMandatory shouldContain LegalConfig.ENV_CONTACT_EMAIL
            }
        }

        test("L11: HTML/script-shaped values are accepted (no blocklisting -- escaping is kotlinx.html's job)") {
            val payloads =
                listOf(
                    "<script>alert(1)</script>",
                    "\"><img onerror=alert(1)>",
                    "Tom & Jerry",
                    "O'Brien",
                )
            payloads.forEach { payload ->
                val config = LegalConfig.load(completeEnvOf(LegalConfig.ENV_OPERATOR_NAME to payload))
                config.operatorName shouldBe payload
                config.invalid.shouldBeEmpty()
            }
        }

        test("L12: leading/trailing whitespace is trimmed") {
            val config = LegalConfig.load(completeEnvOf(LegalConfig.ENV_CITY to "  Musterstadt  "))
            config.city shouldBe "Musterstadt"
        }

        test("L13: whitespace-only value behaves like unset -- null, no invalid entry, but missingMandatory") {
            val config = LegalConfig.load(completeEnvOf(LegalConfig.ENV_OPERATOR_NAME to "   "))
            config.operatorName shouldBe null
            config.invalid.shouldBeEmpty()
            config.missingMandatory shouldContain LegalConfig.ENV_OPERATOR_NAME
        }

        test("L14: load() never throws, across every scenario above and every field with every control char") {
            shouldNotThrowAny { LegalConfig.load(envOf()) }
            shouldNotThrowAny { LegalConfig.load(completeEnvOf()) }
            ALL_ENV_NAMES.forEach { name ->
                (C0_SAMPLE + listOf('\r', '\n')).forEach { controlChar ->
                    shouldNotThrowAny { LegalConfig.load(completeEnvOf(name to "Bad${controlChar}Value")) }
                }
            }
        }
    })
