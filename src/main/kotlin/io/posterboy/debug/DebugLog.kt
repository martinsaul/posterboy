package io.posterboy.debug

import io.posterboy.routing.RouteDecision
import io.posterboy.smtp.InboundEmail
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

data class DebugEntry(
    val id: String,
    val timestamp: Instant,
    val originalFrom: String,
    val originalTo: List<String>,
    val subject: String,
    val bodyPreview: String,
    val attachmentCount: Int,
    val routes: List<RouteInfo>
)

data class RouteInfo(
    val recipient: String,
    val pluginId: String,
    val ruleName: String?,
    val resolvedFrom: String?
)

typealias DebugListener = (DebugEntry) -> Unit

object DebugLog {

    private val log = LoggerFactory.getLogger(DebugLog::class.java)
    private val entries = ConcurrentLinkedDeque<DebugEntry>()
    private val listeners = CopyOnWriteArrayList<DebugListener>()
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L // 12 hours

    @Volatile
    var enabled = false
        private set

    fun enable() {
        enabled = true
        log.info("Debug mode enabled")
    }

    fun disable() {
        enabled = false
        entries.clear()
        log.info("Debug mode disabled, entries cleared")
    }

    fun hasListeners(): Boolean = listeners.isNotEmpty()

    fun addListener(listener: DebugListener) {
        listeners.add(listener)
        if (!enabled) enable()
    }

    fun removeListener(listener: DebugListener) {
        listeners.remove(listener)
        if (listeners.isEmpty() && enabled) {
            disable()
        }
    }

    fun record(email: InboundEmail, decisions: List<RouteDecision>, resolvedFromAlias: String?) {
        if (!enabled) return

        pruneOld()

        val entry = DebugEntry(
            id = "${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}",
            timestamp = Instant.now(),
            originalFrom = email.from,
            originalTo = email.to,
            subject = email.subject,
            bodyPreview = (email.bodyPlain ?: email.bodyHtml ?: "").take(200),
            attachmentCount = email.attachments.size,
            routes = decisions.map { d ->
                RouteInfo(
                    recipient = email.to.firstOrNull() ?: "",
                    pluginId = d.pluginId,
                    ruleName = d.ruleName,
                    resolvedFrom = resolvedFromAlias
                )
            }
        )

        entries.addFirst(entry)
        listeners.forEach { it(entry) }
    }

    fun getEntries(): List<DebugEntry> {
        pruneOld()
        return entries.toList()
    }

    fun getEntry(id: String): DebugEntry? = entries.find { it.id == id }

    private fun pruneOld() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        entries.removeIf { it.timestamp.toEpochMilli() < cutoff }
    }
}
