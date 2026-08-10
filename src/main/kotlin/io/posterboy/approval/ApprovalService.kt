package io.posterboy.approval

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.posterboy.config.ApprovalConfig
import io.posterboy.plugin.DeliveryPlugin
import io.posterboy.plugin.PluginRegistry
import io.posterboy.plugin.RoutingContext
import io.posterboy.routing.RouteDecision
import io.posterboy.smtp.EmailParser
import io.posterboy.smtp.InboundEmail
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class ApprovalService(
    private val config: ApprovalConfig,
    private val pluginRegistry: PluginRegistry,
    private val defaultAction: String,
    private val templateResolver: (String, DeliveryPlugin) -> String
) {

    private val log = LoggerFactory.getLogger(ApprovalService::class.java)
    private val mapper = jacksonObjectMapper()
    private val store = HeldMessageStore(config.dbPath)

    fun holdMessage(
        email: InboundEmail,
        rawBytes: ByteArray,
        decisions: List<RouteDecision>
    ): String {
        val decisionsJson = mapper.writeValueAsString(
            decisions.map { mapOf("pluginId" to it.pluginId, "ruleName" to it.ruleName, "extraConfig" to it.extraConfig) }
        )
        val bodyPreview = (email.bodyPlain ?: email.bodyHtml ?: "").take(200)

        val id = store.insert(
            fromAddr = email.from,
            recipients = email.to,
            subject = email.subject,
            bodyPreview = bodyPreview,
            rawBytes = rawBytes,
            routeDecisions = decisionsJson
        )

        sendNotifications(email)
        return id
    }

    fun listPending(): List<HeldMessage> = store.listPending()

    fun getById(id: String): HeldMessage? = store.getById(id)

    fun approve(id: String) {
        val msg = store.getById(id) ?: throw IllegalArgumentException("Not found: $id")
        require(msg.status == "pending") { "Message is not pending" }

        val email = EmailParser.parse(msg.fromAddr, msg.recipients, msg.rawBytes)

        val decisions = deserializeDecisions(msg.routeDecisions)
        val resolvedDecisions = decisions.map { d ->
            if (d.pluginId == "hold") {
                val forwardTo = d.extraConfig["forward_to"] as? String ?: defaultAction
                d.copy(pluginId = forwardTo)
            } else d
        }

        deliverViaPlugins(email, resolvedDecisions)
        store.updateStatus(id, "approved")
        log.info("Approved and delivered held message: id={} subject={}", id, msg.subject)
    }

    fun dismiss(id: String) {
        val msg = store.getById(id) ?: throw IllegalArgumentException("Not found: $id")
        require(msg.status == "pending") { "Message is not pending" }
        store.updateStatus(id, "dismissed")
        log.info("Dismissed held message: id={} subject={}", id, msg.subject)
    }

    fun prune() {
        store.deleteDecided(config.retentionHours)
    }

    fun shutdown() {
        store.close()
    }

    private fun sendNotifications(email: InboundEmail) {
        for (channel in config.notifications) {
            val plugin = pluginRegistry.get(channel.plugin)
            if (plugin == null) {
                log.warn("Notification plugin not available: {}", channel.plugin)
                continue
            }

            val notificationEmail = InboundEmail(
                from = email.from,
                to = email.to,
                subject = "Held for approval: ${email.subject}",
                bodyPlain = buildString {
                    appendLine("A message has been held for manual approval.")
                    appendLine()
                    appendLine("From: ${email.from}")
                    appendLine("To: ${email.to.joinToString(", ")}")
                    appendLine("Subject: ${email.subject}")
                    appendLine()
                    appendLine("Review it in the Posterboy admin UI.")
                },
                bodyHtml = null,
                attachments = emptyList()
            )

            val context = RoutingContext(
                matchedRule = "hold-notification",
                pluginConfig = channel.config
            )

            try {
                runBlocking { plugin.deliver(notificationEmail, context) }
            } catch (e: Exception) {
                log.error("Failed to send hold notification via {}: {}", channel.plugin, e.message)
            }
        }
    }

    private fun deliverViaPlugins(email: InboundEmail, decisions: List<RouteDecision>) {
        val byPlugin = decisions.groupBy { it.pluginId }
        for ((pluginId, routes) in byPlugin) {
            val plugin = pluginRegistry.get(pluginId)
            if (plugin == null) {
                log.warn("No plugin found for action: {}", pluginId)
                continue
            }

            val context = RoutingContext(
                matchedRule = routes.first().ruleName,
                pluginConfig = routes.first().extraConfig
            )

            try {
                runBlocking {
                    plugin.deliver(
                        resolveTemplates(email, plugin),
                        context
                    )
                }
            } catch (e: Exception) {
                log.error("Plugin {} failed to deliver approved message: {}", pluginId, e.message, e)
            }
        }
    }

    private fun resolveTemplates(email: InboundEmail, plugin: DeliveryPlugin): InboundEmail {
        val resolveBody = { body: String? -> body?.let { templateResolver(it, plugin) } }
        return email.copy(
            bodyPlain = resolveBody(email.bodyPlain),
            bodyHtml = resolveBody(email.bodyHtml),
            subject = templateResolver(email.subject, plugin)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserializeDecisions(json: String): List<RouteDecision> {
        val list: List<Map<String, Any?>> = mapper.readValue(json)
        return list.map { m ->
            RouteDecision(
                pluginId = m["pluginId"] as? String ?: "",
                ruleName = m["ruleName"] as? String,
                extraConfig = (m["extraConfig"] as? Map<String, Any>) ?: emptyMap()
            )
        }
    }
}
