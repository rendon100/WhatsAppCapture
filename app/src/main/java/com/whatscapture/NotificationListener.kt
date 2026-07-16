package com.whatscapture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        Log.i(TAG, "Notification listener conectado")
    }

    override fun onListenerDisconnected() {
        connected = false
        Log.w(TAG, "Notification listener desconectado, solicitando rebind")
        try {
            forceRebind(this)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo solicitar rebind", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != WHATSAPP_PACKAGE && sbn.packageName != WHATSAPP_BUSINESS_PACKAGE) return
        if (!connected) {
            Log.w(TAG, "Notificacion recibida mientras el listener no figura conectado")
        }

        val extras: Bundle = sbn.notification.extras ?: return
        val chatName = extras.getString(EXTRA_TITLE, "").ifBlank { "Desconocido" }
        val selfDisplayName = extras
            .getCharSequence(EXTRA_SELF_DISPLAY_NAME)
            ?.toString()
            ?.trim()
            .orEmpty()

        val messagingMessages = extractMessagingMessages(extras, selfDisplayName)
        val hasOutgoingInBundle = messagingMessages.any { it.isFromMe }
        if (messagingMessages.isNotEmpty()) {
            messagingMessages
                .sortedBy { it.timestamp }
                .forEach { message ->
                    val mediaKey = if (message.dataUri.isNotEmpty()) {
                        "${message.dataMimeType}|${message.dataUri}"
                    } else {
                        ""
                    }

                    val messageSignature = buildMessageSignature(
                        sbn = sbn,
                        sender = chatName,
                        messageText = message.text,
                        messagingTimestamp = message.timestamp,
                        messagingSender = message.sender,
                        payloadKey = mediaKey
                    )
                    if (!shouldSend(messageSignature)) return@forEach

                    if (message.dataUri.isNotEmpty()) {
                        sendMediaFromMessage(
                            mediaUri = message.dataUri,
                            mimeType = message.dataMimeType,
                            fallbackName = "whatsapp_media_${message.timestamp}"
                        )
                    }

                    if (message.isFromMe) return@forEach

                    val senderLabel = when {
                        message.isFromMe -> "Tu"
                        message.sender.isNotEmpty() -> message.sender
                        else -> chatName
                    }
                    val directionEmoji = if (message.isFromMe) "📤" else "📩"
                    val finalMsg = """
                        $directionEmoji *WhatsApp - $chatName*
                        $senderLabel: ${message.text}
                    """.trimIndent()

                    CoroutineScope(Dispatchers.IO).launch {
                        TelegramSender.sendMessage(
                            token = Config.getToken(this@NotificationListener),
                            chatId = Config.getChatId(this@NotificationListener),
                            text = finalMsg
                        )
                    }
                }
        }

        val text = extras.getString(EXTRA_TEXT, "")
        val summary = extras.getString(EXTRA_SUMMARY_TEXT, "")
        val bigText = extras.getString(EXTRA_BIG_TEXT, "")

        val fallbackMessage = extractFallbackMessage(
            bigText = bigText,
            summary = summary,
            text = text,
            selfDisplayName = selfDisplayName
        )

        if (fallbackMessage != null) {
            if (fallbackMessage.isFromMe) {
                return
            }

            val fallbackAlreadyInBundle = messagingMessages.any {
                normalizeForCompare(it.text) == normalizeForCompare(fallbackMessage.text)
            }
            val shouldUseFallback =
                messagingMessages.isEmpty() ||
                    (fallbackMessage.isFromMe && !hasOutgoingInBundle) ||
                    !fallbackAlreadyInBundle

            if (shouldUseFallback) {
                val messageSignature = buildMessageSignature(
                    sbn = sbn,
                    sender = chatName,
                    messageText = fallbackMessage.text,
                    messagingTimestamp = sbn.postTime,
                    messagingSender = fallbackMessage.sender
                )
                if (shouldSend(messageSignature)) {
                    val senderLabel = when {
                        fallbackMessage.isFromMe -> "Tu"
                        fallbackMessage.sender.isNotEmpty() -> fallbackMessage.sender
                        else -> chatName
                    }
                    val directionEmoji = if (fallbackMessage.isFromMe) "📤" else "📩"
                    val finalMsg = """
                        $directionEmoji *WhatsApp - $chatName*
                        $senderLabel: ${fallbackMessage.text}
                    """.trimIndent()

                    CoroutineScope(Dispatchers.IO).launch {
                        TelegramSender.sendMessage(
                            token = Config.getToken(this@NotificationListener),
                            chatId = Config.getChatId(this@NotificationListener),
                            text = finalMsg
                        )
                    }
                }
            }
        }
    }

    private fun extractMessagingMessages(extras: Bundle, selfDisplayName: String): List<CapturedMessage> {
        return try {
            val bundledMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            Notification.MessagingStyle.Message
                .getMessagesFromBundleArray(bundledMessages)
                .mapNotNull { msg ->
                    val text = msg.text?.toString()?.trim().orEmpty()
                    val dataUri = msg.dataUri?.toString().orEmpty()
                    val dataMimeType = msg.dataMimeType?.trim().orEmpty()
                    if (text.isEmpty() && dataUri.isEmpty()) return@mapNotNull null

                    val sender = msg.sender?.toString()?.trim().orEmpty()
                    val isFromMe = isLikelySelfMessage(sender, selfDisplayName)
                    CapturedMessage(
                        sender = sender,
                        text = text,
                        timestamp = msg.timestamp,
                        isFromMe = isFromMe,
                        dataUri = dataUri,
                        dataMimeType = dataMimeType
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isLikelySelfMessage(sender: String, selfDisplayName: String): Boolean {
        val normalizedSender = sender.lowercase().trim()
        val normalizedSelf = selfDisplayName.lowercase().trim()
        if (normalizedSender.isEmpty()) return false
        if (normalizedSelf.isNotEmpty() && normalizedSender == normalizedSelf) return true

        return normalizedSender == "you" ||
            normalizedSender == "tu" ||
            normalizedSender == "tú" ||
            normalizedSender == "yo"
    }

    private fun extractFallbackMessage(
        bigText: String,
        summary: String,
        text: String,
        selfDisplayName: String
    ): CapturedMessage? {
        val candidate = when {
            bigText.isNotBlank() -> bigText.trim()
            summary.isNotBlank() -> summary.trim()
            text.isNotBlank() -> text.trim()
            else -> return null
        }

        val parts = candidate.split(':', limit = 2)
        if (parts.size == 2) {
            val possibleSender = parts[0].trim()
            val possibleText = parts[1].trim()
            if (possibleText.isNotEmpty()) {
                return CapturedMessage(
                    sender = possibleSender,
                    text = possibleText,
                    timestamp = 0L,
                    isFromMe = isLikelySelfMessage(possibleSender, selfDisplayName)
                )
            }
        }

        return CapturedMessage(
            sender = "",
            text = candidate,
            timestamp = 0L,
            isFromMe = false
        )
    }

    private fun normalizeForCompare(value: String): String {
        return value.lowercase().trim().replace("\\s+".toRegex(), " ")
    }

    private fun sendMediaFromMessage(mediaUri: String, mimeType: String, fallbackName: String) {
        try {
            val uri = Uri.parse(mediaUri)
            val input = contentResolver.openInputStream(uri) ?: return
            val extension = extensionFromMimeType(mimeType)
            val tempFile = java.io.File.createTempFile(fallbackName, extension, cacheDir)

            input.use { stream ->
                tempFile.outputStream().use { output ->
                    stream.copyTo(output)
                }
            }

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                tempFile.delete()
                return
            }

            val sent = TelegramSender.sendFile(
                token = Config.getToken(this),
                chatId = Config.getChatId(this),
                filePath = tempFile.absolutePath,
                fileName = tempFile.name
            )

            if (!sent) {
                Log.e(TAG, "No se pudo enviar multimedia desde dataUri: $mediaUri")
            }

            tempFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando multimedia desde dataUri", e)
        }
    }

    private fun extensionFromMimeType(mimeType: String): String {
        val normalized = mimeType.lowercase().trim()
        return when {
            normalized.contains("jpeg") -> ".jpg"
            normalized.contains("png") -> ".png"
            normalized.contains("gif") -> ".gif"
            normalized.contains("webp") -> ".webp"
            normalized.contains("mp4") -> ".mp4"
            normalized.contains("3gpp") -> ".3gp"
            normalized.contains("matroska") -> ".mkv"
            normalized.contains("mpeg") && normalized.contains("audio") -> ".mp3"
            normalized.contains("ogg") -> ".ogg"
            normalized.contains("pdf") -> ".pdf"
            normalized.contains("msword") -> ".doc"
            normalized.contains("officedocument") -> ".docx"
            else -> ".bin"
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    companion object {
        private const val TAG = "NotificationListener"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val EXTRA_TITLE = "android.title"
        private const val EXTRA_TEXT = "android.text"
        private const val EXTRA_SUMMARY_TEXT = "android.summaryText"
        private const val EXTRA_BIG_TEXT = "android.bigText"
        private const val EXTRA_SELF_DISPLAY_NAME = "android.selfDisplayName"
        private const val MAX_TRACKED_MESSAGES = 600

        @Volatile
        private var connected = false
        private val sentSignatures = LinkedHashSet<String>()

        private data class CapturedMessage(
            val sender: String,
            val text: String,
            val timestamp: Long,
            val isFromMe: Boolean,
            val dataUri: String,
            val dataMimeType: String
        )

        private fun buildMessageSignature(
            sbn: StatusBarNotification,
            sender: String,
            messageText: String,
            messagingTimestamp: Long,
            messagingSender: String,
            payloadKey: String = ""
        ): String {
            val normalizedText = messageText.trim()
            val normalizedSender = sender.trim()
            val normalizedMessagingSender = messagingSender.trim()
            val normalizedPayloadKey = payloadKey.trim()
            val eventTimestamp = if (messagingTimestamp > 0L) messagingTimestamp else sbn.postTime
            val notificationKey = sbn.key ?: ""

            return listOf(
                sbn.packageName,
                notificationKey,
                eventTimestamp.toString(),
                normalizedSender,
                normalizedMessagingSender,
                normalizedText,
                normalizedPayloadKey
            ).joinToString("|")
        }

        private fun shouldSend(signature: String): Boolean {
            val normalized = signature.trim()
            if (normalized.isEmpty()) return false

            synchronized(sentSignatures) {
                if (sentSignatures.contains(normalized)) return false
                sentSignatures.add(normalized)

                while (sentSignatures.size > MAX_TRACKED_MESSAGES) {
                    val iterator = sentSignatures.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    } else {
                        break
                    }
                }
            }
            return true
        }

        fun forceRebind(context: Context) {
            val component = ComponentName(context, NotificationListener::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestRebind(component)
                return
            }

            // Fallback for old versions: toggling the component forces a fresh bind.
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
