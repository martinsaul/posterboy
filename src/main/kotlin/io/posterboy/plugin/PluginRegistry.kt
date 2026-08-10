package io.posterboy.plugin

import org.slf4j.LoggerFactory
import java.util.ServiceLoader

class PluginRegistry {

    private val log = LoggerFactory.getLogger(PluginRegistry::class.java)
    private val plugins = mutableMapOf<String, DeliveryPlugin>()
    private val initializedPlugins = mutableSetOf<String>()
    private val disabledPlugins = mutableSetOf<String>()

    fun discoverAndInitialize(
        pluginConfigs: Map<String, Map<String, Any>>,
        classLoader: ClassLoader = DeliveryPlugin::class.java.classLoader,
    ) {
        plugins.clear()
        initializedPlugins.clear()
        disabledPlugins.clear()
        val loader = ServiceLoader.load(DeliveryPlugin::class.java, classLoader)
        for (plugin in loader) {
            val config = pluginConfigs[plugin.id] ?: emptyMap()
            log.info("Discovered plugin: {} ({})", plugin.name, plugin.id)
            plugins[plugin.id] = plugin

            val enabled = config["enabled"] as? Boolean ?: true
            if (!enabled) {
                disabledPlugins.add(plugin.id)
                log.info("Plugin {} is disabled", plugin.id)
                continue
            }

            if (config.isNotEmpty()) {
                try {
                    plugin.initialize(config)
                    initializedPlugins.add(plugin.id)
                    log.info("Initialized plugin: {}", plugin.id)
                } catch (e: Exception) {
                    log.error("Failed to initialize plugin: {}", plugin.id, e)
                }
            } else {
                log.info("Plugin {} has no config, available for setup via admin UI", plugin.id)
            }
        }
        log.info("Loaded {} plugin(s), {} initialized: {}",
            plugins.size, initializedPlugins.size, initializedPlugins.joinToString())
    }

    fun get(id: String): DeliveryPlugin? =
        if (id in initializedPlugins && id !in disabledPlugins) plugins[id] else null

    fun all(): Collection<DeliveryPlugin> = plugins.values

    fun isInitialized(id: String): Boolean = id in initializedPlugins && id !in disabledPlugins

    fun isDisabled(id: String): Boolean = id in disabledPlugins

    fun shutdownAll() {
        initializedPlugins.forEach { id ->
            try {
                plugins[id]?.shutdown()
            } catch (e: Exception) {
                log.error("Error shutting down plugin: {}", id, e)
            }
        }
    }
}
