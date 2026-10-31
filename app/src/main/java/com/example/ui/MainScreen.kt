package com.example.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.DarkNavyBg
import com.example.viewmodel.BoardScreenMode
import com.example.viewmodel.SmartBoardViewModel

@Composable
fun MainScreen(
    boardViewModel: SmartBoardViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val uiState by boardViewModel.uiState.collectAsState()
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Keep screen awake for 86" Smart Display (never sleeps during operation)
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Fullscreen Immersive Kiosk Mode va avtomatik Jonli fon ochilishi
    LaunchedEffect(uiState.isLocked) {
        activity?.let { act ->
            val window = act.window
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (uiState.isLocked) {
            // Standby lock rejimiga o'tish va modal/dialoglarni yopish
            boardViewModel.setScreenMode(BoardScreenMode.STANDBY_LOCK)
            boardViewModel.closeAdminPinDialog()
            boardViewModel.closeSettingsDialog()

            webViewInstance?.let { webView ->
                webView.post {
                    webView.onResume()
                    webView.resumeTimers()
                    // Agar fon sozlamalari oynasi ochiq bo'lsa, toza fon ko'rinishi uchun uni yopish
                    webView.evaluateJavascript(
                        """
                        (function() {
                            var modal = document.getElementById('settingsModal');
                            if (modal) modal.classList.remove('active');
                            if (typeof initParticles === 'function') initParticles();
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }
        }
    }

    // Intercept Back button:
    // When locked: students cannot exit the app!
    // When unlocked: closes whiteboard or minimizes
    BackHandler {
        if (uiState.isLocked) {
            // Tanaffus vaqtida ekranda ortiqcha xabarlar chiqmasligi uchun hech nima ko'rsatmaydi
        } else {
            if (uiState.currentMode == BoardScreenMode.WHITEBOARD_CANVAS) {
                boardViewModel.setScreenMode(BoardScreenMode.UNLOCKED_DESKTOP)
            } else {
                // If on desktop, teacher can choose to lock or minimize
                boardViewModel.lockBoard()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkNavyBg)
    ) {
        // 1. Background Live Wallpaper Effect (Always running in standby & desktop)
        SamWebView(
            url = uiState.config.wallpaperUrl,
            isDesktopMode = true,
            touchDragEnabled = true, // Har doim soatni surish va boshqarish imkoniyati
            zoomScale = 1.0f,
            onOpenAdminPin = {
                boardViewModel.openAdminPinDialog()
            },
            onLoadingChanged = {},
            onProgressChanged = {},
            onErrorOccurred = {},
            onWebViewCreated = { webView -> webViewInstance = webView },
            modifier = Modifier.fillMaxSize()
        )

        // 2. State-based UI Layer
        if (uiState.isLocked) {
            // STANDBY / KIOSK LOCK SCREEN (Toza jonli fon, doska faqat bot orqali ochiladi)
            StandbyLockScreen(
                state = uiState,
                onOpenAdminPin = {
                    boardViewModel.openAdminPinDialog()
                },
                onRequestRegisterViaBot = {
                    boardViewModel.requestBoardRegistrationViaBot()
                },
                onRegisterRoomDirectly = { roomName ->
                    boardViewModel.registerThisBoardAsRoom(roomName)
                }
            )
        } else {
            // UNLOCKED LESSON SESSION ACTIVE
            when (uiState.currentMode) {
                BoardScreenMode.WHITEBOARD_CANVAS -> {
                    WhiteboardCanvas(
                        onCloseWhiteboard = {
                            boardViewModel.setScreenMode(BoardScreenMode.UNLOCKED_DESKTOP)
                        },
                        onLockBoard = {
                            boardViewModel.lockBoard()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                BoardScreenMode.UNLOCKED_DESKTOP -> {
                    // Desktop view: live wallpaper in background with teacher tools
                }
                BoardScreenMode.STANDBY_LOCK -> {
                    // Handled above
                }
            }

            // Floating Active 45-Minute Lesson Overlay (Clean timer, teacher badge, and instant Lock button)
            ActiveLessonOverlay(
                state = uiState,
                onFinishLesson = {
                    boardViewModel.lockBoard()
                },
                onOpenWhiteboard = {
                    boardViewModel.setScreenMode(BoardScreenMode.WHITEBOARD_CANVAS)
                },
                onExtendMinutes = { addMin ->
                    boardViewModel.extendSession(addMin)
                },
                onTogglePause = {
                    boardViewModel.toggleTimerPause()
                }
            )
        }

        // 3. Admin / PIN Dialogs (Faqat tizim sozlamalari uchun)
        if (uiState.showAdminPinDialog) {
            AdminPinDialog(
                correctPin = uiState.config.adminPin,
                onOpenSettings = {
                    boardViewModel.closeAdminPinDialog()
                    boardViewModel.openSettingsDialog()
                },
                onDismiss = {
                    boardViewModel.closeAdminPinDialog()
                }
            )
        }

        if (uiState.showSettingsDialog) {
            AdminSettingsDialog(
                currentConfig = uiState.config,
                teachers = uiState.teachers,
                itAdmins = uiState.itAdmins,
                accessLogs = uiState.accessLogs,
                rooms = uiState.rooms,
                androidId = uiState.androidId,
                macAddress = uiState.macAddress,
                onAddRoom = { id, name -> boardViewModel.addRoom(id, name) },
                onRemoveRoom = { id -> boardViewModel.removeRoom(id) },
                onClearRooms = { boardViewModel.clearRooms() },
                onAddTeacher = { id, name -> boardViewModel.addTeacher(id, name) },
                onRemoveTeacher = { id -> boardViewModel.removeTeacher(id) },
                onAddItAdmin = { id, name -> boardViewModel.addItAdmin(id, name) },
                onRemoveItAdmin = { id -> boardViewModel.removeItAdmin(id) },
                onClearLogs = { boardViewModel.clearLogs() },
                onSaveConfig = { updated ->
                    boardViewModel.updateConfig(updated)
                },
                onTestToken = { token ->
                    boardViewModel.testTelegramToken(token)
                },
                onTestServer = { url ->
                    boardViewModel.testServerConnection(url)
                },
                onDismiss = {
                    boardViewModel.closeSettingsDialog()
                }
            )
        }
    }
}
