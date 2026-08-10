package io.posterboy.plugin

import org.slf4j.LoggerFactory
import java.util.ServiceLoader

/**
 * Discovers and manages the lifecycle of [Extension]s found via [ServiceLoader]
 * over the supplied [classLoader] (which includes any drop-in jars). Discovery
 * and initialization are separate steps because extensions initialize once the
 * runtime context (e.g. the admin HTTP app) is ready.
 */
class ExtensionRegistry(private val classLoader: ClassLoader) {

    private val log = LoggerFactory.getLogger(ExtensionRegistry::class.java)
    private val extensions = mutableListOf<Extension>()
    private val initialized = mutableListOf<Extension>()

    /** Load all providers. Safe to call once at startup. */
    fun discover() {
        extensions.clear()
        ServiceLoader.load(Extension::class.java, classLoader).forEach { ext ->
            log.info("Discovered extension: {} ({})", ext.name, ext.id)
            extensions.add(ext)
        }
        log.info("Discovered {} extension(s): {}", extensions.size, extensions.joinToString { it.id })
    }

    /**
     * Initialize each discovered extension with a context built by [contextFor].
     * A failing extension is logged and skipped; it never takes down the app or
     * the other extensions.
     */
    fun initializeAll(contextFor: (Extension) -> ExtensionContext) {
        for (ext in extensions) {
            try {
                ext.initialize(contextFor(ext))
                initialized.add(ext)
                log.info("Initialized extension: {}", ext.id)
            } catch (e: Exception) {
                log.error("Failed to initialize extension: {}", ext.id, e)
            }
        }
    }

    fun shutdownAll() {
        for (ext in initialized) {
            try {
                ext.shutdown()
            } catch (e: Exception) {
                log.error("Error shutting down extension: {}", ext.id, e)
            }
        }
        initialized.clear()
    }

    fun all(): List<Extension> = extensions.toList()
}
