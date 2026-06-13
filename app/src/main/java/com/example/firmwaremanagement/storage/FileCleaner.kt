package com.example.firmwaremanagement.storage

import android.content.Context
import com.example.firmwaremanagement.model.Stage
import com.example.firmwaremanagement.utils.OtaPathProvider
import java.io.File

object FileCleaner {
    private const val TEMP_FILE = "firmware.zip.tmp"
    private const val FINAL_FILE = "firmware.zip"
    private const val TASK_STATE_FILE = "task_state.json"

    fun cleanupStage(context: Context, stage: Stage) {
        when (stage) {
            Stage.DOWNLOADING -> cleanTempFile(context)
            Stage.PREPARING -> cleanFinalFile(context)
            Stage.APPLYING -> cleanFinalFile(context)
            else -> {}
        }
    }

    fun cleanAll(context: Context) {
        cleanupOtaFiles(context)
        deleteFile(context, TASK_STATE_FILE)
    }

    fun cleanupOtaFiles(context: Context) {
        val dir = OtaPathProvider.getBaseDir(context)
        deleteDir(dir)
        OtaPathProvider.clearCache()
    }

    fun cleanTempFile(context: Context) {
        deleteFile(context, TEMP_FILE)
    }

    fun cleanFinalFile(context: Context) {
        deleteFile(context, FINAL_FILE)
    }

    private fun deleteFile(context: Context, fileName: String): Boolean {
        val file = File(OtaPathProvider.getBaseDir(context), fileName)
        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }

    private fun deleteDir(dir: File): Boolean {
        if (!dir.exists()) {
            return true
        }
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { child ->
                deleteDir(child)
            }
        }
        return dir.delete()
    }
}
