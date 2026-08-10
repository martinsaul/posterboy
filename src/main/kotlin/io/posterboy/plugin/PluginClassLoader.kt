package io.posterboy.plugin

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader

/**
 * Builds the classloader used to discover plugins and extensions. It layers any
 * `*.jar` files found in a drop-in directory on top of the application
 * classloader, so a deployment can add delivery plugins or extensions by simply
 * dropping a jar into that folder — no rebuild of the core.
 *
 * The bundled built-in plugins live on the application classpath and are always
 * available regardless of the directory.
 */
object PluginClassLoader {

    private val log = LoggerFactory.getLogger(PluginClassLoader::class.java)

    /**
     * @param dir    directory to scan for `*.jar` (may be null/blank/missing).
     * @param parent parent classloader (the app's), which provides the core API
     *               classes so dropped-in jars can compile against them as
     *               `compileOnly`.
     * @return a [URLClassLoader] over the discovered jars, or [parent] unchanged
     *         when the directory is absent or empty.
     */
    fun build(dir: String?, parent: ClassLoader): ClassLoader {
        if (dir.isNullOrBlank()) return parent
        val d = File(dir)
        if (!d.isDirectory) {
            log.info("Plugins dir '{}' not present; using bundled plugins only", dir)
            return parent
        }
        val jars = d.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (jars.isEmpty()) {
            log.info("Plugins dir '{}' has no jars; using bundled plugins only", dir)
            return parent
        }
        log.info("Loading {} drop-in jar(s) from '{}': {}", jars.size, dir, jars.joinToString { it.name })
        return URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), parent)
    }
}
