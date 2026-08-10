package io.posterboy.smtp

import io.posterboy.config.SmtpAuthConfig
import io.posterboy.config.SmtpConfig
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmtpServerTest {

    @Test
    fun `receives and parses a plain text email`() {
        val received = AtomicReference<InboundEmail>()
        val latch = CountDownLatch(1)

        val config = SmtpConfig(port = 2626, auth = SmtpAuthConfig(enabled = false))
        val server = SmtpServer(config) { email, _ ->
            received.set(email)
            latch.countDown()
        }

        server.start()
        try {
            sendTestEmail(2626, "sender@test.com", "recipient@example.com", "Hello Test", "This is the body")
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for email")

            val email = received.get()
            assertNotNull(email)
            assertEquals("sender@test.com", email.from)
            assertEquals(listOf("recipient@example.com"), email.to)
            assertEquals("Hello Test", email.subject)
            assertTrue(email.bodyPlain?.contains("This is the body") == true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `receives email with basic auth`() {
        val received = AtomicReference<InboundEmail>()
        val latch = CountDownLatch(1)

        val config = SmtpConfig(
            port = 2627,
            auth = SmtpAuthConfig(enabled = true, username = "relay", password = "secret")
        )
        val server = SmtpServer(config) { email, _ ->
            received.set(email)
            latch.countDown()
        }

        server.start()
        try {
            sendTestEmail(
                2627, "sender@test.com", "recipient@example.com",
                "Auth Test", "Authenticated body",
                username = "relay", password = "secret"
            )
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for email")

            val email = received.get()
            assertNotNull(email)
            assertEquals("Auth Test", email.subject)
        } finally {
            server.stop()
        }
    }

    private fun sendTestEmail(
        port: Int, from: String, to: String, subject: String, body: String,
        username: String? = null, password: String? = null
    ) {
        val props = Properties().apply {
            put("mail.smtp.host", "localhost")
            put("mail.smtp.port", port.toString())
            if (username != null) {
                put("mail.smtp.auth", "true")
            }
        }

        val session = if (username != null) {
            Session.getInstance(props, object : jakarta.mail.Authenticator() {
                override fun getPasswordAuthentication() =
                    jakarta.mail.PasswordAuthentication(username, password)
            })
        } else {
            Session.getInstance(props)
        }

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            setRecipient(MimeMessage.RecipientType.TO, InternetAddress(to))
            setSubject(subject)
            setText(body)
        }

        jakarta.mail.Transport.send(message)
    }
}
