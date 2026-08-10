package io.posterboy.plugins.discord

import io.posterboy.smtp.InboundEmail

object DiscordFormatter {

    private const val MAX_CONTENT_LENGTH = 1800 // Discord limit is 2000, leave room for framing

    fun format(email: InboundEmail): String {
        val body = email.bodyPlain ?: stripHtml(email.bodyHtml ?: "")
        val truncatedBody = if (body.length > MAX_CONTENT_LENGTH) {
            body.take(MAX_CONTENT_LENGTH) + "\n... (truncated)"
        } else {
            body
        }

        return buildString {
            appendLine("```")
            appendLine("From:    ${email.from}")
            appendLine("To:      ${email.to.joinToString(", ")}")
            appendLine("Subject: ${email.subject}")
            appendLine("---")
            appendLine(truncatedBody.trim())

            if (email.attachments.isNotEmpty()) {
                appendLine("---")
                appendLine("Attachments:")
                email.attachments.forEach { att ->
                    val size = if (att.sizeBytes > 0) formatSize(att.sizeBytes) else "unknown size"
                    appendLine("  - ${att.filename} (${att.mimeType}, $size)")
                }
            }

            appendLine("```")
        }
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
        }
    }
}
