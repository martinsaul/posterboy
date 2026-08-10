package io.posterboy.plugins.gmail

import io.posterboy.plugin.DeliveryPlugin
import io.posterboy.plugin.RoutingContext
import io.posterboy.smtp.InboundEmail
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GmailPlugin : DeliveryPlugin {

    override val id = "gmail"
    override val name = "Gmail"
    override val senderAddress: String get() = defaultAlias

    private val log = LoggerFactory.getLogger(GmailPlugin::class.java)

    private var strategy: SendStrategy? = null
    private var defaultAlias: String = ""

    // Direct-to-Discord operator alert channel. Bypasses the router so a broken
    // gmail plugin cannot accidentally self-route its own failure email back
    // into itself.
    private var alertWebhook: String = ""
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val lastAlertAt = AtomicReference<Instant?>(null)
    private val alertCooldown: Duration = Duration.ofHours(1)

    val apiStrategy: ApiSendStrategy? get() = strategy as? ApiSendStrategy
    val isConfigured: Boolean get() = strategy != null

    @Suppress("UNCHECKED_CAST")
    override fun initialize(config: Map<String, Any>) {
        val strategyName = config["strategy"] as? String ?: "smtp"

        strategy = when (strategyName) {
            "smtp" -> SmtpSendStrategy()
            "api" -> ApiSendStrategy()
            else -> throw IllegalArgumentException("Unknown Gmail strategy: $strategyName")
        }

        defaultAlias = config["default_alias"] as? String
            ?: (config["smtp"] as? Map<String, Any>)?.get("username") as? String
            ?: ""

        alertWebhook = config["alert_webhook"] as? String ?: ""

        strategy!!.initialize(config)

        log.info(
            "Gmail plugin initialized: strategy={}, default_alias={}, alerting={}",
            strategyName, defaultAlias, if (alertWebhook.isBlank()) "disabled" else "enabled"
        )

        // Startup health check: if the API strategy failed live validation
        // during its own initialize(), alert the operator now. This catches
        // the "refresh token was revoked since last boot" case on restart
        // instead of waiting for the first real send.
        val api = apiStrategy
        if (api != null && api.hasCredentials && !api.isAuthorized) {
            log.error("Gmail API configured but not authorized at startup")
            fireAlert(
                "Gmail plugin unauthorized at startup",
                "The Gmail API refresh token is invalid (revoked, scope-changed, or password reset). " +
                    "Messages routed to Gmail will fail until someone re-authorizes via the admin UI."
            )
        }
    }

    override suspend fun deliver(email: InboundEmail, context: RoutingContext) {
        val s = strategy ?: throw IllegalStateException("Gmail plugin not configured")
        val fromAlias = context.pluginConfig["from_alias"] as? String
            ?: defaultAlias.ifEmpty { throw IllegalStateException("No default_alias configured for Gmail") }
        val recipients = (context.pluginConfig["to"] as? String)?.let { listOf(it) } ?: email.to

        try {
            s.send(email, fromAlias, recipients)
        } catch (e: Exception) {
            log.error("Gmail delivery failed: from={} to={} subject={}", fromAlias, email.to, email.subject, e)
            if (ApiSendStrategy.looksLikeAuthFailure(e)) {
                fireAlert(
                    "Gmail plugin OAuth failure",
                    "Delivery failed with an auth error: ${e.message}. " +
                        "The Gmail refresh token has likely been revoked. " +
                        "Messages will keep failing until someone re-authorizes at " +
                        "the admin UI. " +
                        "Subject of the dropped message: '${email.subject}'."
                )
            }
            throw e
        }
    }

    /**
     * Rate-limited alert to a Discord webhook. Alerts more than once per
     * [alertCooldown] are silently dropped to avoid flooding the channel.
     * Failures posting the alert are logged but never propagate -- alerting
     * must not break the main delivery path.
     */
    private fun fireAlert(title: String, body: String) {
        if (alertWebhook.isBlank()) return

        val now = Instant.now()
        val last = lastAlertAt.get()
        if (last != null && Duration.between(last, now) < alertCooldown) {
            log.debug("Skipping gmail alert (cooldown active): {}", title)
            return
        }
        if (!lastAlertAt.compareAndSet(last, now)) {
            // Someone else raced us; they'll handle it.
            return
        }

        try {
            val content = "**Posterboy - Gmail:** $title\n$body"
            val json = """{"content": ${jsonEscape(content)}}"""
            val req = Request.Builder()
                .url(alertWebhook)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    log.info("Sent Gmail health alert to webhook: {}", title)
                } else {
                    log.warn("Gmail alert webhook returned status={}", resp.code)
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to deliver Gmail health alert: {}", e.message)
        }
    }

    private fun jsonEscape(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    override fun shutdown() {
        strategy?.shutdown()
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {
            // best-effort cleanup
        }
    }
}
