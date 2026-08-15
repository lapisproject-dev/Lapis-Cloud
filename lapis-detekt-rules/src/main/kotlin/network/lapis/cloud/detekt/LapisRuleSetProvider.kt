package network.lapis.cloud.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class LapisRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("lapis")

    // Detekt 2.0 takes rule *constructor references*, not instances.
    override fun instance() = RuleSet(ruleSetId, listOf(::RequireNamedArguments))
}
