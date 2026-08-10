package io.posterboy.smtp

import io.posterboy.config.SmtpConfig
import org.slf4j.LoggerFactory
import org.subethamail.smtp.AuthenticationHandlerFactory
import org.subethamail.smtp.auth.EasyAuthenticationHandlerFactory
import org.subethamail.smtp.auth.LoginFailedException
import org.subethamail.smtp.auth.UsernamePasswordValidator
import org.subethamail.smtp.helper.SimpleMessageListener
import org.subethamail.smtp.helper.SimpleMessageListenerAdapter
import org.subethamail.smtp.server.SMTPServer
import java.io.InputStream

class SmtpServer(
    private val config: SmtpConfig,
    private val onMessage: (InboundEmail, ByteArray) -> Unit
) {

    private val log = LoggerFactory.getLogger(SmtpServer::class.java)
    private lateinit var server: SMTPServer

    fun start() {
        val listener = object : SimpleMessageListener {
            override fun accept(from: String, recipient: String): Boolean = true

            override fun deliver(from: String, recipient: String, data: InputStream) {
                try {
                    val bytes = data.readBytes()
                    val email = EmailParser.parse(from, listOf(recipient), bytes)
                    log.info("Received email from={} to={} subject={}", email.from, email.to, email.subject)
                    onMessage(email, bytes)
                } catch (e: Exception) {
                    log.error("Failed to process inbound email from={} to={}", from, recipient, e)
                }
            }
        }

        val handlerFactory = SimpleMessageListenerAdapter(listener)

        server = if (config.auth.enabled) {
            val validator = UsernamePasswordValidator { username, password ->
                if (username != config.auth.username || password != config.auth.password) {
                    throw LoginFailedException("Invalid credentials")
                }
            }
            val authFactory = EasyAuthenticationHandlerFactory(validator)
            SMTPServer(handlerFactory, authFactory)
        } else {
            SMTPServer(handlerFactory)
        }

        server.port = config.port
        server.start()
        log.info("SMTP server listening on port {}, auth={}", config.port, config.auth.enabled)
    }

    fun stop() {
        if (::server.isInitialized) {
            server.stop()
            log.info("SMTP server stopped")
        }
    }
}
