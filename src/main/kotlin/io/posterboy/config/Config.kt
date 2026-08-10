package io.posterboy.config

import com.fasterxml.jackson.annotation.JsonIgnore

data class SmtpAuthConfig(
    val enabled: Boolean = false,
    val username: String = "",
    val password: String = ""
)

data class SmtpConfig(
    val port: Int = 2525,
    val auth: SmtpAuthConfig = SmtpAuthConfig()
)

data class RuleMatch(
    val from: String? = null,
    val fromDomain: String? = null,
    val to: String? = null,
    val toDomain: List<String>? = null,
    val toDomainHasMx: Boolean? = null,
    // Negation: the rule matches only when this nested match does NOT match.
    // Lets a broad rule carve out exceptions (e.g. "everyone to Discord EXCEPT
    // a specific noreply sender") without needing a negated predicate on every field.
    val not: RuleMatch? = null
)

data class RoutingRule(
    val match: RuleMatch = RuleMatch(),
    val action: String? = null,
    val actions: List<String>? = null,
    val config: Map<String, Any> = emptyMap()
) {
    @get:JsonIgnore
    val effectiveActions: List<String>
        get() = when {
            !actions.isNullOrEmpty() -> actions
            !action.isNullOrBlank() -> listOf(action)
            else -> emptyList()
        }
}

data class MxHeuristicConfig(
    val enabled: Boolean = true,
    val cacheTtlSeconds: Long = 3600
)

data class RoutingConfig(
    val mxHeuristic: MxHeuristicConfig = MxHeuristicConfig(),
    val rules: List<RoutingRule> = emptyList(),
    val defaultAction: String = "discord"
)

data class AdminConfig(
    val enabled: Boolean = true,
    val port: Int = 8080
)

data class NotificationChannelConfig(
    val plugin: String = "",
    val config: Map<String, Any> = emptyMap()
)

data class ApprovalConfig(
    val enabled: Boolean = false,
    val dbPath: String = "/data/posterboy.db",
    val notifications: List<NotificationChannelConfig> = emptyList(),
    val retentionHours: Int = 72
)

data class PosterboyConfig(
    val smtp: SmtpConfig = SmtpConfig(),
    val admin: AdminConfig = AdminConfig(),
    val plugins: Map<String, Map<String, Any>> = emptyMap(),
    val routing: RoutingConfig = RoutingConfig(),
    val approval: ApprovalConfig = ApprovalConfig(),
    // Directory scanned for drop-in plugin/extension jars. Falls back to the
    // POSTERBOY_PLUGINS_DIR env var, then /data/plugins. Only bundled plugins
    // load if it's absent.
    val pluginsDir: String? = null,
    // Per-extension config blocks, keyed by extension id (see docs/EXTENSIONS.md).
    val extensions: Map<String, Map<String, Any>> = emptyMap()
)
