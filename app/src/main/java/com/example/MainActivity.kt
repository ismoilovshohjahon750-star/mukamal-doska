package com.example

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.model.LessonSessionManager
import com.example.service.FloatingSessionService
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.BoardScreenMode
import com.example.viewmodel.SmartBoardViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val boardViewModel: SmartBoardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 86" Doska hech qachon o'chib qolmasligi uchun ekran doim yoqiq turadi
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Tizim panellarini yashirish (Immersive Kiosk Mode)
        applyKioskImmersive()

        // 24/7 Orqa fon xizmatini darhol ishga tushirish (Botdan istalgan payt lock olish uchun)
        FloatingSessionService.start(this)

        // Orqaga (Back) tugmasini qulflangan paytda to'liq bloklash
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (boardViewModel.uiState.value.isLocked) {
                    // Qulflangan paytda orqaga qaytish to'liq bloklanadi (hech qanday qayta yuklashsiz)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // LessonSessionManager dan ilovani qayta ochish buyrug'i
        LessonSessionManager.onOpenApp = {
            bringActivityToFront(force = true)
        }

        // Qulflangan holatni kuzatish (Faqat holat o'zgargandagina bir marta ishga tushadi)
        var previousLocked: Boolean? = null
        lifecycleScope.launch {
            boardViewModel.uiState.map { it.isLocked }.distinctUntilChanged().collect { locked ->
                if (locked) {
                    applyKioskImmersive()
                    enableKioskLockTask()

                    if (previousLocked == false) {
                        bringActivityToFront(force = true)
                    }
                } else {
                    disableKioskLockTask()
                }
                previousLocked = locked
            }
        }

        // Telegram bot orqali to'g'ridan-to'g'ri lock/block buyrug'i kelganda
        lifecycleScope.launch {
            boardViewModel.onLockEvent.collect {
                applyKioskImmersive()
                enableKioskLockTask()
                bringActivityToFront(force = true)
            }
        }

        // Telegram bot orqali dars ochilganda suzuvchi xizmat
        lifecycleScope.launch {
            boardViewModel.shouldMinimizeToDesktop.collect {
                FloatingSessionService.start(this@MainActivity)
            }
        }

        setContent {
            MyApplicationTheme {
                MainScreen(boardViewModel = boardViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("EXTRA_OPEN_WHITEBOARD", false)) {
            boardViewModel.setScreenMode(BoardScreenMode.WHITEBOARD_CANVAS)
        } else if (boardViewModel.uiState.value.isLocked || intent.getBooleanExtra("EXTRA_ENSURE_WALLPAPER", false)) {
            // Jonli fon rejimini tiklash va dialoglarni yopish
            boardViewModel.setScreenMode(BoardScreenMode.STANDBY_LOCK)
            boardViewModel.closeAdminPinDialog()
            boardViewModel.closeSettingsDialog()
            applyKioskImmersive()
            enableKioskLockTask()
        }
    }

    override fun onResume() {
        super.onResume()
        FloatingSessionService.start(this)
        if (boardViewModel.uiState.value.isLocked) {
            applyKioskImmersive()
            enableKioskLockTask()
        }
    }

    override fun onPause() {
        super.onPause()
        // Standby paytida dialoglar yoki sozlamalar ochilganda to'xtab qolmasligi uchun pauzada faqat immersive saqlanadi
    }

    override fun onStop() {
        super.onStop()
        // Qulflangan paytda foydalanuvchi qandaydir yo'l bilan ilovadan chiqib ketishga urinsa, darhol qayta oldinga chiqarish
        if (boardViewModel.uiState.value.isLocked) {
            bringActivityToFront(force = true)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (boardViewModel.uiState.value.isLocked) {
            applyKioskImmersive()
            if (!hasFocus) {
                // Bildirishnomalar paneli yoki tizim menyularini darhol yopish
                try {
                    @Suppress("DEPRECATION")
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                } catch (_: Exception) {}

                // Agar fokus tizim tomonidan tortib olinsa (masalan Recents ochilsa), 150ms dan so'ng tekshirib oldinga chiqarish
                window?.decorView?.postDelayed({
                    if (boardViewModel.uiState.value.isLocked && !hasWindowFocus()) {
                        bringActivityToFront(force = true)
                    }
                }, 150L)
            }
        }
    }

    /**
     * Agar doska qulflangan bo'lsa va foydalanuvchi tizim orqali chiqishga urinsa (Home yoki Recents bosilganda)
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (boardViewModel.uiState.value.isLocked) {
            bringActivityToFront(force = true)
        } else {
            if (Settings.canDrawOverlays(this)) {
                FloatingSessionService.start(this)
            }
        }
    }


    /**
     * Klaviaturadagi yoki doska apparat tugmalaridagi Back, Home, App Switch, Search, Settings tugmalarini to'sish
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (boardViewModel.uiState.value.isLocked) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_WINDOW,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_ASSIST,
                KeyEvent.KEYCODE_VOICE_ASSIST,
                KeyEvent.KEYCODE_ALL_APPS,
                KeyEvent.KEYCODE_ESCAPE -> {
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var isLockTaskActive = false

    private fun enableKioskLockTask() {
        if (isLockTaskActive) return
        try {
            startLockTask()
            isLockTaskActive = true
        } catch (_: Exception) {}
    }

    private fun disableKioskLockTask() {
        if (!isLockTaskActive) return
        try {
            stopLockTask()
            isLockTaskActive = false
        } catch (_: Exception) {}
    }

    private var lastBringToFrontTime = 0L

    fun bringActivityToFront(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastBringToFrontTime < 2500L) {
            return // Qayta-qayta takrorlanish (loop)ni oldini olish
        }
        lastBringToFrontTime = now

        applyKioskImmersive()

        try {
            // Taskni tizim darajasida eng oldingi o'ringa (foreground) olib chiqish
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
        } catch (_: Exception) {}

        try {
            val reopenIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("EXTRA_ENSURE_WALLPAPER", true)
            }
            try {
                val pi = PendingIntent.getActivity(
                    this,
                    0,
                    reopenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pi.send()
            } catch (_: Exception) {}
            startActivity(reopenIntent)
        } catch (_: Exception) {}
    }

    private fun applyKioskImmersive() {
        val w = window ?: return
        w.decorView.post {
            val insetsController = WindowCompat.getInsetsController(w, w.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
