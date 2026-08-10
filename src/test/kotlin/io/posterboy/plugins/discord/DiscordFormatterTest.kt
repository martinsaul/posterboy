package io.posterboy.plugins.discord

import io.posterboy.smtp.AttachmentMeta
import io.posterboy.smtp.InboundEmail
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DiscordFormatterTest {

    @Test
    fun `formats basic email as code block`() {
        val email = InboundEmail(
            from = "alerts@monitor.example",
            to = listOf("ops@example.com"),
            subject = "Service Down: web-01",
            bodyPlain = "web-01 is not responding on port 443",
            bodyHtml = null,
            attachments = emptyList()
        )

        val result = DiscordFormatter.format(email)

        assertTrue(result.startsWith("```"))
        assertTrue(result.trimEnd().endsWith("```"))
        assertTrue("From:    alerts@monitor.example" in result)
        assertTrue("To:      ops@example.com" in result)
        assertTrue("Subject: Service Down: web-01" in result)
        assertTrue("web-01 is not responding on port 443" in result)
    }

    @Test
    fun `includes attachment metadata`() {
        val email = InboundEmail(
            from = "test@example.com",
            to = listOf("dest@example.com"),
            subject = "Report",
            bodyPlain = "See attached",
            bodyHtml = null,
            attachments = listOf(
                AttachmentMeta("report.pdf", "application/pdf", 1048576),
                AttachmentMeta("logo.png", "image/png", 2048)
            )
        )

        val result = DiscordFormatter.format(email)

        assertTrue("Attachments:" in result)
        assertTrue("report.pdf" in result)
        assertTrue("application/pdf" in result)
        assertTrue("1.0 MB" in result)
        assertTrue("logo.png" in result)
        assertTrue("2 KB" in result)
    }

    @Test
    fun `falls back to html when no plain text`() {
        val email = InboundEmail(
            from = "test@example.com",
            to = listOf("dest@example.com"),
            subject = "HTML only",
            bodyPlain = null,
            bodyHtml = "<p>Hello <b>world</b></p><br/>Line two",
            attachments = emptyList()
        )

        val result = DiscordFormatter.format(email)

        assertTrue("Hello world" in result)
        assertFalse("<p>" in result)
        assertFalse("<b>" in result)
    }

    @Test
    fun `truncates very long body`() {
        val longBody = "x".repeat(3000)
        val email = InboundEmail(
            from = "test@example.com",
            to = listOf("dest@example.com"),
            subject = "Long",
            bodyPlain = longBody,
            bodyHtml = null,
            attachments = emptyList()
        )

        val result = DiscordFormatter.format(email)

        assertTrue("... (truncated)" in result)
        assertTrue(result.length < 2100)
    }
}
