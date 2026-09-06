package network.lapis.cloud.server.payment.bankstatement

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentReferenceCode
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaSequenceType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.security.SecureRandom
import kotlin.uuid.Uuid

/**
 * Exercises [BankStatementMatcher] against real `member`/`membership_tier`/`contribution` rows --
 * own freshly created fixtures per test, deleted in [io.kotest.core.spec.style.FunSpecRootScope
 * .afterTest] (never `DevSeedData`'s shared demo fixtures, same house style
 * `ContributionPostingBridgeTest` establishes).
 *
 * **R2 (IBAN/SEPA-mandate) review fix (MEDIUM, Runde-2-Fund #3)**: this class's own KDoc used to
 * claim R2 "is exercised by `BankStatementImportServiceTest`, not here" -- false. EVERY
 * `BankStatementImportService` construction in that file (and in `BankStatementStoreTest`) passes
 * `secretBox = null`, and [BankStatementMatcher.matchByIban] returns `null` immediately whenever
 * `secretBox == null` -- so [matchByIban]'s actual logic (decrypt candidates, disambiguate,
 * fall through to R3) had NEVER executed anywhere in this repository; a reviewer trusting the old
 * KDoc would wrongly believe it was covered. The "R2: IBAN-Abgleich..." tests below are this file's
 * own real [SecretBox], with a genuinely `secretBox.seal`-ed `sepa_mandate.debtor_iban_ciphertext`
 * row -- the same fixture shape `matchByIban` itself decrypts via `secretBox.open`.
 */
class BankStatementMatcherTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        lateinit var memberId: Uuid
        lateinit var tierId: Uuid
        lateinit var contributionId: Uuid
        var fixtureCreated = false
        val createdMandateIds = mutableListOf<Uuid>()

        fun randomSecretBoxKey(): ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

        /** Grants [memberId] an ACTIVE `sepa_mandate` whose `debtor_iban_ciphertext` is a REAL [secretBox]-sealed encryption of [rawIban] -- the exact fixture shape [BankStatementMatcher.matchByIban] itself decrypts via `secretBox.open`, not the "unused-ciphertext"/plaintext-garbage placeholders other tests in this codebase use for tests that never exercise decryption. */
        fun grantMandate(
            memberId: Uuid,
            rawIban: String,
            secretBox: SecretBox,
        ): Uuid {
            val mandateId = Uuid.random()
            val normalizedIban = IbanValidator.normalize(rawIban)
            transaction {
                SepaMandateTable.insert {
                    it[id] = mandateId
                    it[SepaMandateTable.memberId] = memberId
                    it[mandateReference] = "MATCHER-TEST-${mandateId.toString().take(8)}"
                    it[debtorName] = "IBAN Testperson"
                    it[debtorIbanCiphertext] = secretBox.seal(plaintext = normalizedIban, aad = mandateId.toString())
                    it[debtorIbanSetAt] = DbClock.nowLocalDateTime()
                    it[debtorIbanLast4] = normalizedIban.takeLast(4)
                    it[debtorBic] = null
                    it[signatureDate] = LocalDate(2026, 1, 1)
                    it[sequenceType] = SepaSequenceType.RCUR
                    it[status] = SepaMandateStatus.ACTIVE
                    it[grantedAt] = DbClock.nowLocalDateTime()
                    it[revokedAt] = null
                    it[revokedBy] = null
                    it[revocationReason] = null
                    it[lastUsedAt] = null
                    it[lastDebitedAmount] = null
                    it[createdBy] = memberId
                }
            }
            createdMandateIds += mandateId
            return mandateId
        }

        fun setUpFixture(
            displayName: String = "Max Mustermann",
            amountDue: BigDecimal = BigDecimal("48.00"),
            status: ContributionStatus = ContributionStatus.OPEN,
            paymentReference: String? = null,
        ) {
            memberId = Uuid.random()
            tierId = Uuid.random()
            contributionId = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[id] = memberId
                    it[MemberTable.displayName] = displayName
                    it[email] = "matcher-test-${Uuid.random()}@example.org"
                    it[MemberTable.status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[AccountTable.memberId] = memberId
                    it[role] = AccountRole.MEMBER
                }
                MembershipTierTable.insert {
                    it[id] = tierId
                    it[name] = "Standard"
                    it[description] = "Standardbeitrag"
                    it[contributionAmount] = amountDue
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
                ContributionTable.insert {
                    it[id] = contributionId
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = tierId
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[ContributionTable.amountDue] = amountDue
                    it[ContributionTable.status] = status
                    it[createdAt] = kotlinx.datetime.LocalDateTime(2026, 1, 1, 0, 0)
                    it[dueDate] = LocalDate(2026, 1, 15)
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                    it[ContributionTable.paymentReference] = paymentReference
                }
            }
            fixtureCreated = true
        }

        afterTest {
            if (!fixtureCreated) return@afterTest
            transaction {
                if (createdMandateIds.isNotEmpty()) {
                    SepaMandateTable.deleteWhere { SepaMandateTable.id inList createdMandateIds }
                }
                ContributionTable.deleteWhere { ContributionTable.id eq contributionId }
                MembershipTierTable.deleteWhere { MembershipTierTable.id eq tierId }
                AccountTable.deleteWhere { AccountTable.memberId eq memberId }
                MemberTable.deleteWhere { MemberTable.id eq memberId }
            }
            createdMandateIds.clear()
            fixtureCreated = false
        }

        test("a non-positive amount is IGNORED without ever reaching the DB rules") {
            val outcome =
                BankStatementMatcher.match(
                    line =
                        NormalizedLine(
                            amount = BigDecimal("-5.00"),
                            currency = "EUR",
                            purpose = null,
                            counterpartyName = null,
                            counterpartyIbanRaw = null,
                        ),
                    secretBox = null,
                )
            outcome.status shouldBe BankStatementLineStatus.IGNORED
            outcome.autoPost shouldBe false
        }

        test("R1: a valid reference matching an OPEN contribution with the exact amount is auto-posted") {
            val reference = PaymentReferenceCode.PREFIX + PaymentReferenceCode.encode(4242)
            setUpFixture(amountDue = BigDecimal("48.00"), status = ContributionStatus.OPEN, paymentReference = reference)

            val outcome =
                transaction {
                    BankStatementMatcher.match(
                        line =
                            NormalizedLine(
                                amount = BigDecimal("48.00"),
                                currency = "EUR",
                                purpose = "Beitrag $reference danke",
                                counterpartyName = null,
                                counterpartyIbanRaw = null,
                            ),
                        secretBox = null,
                    )
                }
            outcome.status shouldBe BankStatementLineStatus.POSTED
            outcome.autoPost shouldBe true
            outcome.contributionId shouldBe contributionId
        }

        test("R1: a valid reference but wrong amount is AMBIGUOUS, never auto-posted") {
            val reference = PaymentReferenceCode.PREFIX + PaymentReferenceCode.encode(4243)
            setUpFixture(amountDue = BigDecimal("48.00"), status = ContributionStatus.OPEN, paymentReference = reference)

            val outcome =
                transaction {
                    BankStatementMatcher.match(
                        line =
                            NormalizedLine(
                                amount = BigDecimal("40.00"),
                                currency = "EUR",
                                purpose = "Beitrag $reference",
                                counterpartyName = null,
                                counterpartyIbanRaw = null,
                            ),
                        secretBox = null,
                    )
                }
            outcome.status shouldBe BankStatementLineStatus.AMBIGUOUS
            outcome.autoPost shouldBe false
        }

        test("R1: a valid reference on an already-PAID (SETTLED) contribution is AMBIGUOUS, never auto-posted") {
            val reference = PaymentReferenceCode.PREFIX + PaymentReferenceCode.encode(4244)
            setUpFixture(amountDue = BigDecimal("48.00"), status = ContributionStatus.PAID, paymentReference = reference)

            val outcome =
                transaction {
                    BankStatementMatcher.match(
                        line =
                            NormalizedLine(
                                amount = BigDecimal("48.00"),
                                currency = "EUR",
                                purpose = "Beitrag $reference",
                                counterpartyName = null,
                                counterpartyIbanRaw = null,
                            ),
                        secretBox = null,
                    )
                }
            outcome.status shouldBe BankStatementLineStatus.AMBIGUOUS
            outcome.autoPost shouldBe false
        }

        test("R3: matching first+last name tokens with a centgenau amount is SUGGESTED, never auto-posted") {
            setUpFixture(displayName = "Erika Musterfrau", amountDue = BigDecimal("48.00"), status = ContributionStatus.OPEN)

            val outcome =
                transaction {
                    BankStatementMatcher.match(
                        line =
                            NormalizedLine(
                                amount = BigDecimal("48.00"),
                                currency = "EUR",
                                purpose = "Beitrag",
                                counterpartyName = "Erika Musterfrau",
                                counterpartyIbanRaw = null,
                            ),
                        secretBox = null,
                    )
                }
            outcome.status shouldBe BankStatementLineStatus.SUGGESTED
            outcome.autoPost shouldBe false
            outcome.contributionId shouldBe contributionId
        }

        test("R4: no rule matches -> UNMATCHED, pointing at manual donation assignment") {
            setUpFixture(displayName = "Erika Musterfrau", amountDue = BigDecimal("48.00"), status = ContributionStatus.OPEN)

            val outcome =
                transaction {
                    BankStatementMatcher.match(
                        line =
                            NormalizedLine(
                                amount = BigDecimal("48.00"),
                                currency = "EUR",
                                purpose = "voellig unbezogene Ueberweisung",
                                counterpartyName = "Unbekannt AG",
                                counterpartyIbanRaw = null,
                            ),
                        secretBox = null,
                    )
                }
            outcome.status shouldBe BankStatementLineStatus.UNMATCHED
            outcome.autoPost shouldBe false
        }

        test("a PAID/SETTLED contribution is never matched via R3 even with a matching name+amount") {
            setUpFixture(displayName = "Erika Musterfrau", amountDue = BigDecimal("48.00"), status = ContributionStatus.PAID)

            val outcome =
                transaction {
                    BankStatementMatcher.match(
                        line =
                            NormalizedLine(
                                amount = BigDecimal("48.00"),
                                currency = "EUR",
                                purpose = "Beitrag",
                                counterpartyName = "Erika Musterfrau",
                                counterpartyIbanRaw = null,
                            ),
                        secretBox = null,
                    )
                }
            outcome.status shouldBe BankStatementLineStatus.UNMATCHED
        }

        // Review fix (MEDIUM, Runde-1-Fund #13): R1's amount-only comparison is a real "wrong
        // currency happens to numerically equal the Sollbetrag" auto-post hole -- see
        // BankStatementMatcher.matchByReference's own "Review fix (MEDIUM ...)" comment.
        test("R1: a valid reference and correct amount but the WRONG currency is AMBIGUOUS, never auto-posted") {
            val reference = PaymentReferenceCode.PREFIX + PaymentReferenceCode.encode(4245)
            setUpFixture(amountDue = BigDecimal("48.00"), status = ContributionStatus.OPEN, paymentReference = reference)

            val outcome =
                transaction {
                    BankStatementMatcher.match(
                        line =
                            NormalizedLine(
                                amount = BigDecimal("48.00"),
                                currency = "CHF",
                                purpose = "Beitrag $reference",
                                counterpartyName = null,
                                counterpartyIbanRaw = null,
                            ),
                        secretBox = null,
                    )
                }
            outcome.status shouldBe BankStatementLineStatus.AMBIGUOUS
            outcome.autoPost shouldBe false
            outcome.contributionId shouldBe contributionId
            outcome.explanation shouldContain "Waehrung"
        }

        // Review fix (MEDIUM, Runde-2-Fund #3): R2 (matchByIban) had NEVER executed anywhere in this
        // repository -- see this class's own KDoc above. This is the first real execution of its
        // decrypt-and-match happy path: a genuine `secretBox.seal`-ed mandate, decrypted via
        // `secretBox.open` inside `matchByIban` itself, resolving to exactly one member with exactly
        // one matching open contribution.
        test("R2: IBAN-Abgleich decrypts a real SEPA-mandate ciphertext and matches exactly one open contribution -> SUGGESTED") {
            val secretBox = SecretBox(randomSecretBoxKey())
            setUpFixture(displayName = "IBAN Testperson", amountDue = BigDecimal("48.00"), status = ContributionStatus.OPEN)
            val rawIban = "DE89370400440532013000"
            grantMandate(memberId = memberId, rawIban = rawIban, secretBox = secretBox)

            val outcome =
                transaction {
                    BankStatementMatcher.match(
                        line =
                            NormalizedLine(
                                amount = BigDecimal("48.00"),
                                currency = "EUR",
                                purpose = "Dauerauftrag ohne Verwendungszweck-Referenz",
                                counterpartyName = null,
                                counterpartyIbanRaw = rawIban,
                            ),
                        secretBox = secretBox,
                    )
                }
            outcome.status shouldBe BankStatementLineStatus.SUGGESTED
            outcome.autoPost shouldBe false
            outcome.contributionId shouldBe contributionId
            outcome.explanation shouldContain "IBAN-Abgleich"
        }

        // Review fix (MEDIUM, Runde-2-Fund #3): exercises matchByIban's OWN ambiguity handling
        // (distinct from R1's amount-mismatch AMBIGUOUS branch) -- the IBAN resolves to exactly one
        // member, but that member has two equally-matching open contributions, so matchByIban itself
        // returns AMBIGUOUS rather than guessing which one the payment belongs to.
        test("R2: IBAN identifies a member with TWO equally-matching open contributions -> AMBIGUOUS") {
            val secretBox = SecretBox(randomSecretBoxKey())
            setUpFixture(displayName = "IBAN Mehrdeutig", amountDue = BigDecimal("48.00"), status = ContributionStatus.OPEN)
            val rawIban = "DE89370400440532013000"
            grantMandate(memberId = memberId, rawIban = rawIban, secretBox = secretBox)

            val secondTierId = Uuid.random()
            val secondContributionId = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[id] = secondTierId
                    it[name] = "Zweiter Standard"
                    it[description] = "Zweiter Standardbeitrag, selber Betrag"
                    it[contributionAmount] = BigDecimal("48.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
                ContributionTable.insert {
                    it[id] = secondContributionId
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = secondTierId
                    it[periodStart] = LocalDate(2027, 1, 1)
                    it[periodEnd] = LocalDate(2027, 12, 31)
                    it[ContributionTable.amountDue] = BigDecimal("48.00")
                    it[ContributionTable.status] = ContributionStatus.OPEN
                    it[createdAt] = kotlinx.datetime.LocalDateTime(2027, 1, 1, 0, 0)
                    it[dueDate] = LocalDate(2027, 1, 15)
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                    it[ContributionTable.paymentReference] = null
                }
            }

            try {
                val outcome =
                    transaction {
                        BankStatementMatcher.match(
                            line =
                                NormalizedLine(
                                    amount = BigDecimal("48.00"),
                                    currency = "EUR",
                                    purpose = "Dauerauftrag ohne Verwendungszweck-Referenz",
                                    counterpartyName = null,
                                    counterpartyIbanRaw = rawIban,
                                ),
                            secretBox = secretBox,
                        )
                    }
                outcome.status shouldBe BankStatementLineStatus.AMBIGUOUS
                outcome.autoPost shouldBe false
                outcome.contributionId shouldBe null
                outcome.explanation shouldContain "IBAN-Abgleich"
            } finally {
                transaction {
                    ContributionTable.deleteWhere { ContributionTable.id eq secondContributionId }
                    MembershipTierTable.deleteWhere { MembershipTierTable.id eq secondTierId }
                }
            }
        }
    })
