package com.example.model

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig

data class SmartBoardConfig(
    val botToken: String = DEFAULT_BOT_TOKEN,
    val botUsername: String = DEFAULT_BOT_USERNAME,
    val boardId: String = "",
    val roomName: String = "",
    val adminPin: String = "1234",
    val defaultSessionMinutes: Int = 45,
    val warningMinutes: Int = 5,
    val wallpaperUrl: String = "https://sam-corp-live-wallpaper.vercel.app/",
    val useBuiltinWallpaper: Boolean = false,
    val keepScreenAwake: Boolean = true,
    val isRegistered: Boolean = false,
    val connectionMode: String = MODE_RENDER_SERVER,
    val serverUrl: String = DEFAULT_SERVER_URL
) {
    companion object {
        const val DEFAULT_BOT_TOKEN = ""
        const val DEFAULT_BOT_USERNAME = ""
        const val DEFAULT_SERVER_URL = "https://mukamal-doska.onrender.com"
        const val MODE_RENDER_SERVER = "RENDER_SERVER"
        const val MODE_DIRECT_BOT = "DIRECT_BOT"
    }
}

class SmartBoardPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("smartboard_86_prefs", Context.MODE_PRIVATE)

    fun loadConfig(): SmartBoardConfig {
        val configuredToken = try {
            if (BuildConfig.TELEGRAM_BOT_TOKEN.isNotBlank() && !BuildConfig.TELEGRAM_BOT_TOKEN.startsWith("YOUR_")) {
                BuildConfig.TELEGRAM_BOT_TOKEN.trim()
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
        val savedToken = prefs.getString(KEY_BOT_TOKEN, null)
        val finalToken = if (savedToken.isNullOrBlank() || savedToken.startsWith("YOUR_") || !savedToken.contains(":")) {
            configuredToken
        } else {
            savedToken.trim()
        }

        val savedUsername = prefs.getString(KEY_BOT_USERNAME, null)
        val finalUsername = if (savedUsername.isNullOrBlank() || savedUsername.startsWith("YOUR_")) {
            ""
        } else {
            savedUsername.trim()
        }

        val savedBoardId = prefs.getString(KEY_BOARD_ID, "") ?: ""
        val finalBoardId = if (savedBoardId.isNotBlank()) {
            savedBoardId
        } else {
            val genId = "BOARD_" + java.util.UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
            prefs.edit().putString(KEY_BOARD_ID, genId).apply()
            genId
        }

        return SmartBoardConfig(
            botToken = finalToken,
            botUsername = finalUsername,
            boardId = finalBoardId,
            roomName = prefs.getString(KEY_ROOM_NAME, "") ?: "",
            adminPin = prefs.getString(KEY_ADMIN_PIN, "1234") ?: "1234",
            defaultSessionMinutes = prefs.getInt(KEY_SESSION_MINUTES, 45),
            warningMinutes = prefs.getInt(KEY_WARNING_MINUTES, 5),
            wallpaperUrl = prefs.getString(KEY_WALLPAPER_URL, "https://sam-corp-live-wallpaper.vercel.app/") ?: "https://sam-corp-live-wallpaper.vercel.app/",
            useBuiltinWallpaper = prefs.getBoolean(KEY_USE_BUILTIN_WALLPAPER, false),
            keepScreenAwake = prefs.getBoolean(KEY_KEEP_AWAKE, true),
            isRegistered = prefs.getBoolean(KEY_IS_REGISTERED, false),
            connectionMode = prefs.getString(KEY_CONNECTION_MODE, SmartBoardConfig.MODE_RENDER_SERVER) ?: SmartBoardConfig.MODE_RENDER_SERVER,
            serverUrl = prefs.getString(KEY_SERVER_URL, SmartBoardConfig.DEFAULT_SERVER_URL) ?: SmartBoardConfig.DEFAULT_SERVER_URL
        )
    }

    fun isBoardRegistered(): Boolean = prefs.getBoolean(KEY_IS_REGISTERED, false)

    fun setBoardRegistered(registered: Boolean) {
        prefs.edit().putBoolean(KEY_IS_REGISTERED, registered).apply()
    }

    fun saveConfig(config: SmartBoardConfig) {
        prefs.edit().apply {
            putString(KEY_BOT_TOKEN, config.botToken)
            putString(KEY_BOT_USERNAME, config.botUsername)
            putString(KEY_BOARD_ID, config.boardId)
            putString(KEY_ROOM_NAME, config.roomName)
            putString(KEY_ADMIN_PIN, config.adminPin)
            putInt(KEY_SESSION_MINUTES, config.defaultSessionMinutes)
            putInt(KEY_WARNING_MINUTES, config.warningMinutes)
            putString(KEY_WALLPAPER_URL, config.wallpaperUrl)
            putBoolean(KEY_USE_BUILTIN_WALLPAPER, config.useBuiltinWallpaper)
            putBoolean(KEY_KEEP_AWAKE, config.keepScreenAwake)
            putBoolean(KEY_IS_REGISTERED, config.isRegistered)
            putString(KEY_CONNECTION_MODE, config.connectionMode)
            putString(KEY_SERVER_URL, config.serverUrl)
            apply()
        }
    }

    companion object {
        private const val KEY_BOT_TOKEN = "key_bot_token"
        private const val KEY_BOT_USERNAME = "key_bot_username"
        private const val KEY_BOARD_ID = "key_board_id"
        private const val KEY_ROOM_NAME = "key_room_name"
        private const val KEY_ADMIN_PIN = "key_admin_pin"
        private const val KEY_SESSION_MINUTES = "key_session_minutes"
        private const val KEY_WARNING_MINUTES = "key_warning_minutes"
        private const val KEY_WALLPAPER_URL = "key_wallpaper_url"
        private const val KEY_USE_BUILTIN_WALLPAPER = "key_use_builtin_wallpaper"
        private const val KEY_KEEP_AWAKE = "key_keep_awake"
        private const val KEY_IS_REGISTERED = "key_is_registered"
        private const val KEY_CONNECTION_MODE = "key_connection_mode"
        private const val KEY_SERVER_URL = "key_server_url"
    }
}
