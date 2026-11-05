package io.posterboy.plugins.gmail

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.Message
import io.posterboy.smtp.InboundEmail
import jakarta.activation.DataHandler
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.util.Base64
import java.util.Properties

class ApiSendStrategy : SendStrategy {

    private val log = LoggerFactory.getLogger(ApiSendStrategy::class.java)

    private var gmail: Gmail? = null
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport = NetHttpTransport.Builder().build()
    private var flow: GoogleAuthorizationCodeFlow? = null
    private var tokensPath: String = "/data/tokens"

    val isAuthorized: Boolean get() = gmail != null

    val hasCredentials: Boolean get() = flow != null

    @Suppress("UNCHECKED_CAST")
    override fun initialize(config: Map<String, Any>) {
        val apiConfig = config["api"] as? Map<*, *> ?: emptyMap<String, Any>()

        // Look for tokens_path and credentials in both api sub-block and top-level
        tokensPath = apiConfig["tokens_path"] as? String
            ?: config["tokens_path"] as? String
            ?: "/data/tokens"
        File(tokensPath).mkdirs()

        // Credentials can come from:
        // 1. Inline as client_id + client_secret (in api block or top-level)
        // 2. Full GCP JSON blob stored under "credentials" key
        val clientId = apiConfig["client_id"] as? String ?: config["client_id"] as? String
        val clientSecret = apiConfig["client_secret"] as? String ?: config["client_secret"] as? String
        val credentialsJson = apiConfig["credentials"] as? String ?: config["credentials"] as? String

        when {
            clientId != null && clientSecret != null -> {
                initializeFlowFromInline(clientId, clientSecret)
                tryLoadExistingToken()
            }
            credentialsJson != null -> {
                initializeFlowFromJson(credentialsJson)
                tryLoadExistingToken()
            }
            else -> {
                log.warn("Gmail API: no credentials configured. Set client_id/client_secret or paste credentials JSON via admin UI.")
            }
        }
    }

    private fun initializeFlowFromInline(clientId: String, clientSecret: String) {
        val secrets = GoogleClientSecrets().apply {
            web = GoogleClientSecrets.Details().apply {
                this.clientId = clientId
                this.clientSecret = clientSecret
                this.tokenUri = "https://oauth2.googleapis.com/token"
                this.authUri = "https://accounts.google.com/o/oauth2/auth"
            }
        }
        flow = GoogleAuthorizationCodeFlow.Builder(
            httpTransport, jsonFactory, secrets,
            listOf(GmailScopes.GMAIL_SEND, GmailScopes.GMAIL_SETTINGS_BASIC)
        )
            .setDataStoreFactory(FileDataStoreFactory(File(tokensPath)))
            .setAccessType("offline")
            .build()
        log.info("Gmail API flow initialized from inline credentials")
    }

    private fun initializeFlowFromJson(json: String) {
        val secrets = GoogleClientSecrets.load(jsonFactory, StringReader(json))
        flow = GoogleAuthorizationCodeFlow.Builder(
            httpTransport, jsonFactory, secrets,
            listOf(GmailScopes.GMAIL_SEND, GmailScopes.GMAIL_SETTINGS_BASIC)
        )
            .setDataStoreFactory(FileDataStoreFactory(File(tokensPath)))
            .setAccessType("offline")
            .build()
        log.info("Gmail API flow initialized from credentials JSON")
    }

    private fun tryLoadExistingToken() {
        val credential = flow?.loadCredential("user")
        if (credential == null || credential.refreshToken == null) {
            log.warn("No OAuth token found. Authorize via admin UI.")
            return
        }

        // Prove the refresh token is actually live by exchanging it for a
        // new access token. Works regardless of which Gmail scopes we hold
        // (unlike a getProfile() check, which needs gmail.readonly). An
        // Internal-mode refresh token is long-lived but can still die from
        // password change, manual revocation, admin revocation, or scope
        // change; failing here instead of on first send lets us alert the
        // operator proactively.
        try {
            val refreshed = credential.refreshToken()
            if (!refreshed) {
                log.error("Gmail API refresh token exchange returned false; token is dead")
                return
            }
        } catch (e: Exception) {
            log.error("Gmail API refresh token exchange failed: {}", e.message)
            return
        }

        gmail = Gmail.Builder(httpTransport, jsonFactory, credential)
            .setApplicationName("Posterboy")
            .build()
        log.info("Gmail API authorized with stored token (refresh exchange OK)")
    }

    /**
     * Re-exchange the refresh token for a fresh access token. Safe to call
     * periodically to detect a revoked refresh token without waiting for a
     * real send to fail.
     */
    fun validate(): Boolean {
        val g = gmail ?: return false
        // The Credential used inside the Gmail client is the same one we
        // loaded from the flow; calling refreshToken on it directly re-hits
        // Google's token endpoint.
        return try {
            val credential = flow?.loadCredential("user") ?: return false
            val ok = credential.refreshToken()
            if (!ok) {
                log.error("Gmail API refresh token exchange returned false during revalidation")
                gmail = null
                false
            } else {
                true
            }
        } catch (e: Exception) {
            log.error("Gmail API refresh token exchange failed during revalidation: {}", e.message)
            gmail = null
            false
        }
    }

