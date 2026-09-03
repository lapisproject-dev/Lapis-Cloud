package network.lapis.cloud.server.crm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionInput
import network.lapis.cloud.shared.domain.CrmInteractionKind
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import network.lapis.cloud.shared.rpc.BadRequestException

class CrmContactPolicyTest :
    FunSpec({
        // ── retentionReviewDueAt ─────────────────────────────────────────────────────

        test("retentionReviewDueAt uses lastInteractionAt when present") {
            val createdAt = LocalDateTime(2026, 1, 1, 10, 0)
            val lastInteraction = LocalDateTime(2026, 6, 1, 10, 0)
            CrmContactPolicy.retentionReviewDueAt(lastInteractionAt = lastInteraction, createdAt = createdAt) shouldBe
                LocalDateTime(2028, 6, 1, 10, 0)
        }

        test("retentionReviewDueAt falls back to createdAt when there is no interaction yet") {
            val createdAt = LocalDateTime(2026, 1, 1, 10, 0)
            CrmContactPolicy.retentionReviewDueAt(lastInteractionAt = null, createdAt = createdAt) shouldBe LocalDateTime(2028, 1, 1, 10, 0)
        }

        test("retentionReviewDueAt handles a year boundary / leap year correctly (exact calendar-month arithmetic)") {
            val createdAt = LocalDateTime(2026, 2, 28, 12, 0)
            CrmContactPolicy.retentionReviewDueAt(lastInteractionAt = null, createdAt = createdAt) shouldBe
                LocalDateTime(2028, 2, 28, 12, 0)
        }

        // ── normalizeEmail ───────────────────────────────────────────────────────────

        test("normalizeEmail trims and lowercases") {
            CrmContactPolicy.normalizeEmail("  Foo.Bar@Example.ORG  ") shouldBe "foo.bar@example.org"
        }

        test("normalizeEmail turns blank/null into null") {
            CrmContactPolicy.normalizeEmail(null) shouldBe null
            CrmContactPolicy.normalizeEmail("   ") shouldBe null
        }

        // ── mayReceiveEmail ──────────────────────────────────────────────────────────

        test("mayReceiveEmail is false for LEGITIMATE_INTEREST even with an email on file") {
            CrmContactPolicy.mayReceiveEmail(
                lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
                consentGivenAt = null,
                consentWithdrawnAt = null,
                email = "a@b.de",
            ) shouldBe false
        }

        test("mayReceiveEmail is false for CONTRACT even with an email on file") {
            CrmContactPolicy.mayReceiveEmail(
                lawfulBasis = CrmLawfulBasis.CONTRACT,
                consentGivenAt = null,
                consentWithdrawnAt = null,
                email = "a@b.de",
            ) shouldBe false
        }

        test("mayReceiveEmail is false for CONSENT without a recorded consentGivenAt") {
            CrmContactPolicy.mayReceiveEmail(
                lawfulBasis = CrmLawfulBasis.CONSENT,
                consentGivenAt = null,
                consentWithdrawnAt = null,
                email = "a@b.de",
            ) shouldBe false
        }

        test("mayReceiveEmail is false for CONSENT with a withdrawn consent") {
            CrmContactPolicy.mayReceiveEmail(
                lawfulBasis = CrmLawfulBasis.CONSENT,
                consentGivenAt = LocalDateTime(2026, 1, 1, 0, 0),
                consentWithdrawnAt = LocalDateTime(2026, 2, 1, 0, 0),
                email = "a@b.de",
            ) shouldBe false
        }

        test("mayReceiveEmail is false for a valid CONSENT with no email address") {
            CrmContactPolicy.mayReceiveEmail(
                lawfulBasis = CrmLawfulBasis.CONSENT,
                consentGivenAt = LocalDateTime(2026, 1, 1, 0, 0),
                consentWithdrawnAt = null,
                email = null,
            ) shouldBe false
        }

        test("mayReceiveEmail is true only for the full valid case: CONSENT, given, not withdrawn, email present") {
            CrmContactPolicy.mayReceiveEmail(
                lawfulBasis = CrmLawfulBasis.CONSENT,
                consentGivenAt = LocalDateTime(2026, 1, 1, 0, 0),
                consentWithdrawnAt = null,
                email = "a@b.de",
            ) shouldBe true
        }

        // ── validate ─────────────────────────────────────────────────────────────────

        fun baseInput() =
            CrmContactInput(
                displayName = "Max Mustermann",
                email = null,
                phone = null,
                street = null,
                postalCode = null,
                city = null,
                country = null,
                contactType = CrmContactType.INTERESSENT,
                lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
                consentSource = null,
                consentGivenAt = null,
                externalDonorId = null,
                memberId = null,
            )

        // Fixed reference "now" for every validate() call below -- well after every hardcoded
        // 2026-01-01 consentGivenAt these tests use, so it never itself trips the future-check.
        val validateNow = LocalDateTime(2026, 6, 1, 0, 0)

        test("validate rejects a blank displayName") {
            shouldThrow<BadRequestException> { CrmContactPolicy.validate(input = baseInput().copy(displayName = "   "), now = validateNow) }
        }

        test("validate rejects CONSENT without consentSource") {
            shouldThrow<BadRequestException> {
                CrmContactPolicy.validate(
                    input =
                        baseInput().copy(
                            lawfulBasis = CrmLawfulBasis.CONSENT,
                            consentSource = null,
                            consentGivenAt = LocalDateTime(2026, 1, 1, 0, 0),
                        ),
                    now = validateNow,
                )
            }
        }

        test("validate rejects CONSENT without consentGivenAt") {
            shouldThrow<BadRequestException> {
                CrmContactPolicy.validate(
                    input = baseInput().copy(lawfulBasis = CrmLawfulBasis.CONSENT, consentSource = "Infostand", consentGivenAt = null),
                    now = validateNow,
                )
            }
        }

        test("validate accepts a complete CONSENT input") {
            CrmContactPolicy.validate(
                input =
                    baseInput().copy(
                        lawfulBasis = CrmLawfulBasis.CONSENT,
                        consentSource = "Infostand Braunschweig",
                        consentGivenAt = LocalDateTime(2026, 1, 1, 0, 0),
                    ),
                now = validateNow,
            )
        }

        test("validate rejects a contact linked to both an external donor and a member") {
            shouldThrow<BadRequestException> {
                CrmContactPolicy.validate(
                    input =
                        baseInput().copy(
                            externalDonorId = "00000000-0000-0000-0000-000000000001",
                            memberId = "00000000-0000-0000-0000-000000000002",
                        ),
                    now = validateNow,
                )
            }
        }

        // ── validate: consentGivenAt must not lie in the future (review finding "consentGivenAt
        //    darf beliebig weit in der Zukunft liegen") ────────────────────────────────────────

        test("validate rejects a consentGivenAt after now") {
            val now = LocalDateTime(2026, 6, 1, 12, 0)
            shouldThrow<BadRequestException> {
                CrmContactPolicy.validate(
                    input =
                        baseInput().copy(
                            lawfulBasis = CrmLawfulBasis.CONSENT,
                            consentSource = "Infostand",
                            consentGivenAt = LocalDateTime(2026, 6, 1, 12, 1),
                        ),
                    now = now,
                )
            }
        }

        test("validate accepts a consentGivenAt exactly equal to now") {
            val now = LocalDateTime(2026, 6, 1, 12, 0)
            CrmContactPolicy.validate(
                input = baseInput().copy(lawfulBasis = CrmLawfulBasis.CONSENT, consentSource = "Infostand", consentGivenAt = now),
                now = now,
            )
        }

        test("validate accepts a consentGivenAt in the past") {
            CrmContactPolicy.validate(
                input =
                    baseInput().copy(
                        lawfulBasis = CrmLawfulBasis.CONSENT,
                        consentSource = "Infostand",
                        consentGivenAt = LocalDateTime(2026, 1, 1, 0, 0),
                    ),
                now = LocalDateTime(2026, 6, 1, 12, 0),
            )
        }

        // ── validate: clearConsentEvidence is mutually exclusive with an active CONSENT basis
        //    (review finding "eine irrtümlich erfasste Einwilligung lässt sich nicht entfernen") ──

        test("validate rejects clearConsentEvidence while lawfulBasis is still CONSENT") {
            shouldThrow<BadRequestException> {
                CrmContactPolicy.validate(
                    input =
                        baseInput().copy(
                            lawfulBasis = CrmLawfulBasis.CONSENT,
                            consentSource = "Infostand",
                            consentGivenAt = LocalDateTime(2026, 1, 1, 0, 0),
                            clearConsentEvidence = true,
                        ),
                    now = validateNow,
                )
            }
        }

        test("validate accepts clearConsentEvidence once lawfulBasis is no longer CONSENT") {
            CrmContactPolicy.validate(
                input = baseInput().copy(lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST, clearConsentEvidence = true),
                now = validateNow,
            )
        }

        // ── validate: length caps (mirror V17__crm_contacts.sql VARCHAR widths) ────────

        test("validate rejects a displayName longer than 300 characters") {
            shouldThrow<BadRequestException> {
                CrmContactPolicy.validate(input = baseInput().copy(displayName = "x".repeat(301)), now = validateNow)
            }
        }

        test("validate accepts a displayName at exactly 300 characters") {
            CrmContactPolicy.validate(input = baseInput().copy(displayName = "x".repeat(300)), now = validateNow)
        }

        test("validate rejects a consentSource longer than 200 characters") {
            shouldThrow<BadRequestException> {
                CrmContactPolicy.validate(
                    input =
                        baseInput().copy(
                            lawfulBasis = CrmLawfulBasis.CONSENT,
                            consentSource = "x".repeat(201),
                            consentGivenAt = LocalDateTime(2026, 1, 1, 0, 0),
                        ),
                    now = validateNow,
                )
            }
        }

        test("validate rejects a street longer than 200 characters") {
            shouldThrow<BadRequestException> {
                CrmContactPolicy.validate(
                    input = baseInput().copy(street = "x".repeat(201)),
                    now = validateNow,
                )
            }
        }

        // ── validateInteraction ──────────────────────────────────────────────────────

        fun baseInteraction() =
            CrmInteractionInput(
                contactId = "00000000-0000-0000-0000-000000000001",
                occurredAt = null,
                kind = CrmInteractionKind.NOTE,
                summary = "Kurzes Telefonat.",
            )

        test("validateInteraction rejects a blank summary") {
            shouldThrow<BadRequestException> {
                CrmContactPolicy.validateInteraction(input = baseInteraction().copy(summary = "   "), now = LocalDateTime(2026, 1, 1, 0, 0))
            }
        }

        test("validateInteraction rejects a summary longer than 4000 characters") {
            shouldThrow<BadRequestException> {
                CrmContactPolicy.validateInteraction(
                    input = baseInteraction().copy(summary = "x".repeat(4001)),
                    now = LocalDateTime(2026, 1, 1, 0, 0),
                )
            }
        }

        test("validateInteraction accepts a summary at exactly 4000 characters") {
            CrmContactPolicy.validateInteraction(
                input = baseInteraction().copy(summary = "x".repeat(4000)),
                now = LocalDateTime(2026, 1, 1, 0, 0),
            )
        }

        test("validateInteraction rejects an occurredAt in the future") {
            val now = LocalDateTime(2026, 6, 1, 12, 0)
            shouldThrow<BadRequestException> {
                CrmContactPolicy.validateInteraction(
                    input = baseInteraction().copy(occurredAt = LocalDateTime(2026, 6, 1, 12, 1)),
                    now = now,
                )
            }
        }

        test("validateInteraction accepts an occurredAt equal to now") {
            val now = LocalDateTime(2026, 6, 1, 12, 0)
            CrmContactPolicy.validateInteraction(input = baseInteraction().copy(occurredAt = now), now = now)
        }

        test("validateInteraction accepts a backdated occurredAt in the past") {
            val now = LocalDateTime(2026, 6, 1, 12, 0)
            CrmContactPolicy.validateInteraction(input = baseInteraction().copy(occurredAt = LocalDateTime(2026, 1, 1, 0, 0)), now = now)
        }

        test("validateInteraction accepts occurredAt = null (defaults to now server-side)") {
            CrmContactPolicy.validateInteraction(input = baseInteraction().copy(occurredAt = null), now = LocalDateTime(2026, 1, 1, 0, 0))
        }
    })
