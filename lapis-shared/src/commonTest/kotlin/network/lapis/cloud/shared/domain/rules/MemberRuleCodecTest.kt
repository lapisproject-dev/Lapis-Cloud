package network.lapis.cloud.shared.domain.rules

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemberRuleCodecTest {
    private val id1 = "11111111-1111-1111-1111-111111111111"
    private val id2 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

    private fun reasonOf(
        json: String,
        context: RuleContext = RuleContext.MAILING,
    ): InvalidRuleReason = assertFailsWith<InvalidMemberRuleException> { MemberRuleCodec.parse(text = json, context = context) }.reason

    private fun committeeRule(ids: List<String>) =
        """{"v":1,"op":"ALL","conditions":[{"type":"COMMITTEE","committeeIds":[${ids.joinToString(",") { "\"$it\"" }}]}]}"""

    @Test
    fun roundtripIsCanonicalAndDeterministic() {
        val messy =
            """{"op":"ANY","v":1,"conditions":[{"type":"COMMITTEE","committeeIds":["$id2","$id1"]},
                {"type":"ROLE","values":["TREASURER","BOARD"]},{"type":"JOINED_WITHIN_DAYS","days":30}]}"""
        val rule = MemberRuleCodec.parse(text = messy, context = RuleContext.MAILING)
        val encoded = MemberRuleCodec.encode(rule)
        assertEquals(encoded, MemberRuleCodec.encode(MemberRuleCodec.parse(text = encoded, context = RuleContext.MAILING)))
        assertTrue(encoded.indexOf(id1) < encoded.indexOf(id2), "ids are sorted")
        assertTrue(encoded.indexOf("BOARD") < encoded.indexOf("TREASURER"), "enum values are sorted")
        assertTrue(encoded.contains("\"v\":1"))
    }

    @Test
    fun upperCaseIdsAreNormalised() {
        val rule = MemberRuleCodec.parse(text = committeeRule(listOf(id2.uppercase())), context = RuleContext.MAILING)
        assertEquals(setOf(id2), (rule.conditions.single() as RuleCondition.Committee).committeeIds)
    }

    @Test
    fun unknownFieldsTypesAndGarbageAreMalformed() {
        assertEquals(InvalidRuleReason.MALFORMED, reasonOf(json = """{"v":1,"op":"ALL","conditions":[],"extra":1}"""))
        assertEquals(InvalidRuleReason.MALFORMED, reasonOf(json = """{"v":1,"op":"ALL","conditions":[{"type":"EVIL","x":1}]}"""))
        assertEquals(InvalidRuleReason.MALFORMED, reasonOf(json = """{"v":1,"op":"NONE","conditions":[]}"""))
        assertEquals(InvalidRuleReason.MALFORMED, reasonOf(json = "not json"))
        assertEquals(InvalidRuleReason.MALFORMED, reasonOf(json = ""))
        assertEquals(
            InvalidRuleReason.MALFORMED,
            reasonOf(json = """{"v":1,"op":"ALL","conditions":[{"type":"ROLE","values":["SUPERUSER"]}]}"""),
        )
        assertEquals(InvalidRuleReason.MALFORMED, reasonOf(json = """{"__proto__":{"x":1},"v":1,"op":"ALL","conditions":[]}"""))
    }

    @Test
    fun versionMustBeOne() {
        assertEquals(InvalidRuleReason.UNSUPPORTED_VERSION, reasonOf(json = """{"v":0,"op":"ALL","conditions":[]}"""))
        assertEquals(InvalidRuleReason.UNSUPPORTED_VERSION, reasonOf(json = """{"v":2,"op":"ALL","conditions":[]}"""))
    }

    @Test
    fun countAndSizeLimits() {
        val nine = (1..9).joinToString(",") { """{"type":"JOINED_WITHIN_DAYS","days":$it}""" }
        assertEquals(InvalidRuleReason.TOO_MANY_CONDITIONS, reasonOf(json = """{"v":1,"op":"ALL","conditions":[$nine]}"""))
        val eight = (1..8).joinToString(",") { """{"type":"JOINED_WITHIN_DAYS","days":$it}""" }
        assertEquals(
            8,
            MemberRuleCodec.parse(text = """{"v":1,"op":"ALL","conditions":[$eight]}""", context = RuleContext.MAILING).conditions.size,
        )

        val manyIds = (1..21).map { "00000000-0000-0000-0000-${it.toString().padStart(12, '0')}" }
        assertEquals(InvalidRuleReason.TOO_MANY_VALUES, reasonOf(json = committeeRule(manyIds)))
        assertEquals(InvalidRuleReason.EMPTY_VALUES, reasonOf(json = committeeRule(emptyList())))
        assertEquals(InvalidRuleReason.EMPTY_VALUES, reasonOf(json = """{"v":1,"op":"ALL","conditions":[{"type":"ROLE","values":[]}]}"""))

        assertEquals(
            InvalidRuleReason.TOO_LARGE,
            reasonOf(json = """{"v":1,"op":"ALL","conditions":[],"x":"${"a".repeat(MemberRuleCodec.MAX_BYTES)}"}"""),
        )
        // multi-byte characters count as bytes, not as characters
        val multibyte = "ä".repeat(MemberRuleCodec.MAX_BYTES / 2 + 1)
        assertEquals(InvalidRuleReason.TOO_LARGE, reasonOf(json = """{"v":1,"op":"ALL","conditions":[],"x":"$multibyte"}"""))
    }

    @Test
    fun idsMustBeUuids() {
        assertEquals(InvalidRuleReason.INVALID_ID, reasonOf(json = committeeRule(listOf("not-a-uuid"))))
        assertEquals(InvalidRuleReason.INVALID_ID, reasonOf(json = committeeRule(listOf("'; DROP TABLE member; --"))))
        assertEquals(InvalidRuleReason.INVALID_ID, reasonOf(json = committeeRule(listOf("__proto__"))))
        assertEquals(InvalidRuleReason.INVALID_ID, reasonOf(json = committeeRule(listOf("$id1 "))))
        assertEquals(InvalidRuleReason.INVALID_ID, reasonOf(json = """{"v":1,"op":"ALL","conditions":[{"type":"TIER","tierIds":["1"]}]}"""))
    }

    @Test
    fun daysRange() {
        assertEquals(
            InvalidRuleReason.DAYS_OUT_OF_RANGE,
            reasonOf(json = """{"v":1,"op":"ALL","conditions":[{"type":"JOINED_WITHIN_DAYS","days":0}]}"""),
        )
        assertEquals(
            InvalidRuleReason.DAYS_OUT_OF_RANGE,
            reasonOf(json = """{"v":1,"op":"ALL","conditions":[{"type":"JOINED_WITHIN_DAYS","days":3651}]}"""),
        )
        assertEquals(
            InvalidRuleReason.DAYS_OUT_OF_RANGE,
            reasonOf(json = """{"v":1,"op":"ALL","conditions":[{"type":"JOINED_WITHIN_DAYS","days":-5}]}"""),
        )
        MemberRuleCodec.parse(
            text = """{"v":1,"op":"ALL","conditions":[{"type":"JOINED_WITHIN_DAYS","days":3650}]}""",
            context = RuleContext.MAILING,
        )
        MemberRuleCodec.parse(
            text = """{"v":1,"op":"ALL","conditions":[{"type":"JOINED_WITHIN_DAYS","days":1}]}""",
            context = RuleContext.MAILING,
        )
    }

    @Test
    fun statusBlockOnlyInCommitteeContext() {
        val json = """{"v":1,"op":"ALL","conditions":[{"type":"STATUS","values":["ACTIVE"]}]}"""
        assertEquals(InvalidRuleReason.BLOCK_NOT_ALLOWED_IN_CONTEXT, reasonOf(json = json, context = RuleContext.MAILING))
        val rule = MemberRuleCodec.parse(text = json, context = RuleContext.COMMITTEE)
        assertEquals(setOf(MemberStatus.ACTIVE), (rule.conditions.single() as RuleCondition.Status).values)
    }

    @Test
    fun exceptionMessageNeverCarriesTheInput() {
        val secret = "TOPSECRET-MEMBER-NAME"
        listOf(
            """{"v":1,"op":"ALL","conditions":[{"type":"$secret"}]}""",
            committeeRule(listOf(secret)),
            "$secret not json",
        ).forEach { json ->
            val e = assertFailsWith<InvalidMemberRuleException> { MemberRuleCodec.parse(text = json, context = RuleContext.MAILING) }
            assertFalse(e.message.orEmpty().contains(secret))
            assertEquals(e.reason.name, e.message)
        }
    }

    @Test
    fun roleValuesAreEnumsOnly() {
        val rule =
            MemberRuleCodec.parse(
                text = """{"v":1,"op":"ANY","conditions":[{"type":"ROLE","values":["BOARD"]}]}""",
                context = RuleContext.MAILING,
            )
        assertEquals(setOf(AccountRole.BOARD), (rule.conditions.single() as RuleCondition.Role).values)
    }
}
