package com.whatscapture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
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

        val title = extras.getString(EXTRA_TITLE, "")
        val text = extras.getString(EXTRA_TEXT, "")
        val summary = extras.getString(EXTRA_SUMMARY_TEXT, "")
        val bigText = extras.getString(EXTRA_BIG_TEXT, "")

        val latestMessagingText = try {
            val bundledMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            Notification.MessagingStyle.Message
                .getMessagesFromBundleArray(bundledMessages)
                .lastOrNull()
                ?.text
                ?.toString()
                ?.trim()
                .orEmpty()
        } catch (_: Exception) {
            ""
        }

        val messageText = when {
            latestMessagingText.isNotEmpty() -> latestMessagingText
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

        val messageSignature = "${sbn.packageName}|$sender|${messageText.trim()}"
        if (!shouldSend(messageSignature)) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            TelegramSender.sendMessage(
                token = Config.getToken(this@NotificationListener),
                chatId = Config.getChatId(this@NotificationListener),
                text = finalMsg
            )
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
        private const val MAX_TRACKED_MESSAGES = 600
        @Volatile
        private var connected = false
        private val sentSignatures = LinkedHashSet<String>()

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
