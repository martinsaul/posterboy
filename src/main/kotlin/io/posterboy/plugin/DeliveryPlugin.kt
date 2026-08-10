package io.posterboy.plugin

import io.posterboy.smtp.InboundEmail

data class RoutingContext(
    val matchedRule: String?,
    val pluginConfig: Map<String, Any>
)

interface DeliveryPlugin {
    val id: String
    val name: String

    /** The sender identity this plugin uses (e.g. Gmail alias). Empty if not applicable. */
    val senderAddress: String get() = ""

    fun initialize(config: Map<String, Any>)
    suspend fun deliver(email: InboundEmail, context: RoutingContext)
    fun shutdown() {}
}
