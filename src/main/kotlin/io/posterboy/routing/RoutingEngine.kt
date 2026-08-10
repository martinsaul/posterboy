package io.posterboy.routing

import io.posterboy.config.RoutingConfig
import io.posterboy.config.RoutingRule
import io.posterboy.config.RuleMatch
import io.posterboy.smtp.InboundEmail
import org.slf4j.LoggerFactory

data class RouteDecision(
    val pluginId: String,
    val ruleName: String?,
    val extraConfig: Map<String, Any>
)

class RoutingEngine(
    private val config: RoutingConfig,
    private val mxResolver: MxResolver?
) {

    private val log = LoggerFactory.getLogger(RoutingEngine::class.java)

    fun route(email: InboundEmail): List<RouteDecision> {
        return email.to.flatMap { recipient ->
            routeRecipient(email, recipient)
        }
    }

    // Per recipient: collect actions from every matching rule (fan-out).
    // If no rule matched, fall back to defaultAction.
    // Duplicate plugin ids across rules are collapsed (first occurrence wins for ruleName/config).
    private fun routeRecipient(email: InboundEmail, recipient: String): List<RouteDecision> {
        val recipientDomain = recipient.substringAfter("@").lowercase()

        val decisions = mutableListOf<RouteDecision>()
        val seen = mutableSetOf<String>()

        for (rule in config.rules) {
            if (!matches(rule.match, email.from, recipient, recipientDomain)) continue
            val actions = rule.effectiveActions
            if (actions.isEmpty()) {
                log.warn("Rule matched for {} but declared no action(s); skipping", recipient)
                continue
            }
            for (action in actions) {
                if (seen.add(action)) {
                    log.debug("Rule matched for {}: action={}", recipient, action)
                    decisions.add(RouteDecision(action, action, rule.config))
                }
            }
        }

        if (decisions.isEmpty()) {
            log.debug("No rule matched for {}, using default: {}", recipient, config.defaultAction)
            decisions.add(RouteDecision(config.defaultAction, null, emptyMap()))
        }

        return decisions
    }

    private fun matches(match: RuleMatch, from: String, to: String, toDomain: String): Boolean {
        // All specified criteria must match (AND logic)
        match.from?.let { pattern ->
            if (!globMatch(from.lowercase(), pattern.lowercase())) return false
        }

        match.fromDomain?.let { pattern ->
            val fromDomain = from.substringAfter("@").lowercase()
            if (!globMatch(fromDomain, pattern.lowercase())) return false
        }

        match.to?.let { pattern ->
            if (!globMatch(to.lowercase(), pattern.lowercase())) return false
        }

        match.toDomain?.let { domains ->
            val matched = domains.any { pattern -> globMatch(toDomain, pattern.lowercase()) }
            if (!matched) return false
        }

        match.toDomainHasMx?.let { shouldHaveMx ->
            if (mxResolver == null) return false
            val hasMx = mxResolver.hasMxRecord(toDomain)
            if (hasMx != shouldHaveMx) return false
        }

        // Negation: if a `not` sub-match is present and it matches, this rule is
        // excluded. Evaluated last so it can veto an otherwise-successful match.
        match.not?.let { negated ->
            if (matches(negated, from, to, toDomain)) return false
        }

        return true
    }

    companion object {
        fun globMatch(value: String, pattern: String): Boolean {
            val regex = buildString {
                append("^")
                for (ch in pattern) {
                    when (ch) {
                        '*' -> append(".*")
                        '?' -> append(".")
                        '.' -> append("\\.")
                        else -> append(ch)
                    }
                }
                append("$")
            }
            return Regex(regex).matches(value)
        }
    }
}
