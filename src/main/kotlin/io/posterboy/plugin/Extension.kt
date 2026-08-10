package io.posterboy.plugin

import io.javalin.Javalin
import io.posterboy.smtp.InboundEmail

/**
 * The runtime surface handed to an [Extension] at initialization. It's how an
 * extension reaches into Posterboy without the core depending on the extension.
 */
interface ExtensionContext {
    /** This extension's config block from `extensions.<id>` in posterboy.yml (may be empty). */
    val config: Map<String, Any>

    /**
     * The admin Javalin app, for registering HTTP routes (e.g. an authenticated
     * ingest endpoint). Null when the admin server is disabled — HTTP-based
     * extensions should no-op in that case. Routes must be registered during
     * [Extension.initialize] (before the server starts accepting requests).
     */
    val javalin: Javalin?

    /** The delivery-plugin registry, for extensions that need to inspect plugins. */
    val pluginRegistry: PluginRegistry

    /**
     * Inject a message into the routing + fan-out pipeline, exactly as if it had
     * arrived over SMTP. Lets an extension add alternative ingress (HTTP, a queue,
     * a webhook) that reuses all the routing, hold/approval, and delivery logic.
     */
    fun submit(email: InboundEmail)
}

/**
 * A drop-in module that extends Posterboy beyond delivery. Where a
 * [DeliveryPlugin] answers "where does a message go?", an Extension answers
 * "what else can Posterboy do?" — add HTTP endpoints (custom ingress, auth,
 * admin actions), run background tasks (health/heartbeat reporters), or bridge
 * to another system.
 *
 * Extensions are discovered via Java [java.util.ServiceLoader] over both the
 * bundled classpath and any jars dropped into the plugins directory (see
 * `POSTERBOY_PLUGINS_DIR` / `plugins_dir`), so a deployment can add capabilities
 * by dropping a jar in a folder — no fork, no rebuild of the core.
 *
 * Register providers in a jar's
 * `META-INF/services/io.posterboy.plugin.Extension` file. See docs/EXTENSIONS.md.
 */
interface Extension {
    /** Stable id; its config lives under `extensions.<id>` in posterboy.yml. */
    val id: String

    /** Human-readable name (logs / admin). Defaults to [id]. */
    val name: String get() = id

    /** Called once at startup with the runtime [ctx]. Register routes / start tasks here. */
    fun initialize(ctx: ExtensionContext)

    /** Release resources on shutdown. Optional. */
    fun shutdown() {}
}
