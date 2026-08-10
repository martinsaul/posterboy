package io.posterboy.routing

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MxResolverTest {

    private val resolver = MxResolver(cacheTtlMs = 60_000)

    @Test
    fun `gmail has MX records`() {
        assertTrue(resolver.hasMxRecord("gmail.com"))
    }

    @Test
    fun `outlook has MX records`() {
        assertTrue(resolver.hasMxRecord("outlook.com"))
    }

    @Test
    fun `nonexistent domain has no MX records`() {
        assertFalse(resolver.hasMxRecord("thisdomain-definitely-does-not-exist-xyz123.com"))
    }

    @Test
    fun `cached result is returned on second call`() {
        // First call populates cache
        val first = resolver.hasMxRecord("gmail.com")
        // Second call should hit cache (same result)
        val second = resolver.hasMxRecord("gmail.com")
        assertTrue(first)
        assertTrue(second)
    }

    @Test
    fun `evict clears cache`() {
        resolver.hasMxRecord("gmail.com")
        resolver.evict("gmail.com")
        // Should re-lookup after eviction
        assertTrue(resolver.hasMxRecord("gmail.com"))
    }
}
