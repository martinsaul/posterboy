package io.posterboy

import io.javalin.Javalin
import io.posterboy.approval.ApprovalService
import io.posterboy.config.ConfigLoader
import io.posterboy.config.PosterboyConfig
import io.posterboy.debug.DebugLog
import io.posterboy.plugin.DeliveryPlugin
import io.posterboy.plugin.ExtensionContext
import io.posterboy.plugin.PluginClassLoader
import io.posterboy.plugin.PluginRegistry
import io.posterboy.plugin.RoutingContext
import io.posterboy.routing.MxResolver
import io.posterboy.routing.RoutingEngine
import io.posterboy.smtp.InboundEmail
import io.posterboy.smtp.SmtpServer
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class Posterboy(val configPath: String) {

    private val log = LoggerFactory.getLogger(Posterboy::class.java)

    var config: PosterboyConfig = ConfigLoader.load(configPath)
        private set
    val pluginRegistry = PluginRegistry()

    /**
     * Classloader for discovering delivery plugins AND extensions, layering any
     * drop-in jars over the app classpath. Stable across config reloads (the
     * jar directory doesn't change at runtime). Resolution order for the dir:
     * config `plugins_dir`, then `POSTERBOY_PLUGINS_DIR`, then `/data/plugins`.
     */
    val pluginClassLoader: ClassLoader = PluginClassLoader.build(
        config.pluginsDir ?: System.getenv("POSTERBOY_PLUGINS_DIR") ?: "/data/plugins",
        this::class.java.classLoader,
    )

    private var mxResolver: MxResolver? = null
    private var routingEngine: RoutingEngine? = null
    private var smtpServer: SmtpServer? = null
    var approvalService: ApprovalService? = null
        private set

    fun start() {
        pluginRegistry.discoverAndInitialize(config.plugins, pluginClassLoader)

        mxResolver = if (config.routing.mxHeuristic.enabled) {
            MxResolver(config.routing.mxHeuristic.cacheTtlSeconds * 1000)
        } else null

        routingEngine = RoutingEngine(config.routing, mxResolver)

        if (config.approval.enabled) {
            approvalService = ApprovalService(
                config = config.approval,
                pluginRegistry = pluginRegistry,
                defaultAction = config.routing.defaultAction,
                templateResolver = ::resolveTemplate
            )
            log.info("Approval queue enabled (db={})", config.approval.dbPath)
        }

        smtpServer = SmtpServer(config.smtp) { email, rawBytes -> processEmail(email, rawBytes) }

        smtpServer!!.start()
        log.info("Posterboy is ready")
    }

    /**
     * Routes an inbound email through the routing engine, holds anything matching a
     * hold rule for approval, and fans the rest out to the matched delivery plugins.
     *
     * [rawBytes] is the original MIME payload from the SMTP listener. When null (e.g.
     * a synthesized message), we reconstruct stored bytes from the body so the
     * approval/hold path still works.
     */
    fun processEmail(email: InboundEmail, rawBytes: ByteArray?) {
        val decisions = routingEngine!!.route(email)

        // Record to debug log if enabled
        DebugLog.record(email, decisions, null)

        // Separate hold decisions from immediate-delivery decisions
        val (holdDecisions, deliverDecisions) = decisions.partition { it.pluginId == "hold" }

        // Hold matching messages for approval
        if (holdDecisions.isNotEmpty()) {
            val service = approvalService
            if (service != null) {
                val raw = rawBytes ?: (email.bodyPlain ?: email.bodyHtml ?: "").toByteArray()
                service.holdMessage(email, raw, holdDecisions)
            } else {
                log.warn("Message matched hold rule but approval queue is disabled; dropping hold decisions for: {}", email.subject)
            }
        }

        // When hold decisions exist, suppress their forward_to targets from immediate delivery
        // (those plugins will fire on approval instead)
        val suppressedPlugins = holdDecisions
            .mapNotNull { it.extraConfig["forward_to"] as? String }
            .toSet()
        val filteredDecisions = if (suppressedPlugins.isNotEmpty()) {
            deliverDecisions.filter { it.pluginId !in suppressedPlugins }
        } else {
            deliverDecisions
        }

        // Deliver non-hold decisions immediately
        val byPlugin = filteredDecisions.groupBy { it.pluginId }

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
                log.error("Plugin {} failed to deliver email: {}", pluginId, e.message, e)
                if (mxResolver != null) {
                    email.to.forEach { recipient ->
                        mxResolver!!.evict(recipient.substringAfter("@").lowercase())
                    }
                }
            }
        }
    }

    /**
     * Builds the [ExtensionContext] handed to an extension at initialization.
     * [extensionId] selects the extension's config block; [app] is the admin
     * Javalin app (or null when the admin server is disabled).
     */
    fun extensionContext(extensionId: String, app: Javalin?): ExtensionContext {
        val self = this
        return object : ExtensionContext {
            override val config: Map<String, Any> = self.config.extensions[extensionId] ?: emptyMap()
            override val javalin: Javalin? = app
            override val pluginRegistry: PluginRegistry = self.pluginRegistry
            override fun submit(email: InboundEmail) = self.processEmail(email, null)
        }
    }

    fun stop() {
        smtpServer?.stop()
        approvalService?.shutdown()
        approvalService = null
        pluginRegistry.shutdownAll()
        log.info("Posterboy stopped")
    }

    fun reloadConfig(newConfig: PosterboyConfig) {
        log.info("Reloading configuration...")
        stop()
        config = newConfig
        start()
        log.info("Configuration reloaded successfully")
    }

    fun saveConfig(newConfig: PosterboyConfig) {
        ConfigLoader.save(configPath, newConfig)
        reloadConfig(newConfig)
    }

    private fun resolveTemplates(email: InboundEmail, plugin: DeliveryPlugin): InboundEmail {
        val resolve = { body: String? -> body?.let { resolveTemplate(it, plugin) } }
        return email.copy(
            bodyPlain = resolve(email.bodyPlain),
            bodyHtml = resolve(email.bodyHtml),
            subject = resolveTemplate(email.subject, plugin)
        )
    }

    companion object {
        private val TEMPLATE_PATTERN = Regex("\\{\\{sender}}")

        fun resolveTemplate(text: String, plugin: DeliveryPlugin): String {
            if (!text.contains("{{")) return text
            return TEMPLATE_PATTERN.replace(text, plugin.senderAddress)
        }
    }
}
