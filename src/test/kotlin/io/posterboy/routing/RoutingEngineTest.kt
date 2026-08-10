package io.posterboy.routing

import io.posterboy.config.*
import io.posterboy.smtp.InboundEmail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingEngineTest {

    private fun email(from: String = "sender@example.com", to: List<String>, subject: String = "Test") =
        InboundEmail(from, to, subject, "body", null, emptyList())

    @Test
    fun `example domain routes to discord`() {
        val config = RoutingConfig(
            mxHeuristic = MxHeuristicConfig(enabled = false),
            rules = listOf(
                RoutingRule(
                    match = RuleMatch(toDomain = listOf("example.com", "*.example.com")),
                    action = "discord"
                )
            ),
            defaultAction = "gmail"
        )
        val engine = RoutingEngine(config, null)

        val decisions = engine.route(email(to = listOf("user@example.com")))
        assertEquals(1, decisions.size)
        assertEquals("discord", decisions[0].pluginId)
    }

    @Test
    fun `example subdomain routes to discord`() {
        val config = RoutingConfig(
            mxHeuristic = MxHeuristicConfig(enabled = false),
            rules = listOf(
                RoutingRule(
                    match = RuleMatch(toDomain = listOf("example.com", "*.example.com")),
                    action = "discord"
                )
            ),
            defaultAction = "gmail"
        )
        val engine = RoutingEngine(config, null)

        val decisions = engine.route(email(to = listOf("alerts@home.example.com")))
        assertEquals("discord", decisions[0].pluginId)
    }

    @Test
    fun `unmatched recipient uses default action`() {
        val config = RoutingConfig(
            mxHeuristic = MxHeuristicConfig(enabled = false),
            rules = listOf(
                RoutingRule(
                    match = RuleMatch(toDomain = listOf("example.com")),
                    action = "discord"
                )
            ),
            defaultAction = "discord"
        )
        val engine = RoutingEngine(config, null)

        val decisions = engine.route(email(to = listOf("someone@randomdomain.org")))
        assertEquals(1, decisions.size)
        assertEquals("discord", decisions[0].pluginId)
    }

    @Test
    fun `all matching rules fire, deduplicated by plugin id`() {
        // Fan-out: every matching rule contributes its action(s).
        val config = RoutingConfig(
            mxHeuristic = MxHeuristicConfig(enabled = false),
            rules = listOf(
                // Universal rule: everyone goes to Discord
                RoutingRule(match = RuleMatch(), action = "discord"),
                // Domain-specific rule: gmail.com recipients also go to Gmail
                RoutingRule(
                    match = RuleMatch(toDomain = listOf("gmail.com")),
                    action = "gmail"
                ),
                // Glob rule that also matches gmail.com — should be deduped by plugin id
                RoutingRule(
                    match = RuleMatch(toDomain = listOf("*.com")),
                    action = "gmail"
                )
            ),
            defaultAction = "discord"
        )
        val engine = RoutingEngine(config, null)

        val decisions = engine.route(email(to = listOf("user@gmail.com")))
        val plugins = decisions.map { it.pluginId }.toSet()
        assertEquals(setOf("discord", "gmail"), plugins)
        assertEquals(2, decisions.size) // no duplicate gmail despite two matching rules
    }

    @Test
    fun `rule with no match block matches everything`() {
        val config = RoutingConfig(
            mxHeuristic = MxHeuristicConfig(enabled = false),
            rules = listOf(
                RoutingRule(action = "discord") // no match clause → matches every recipient
            ),
            defaultAction = "gmail"
        )
        val engine = RoutingEngine(config, null)

        val decisions = engine.route(email(to = listOf("anyone@anywhere.tld")))
        assertEquals(1, decisions.size)
        assertEquals("discord", decisions[0].pluginId)
    }

    @Test
    fun `actions list declares multiple plugins on a single rule`() {
        val config = RoutingConfig(
            mxHeuristic = MxHeuristicConfig(enabled = false),
            rules = listOf(
                RoutingRule(
                    match = RuleMatch(toDomain = listOf("example.com")),
                    actions = listOf("discord", "gmail")
                )
            ),
            defaultAction = "discord"
        )
        val engine = RoutingEngine(config, null)

        val decisions = engine.route(email(to = listOf("ops@example.com")))
        assertEquals(setOf("discord", "gmail"), decisions.map { it.pluginId }.toSet())
    }

    @Test
    fun `multiple recipients route independently`() {
        val config = RoutingConfig(
            mxHeuristic = MxHeuristicConfig(enabled = false),
            rules = listOf(
                RoutingRule(
                    match = RuleMatch(toDomain = listOf("example.com")),
                    action = "discord"
                )
            ),
            defaultAction = "gmail"
        )
        val engine = RoutingEngine(config, null)

        val decisions = engine.route(email(to = listOf("a@example.com", "b@gmail.com")))
        assertEquals(2, decisions.size)
        assertTrue(decisions.any { it.pluginId == "discord" })
        assertTrue(decisions.any { it.pluginId == "gmail" })
    }

    @Test
    fun `from domain matching works`() {
        val config = RoutingConfig(
            mxHeuristic = MxHeuristicConfig(enabled = false),
            rules = listOf(
                RoutingRule(
                    match = RuleMatch(fromDomain = "uptimekuma.local"),
                    action = "discord"
                )
            ),
            defaultAction = "gmail"
        )
        val engine = RoutingEngine(config, null)

        val decisions = engine.route(email(from = "alerts@uptimekuma.local", to = listOf("someone@gmail.com")))
        assertEquals("discord", decisions[0].pluginId)
    }

    @Test
    fun `not sub-match excludes an otherwise-matching rule`() {
        // Catch-all Discord rule that carves out the noreply sender.
        val config = RoutingConfig(
            mxHeuristic = MxHeuristicConfig(enabled = false),
            rules = listOf(
                RoutingRule(
                    match = RuleMatch(not = RuleMatch(from = "noreply@example.com")),
                    action = "discord"
                )
            ),
            defaultAction = "gmail"
        )
        val engine = RoutingEngine(config, null)

        // Non-noreply sender still hits Discord.
        val normal = engine.route(email(from = "uptime@home.example.com", to = listOf("alerts@home.example.com")))
        assertEquals("discord", normal[0].pluginId)

        // The excluded sender falls from the Discord rule -> falls through to default.
        val noreply = engine.route(email(from = "noreply@example.com", to = listOf("abuse@isp.example")))
        assertEquals(1, noreply.size)
        assertEquals("gmail", noreply[0].pluginId)
    }

    @Test
    fun `noreply reaches gmail but not discord in the full ruleset`() {
        // Mirrors the SHAPE of the deployed ruleset: catch-all Discord (minus noreply)
        // plus a second rule sending to gmail. The deployed gmail rule keys on
        // toDomainHasMx; here a toDomain="*" glob stands in for it so the test needs
        // no MxResolver. Excluded sender must deliver via gmail with no Discord ping.
        val config = RoutingConfig(
            mxHeuristic = MxHeuristicConfig(enabled = false),
            rules = listOf(
                RoutingRule(
                    match = RuleMatch(not = RuleMatch(from = "noreply@example.com")),
                    actions = listOf("discord")
                ),
                RoutingRule(
                    match = RuleMatch(toDomain = listOf("*")),
                    actions = listOf("gmail")
                )
            ),
            defaultAction = "discord"
        )
        val engine = RoutingEngine(config, null)

        val noreply = engine.route(email(from = "noreply@example.com", to = listOf("abuse@microsoft.com")))
        assertEquals(setOf("gmail"), noreply.map { it.pluginId }.toSet())

        val normalExternal = engine.route(email(from = "someone@home.example.com", to = listOf("user@microsoft.com")))
        assertEquals(setOf("discord", "gmail"), normalExternal.map { it.pluginId }.toSet())
    }

    @Test
    fun `glob matching handles wildcards`() {
        assertEquals(true, RoutingEngine.globMatch("home.example.com", "*.example.com"))
        assertEquals(true, RoutingEngine.globMatch("deep.sub.example.com", "*.example.com"))
        assertEquals(false, RoutingEngine.globMatch("example.com", "*.example.com"))
        assertEquals(true, RoutingEngine.globMatch("example.com", "example.com"))
        assertEquals(true, RoutingEngine.globMatch("gmail.com", "*.com"))
    }
}
