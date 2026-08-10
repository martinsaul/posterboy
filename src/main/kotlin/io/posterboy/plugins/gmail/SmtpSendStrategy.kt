package io.posterboy.plugins.gmail

import io.posterboy.smtp.InboundEmail
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import java.util.Properties

class SmtpSendStrategy : SendStrategy {

    private val log = LoggerFactory.getLogger(SmtpSendStrategy::class.java)

    private lateinit var session: Session
    private lateinit var username: String
    private lateinit var password: String

    override fun initialize(config: Map<String, Any>) {
        val smtpConfig = config["smtp"] as? Map<*, *>
            ?: throw IllegalArgumentException("Gmail plugin SMTP strategy requires 'smtp' config")

        val host = smtpConfig["host"] as? String ?: "smtp.gmail.com"
        val port = (smtpConfig["port"] as? Number)?.toInt() ?: 587
        username = smtpConfig["username"] as? String
            ?: throw IllegalArgumentException("Gmail SMTP requires 'username'")
        password = smtpConfig["password"] as? String
            ?: throw IllegalArgumentException("Gmail SMTP requires 'password'")

        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
            put("mail.smtp.writetimeout", "10000")
        }

        session = Session.getInstance(props, object : jakarta.mail.Authenticator() {
            override fun getPasswordAuthentication() =
                jakarta.mail.PasswordAuthentication(username, password)
        })

        log.info("Gmail SMTP strategy initialized: {}:{} as {}", host, port, username)
    }

    override fun send(email: InboundEmail, fromAlias: String, toAddresses: List<String>) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(fromAlias))
            toAddresses.forEach { addr ->
                addRecipient(MimeMessage.RecipientType.TO, InternetAddress(addr))
            }
            subject = email.subject

            // Prefer plain text, fall back to HTML
            val body = email.bodyPlain ?: email.bodyHtml ?: ""
            if (email.bodyHtml != null && email.bodyPlain == null) {
                setContent(body, "text/html; charset=utf-8")
            } else {
                setText(body, "utf-8")
            }
        }

        jakarta.mail.Transport.send(message)
        log.info("Sent email via Gmail SMTP: from={} to={} subject={}", fromAlias, toAddresses, email.subject)
    }
}
