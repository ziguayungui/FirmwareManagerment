package com.example.firmwaremanagement.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.firmwaremanagement.model.Stage
import com.example.firmwaremanagement.storage.PrefsManager
import com.example.firmwaremanagement.storage.TaskStateManager
import com.example.firmwaremanagement.storage.FileCleaner
import com.example.firmwaremanagement.utils.SysPropUtils

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        if (TaskStateManager.getCurrentStage(context) == Stage.REBOOT_PENDING) {
            val pendingVer = PrefsManager.getPendingSlotVersion()
            val currentVer = SysPropUtils.getCurrentVersion()

            if (currentVer == pendingVer) {
                FileCleaner.cleanAll(context)
                PrefsManager.clearPendingSlotVersion()
            } else {
                FileCleaner.cleanupOtaFiles(context)
            }
        }
    }
}
