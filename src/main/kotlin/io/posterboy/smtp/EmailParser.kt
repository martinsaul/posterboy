package io.posterboy.smtp

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.Part
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.Properties

object EmailParser {

    private val log = LoggerFactory.getLogger(EmailParser::class.java)
    private val session = Session.getDefaultInstance(Properties())

    fun parse(from: String, recipients: List<String>, data: ByteArray): InboundEmail {
        val message = MimeMessage(session, ByteArrayInputStream(data))

        val subject = message.subject ?: "(no subject)"
        var bodyPlain: String? = null
        var bodyHtml: String? = null
        val attachments = mutableListOf<AttachmentMeta>()
        val fullAttachments = mutableListOf<Attachment>()

        extractParts(message, { plain, html ->
            bodyPlain = plain
            bodyHtml = html
        }, attachments, fullAttachments)

        return InboundEmail(
            from = from,
            to = recipients,
            subject = subject,
            bodyPlain = bodyPlain,
            bodyHtml = bodyHtml,
            attachments = attachments,
            fullAttachments = fullAttachments
        )
    }

    private fun extractParts(
        part: Part,
        onBody: (plain: String?, html: String?) -> Unit,
        attachments: MutableList<AttachmentMeta>,
        fullAttachments: MutableList<Attachment>
    ) {
        val contentType = part.contentType?.lowercase() ?: ""

        when {
            part.isMimeType("text/plain") && part.disposition == null -> {
                onBody(part.content as? String, null)
            }
            part.isMimeType("text/html") && part.disposition == null -> {
                onBody(null, part.content as? String)
            }
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as MimeMultipart
                var plain: String? = null
                var html: String? = null
                for (i in 0 until multipart.count) {
                    val child = multipart.getBodyPart(i)
                    extractParts(child, { p, h ->
                        if (p != null) plain = p
                        if (h != null) html = h
                    }, attachments, fullAttachments)
                }
                onBody(plain, html)
            }
            part.disposition != null -> {
                val filename = part.fileName ?: "unnamed"
                val mimeType = contentType.split(";")[0].trim()
                val bytes = try { part.inputStream.readBytes() } catch (e: Exception) {
                    log.warn("Failed to read attachment bytes for {}: {}", filename, e.message)
                    null
                }
                val size = bytes?.size?.toLong() ?: try { part.size.toLong() } catch (_: Exception) { -1L }
                val meta = AttachmentMeta(filename, mimeType, size)
                attachments.add(meta)
                if (bytes != null) {
                    fullAttachments.add(Attachment(meta, bytes))
                }
            }
        }
    }
}
