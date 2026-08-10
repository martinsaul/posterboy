package io.posterboy.smtp

data class AttachmentMeta(
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long
)

data class Attachment(
    val meta: AttachmentMeta,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is Attachment && meta == other.meta && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * meta.hashCode() + data.contentHashCode()
}

data class InboundEmail(
    val from: String,
    val to: List<String>,
    val subject: String,
    val bodyPlain: String?,
    val bodyHtml: String?,
    val attachments: List<AttachmentMeta>,
    val fullAttachments: List<Attachment> = emptyList()
)
