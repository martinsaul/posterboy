package io.posterboy.plugin

import io.javalin.Javalin
import io.posterboy.smtp.InboundEmail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtensionRegistryTest {

    @Test
    fun `discovers and initializes extensions via ServiceLoader`() {
        val registry = ExtensionRegistry(javaClass.classLoader)
        registry.discover()

        val ext = registry.all().filterIsInstance<TestExtension>().singleOrNull()
        assertNotNull(ext, "TestExtension should be discovered from the classpath")

        val submitted = mutableListOf<InboundEmail>()
        registry.initializeAll { _ ->
            object : ExtensionContext {
                override val config: Map<String, Any> = mapOf("greeting" to "hello")
                override val javalin: Javalin? = null
                override val pluginRegistry: PluginRegistry = PluginRegistry()
                override fun submit(email: InboundEmail) { submitted.add(email) }
            }
        }

        // initialize() ran with the injected config…
        assertTrue(ext.initialized)
        assertEquals("hello", ext.greeting)
        // …and its ctx.submit() reached the pipeline sink.
        assertEquals(1, submitted.size)
        assertEquals("hi", submitted[0].subject)
    }
}
