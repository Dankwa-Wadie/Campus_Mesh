package com.campusmesh.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // This receiver has to be exported for the system to deliver BOOT_COMPLETED to it, which
        // also means any app can target it with an explicit intent. Only act on the real thing.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Ensure preferences are initialized on cold boot before reading values
        try { MeshServicePreferences.init(context.applicationContext) } catch (_: Exception) { }

        if (MeshServicePreferences.isAutoStartEnabled(true)) {
            MeshForegroundService.start(context.applicationContext)
        }
    }
}
