package io.posterboy.plugins.gmail

import io.posterboy.smtp.InboundEmail

interface SendStrategy {
    fun initialize(config: Map<String, Any>)
    fun send(email: InboundEmail, fromAlias: String, toAddresses: List<String>)
    fun shutdown() {}
}