    fun setCredentialsFromJson(json: String) {
        initializeFlowFromJson(json)
        log.info("GCP credentials loaded from pasted JSON")
    }

    fun getAuthorizationUrl(redirectUri: String): String {
        val f = flow ?: throw IllegalStateException("Configure credentials first")
        return f.newAuthorizationUrl()
            .setRedirectUri(redirectUri)
            .set("prompt", "consent")
            .build()
    }

    fun exchangeCode(code: String, redirectUri: String) {
        val f = flow ?: throw IllegalStateException("Configure credentials first")
        val tokenResponse = f.newTokenRequest(code)
            .setRedirectUri(redirectUri)
            .execute()
        f.createAndStoreCredential(tokenResponse, "user")

        val credential = f.loadCredential("user")!!
        gmail = Gmail.Builder(httpTransport, jsonFactory, credential)
            .setApplicationName("Posterboy")
            .build()
        log.info("Gmail OAuth authorization complete, token stored")
    }

    fun listAliases(): List<Map<String, String>> {
        val g = gmail ?: throw IllegalStateException("Gmail not authorized")
        val sendAs = g.users().settings().sendAs().list("me").execute()
        return sendAs.sendAs.map { alias ->
            mapOf(
                "email" to alias.sendAsEmail,
                "displayName" to (alias.displayName ?: ""),
                "isPrimary" to (alias.isPrimary?.toString() ?: "false"),
                "isDefault" to (alias.isDefault?.toString() ?: "false")
            )
        }
    }

    override fun send(email: InboundEmail, fromAlias: String, toAddresses: List<String>) {
        val g = gmail ?: throw IllegalStateException(
            "Gmail not authorized. Complete OAuth setup via the admin UI."
        )

        val encodedEmail = Base64.getUrlEncoder().encodeToString(buildRaw(email, fromAlias, toAddresses))

        val message = Message().apply { raw = encodedEmail }
        try {
            g.users().messages().send("me", message).execute()
        } catch (e: Exception) {
            // An auth failure partway through life (e.g., token revoked since
            // startup validation) should flip us to unauthorized so subsequent
            // sends fail-fast and the plugin-level alerter can run.
            if (looksLikeAuthFailure(e)) {
                log.error("Gmail send hit auth failure; marking unauthorized")
                gmail = null
            }
            throw e
        }

        log.info("Sent email via Gmail API: from={} to={} subject={}", fromAlias, toAddresses, email.subject)
    }

    companion object {
        fun looksLikeAuthFailure(e: Throwable): Boolean {
            val msg = (e.message ?: "").lowercase()
            return "invalid_grant" in msg ||
                "unauthorized" in msg ||
                "not authorized" in msg ||
                "invalid_token" in msg ||
                "401" in msg
        }

        /**
         * RFC 5322 line endings. JavaMail writes headers with CRLF but passes a text body through
         * as given, and the Gmail API stores the raw bytes as-is, so a bare-LF body (e.g. JSON
         * ingest) became a mixed-ending message. IMAP clients that trust
         * the CRLF-normalised RFC822.SIZE (FairEmail) then truncated the tail by one byte per line.
         */
        internal fun crlf(s: String): String = s.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n")

        /** The exact MIME bytes sent through the Gmail API (headers and body CRLF). */
        internal fun buildRaw(email: InboundEmail, fromAlias: String, toAddresses: List<String>): ByteArray {
            val session = Session.getDefaultInstance(Properties())
            val mimeMessage = MimeMessage(session).apply {
                setFrom(InternetAddress(fromAlias))
                toAddresses.forEach { addr ->
                    addRecipient(MimeMessage.RecipientType.TO, InternetAddress(addr))
                }
                subject = email.subject

                if (email.fullAttachments.isNotEmpty()) {
                    val multipart = MimeMultipart("mixed")

                    // Body part
                    val bodyPart = MimeBodyPart()
                    val body = crlf(email.bodyPlain ?: email.bodyHtml ?: "")
                    if (email.bodyHtml != null && email.bodyPlain == null) {
                        bodyPart.setContent(body, "text/html; charset=utf-8")
                    } else {
                        bodyPart.setText(body, "utf-8")
                    }
                    multipart.addBodyPart(bodyPart)

                    // Attachment parts
                    for (att in email.fullAttachments) {
                        val attPart = MimeBodyPart()
                        attPart.dataHandler = DataHandler(ByteArrayDataSource(att.data, att.meta.mimeType))
                        attPart.fileName = att.meta.filename
                        multipart.addBodyPart(attPart)
                    }

                    setContent(multipart)
                } else {
                    val body = crlf(email.bodyPlain ?: email.bodyHtml ?: "")
                    if (email.bodyHtml != null && email.bodyPlain == null) {
                        setContent(body, "text/html; charset=utf-8")
                    } else {
                        setText(body, "utf-8")
                    }
                }
            }
            val buffer = ByteArrayOutputStream()
            mimeMessage.writeTo(buffer)
            return buffer.toByteArray()
        }
    }
}
