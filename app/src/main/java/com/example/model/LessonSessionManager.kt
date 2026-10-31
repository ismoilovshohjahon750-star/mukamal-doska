package com.example.model

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Doska dars sessiyasi holati - ViewModel, Floating Overlay Service va
 * 24/7 Telegram bot o'rtasida ma'lumotlarni sinxronlashtirish uchun yagona boshqaruvchi obyekt.
 */
object LessonSessionManager {
    val activeSeconds = MutableStateFlow(45 * 60)
    val sessionTotalSeconds = MutableStateFlow(45 * 60)
    val isLocked = MutableStateFlow(true)
    val isPaused = MutableStateFlow(false)
    val teacherName = MutableStateFlow("O'qituvchi")
    val currentRoomId = MutableStateFlow("XONA-1")
    val botStatusText = MutableStateFlow("Kutilmoqda")

    // Botdan kelgan buyruqlar uchun hodisalar
    var onUnlockFromBot: ((teacherName: String, durationMinutes: Int, targetRoomId: String) -> Unit)? = null
    var onLockFromBot: ((targetRoomId: String) -> Unit)? = null

    // Overlay va tizim boshqaruv harakatlari
    var onExtendMinutes: ((Int) -> Unit)? = null
    var onTogglePause: (() -> Unit)? = null
    var onFinishLesson: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null
    var onOpenWhiteboard: (() -> Unit)? = null
    var onReloadBotToken: (() -> Unit)? = null

    // Yangi doska aniqlanganda yoki botdan xona ro'yxatdan o'tkazilganda
    var onBoardRegisteredFromBot: ((boardId: String, roomName: String) -> Unit)? = null
    var onRequestRegisterNewBoard: ((macAddress: String, androidId: String) -> Unit)? = null
}
