package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus

/** V1.9.226 -- hostile and malformed input of the reader of one member record. */
class LupaBbRecordParserTest :
    FunSpec({
        val lookups =
            LupaBbLookups(
                statuses = mapOf(1L to "Mitglied", 10L to "Storniert", 11L to "Ablehnung", 12L to "in Aufnahme", 13L to "Seltsam"),
                countries = mapOf(1L to "Deutschland", 2L to "Atlantis"),
                regionalAssociations = mapOf(5L to "Testland-Nord"),
            )

        fun wire(
            personNumber: Long? = 100,
            statusId: Long? = 1,
            given: String? = "Erika",
            family: String? = "Muster",
            email: String? = "erika@example.org",
            override: (WireMember) -> WireMember = { it },
        ): WireMember =
            override(
                WireMember(
                    personNumber = personNumber,
                    statusId = statusId,
                    company = null,
                    title = null,
                    givenName = given,
                    familyName = family,
                    nameAffix = null,
                    personType = "nat",
                    birthDate = null,
                    street = "Weg",
                    houseNumber = "1",
                    poBox = null,
                    city = "Braunschweig",
                    postalCode = "38100",
                    addressSupplement = null,
                    countryId = 1,
                    email = email,
                    phone = null,
                    mobile = null,
                    regionalAssociationId = null,
                    joinedAt = null,
                ),
            )

        fun rejected(w: WireMember): ParsedRecord.Rejected =
            LupaBbRecordParser.parse(wire = w, lookups = lookups).shouldBeInstanceOf<ParsedRecord.Rejected>()

        test("a normal record is read and normalised") {
            val parsed =
                LupaBbRecordParser.parse(
                    wire = wire { it.copy(title = " Dr.  ", givenName = "Erika\tMaria", nameAffix = "Jr.") },
                    lookups = lookups,
                )
            val r = parsed.shouldBeInstanceOf<ParsedRecord.Valid>().record
            r.displayName shouldBe "Dr. Erika Maria Muster Jr."
            r.street shouldBe "Weg 1"
            r.country shouldBe LupaBbCountry.Code("DE")
            r.status shouldBe MemberStatus.ACTIVE
            r.email shouldBe "erika@example.org"
        }

        test("a legal person shows its company name") {
            val r =
                LupaBbRecordParser
                    .parse(wire = wire { it.copy(personType = "jur", company = "Muster GmbH") }, lookups = lookups)
                    .shouldBeInstanceOf<ParsedRecord.Valid>()
                    .record
            r.displayName shouldBe "Muster GmbH"
            r.personType shouldBe "LEGAL"
        }

        test("the person number must be a positive number below the limit") {
            for (bad in listOf(null, 0L, -5L, LupaBbRecordParser.MAX_PERSON_NUMBER + 1, Long.MAX_VALUE)) {
                val r = rejected(wire(personNumber = bad))
                r.reason shouldBe LupaBbRowReasonCode.INVALID_PERSON_NUMBER
                r.category shouldBe LupaBbCategory.ERROR
            }
        }

        test("control characters, NUL and DEL anywhere are an error; TAB and newline are collapsed") {
            for (c in listOf('\u0000', '\u0007', '\u001b', '\u007f')) {
                rejected(wire { it.copy(city = "Bra${c}unschweig") }).reason shouldBe LupaBbRowReasonCode.CONTROL_CHARACTER
            }
            val ok =
                LupaBbRecordParser
                    .parse(
                        wire =
                            wire {
                                it.copy(city = "Braun\tschweig\n")
                            },
                        lookups = lookups,
                    ).shouldBeInstanceOf<ParsedRecord.Valid>()
            ok.record.city shouldBe "Braun schweig"
        }

        test("over-long fields are an error naming the field, never the value") {
            val r = rejected(wire { it.copy(city = "x".repeat(201)) })
            r.reason shouldBe LupaBbRowReasonCode.FIELD_TOO_LONG
            r.param shouldBe "CITY"
            rejected(wire(family = "y".repeat(101))).param shouldBe "FAMILY_NAME"
            rejected(wire { it.copy(phone = "1".repeat(51)) }).param shouldBe "PHONE"
        }

        test("invalid dates are an error; empty and 0000-00-00 are no date") {
            rejected(wire { it.copy(birthDate = "31.12.1990") }).reason shouldBe LupaBbRowReasonCode.INVALID_DATE
            rejected(wire { it.copy(birthDate = "1990-02-30") }).reason shouldBe LupaBbRowReasonCode.INVALID_DATE
            rejected(wire { it.copy(joinedAt = "gestern") }).param shouldBe "JOINED_AT"
            for (empty in listOf("", "  ", "0000-00-00")) {
                LupaBbRecordParser
                    .parse(wire = wire { it.copy(birthDate = empty) }, lookups = lookups)
                    .shouldBeInstanceOf<ParsedRecord.Valid>()
                    .record.dateOfBirth shouldBe null
            }
            LupaBbRecordParser
                .parse(wire = wire { it.copy(birthDate = "1990-02-03") }, lookups = lookups)
                .shouldBeInstanceOf<ParsedRecord.Valid>()
                .record.dateOfBirth shouldBe LocalDate(1990, 2, 3)
        }

        test("a missing or invalid e-mail address is kept as a remark, not as a rejection") {
            val none =
                LupaBbRecordParser
                    .parse(
                        wire = wire(email = null),
                        lookups = lookups,
                    ).shouldBeInstanceOf<ParsedRecord.Valid>()
                    .record
            none.email shouldBe null
            none.emailIssue shouldBe LupaBbRowReasonCode.NO_EMAIL
            val bad =
                LupaBbRecordParser
                    .parse(
                        wire = wire(email = "not-an-address"),
                        lookups = lookups,
                    ).shouldBeInstanceOf<ParsedRecord.Valid>()
                    .record
            bad.emailIssue shouldBe LupaBbRowReasonCode.INVALID_EMAIL
            val crlf = LupaBbRecordParser.parse(wire = wire(email = "a@b.de\r\nBcc: x@y.de"), lookups = lookups)
            crlf.shouldBeInstanceOf<ParsedRecord.Valid>().record.email shouldBe null
            val upper =
                LupaBbRecordParser
                    .parse(
                        wire = wire(email = " Erika@Example.ORG "),
                        lookups = lookups,
                    ).shouldBeInstanceOf<ParsedRecord.Valid>()
                    .record
            upper.email shouldBe "erika@example.org"
        }

        test("cancelled and unknown statuses are skipped as a whole, even when the rest is broken") {
            val cancelled = rejected(wire(statusId = 10) { it.copy(birthDate = "garbage") })
            cancelled.category shouldBe LupaBbCategory.SKIPPED
            cancelled.reason shouldBe LupaBbRowReasonCode.CANCELLED_STATUS
            rejected(wire(statusId = 13)).reason shouldBe LupaBbRowReasonCode.UNKNOWN_STATUS
            rejected(wire(statusId = 999)).reason shouldBe LupaBbRowReasonCode.UNKNOWN_STATUS
            rejected(wire(statusId = null)).reason shouldBe LupaBbRowReasonCode.UNKNOWN_STATUS
        }

        test("a record without any name is an error") {
            rejected(wire(given = null, family = null)).reason shouldBe LupaBbRowReasonCode.NO_NAME
            rejected(wire(given = "  ", family = " ")).reason shouldBe LupaBbRowReasonCode.NO_NAME
        }

        test("an unresolvable country leaves the value open instead of guessing; no country is empty") {
            LupaBbRecordParser
                .parse(wire = wire { it.copy(countryId = 2) }, lookups = lookups)
                .shouldBeInstanceOf<ParsedRecord.Valid>()
                .record.country shouldBe LupaBbCountry.Unresolved
            LupaBbRecordParser
                .parse(wire = wire { it.copy(countryId = null) }, lookups = lookups)
                .shouldBeInstanceOf<ParsedRecord.Valid>()
                .record.country shouldBe LupaBbCountry.Empty
            LupaBbRecordParser
                .parse(wire = wire { it.copy(countryId = 424242) }, lookups = lookups)
                .shouldBeInstanceOf<ParsedRecord.Valid>()
                .record.country shouldBe LupaBbCountry.Unresolved
        }

        test("the regional association is carried by its label only when LupaBB sends it") {
            LupaBbRecordParser
                .parse(wire = wire { it.copy(regionalAssociationId = 5) }, lookups = lookups)
                .shouldBeInstanceOf<ParsedRecord.Valid>()
                .record.regionalAssociationLabel shouldBe "Testland-Nord"
            LupaBbRecordParser
                .parse(wire = wire(), lookups = lookups)
                .shouldBeInstanceOf<ParsedRecord.Valid>()
                .record.regionalAssociationLabel shouldBe null
        }

        test("the wire reader survives hostile JSON: wrong types, huge numbers, nested garbage") {
            val bad =
                listOf(
                    "[]",
                    "null",
                    """{"status":true}""",
                    """{"status":true,"data":{}}""",
                    """{"status":true,"data":[1,2]}""",
                    """{"status":true,"data":[{"personennummer":{"x":1}}]}""",
                    """{"status":"nein","data":[]}""",
                    "{not json",
                    "",
                )
            for (body in bad) {
                val e = runCatching { LupaBbWire.parseMemberPage(body) }.exceptionOrNull()
                e.shouldBeInstanceOf<LupaBbFetchException>().code shouldBe LupaBbErrorCode.UNEXPECTED_RESPONSE
            }
            val huge =
                LupaBbWire.parseMemberPage(
                    """{"status":true,"data":[{"personennummer":"99999999999999999999999","mitglied_status":"1"}]}""",
                )
            huge.members.single().personNumber shouldBe null
            LupaBbRecordParser.parse(wire = huge.members.single(), lookups = lookups).shouldBeInstanceOf<ParsedRecord.Rejected>()
        }
    })
