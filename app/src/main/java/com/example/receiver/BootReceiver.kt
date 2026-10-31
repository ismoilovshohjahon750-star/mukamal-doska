package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.service.FloatingSessionService

/**
 * Doska qayta yoqilganda (Reboot / Power ON) 24/7 orqa fon xizmati va
 * Telegram bot boshqaruvini avtomatik ishga tushiruvchi qabul qiluvchi.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            FloatingSessionService.start(context)
        }
    }
}
