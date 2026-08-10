package io.posterboy.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigLoaderTest {

    @Test
    fun `loads sample config`() {
        val configPath = File("config/posterboy.yml").absolutePath
        // Set env var for the test
        val config = ConfigLoader.load(configPath)

        assertEquals(2525, config.smtp.port)
        assertTrue(config.smtp.auth.enabled)
        assertEquals("relay", config.smtp.auth.username)
        assertTrue(config.plugins.containsKey("discord"))
        assertTrue(config.routing.mxHeuristic.enabled)
        assertEquals(3600, config.routing.mxHeuristic.cacheTtlSeconds)
        assertEquals("discord", config.routing.defaultAction)
    }

    @Test
    fun `parses routing rules`() {
        val configPath = File("config/posterboy.yml").absolutePath
        val config = ConfigLoader.load(configPath)

        assertTrue(config.routing.rules.isNotEmpty())
        val firstRule = config.routing.rules[0]
        assertEquals("discord", firstRule.action)
        assertTrue(firstRule.match.toDomain?.contains("example.com") == true)
    }
}
