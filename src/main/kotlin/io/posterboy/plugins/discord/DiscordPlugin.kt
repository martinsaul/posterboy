package io.posterboy.plugins.discord

import io.posterboy.plugin.DeliveryPlugin
import io.posterboy.plugin.RoutingContext
import io.posterboy.smtp.InboundEmail
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class DiscordPlugin : DeliveryPlugin {

    override val id = "discord"
    override val name = "Discord Webhook"

    private val log = LoggerFactory.getLogger(DiscordPlugin::class.java)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var defaultWebhook: String = ""
    private val aliases = mutableMapOf<String, String>() // alias name -> webhook URL

    @Suppress("UNCHECKED_CAST")
    override fun initialize(config: Map<String, Any>) {
        defaultWebhook = config["default_webhook"] as? String
            ?: throw IllegalArgumentException("Discord plugin requires 'default_webhook' config")

        val aliasConfig = config["aliases"] as? Map<String, Map<String, Any>> ?: emptyMap()
        aliasConfig.forEach { (alias, props) ->
            val webhook = props["webhook"] as? String
                ?: throw IllegalArgumentException("Discord alias '$alias' requires 'webhook' config")
            aliases[alias.lowercase()] = webhook
        }

        log.info("Discord plugin initialized with {} alias(es), default webhook configured", aliases.size)
    }

    override suspend fun deliver(email: InboundEmail, context: RoutingContext) {
        val webhook = resolveWebhook(email)
        val content = DiscordFormatter.format(email)

        // Discord webhook payload
        val json = """{"content": ${escapeJson(content)}}"""

        val request = Request.Builder()
            .url(webhook)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                log.info("Delivered to Discord: from={} subject={}", email.from, email.subject)
            } else {
                log.error(
                    "Discord webhook failed: status={} body={}",
                    response.code, response.body?.string()?.take(200)
                )
            }
        }
    }

    private fun resolveWebhook(email: InboundEmail): String {
        // Check if any recipient matches an alias (local part before @)
        for (recipient in email.to) {
            val localPart = recipient.substringBefore("@").lowercase()
            aliases[localPart]?.let { return it }
        }
        return defaultWebhook
    }

    private fun escapeJson(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    override fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
