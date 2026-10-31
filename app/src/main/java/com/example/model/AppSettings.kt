package com.example.model

import android.content.Context
import android.content.SharedPreferences

data class WallpaperConfig(
    val url: String = "https://sam-corp-live-wallpaper.vercel.app/",
    val keepScreenOn: Boolean = true,
    val isDesktopMode: Boolean = false,
    val touchDragMode: Boolean = true,
    val isImmersive: Boolean = false,
    val enableOverlayClock: Boolean = false,
    val clockOffsetX: Float = 0f,
    val clockOffsetY: Float = 0f,
    val brightness: Float = 1.0f,
    val screenOrientation: Int = 0, // 0: Auto, 1: Portrait, 2: Landscape
    val zoomScale: Float = 1.0f
)

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sam_wallpaper_prefs", Context.MODE_PRIVATE)

    fun loadConfig(): WallpaperConfig {
        return WallpaperConfig(
            url = prefs.getString(KEY_URL, "https://sam-corp-live-wallpaper.vercel.app/") ?: "https://sam-corp-live-wallpaper.vercel.app/",
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
            isDesktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, false),
            touchDragMode = prefs.getBoolean(KEY_TOUCH_DRAG_MODE, true),
            isImmersive = prefs.getBoolean(KEY_IMMERSIVE, false),
            enableOverlayClock = prefs.getBoolean(KEY_OVERLAY_CLOCK, false),
            clockOffsetX = prefs.getFloat(KEY_CLOCK_X, 0f),
            clockOffsetY = prefs.getFloat(KEY_CLOCK_Y, 0f),
            brightness = prefs.getFloat(KEY_BRIGHTNESS, 1.0f),
            screenOrientation = prefs.getInt(KEY_ORIENTATION, 0),
            zoomScale = prefs.getFloat(KEY_ZOOM, 1.0f)
        )
    }

    fun saveConfig(config: WallpaperConfig) {
        prefs.edit().apply {
            putString(KEY_URL, config.url)
            putBoolean(KEY_KEEP_SCREEN_ON, config.keepScreenOn)
            putBoolean(KEY_DESKTOP_MODE, config.isDesktopMode)
            putBoolean(KEY_TOUCH_DRAG_MODE, config.touchDragMode)
            putBoolean(KEY_IMMERSIVE, config.isImmersive)
            putBoolean(KEY_OVERLAY_CLOCK, config.enableOverlayClock)
            putFloat(KEY_CLOCK_X, config.clockOffsetX)
            putFloat(KEY_CLOCK_Y, config.clockOffsetY)
            putFloat(KEY_BRIGHTNESS, config.brightness)
            putInt(KEY_ORIENTATION, config.screenOrientation)
            putFloat(KEY_ZOOM, config.zoomScale)
            apply()
        }
    }

    companion object {
        private const val KEY_URL = "key_wallpaper_url"
        private const val KEY_KEEP_SCREEN_ON = "key_keep_screen_on"
        private const val KEY_DESKTOP_MODE = "key_desktop_mode"
        private const val KEY_TOUCH_DRAG_MODE = "key_touch_drag_mode"
        private const val KEY_IMMERSIVE = "key_immersive"
        private const val KEY_OVERLAY_CLOCK = "key_overlay_clock"
        private const val KEY_CLOCK_X = "key_clock_x"
        private const val KEY_CLOCK_Y = "key_clock_y"
        private const val KEY_BRIGHTNESS = "key_brightness"
        private const val KEY_ORIENTATION = "key_orientation"
        private const val KEY_ZOOM = "key_zoom"
    }
}
