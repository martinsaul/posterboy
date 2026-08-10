package io.posterboy.admin

import io.posterboy.Posterboy
import io.posterboy.config.ConfigLoader
import io.posterboy.debug.DebugEntry
import io.posterboy.debug.DebugLog
import io.posterboy.plugins.gmail.GmailPlugin
import io.javalin.Javalin
import io.javalin.http.ContentType
import io.javalin.websocket.WsContext
import io.javalin.websocket.enableAutomaticPings
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AdminServer(private val posterboy: Posterboy) {

    private val log = LoggerFactory.getLogger(AdminServer::class.java)
    private lateinit var app: Javalin
    private val wsClients = ConcurrentHashMap<String, WsContext>()
    private val wsSendExecutor = Executors.newSingleThreadExecutor()

    /**
     * @param beforeStart invoked with the fully-built (but not-yet-started) Javalin
     *        app, after all core routes are registered — the hook where extensions
     *        register their own HTTP routes.
     */
    fun start(port: Int, beforeStart: (Javalin) -> Unit = {}) {
        app = Javalin.create { cfg ->
            cfg.staticFiles.add("/static")
            cfg.http.defaultContentType = ContentType.APPLICATION_JSON.mimeType
            cfg.jetty.modifyWebSocketServletFactory { factory ->
                factory.idleTimeout = java.time.Duration.ofHours(1)
                factory.maxTextMessageSize = 65536
            }
        }

        // --- Config API ---

        app.get("/api/config") { ctx -> ctx.json(posterboy.config) }

        app.get("/api/config/yaml") { ctx ->
            ctx.contentType(ContentType.TEXT_PLAIN)
            ctx.result(ConfigLoader.toYaml(posterboy.config))
        }

        app.put("/api/config") { ctx ->
            try {
                val newConfig = ConfigLoader.mapper.readValue(ctx.body(),
                    io.posterboy.config.PosterboyConfig::class.java)
                posterboy.saveConfig(newConfig)
                ctx.json(mapOf("status" to "ok", "message" to "Config saved and reloaded"))
            } catch (e: Exception) {
                log.error("Failed to update config", e)
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        app.post("/api/config/import") { ctx ->
            try {
                val newConfig = ConfigLoader.parse(ctx.body())
                posterboy.saveConfig(newConfig)
                ctx.json(mapOf("status" to "ok", "message" to "Config imported and reloaded"))
            } catch (e: Exception) {
                log.error("Failed to import config", e)
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        app.get("/api/config/export") { ctx ->
            ctx.contentType("application/x-yaml")
            ctx.header("Content-Disposition", "attachment; filename=\"posterboy.yml\"")
            ctx.result(ConfigLoader.toYaml(posterboy.config))
        }

        // --- Plugins API ---

        app.get("/api/plugins") { ctx ->
            val plugins = posterboy.pluginRegistry.all().map { plugin ->
                val base = mutableMapOf<String, Any?>(
                    "id" to plugin.id,
                    "name" to plugin.name,
                    "configured" to posterboy.pluginRegistry.isInitialized(plugin.id),
                    "enabled" to !posterboy.pluginRegistry.isDisabled(plugin.id)
                )
                if (plugin is GmailPlugin) {
                    val api = plugin.apiStrategy
                    base["gmail_status"] = when {
                        posterboy.pluginRegistry.isDisabled(plugin.id) -> "disabled"
                        !plugin.isConfigured -> "not_configured"
                        api == null -> "smtp_mode"
                        api.isAuthorized -> "authorized"
                        api.hasCredentials -> "needs_auth"
                        else -> "needs_credentials"
                    }
                }
                base.toMap()
            }
            ctx.json(plugins)
        }

        app.get("/api/plugins/{id}/config") { ctx ->
            val pluginId = ctx.pathParam("id")
            val pluginConfig = posterboy.config.plugins[pluginId]
            if (pluginConfig != null) {
                ctx.json(pluginConfig)
            } else {
                ctx.status(404).json(mapOf("status" to "error", "message" to "Plugin not configured: $pluginId"))
            }
        }

        app.put("/api/plugins/{id}/config") { ctx ->
            val pluginId = ctx.pathParam("id")
            try {
                @Suppress("UNCHECKED_CAST")
                val pluginConfig = ConfigLoader.mapper.readValue(ctx.body(), Map::class.java) as Map<String, Any>
                val newPlugins = posterboy.config.plugins.toMutableMap()
                newPlugins[pluginId] = pluginConfig
                posterboy.saveConfig(posterboy.config.copy(plugins = newPlugins))
                ctx.json(mapOf("status" to "ok", "message" to "Plugin '$pluginId' config saved and reloaded"))
            } catch (e: Exception) {
                log.error("Failed to update plugin config", e)
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        // --- Plugin enable/disable ---

        app.post("/api/plugins/{id}/enable") { ctx ->
            val pluginId = ctx.pathParam("id")
            try {
                val current = posterboy.config.plugins[pluginId]?.toMutableMap() ?: mutableMapOf()
                current.remove("enabled") // remove false flag, defaults to true
                val newPlugins = posterboy.config.plugins.toMutableMap()
                newPlugins[pluginId] = current
                posterboy.saveConfig(posterboy.config.copy(plugins = newPlugins))
                ctx.json(mapOf("status" to "ok", "message" to "Plugin '$pluginId' enabled"))
            } catch (e: Exception) {
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        app.post("/api/plugins/{id}/disable") { ctx ->
            val pluginId = ctx.pathParam("id")
            try {
                val current = posterboy.config.plugins[pluginId]?.toMutableMap() ?: mutableMapOf()
                current["enabled"] = false
                val newPlugins = posterboy.config.plugins.toMutableMap()
                newPlugins[pluginId] = current
                posterboy.saveConfig(posterboy.config.copy(plugins = newPlugins))
                ctx.json(mapOf("status" to "ok", "message" to "Plugin '$pluginId' disabled"))
            } catch (e: Exception) {
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        // --- Gmail OAuth API ---

        app.get("/api/gmail/status") { ctx ->
            val gmail = posterboy.pluginRegistry.all().find { it.id == "gmail" } as? GmailPlugin
            if (gmail == null) {
                ctx.json(mapOf("status" to "not_configured"))
                return@get
            }
            if (!gmail.isConfigured) {
                val pluginConfig = posterboy.config.plugins["gmail"]
                val hasApiConfig = (pluginConfig?.get("api") as? Map<*, *>)?.containsKey("client_id") == true
                ctx.json(mapOf("status" to if (hasApiConfig) "needs_auth" else "needs_credentials",
                    "has_credentials" to hasApiConfig, "authorized" to false))
                return@get
            }
            val api = gmail.apiStrategy
            if (api == null) { ctx.json(mapOf("status" to "smtp_mode")); return@get }
            ctx.json(mapOf(
                "status" to if (api.isAuthorized) "authorized" else if (api.hasCredentials) "needs_auth" else "needs_credentials",
                "has_credentials" to api.hasCredentials, "authorized" to api.isAuthorized
            ))
        }

        app.post("/api/gmail/credentials") { ctx ->
            try {
                val json = ctx.body()
                val parsed = ConfigLoader.mapper.readValue(json, Map::class.java)
                @Suppress("UNCHECKED_CAST")
                val details = (parsed["web"] as? Map<String, Any>)
                    ?: (parsed["installed"] as? Map<String, Any>)
                    ?: throw IllegalArgumentException("JSON must contain 'web' or 'installed' key")
                val clientId = details["client_id"] as? String ?: throw IllegalArgumentException("Missing client_id")
                val clientSecret = details["client_secret"] as? String ?: throw IllegalArgumentException("Missing client_secret")

                val gmailConfig = (posterboy.config.plugins["gmail"]?.toMutableMap() ?: mutableMapOf()).apply {
                    put("strategy", "api")
                    if (!containsKey("default_alias")) put("default_alias", "")
                    val apiBlock = ((this["api"] as? Map<*, *>)?.toMutableMap() ?: mutableMapOf<String, Any>()).apply {
                        put("client_id", clientId); put("client_secret", clientSecret)
                        if (!containsKey("tokens_path")) put("tokens_path", "/data/tokens")
                    }
                    put("api", apiBlock)
                }
                val newPlugins = posterboy.config.plugins.toMutableMap()
                newPlugins["gmail"] = gmailConfig
                posterboy.saveConfig(posterboy.config.copy(plugins = newPlugins))

                val gmail = posterboy.pluginRegistry.all().find { it.id == "gmail" } as? GmailPlugin
                gmail?.apiStrategy?.setCredentialsFromJson(json)
                ctx.json(mapOf("status" to "ok", "message" to "Credentials saved. Ready to authorize."))
            } catch (e: Exception) {
                log.error("Failed to save credentials", e)
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        app.get("/api/gmail/auth-url") { ctx ->
            val api = (posterboy.pluginRegistry.all().find { it.id == "gmail" } as? GmailPlugin)?.apiStrategy
            if (api == null) { ctx.status(400).json(mapOf("status" to "error", "message" to "Gmail API not configured")); return@get }
            try {
                val scheme = ctx.header("X-Forwarded-Proto") ?: ctx.scheme()
                val host = ctx.header("X-Forwarded-Host") ?: ctx.host() ?: "localhost"
                val redirectUri = "$scheme://$host/api/gmail/callback"
                ctx.json(mapOf("auth_url" to api.getAuthorizationUrl(redirectUri), "redirect_uri" to redirectUri))
            } catch (e: Exception) {
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        app.get("/api/gmail/callback") { ctx ->
            val api = (posterboy.pluginRegistry.all().find { it.id == "gmail" } as? GmailPlugin)?.apiStrategy
            if (api == null) { ctx.status(400).result("Gmail not configured"); return@get }
            val code = ctx.queryParam("code")
            if (code == null) {
                ctx.contentType(ContentType.TEXT_HTML).result(errorPage(ctx.queryParam("error") ?: "unknown error"))
                return@get
            }
            try {
                val scheme = ctx.header("X-Forwarded-Proto") ?: ctx.scheme()
                val host = ctx.header("X-Forwarded-Host") ?: ctx.host() ?: "localhost"
                api.exchangeCode(code, "$scheme://$host/api/gmail/callback")
                ctx.contentType(ContentType.TEXT_HTML).result(successPage("Gmail Authorized", "Posterboy can now send emails via Gmail API."))
            } catch (e: Exception) {
                log.error("OAuth callback failed", e)
                ctx.contentType(ContentType.TEXT_HTML).result(errorPage(e.message ?: "Unknown error"))
            }
        }

        app.get("/api/gmail/aliases") { ctx ->
            val api = (posterboy.pluginRegistry.all().find { it.id == "gmail" } as? GmailPlugin)?.apiStrategy
            if (api == null || !api.isAuthorized) {
                ctx.status(400).json(mapOf("status" to "error", "message" to "Gmail not authorized"))
                return@get
            }
            try {
                ctx.json(api.listAliases())
            } catch (e: Exception) {
                log.error("Failed to list aliases", e)
                ctx.status(500).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        app.post("/api/gmail/alias") { ctx ->
            try {
                @Suppress("UNCHECKED_CAST")
                val body = ConfigLoader.mapper.readValue(ctx.body(), Map::class.java) as Map<String, Any>
                val alias = body["alias"] as? String ?: throw IllegalArgumentException("Missing 'alias'")
                val gmailConfig = posterboy.config.plugins["gmail"]?.toMutableMap() ?: mutableMapOf()
                gmailConfig["default_alias"] = alias
                val newPlugins = posterboy.config.plugins.toMutableMap()
                newPlugins["gmail"] = gmailConfig
                posterboy.saveConfig(posterboy.config.copy(plugins = newPlugins))
                ctx.json(mapOf("status" to "ok", "message" to "Default alias set to $alias"))
            } catch (e: Exception) {
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        // --- Debug API ---

        app.get("/api/debug/entries") { ctx ->
            ctx.json(DebugLog.getEntries())
        }

        app.get("/api/debug/entries/{id}") { ctx ->
            val entry = DebugLog.getEntry(ctx.pathParam("id"))
            if (entry != null) ctx.json(entry)
            else ctx.status(404).json(mapOf("status" to "error", "message" to "Entry not found"))
        }

        app.get("/api/debug/status") { ctx ->
            ctx.json(mapOf("enabled" to DebugLog.enabled, "listeners" to wsClients.size,
                "entries" to DebugLog.getEntries().size))
        }

        // --- Debug WebSocket ---

        app.ws("/ws/debug") { ws ->
            ws.onConnect { ctx ->
                val id = ctx.sessionId()
                wsClients[id] = ctx
                ctx.session.idleTimeout = java.time.Duration.ofHours(1)
                ctx.enableAutomaticPings(30, java.util.concurrent.TimeUnit.SECONDS)
                val wsSession = ctx.session
                val listener: (DebugEntry) -> Unit = { entry ->
                    wsSendExecutor.submit {
                        try {
                            val json = ConfigLoader.jsonMapper.writeValueAsString(entry)
                            log.info("Sending debug entry to WS {}, session.isOpen={}: {}",
                                id, wsSession.isOpen, entry.subject)
                            if (wsSession.isOpen) {
                                wsSession.remote.sendString(json)
                                log.info("Debug entry sent OK to WS {}", id)
                            } else {
                                log.warn("WS session {} not open, removing", id)
                                wsClients.remove(id)
                            }
                        } catch (e: Exception) {
                            log.error("Failed to send debug entry to WS {}: {}", id, e.message, e)
                            wsClients.remove(id)
                        }
                    }
                }
                ctx.attribute("listener", listener)
                DebugLog.addListener(listener)
                log.info("Debug WebSocket connected: {}, total: {}", id, wsClients.size)
            }
            ws.onClose { ctx ->
                val id = ctx.sessionId()
                wsClients.remove(id)
                @Suppress("UNCHECKED_CAST")
                val listener = ctx.attribute<(DebugEntry) -> Unit>("listener")
                if (listener != null) DebugLog.removeListener(listener)
                log.info("Debug WebSocket disconnected: {}, total: {}", id, wsClients.size)
            }
            ws.onError { ctx ->
                val id = ctx.sessionId()
                wsClients.remove(id)
                @Suppress("UNCHECKED_CAST")
                val listener = ctx.attribute<(DebugEntry) -> Unit>("listener")
                if (listener != null) DebugLog.removeListener(listener)
            }
        }

        // --- Approval Queue API ---

        app.get("/api/approval/pending") { ctx ->
            val service = posterboy.approvalService
            if (service == null) { ctx.json(emptyList<Any>()); return@get }
            val pending = service.listPending().map { msg ->
                mapOf(
                    "id" to msg.id,
                    "created_at" to msg.createdAt.toString(),
                    "from" to msg.fromAddr,
                    "recipients" to msg.recipients,
                    "subject" to msg.subject,
                    "body_preview" to msg.bodyPreview,
                    "status" to msg.status
                )
            }
            ctx.json(pending)
        }

        app.get("/api/approval/{id}") { ctx ->
            val service = posterboy.approvalService
            if (service == null) {
                ctx.status(404).json(mapOf("status" to "error", "message" to "Approval queue disabled"))
                return@get
            }
            val msg = service.getById(ctx.pathParam("id"))
            if (msg == null) {
                ctx.status(404).json(mapOf("status" to "error", "message" to "Not found"))
                return@get
            }
            // Parse raw bytes to get full body and attachment list
            val email = io.posterboy.smtp.EmailParser.parse(
                msg.fromAddr, msg.recipients, msg.rawBytes
            )
            ctx.json(mapOf(
                "id" to msg.id, "created_at" to msg.createdAt.toString(),
                "from" to msg.fromAddr, "recipients" to msg.recipients,
                "subject" to msg.subject,
                "body_plain" to email.bodyPlain,
                "body_html" to email.bodyHtml,
                "attachments" to email.attachments.mapIndexed { index, att ->
                    mapOf(
                        "index" to index,
                        "filename" to att.filename,
                        "mime_type" to att.mimeType,
                        "size_bytes" to att.sizeBytes
                    )
                },
                "status" to msg.status
            ))
        }

        app.get("/api/approval/{id}/attachments/{index}") { ctx ->
            val service = posterboy.approvalService
            if (service == null) {
                ctx.status(404).json(mapOf("status" to "error", "message" to "Approval queue disabled"))
                return@get
            }
            val msg = service.getById(ctx.pathParam("id"))
            if (msg == null) {
                ctx.status(404).json(mapOf("status" to "error", "message" to "Not found"))
                return@get
            }
            val index = ctx.pathParam("index").toIntOrNull()
            if (index == null) {
                ctx.status(400).json(mapOf("status" to "error", "message" to "Invalid index"))
                return@get
            }
            val email = io.posterboy.smtp.EmailParser.parse(
                msg.fromAddr, msg.recipients, msg.rawBytes
            )
            if (index < 0 || index >= email.fullAttachments.size) {
                ctx.status(404).json(mapOf("status" to "error", "message" to "Attachment not found"))
                return@get
            }
            val att = email.fullAttachments[index]
            ctx.contentType(att.meta.mimeType)
            ctx.header("Content-Disposition", "attachment; filename=\"${att.meta.filename}\"")
            ctx.result(att.data)
        }

        app.post("/api/approval/{id}/approve") { ctx ->
            val service = posterboy.approvalService
            if (service == null) {
                ctx.status(400).json(mapOf("status" to "error", "message" to "Approval queue disabled"))
                return@post
            }
            try {
                service.approve(ctx.pathParam("id"))
                ctx.json(mapOf("status" to "ok", "message" to "Message approved and delivered"))
            } catch (e: Exception) {
                log.error("Approval failed", e)
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        app.post("/api/approval/{id}/dismiss") { ctx ->
            val service = posterboy.approvalService
            if (service == null) {
                ctx.status(400).json(mapOf("status" to "error", "message" to "Approval queue disabled"))
                return@post
            }
            try {
                service.dismiss(ctx.pathParam("id"))
                ctx.json(mapOf("status" to "ok", "message" to "Message dismissed"))
            } catch (e: Exception) {
                log.error("Dismiss failed", e)
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        // --- Routing API ---

        app.get("/api/routing") { ctx -> ctx.json(posterboy.config.routing) }

        app.put("/api/routing") { ctx ->
            try {
                val routing = ConfigLoader.mapper.readValue(ctx.body(),
                    io.posterboy.config.RoutingConfig::class.java)
                posterboy.saveConfig(posterboy.config.copy(routing = routing))
                ctx.json(mapOf("status" to "ok", "message" to "Routing config saved and reloaded"))
            } catch (e: Exception) {
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        // --- SMTP API ---

        app.get("/api/smtp") { ctx -> ctx.json(posterboy.config.smtp) }

        app.put("/api/smtp") { ctx ->
            try {
                val smtp = ConfigLoader.mapper.readValue(ctx.body(),
                    io.posterboy.config.SmtpConfig::class.java)
                posterboy.saveConfig(posterboy.config.copy(smtp = smtp))
                ctx.json(mapOf("status" to "ok", "message" to "SMTP config saved and reloaded"))
            } catch (e: Exception) {
                ctx.status(400).json(mapOf("status" to "error", "message" to e.message))
            }
        }

        // --- Status ---

        app.get("/api/status") { ctx ->
            ctx.json(mapOf("status" to "running",
                "plugins" to posterboy.pluginRegistry.all().map { it.id },
                "smtp_port" to posterboy.config.smtp.port,
                "admin_port" to posterboy.config.admin.port))
        }

        // Extensions register their routes on the built-but-unstarted app.
        beforeStart(app)

        app.start(port)
        log.info("Admin UI running on port {}", port)
    }

    fun stop() {
        if (::app.isInitialized) { app.stop(); log.info("Admin server stopped") }
    }

    private fun successPage(title: String, msg: String) = """
        <html><body style="background:#0f1117;color:#4ade80;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh">
        <div><h2>$title</h2><p>$msg</p><p><a href="/" style="color:#6c8cff">Back to admin</a></p></div></body></html>
    """.trimIndent()

    private fun errorPage(msg: String) = """
        <html><body style="background:#0f1117;color:#f87171;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh">
        <div><h2>Authorization Failed</h2><p>$msg</p><p><a href="/" style="color:#6c8cff">Back to admin</a></p></div></body></html>
    """.trimIndent()
}
