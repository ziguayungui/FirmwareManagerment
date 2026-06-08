package com.example.firmwaremanagement.storage

import android.content.Context
import com.example.firmwaremanagement.model.Stage
import java.io.File

object FileCleaner {
    private const val OTA_PACKAGE_DIR = "/data/ota_package/"
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
        deleteFile(getFilePath(TASK_STATE_FILE))
    }

    fun cleanupOtaFiles(context: Context) {
        val dir = File(OTA_PACKAGE_DIR)
        deleteDir(dir)
    }

    fun cleanTempFile(context: Context) {
        deleteFile(getFilePath(TEMP_FILE))
    }

    fun cleanFinalFile(context: Context) {
        deleteFile(getFilePath(FINAL_FILE))
    }

    private fun getFilePath(fileName: String): String {
        return OTA_PACKAGE_DIR + fileName
    }

    private fun deleteFile(path: String): Boolean {
        val file = File(path)
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
