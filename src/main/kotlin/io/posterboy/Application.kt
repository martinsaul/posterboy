package io.posterboy

import io.javalin.Javalin
import io.posterboy.admin.AdminServer
import io.posterboy.plugin.ExtensionRegistry
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("io.posterboy.Application")

fun main() {
    val configPath = resolveConfigPath()
    log.info("Posterboy starting up...")

    val posterboy = Posterboy(configPath)
    posterboy.start()

    // Discover drop-in extensions (bundled + jars in the plugins dir). They are
    // initialized once the runtime context is ready — for HTTP extensions that
    // means after the admin app is built but before it starts accepting requests.
    val extensions = ExtensionRegistry(posterboy.pluginClassLoader)
    extensions.discover()
    val initExtensions = { app: Javalin? ->
        extensions.initializeAll { ext -> posterboy.extensionContext(ext.id, app) }
    }

    val adminServer = if (posterboy.config.admin.enabled) {
        AdminServer(posterboy).also { server ->
            server.start(posterboy.config.admin.port) { app -> initExtensions(app) }
        }
    } else {
        // No admin server: HTTP-based extensions get a null app and no-op, but
        // background extensions (reporters, etc.) still initialize.
        initExtensions(null)
        null
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down Posterboy...")
        extensions.shutdownAll()
        adminServer?.stop()
        posterboy.stop()
    })

    Thread.currentThread().join()
}

private fun resolveConfigPath(): String {
    System.getenv("POSTERBOY_CONFIG")?.let { return it }

    val dataConfig = File("/data/posterboy.yml")
    if (dataConfig.exists()) {
        log.info("Using config from {}", dataConfig.absolutePath)
        return dataConfig.absolutePath
    }

    val defaultConfig = File("/etc/posterboy/posterboy.default.yml")
    if (defaultConfig.exists()) {
        log.info("First run: copying default config to {}", dataConfig.absolutePath)
        dataConfig.parentFile.mkdirs()
        defaultConfig.copyTo(dataConfig)
        return dataConfig.absolutePath
    }

    // Fallback for local development
    return "/etc/posterboy/posterboy.yml"
}
