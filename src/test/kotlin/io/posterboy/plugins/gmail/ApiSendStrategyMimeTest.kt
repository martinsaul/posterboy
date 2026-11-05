package io.posterboy.plugins.gmail

import io.posterboy.smtp.InboundEmail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiSendStrategyMimeTest {

    private fun email(body: String) = InboundEmail(
        from = "agent@example.com", to = listOf("martin@example.com"), subject = "Next 24h",
        bodyPlain = body, bodyHtml = null, attachments = emptyList(),
    )

    /** Every LF in the raw message must be part of a CRLF (RFC 5322); no bare CR either. */
    private fun assertCanonical(raw: String) {
        raw.forEachIndexed { i, c ->
            if (c == '\n') assertTrue(i > 0 && raw[i - 1] == '\r', "bare LF at $i")
            if (c == '\r') assertTrue(i + 1 < raw.length && raw[i + 1] == '\n', "bare CR at $i")
        }
    }

    @Test
    fun `a bare-LF plain body is sent with CRLF line endings`() {
        val body = (1..21).joinToString("\n") { "- line $it" } + "\n\n- Thu Nov 12 13:45: Costco tire install.\n\n- Tempo"
        val raw = String(ApiSendStrategy.buildRaw(email(body), "admin@example.com", listOf("martin@example.com")), Charsets.UTF_8)
        assertCanonical(raw)
        assertTrue(raw.trimEnd().endsWith("Costco tire install.\r\n\r\n- Tempo"), raw.takeLast(80))
    }

    @Test
    fun `mixed and CR-only endings are normalised, CRLF is left alone`() {
        assertEquals("a\r\nb\r\nc\r\nd", ApiSendStrategy.crlf("a\nb\r\nc\rd"))
        assertFalse(ApiSendStrategy.crlf("x\r\n").contains("\r\r"))
    }
}
