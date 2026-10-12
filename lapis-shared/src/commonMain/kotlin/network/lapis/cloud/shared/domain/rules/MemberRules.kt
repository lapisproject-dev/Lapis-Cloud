package network.lapis.cloud.shared.domain.rules

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.reflect.KClass

/**
 * Welle V1.9.111 "Dynamische Mailinglisten" -- a small, reusable rule over members ("who belongs to this group?"), evaluated in memory
 * against [MemberFacts]. Lives in `lapis-shared` so the server (list resolution, send) and the client (explanation, builder) share ONE
 * definition, and so a later wave (V1.9.112, committee rules) only picks the rule and a different [RuleContext].
 *
 * **Security model.** A rule is persisted as JSON, so it is untrusted input: the ONLY way in is [MemberRuleCodec.parse], a strict
 * whitelist parser (closed set of condition types, size/count limits, UUID-shaped ids, per-context whitelist). A rule can never contain a
 * member id, a name or an address -- only enums, committee ids, membership-tier ids and a day count -- so neither the stored rule nor an
 * audit snapshot of it can leak personal data, and the rule offers no way to name an individual.
 */
@Serializable
enum class RuleOp { ALL, ANY }

/** Where a rule is used; decides which condition types are allowed ([RuleContextPolicy]). */
enum class RuleContext { MAILING, COMMITTEE }

@Serializable
data class MemberRule(
    val v: Int = 1,
    val op: RuleOp,
    val conditions: List<RuleCondition>,
)

@Serializable
sealed interface RuleCondition {
    /** The member's status is one of [values]. Not offered for mailing lists (they always address ACTIVE members only). */
    @Serializable
    @SerialName("STATUS")
    data class Status(
        val values: Set<MemberStatus>,
    ) : RuleCondition

    /** At least one of the member's accounts has one of [values] as role. */
    @Serializable
    @SerialName("ROLE")
    data class Role(
        val values: Set<AccountRole>,
    ) : RuleCondition

    /** The member joined at most [days] days ago (today counts as 0; a join date in the future never matches). */
    @Serializable
    @SerialName("JOINED_WITHIN_DAYS")
    data class JoinedWithinDays(
        val days: Int,
    ) : RuleCondition

    /** The member holds an active seat in at least one of the committees. */
    @Serializable
    @SerialName("COMMITTEE")
    data class Committee(
        val committeeIds: Set<String>,
    ) : RuleCondition

    /** The member's membership tier is one of the tiers. */
    @Serializable
    @SerialName("TIER")
    data class Tier(
        val tierIds: Set<String>,
    ) : RuleCondition
}

/** What a rule is evaluated against -- built by the server loader, never by a client. Ids are canonical lower-case UUID strings. */
data class MemberFacts(
    val status: MemberStatus,
    val roles: Set<AccountRole>,
    val joinedOn: LocalDate,
    val activeCommitteeIds: Set<String>,
    val tierId: String?,
)

fun RuleCondition.matches(
    facts: MemberFacts,
    today: LocalDate,
): Boolean =
    when (this) {
        is RuleCondition.Status -> facts.status in values
        is RuleCondition.Role -> facts.roles.any { it in values }
        is RuleCondition.JoinedWithinDays -> facts.joinedOn.daysUntil(today).let { it in 0..days }
        is RuleCondition.Committee -> facts.activeCommitteeIds.any { it in committeeIds }
        is RuleCondition.Tier -> facts.tierId != null && facts.tierId in tierIds
    }

/** `ALL []` is true, `ANY []` is false. */
fun MemberRule.matches(
    facts: MemberFacts,
    today: LocalDate,
): Boolean =
    when (op) {
        RuleOp.ALL -> conditions.all { it.matches(facts = facts, today = today) }
        RuleOp.ANY -> conditions.any { it.matches(facts = facts, today = today) }
    }

object RuleContextPolicy {
    private val mailing: Set<KClass<out RuleCondition>> =
        setOf(
            RuleCondition.Role::class,
            RuleCondition.JoinedWithinDays::class,
            RuleCondition.Committee::class,
            RuleCondition.Tier::class,
        )
    private val committee: Set<KClass<out RuleCondition>> = mailing + RuleCondition.Status::class

    fun allowed(ctx: RuleContext): Set<KClass<out RuleCondition>> =
        when (ctx) {
            RuleContext.MAILING -> mailing
            RuleContext.COMMITTEE -> committee
        }
}

/** Why a rule was rejected. The exception message is only this name -- never any part of the rejected input. */
enum class InvalidRuleReason {
    TOO_LARGE,
    MALFORMED,
    UNSUPPORTED_VERSION,
    TOO_MANY_CONDITIONS,
    EMPTY_VALUES,
    TOO_MANY_VALUES,
    INVALID_ID,
    DAYS_OUT_OF_RANGE,
    BLOCK_NOT_ALLOWED_IN_CONTEXT,
}

class InvalidMemberRuleException(
    val reason: InvalidRuleReason,
) : IllegalArgumentException(reason.name)

object MemberRuleCodec {
    const val MAX_BYTES = 4096
    const val MAX_CONDITIONS = 8
    const val MAX_VALUES = 20
    val DAYS_RANGE = 1..3650

