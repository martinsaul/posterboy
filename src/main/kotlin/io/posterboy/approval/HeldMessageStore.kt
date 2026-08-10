package io.posterboy.approval

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

data class HeldMessage(
    val id: String,
    val createdAt: Instant,
    val fromAddr: String,
    val recipients: List<String>,
    val subject: String,
    val bodyPreview: String?,
    val rawBytes: ByteArray,
    val routeDecisions: String,
    val status: String
) {
    override fun equals(other: Any?): Boolean =
        other is HeldMessage && id == other.id

    override fun hashCode(): Int = id.hashCode()
}

class HeldMessageStore(dbPath: String) {

    private val log = LoggerFactory.getLogger(HeldMessageStore::class.java)
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS held_messages (
                    id TEXT PRIMARY KEY,
                    created_at TEXT NOT NULL,
                    from_addr TEXT NOT NULL,
                    recipients TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    body_preview TEXT,
                    raw_bytes BLOB NOT NULL,
                    route_decisions TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending'
                )
                """.trimIndent()
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_held_status ON held_messages(status)")
        }
        log.info("HeldMessageStore initialized at {}", dbPath)
    }

    fun insert(
        fromAddr: String,
        recipients: List<String>,
        subject: String,
        bodyPreview: String?,
        rawBytes: ByteArray,
        routeDecisions: String
    ): String {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        conn.prepareStatement(
            """
            INSERT INTO held_messages (id, created_at, from_addr, recipients, subject, body_preview, raw_bytes, route_decisions, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending')
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, now)
            ps.setString(3, fromAddr)
            ps.setString(4, recipientsToJson(recipients))
            ps.setString(5, subject)
            ps.setString(6, bodyPreview)
            ps.setBytes(7, rawBytes)
            ps.setString(8, routeDecisions)
            ps.executeUpdate()
        }
        log.info("Held message stored: id={} from={} subject={}", id, fromAddr, subject)
        return id
    }

    fun listPending(): List<HeldMessage> {
        val results = mutableListOf<HeldMessage>()
        conn.prepareStatement(
            "SELECT id, created_at, from_addr, recipients, subject, body_preview, route_decisions, status FROM held_messages WHERE status = 'pending' ORDER BY created_at DESC"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(
                        HeldMessage(
                            id = rs.getString("id"),
                            createdAt = Instant.parse(rs.getString("created_at")),
                            fromAddr = rs.getString("from_addr"),
                            recipients = recipientsFromJson(rs.getString("recipients")),
                            subject = rs.getString("subject"),
                            bodyPreview = rs.getString("body_preview"),
                            rawBytes = ByteArray(0), // don't load blobs for listing
                            routeDecisions = rs.getString("route_decisions"),
                            status = rs.getString("status")
                        )
                    )
                }
            }
        }
        return results
    }

    fun getById(id: String): HeldMessage? {
        conn.prepareStatement(
            "SELECT * FROM held_messages WHERE id = ?"
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return HeldMessage(
                    id = rs.getString("id"),
                    createdAt = Instant.parse(rs.getString("created_at")),
                    fromAddr = rs.getString("from_addr"),
                    recipients = recipientsFromJson(rs.getString("recipients")),
                    subject = rs.getString("subject"),
                    bodyPreview = rs.getString("body_preview"),
                    rawBytes = rs.getBytes("raw_bytes"),
                    routeDecisions = rs.getString("route_decisions"),
                    status = rs.getString("status")
                )
            }
        }
    }

    fun updateStatus(id: String, status: String) {
        conn.prepareStatement("UPDATE held_messages SET status = ? WHERE id = ?").use { ps ->
            ps.setString(1, status)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    fun deleteDecided(olderThanHours: Int) {
        val cutoff = Instant.now().minusSeconds(olderThanHours * 3600L).toString()
        conn.prepareStatement(
            "DELETE FROM held_messages WHERE status != 'pending' AND created_at < ?"
        ).use { ps ->
            ps.setString(1, cutoff)
            val deleted = ps.executeUpdate()
            if (deleted > 0) log.info("Pruned {} decided held messages older than {}h", deleted, olderThanHours)
        }
    }

    fun close() {
        conn.close()
    }

    private fun recipientsToJson(recipients: List<String>): String =
        "[${recipients.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }}]"

    private fun recipientsFromJson(json: String): List<String> =
        json.trim().removePrefix("[").removeSuffix("]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
}
