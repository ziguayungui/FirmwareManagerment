package com.example.firmwaremanagement.utils

import android.content.Context
import android.util.Log
import java.io.File

/**
 * OTA 路径提供工具类。
 *
 * 自动检测运行时权限环境，选择合适的下载目录：
 * - 有 system 权限时 → 使用 /data/ota_package/（生产环境，update_engine 可读）
 * - 无 system 权限时 → 使用应用私有目录（开发调试环境）
 */
object OtaPathProvider {

    private const val TAG = "OtaPathProvider"
    private const val SYSTEM_OTA_DIR = "/data/ota_package"
    private const val FIRMWARE_ZIP = "firmware.zip"
    private const val FIRMWARE_ZIP_TMP = "firmware.zip.tmp"
    private const val MIN_FIRMWARE_SIZE = 1024 * 1024L // 1MB

    @Volatile
    private var cachedBaseDir: File? = null

    @Volatile
    private var cachedIsSystem: Boolean? = null

    /**
     * 是否运行在 system 权限环境下（/data/ota_package/ 可写）
     */
    fun isSystemEnvironment(): Boolean {
        cachedIsSystem?.let { return it }
        val result = try {
            val dir = File(SYSTEM_OTA_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir.exists() && dir.canWrite()
        } catch (e: Exception) {
            false
        }
        cachedIsSystem = result
        return result
    }

    /**
     * 获取 OTA 基础目录
     */
    fun getBaseDir(context: Context): File {
        cachedBaseDir?.let { return it }

        val dir = if (isSystemEnvironment()) {
            Log.d(TAG, "System environment detected, using $SYSTEM_OTA_DIR")
            File(SYSTEM_OTA_DIR)
        } else {
            val appDir = File(context.filesDir, "ota_package")
            Log.d(TAG, "Non-system environment, using app private dir: ${appDir.absolutePath}")
            appDir
        }

        if (!dir.exists()) {
            dir.mkdirs()
        }
        cachedBaseDir = dir
        return dir
    }

    /**
     * 获取固件 ZIP 目标文件
     */
    fun getTargetFile(context: Context): File {
        return File(getBaseDir(context), FIRMWARE_ZIP)
    }

    /**
     * 获取固件 ZIP 临时文件（下载中）
     */
    fun getTempFile(context: Context): File {
        return File(getBaseDir(context), FIRMWARE_ZIP_TMP)
    }

    /**
     * 清除缓存（例如在运行时权限变化后调用）
     */
    fun clearCache() {
        cachedBaseDir = null
        cachedIsSystem = null
    }

    /**
     * 校验下载的文件是否有效（文件存在且大小 ≥ 1MB）
     */
    fun validateDownloadedFile(context: Context): Boolean {
        val file = getTargetFile(context)
        if (!file.exists()) {
            Log.w(TAG, "Target file does not exist: ${file.absolutePath}")
            return false
        }
        if (file.length() < MIN_FIRMWARE_SIZE) {
            Log.w(TAG, "Target file too small: ${file.length()} bytes")
            file.delete()
            return false
        }
        return true
    }
}