    private val ID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = false
            encodeDefaults = true
        }

    /**
     * Strictly parses [text] for [context]. Ids are normalised to lower case (an upper-case UUID is accepted and canonicalised, anything
     * else is `INVALID_ID`). Throws [InvalidMemberRuleException] and nothing else.
     */
    fun parse(
        text: String,
        context: RuleContext,
    ): MemberRule {
        if (text.encodeToByteArray().size > MAX_BYTES) throw InvalidMemberRuleException(InvalidRuleReason.TOO_LARGE)
        val decoded =
            try {
                json.decodeFromString(MemberRule.serializer(), text)
            } catch (e: SerializationException) {
                throw InvalidMemberRuleException(InvalidRuleReason.MALFORMED)
            } catch (e: IllegalArgumentException) {
                throw InvalidMemberRuleException(InvalidRuleReason.MALFORMED)
            }
        if (decoded.v != 1) throw InvalidMemberRuleException(InvalidRuleReason.UNSUPPORTED_VERSION)
        if (decoded.conditions.size > MAX_CONDITIONS) throw InvalidMemberRuleException(InvalidRuleReason.TOO_MANY_CONDITIONS)
        val allowed = RuleContextPolicy.allowed(context)
        return MemberRule(
            v = 1,
            op = decoded.op,
            conditions =
                decoded.conditions.map { condition ->
                    if (condition::class !in allowed) throw InvalidMemberRuleException(InvalidRuleReason.BLOCK_NOT_ALLOWED_IN_CONTEXT)
                    validated(condition)
                },
        )
    }

    /** The canonical form: values sorted, ids lower case. The server stores exactly `encode(parse(x))`. */
    fun encode(rule: MemberRule): String = json.encodeToString(MemberRule.serializer(), canonical(rule))

    private fun checkCount(size: Int) {
        if (size == 0) throw InvalidMemberRuleException(InvalidRuleReason.EMPTY_VALUES)
        if (size > MAX_VALUES) throw InvalidMemberRuleException(InvalidRuleReason.TOO_MANY_VALUES)
    }

    private fun normalisedIds(ids: Set<String>): Set<String> {
        checkCount(ids.size)
        val normalised =
            ids.map { id ->
                val lower = id.lowercase()
                if (!ID_REGEX.matches(lower)) throw InvalidMemberRuleException(InvalidRuleReason.INVALID_ID)
                lower
            }
        return normalised.sorted().toCollection(LinkedHashSet())
    }

    private fun validated(condition: RuleCondition): RuleCondition =
        when (condition) {
            is RuleCondition.Status -> {
                checkCount(condition.values.size)
                condition
            }
            is RuleCondition.Role -> {
                checkCount(condition.values.size)
                condition
            }
            is RuleCondition.JoinedWithinDays -> {
                if (condition.days !in DAYS_RANGE) throw InvalidMemberRuleException(InvalidRuleReason.DAYS_OUT_OF_RANGE)
                condition
            }
            is RuleCondition.Committee -> RuleCondition.Committee(normalisedIds(condition.committeeIds))
            is RuleCondition.Tier -> RuleCondition.Tier(normalisedIds(condition.tierIds))
        }

    private fun canonical(rule: MemberRule): MemberRule =
        MemberRule(
            v = rule.v,
            op = rule.op,
            conditions =
                rule.conditions.map { condition ->
                    when (condition) {
                        is RuleCondition.Status -> RuleCondition.Status(condition.values.sortedBy { it.name }.toCollection(LinkedHashSet()))
                        is RuleCondition.Role -> RuleCondition.Role(condition.values.sortedBy { it.name }.toCollection(LinkedHashSet()))
                        is RuleCondition.JoinedWithinDays -> condition
                        is RuleCondition.Committee -> RuleCondition.Committee(condition.committeeIds.sorted().toCollection(LinkedHashSet()))
                        is RuleCondition.Tier -> RuleCondition.Tier(condition.tierIds.sorted().toCollection(LinkedHashSet()))
                    }
                },
        )
}

/** Why a member is NOT addressed by a mailing, in the order the checks run. */
enum class Exclusion { NOT_ACTIVE, ANONYMISED, DECEASED, NO_EMAIL, OPTED_OUT }

data class RecipientFacts(
    val member: MemberFacts,
    val anonymised: Boolean,
    val deceased: Boolean,
    val hasValidEmail: Boolean,
    val optedOut: Boolean,
)

/**
 * The recipient guard, separate from the rule: whoever the rule selects is still only mailed when ACTIVE, not anonymised, not deceased,
 * with a valid address and (unless the list is [mandatory]) not opted out. A mandatory list skips only the opt-out check.
 */
object MailingRecipientGuard {
    fun exclusionOf(
        f: RecipientFacts,
        mandatory: Boolean,
    ): Exclusion? =
        when {
            f.member.status != MemberStatus.ACTIVE -> Exclusion.NOT_ACTIVE
            f.anonymised -> Exclusion.ANONYMISED
            f.deceased -> Exclusion.DECEASED
            !f.hasValidEmail -> Exclusion.NO_EMAIL
            !mandatory && f.optedOut -> Exclusion.OPTED_OUT
            else -> null
        }

    fun eligible(
        f: RecipientFacts,
        mandatory: Boolean,
    ): Boolean = exclusionOf(f = f, mandatory = mandatory) == null
}
