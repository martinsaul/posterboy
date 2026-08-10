package io.posterboy.plugin

import io.posterboy.smtp.InboundEmail

/**
 * A test-only [Extension] used to verify ServiceLoader discovery, config
 * injection, and pipeline submission. Registered via
 * `src/test/resources/META-INF/services/io.posterboy.plugin.Extension`.
 */
class TestExtension : Extension {
    override val id = "test-ext"

    var initialized = false
    var greeting: String? = null

    override fun initialize(ctx: ExtensionContext) {
        initialized = true
        greeting = ctx.config["greeting"] as? String
        ctx.submit(
            InboundEmail(
                from = "a@example.com",
                to = listOf("b@example.com"),
                subject = "hi",
                bodyPlain = "body",
                bodyHtml = null,
                attachments = emptyList(),
            )
        )
    }
}
