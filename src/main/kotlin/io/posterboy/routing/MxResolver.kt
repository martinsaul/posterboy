package io.posterboy.routing

import org.slf4j.LoggerFactory
import org.xbill.DNS.*
import java.util.concurrent.ConcurrentHashMap

class MxResolver(private val cacheTtlMs: Long) {

    private val log = LoggerFactory.getLogger(MxResolver::class.java)

    private data class CacheEntry(val hasMx: Boolean, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun hasMxRecord(domain: String): Boolean {
        val now = System.currentTimeMillis()

        cache[domain]?.let { entry ->
            if (entry.expiresAt > now) return entry.hasMx
        }

        val result = lookupMx(domain)
        cache[domain] = CacheEntry(result, now + cacheTtlMs)
        return result
    }

    fun evict(domain: String) {
        cache.remove(domain)
        log.debug("Evicted MX cache for domain: {}", domain)
    }

    private fun lookupMx(domain: String): Boolean {
        return try {
            val lookup = Lookup(domain, Type.MX)
            val records = lookup.run()
            val hasMx = records != null && records.isNotEmpty()
            log.debug("MX lookup for {}: {}", domain, if (hasMx) "found" else "none")
            hasMx
        } catch (e: Exception) {
            log.warn("MX lookup failed for {}: {}", domain, e.message)
            false
        }
    }
}
