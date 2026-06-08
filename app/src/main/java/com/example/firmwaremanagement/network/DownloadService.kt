package com.example.firmwaremanagement.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.firmwaremanagement.MainActivity
import com.example.firmwaremanagement.R
import com.example.firmwaremanagement.model.Stage
import com.example.firmwaremanagement.model.TaskState
import com.example.firmwaremanagement.storage.TaskStateManager
import com.example.firmwaremanagement.utils.MD5Utils
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1
        private const val PROGRESS_SAVE_THRESHOLD = 1024 * 1024L // 1MB
        private const val NO_DATA_TIMEOUT_MS = 30000L // 30秒无数据超时

        const val ACTION_START_DOWNLOAD = "com.example.firmwaremanagement.ACTION_START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.example.firmwaremanagement.ACTION_PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.example.firmwaremanagement.ACTION_RESUME_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.firmwaremanagement.ACTION_CANCEL_DOWNLOAD"
        
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TARGET_PATH = "extra_target_path"
        const val EXTRA_EXPECTED_MD5 = "extra_expected_md5"

        // 广播 Action
        const val ACTION_DOWNLOAD_ERROR = "com.example.firmwaremanagement.ACTION_DOWNLOAD_ERROR"
        const val ACTION_DOWNLOAD_COMPLETE = "com.example.firmwaremanagement.ACTION_DOWNLOAD_COMPLETE"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
    }

    inner class DownloadBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    private val binder = DownloadBinder()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private var isDownloading = AtomicBoolean(false)
    private var isPaused = AtomicBoolean(false)
    private var isCancelled = AtomicBoolean(false)

    private var currentUrl: String? = null
    private var targetPath: String? = null
    private var expectedMD5: String? = null
    private var tempFile: File? = null
    private var totalBytes: Long = 0L
    private var downloadedBytes: Long = 0L
    private var lastSaveBytes: Long = 0L
    private var downloadStartTime: Long = 0L

    private var response: Response? = null
    private var inputStream: InputStream? = null
    private var lastDataReceivedTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val targetPath = intent.getStringExtra(EXTRA_TARGET_PATH)
                val expectedMD5 = intent.getStringExtra(EXTRA_EXPECTED_MD5)
                if (url != null && targetPath != null && expectedMD5 != null) {
                    startDownload(url, targetPath, expectedMD5)
                }
            }
            ACTION_PAUSE_DOWNLOAD -> pauseDownload()
            ACTION_RESUME_DOWNLOAD -> resumeDownload()
            ACTION_CANCEL_DOWNLOAD -> cancelDownload()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelDownload()
    }

    fun startDownload(url: String, targetPath: String, expectedMD5: String) {
        Log.d(TAG, "startDownload: url=$url, targetPath=$targetPath, expectedMD5=$expectedMD5")
        if (isDownloading.get()) {
            Log.w(TAG, "startDownload: already downloading, ignoring")
            return
        }

        this.currentUrl = url
        this.targetPath = targetPath
        this.expectedMD5 = expectedMD5
        this.downloadedBytes = 0L
        this.lastSaveBytes = 0L
        this.isPaused.set(false)
        this.isCancelled.set(false)
        this.downloadStartTime = System.currentTimeMillis()

        val file = File(targetPath)
        this.tempFile = File(targetPath + ".tmp")
        tempFile?.parentFile?.mkdirs()
        Log.d(TAG, "startDownload: tempFile=${tempFile?.absolutePath}")

        saveTaskState(Stage.DOWNLOADING)

        Thread {
            Log.d(TAG, "startDownload: starting download thread")
            isDownloading.set(true)
            startForeground(NOTIFICATION_ID, createNotification(0, 0, 0, 0))
            executeDownload(false)
            isDownloading.set(false)
            Log.d(TAG, "startDownload: download thread finished, isDownloading=false")
        }.start()
    }

    fun pauseDownload() {
        Log.d(TAG, "pauseDownload: pausing download")
        isPaused.set(true)
        saveTaskState(Stage.IDLE)
    }

    fun resumeDownload() {
        Log.d(TAG, "resumeDownload: resuming download, isPaused=${isPaused.get()}")
        if (!isPaused.get() || currentUrl == null || targetPath == null || expectedMD5 == null) {
            Log.w(TAG, "resumeDownload: conditions not met, ignoring")
            return
        }

        isPaused.set(false)
        saveTaskState(Stage.DOWNLOADING)

        Thread {
            Log.d(TAG, "resumeDownload: starting resume thread")
            executeDownload(true)
        }.start()
    }

    fun cancelDownload() {
        Log.d(TAG, "cancelDownload: cancelling download")
        isCancelled.set(true)
        isPaused.set(false)

        try {
            Log.d(TAG, "cancelDownload: closing streams")
            inputStream?.close()
            response?.close()
        } catch (_: Exception) {
            Log.e(TAG, "cancelDownload: error closing streams", )
        }

        tempFile?.let {
            Log.d(TAG, "cancelDownload: deleting temp file ${it.absolutePath}")
            it.delete()
        }
        TaskStateManager.clearTaskState(applicationContext)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun executeDownload(isResume: Boolean) {
        Log.d(TAG, "executeDownload: start, isResume=$isResume, downloadedBytes=$downloadedBytes")
        lastDataReceivedTime = System.currentTimeMillis()
        
        try {
            val requestBuilder = Request.Builder().url(currentUrl!!)
            Log.d(TAG, "executeDownload: requesting URL: ${currentUrl}")

            if (isResume && downloadedBytes > 0) {
                requestBuilder.addHeader("Range", "bytes=$downloadedBytes-")
                Log.d(TAG, "executeDownload: resume from byte $downloadedBytes")
            }

            Log.d(TAG, "executeDownload: executing HTTP request...")
            response = client.newCall(requestBuilder.build()).execute()
            Log.d(TAG, "executeDownload: response received, code=${response!!.code}, isSuccessful=${response!!.isSuccessful}")

            if (!response!!.isSuccessful && response!!.code != 206) {
                Log.e(TAG, "executeDownload: HTTP request failed, code=${response!!.code}")
                onDownloadError("Download failed: ${response!!.code}")
                return
            }

            // 检查内容类型，防止下载HTML错误页面
            val contentType = response!!.body?.contentType()?.toString() ?: ""
            Log.d(TAG, "executeDownload: contentType=$contentType")
            if (contentType.contains("text/html", ignoreCase = true)) {
                Log.e(TAG, "executeDownload: server returned HTML, not a valid file")
                onDownloadError("Server returned HTML error page (${response!!.code})")
                return
            }

            if (!isResume) {
                totalBytes = response!!.body?.contentLength() ?: 0L
                Log.d(TAG, "executeDownload: totalBytes=$totalBytes (from Content-Length)")
            } else {
                val contentRange = response!!.header("Content-Range")
                Log.d(TAG, "executeDownload: Content-Range header: $contentRange")
                if (contentRange != null) {
                    val totalPart = contentRange.substringAfter("/")
                    if (totalPart != "*") {
                        totalBytes = totalPart.toLongOrNull() ?: totalBytes
                        Log.d(TAG, "executeDownload: totalBytes=$totalBytes (from Content-Range)")
                    }
                }
            }

            inputStream = response!!.body?.byteStream()
            Log.d(TAG, "executeDownload: inputStream obtained")

            if (inputStream == null) {
                Log.e(TAG, "executeDownload: inputStream is null")
                onDownloadError("Failed to get input stream")
                return
            }

            val buffer = ByteArray(8192)
            var bytesRead: Int = 0
            var lastProgressUpdate = 0L
            var hasStartedReceivingData = false
            var totalReadBytes = 0L

            Log.d(TAG, "executeDownload: starting to read data, tempFile=${tempFile?.absolutePath}")
            FileOutputStream(tempFile, isResume).use { outputStream ->
                while (true) {
                    // 检查无数据超时
                    val now = System.currentTimeMillis()
                    if (hasStartedReceivingData && now - lastDataReceivedTime > NO_DATA_TIMEOUT_MS) {
                        Log.e(TAG, "executeDownload: timeout, no data for ${NO_DATA_TIMEOUT_MS / 1000}s, lastDataReceivedTime=$lastDataReceivedTime")
                        onDownloadError("Download timeout: no data received for ${NO_DATA_TIMEOUT_MS / 1000} seconds")
                        return
                    }

                    // 检查是否被取消或暂停
                    if (isCancelled.get()) {
                        Log.d(TAG, "executeDownload: cancelled")
                        return
                    }

                    while (isPaused.get() && !isCancelled.get()) {
                        Thread.sleep(100)
                    }

                    if (isCancelled.get()) {
                        Log.d(TAG, "executeDownload: cancelled after pause")
                        return
                    }

                    // 非阻塞式读取数据，带超时检测
                    val available = try {
                        inputStream!!.available()
                    } catch (e: Exception) {
                        Log.e(TAG, "executeDownload: available() exception: ${e.message}")
                        0
                    }

                    if (available <= 0) {
                        // 没有可用数据，短暂等待后继续检测
                        Thread.sleep(100)
                        continue
                    }

                    bytesRead = inputStream!!.read(buffer)
                    if (bytesRead == -1) {
                        // 流结束
                        Log.d(TAG, "executeDownload: stream ended, totalReadBytes=$downloadedBytes")
                        break
                    }

                    hasStartedReceivingData = true
                    lastDataReceivedTime = System.currentTimeMillis()
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    totalReadBytes += bytesRead

                    if (downloadedBytes - lastSaveBytes >= PROGRESS_SAVE_THRESHOLD) {
                        saveTaskState(Stage.DOWNLOADING)
                        lastSaveBytes = downloadedBytes
                    }

                    if (now - lastProgressUpdate >= 500) {
                        val speed = calculateSpeed()
                        updateNotification(downloadedBytes, totalBytes, speed)
                        lastProgressUpdate = now
                        Log.d(TAG, "executeDownload: progress $downloadedBytes / $totalBytes, speed=$speed B/s")
                    }
                }
            }

            Log.d(TAG, "executeDownload: closing streams, totalReadBytes=$downloadedBytes")
            inputStream?.close()
            response?.close()

            if (isCancelled.get()) {
                Log.d(TAG, "executeDownload: cancelled, skipping verifyAndComplete")
                return
            }

            Log.d(TAG, "executeDownload: calling verifyAndComplete")
            verifyAndComplete()

        } catch (e: Exception) {
            Log.e(TAG, "executeDownload: exception: ${e.message}", e)
            if (!isCancelled.get()) {
                onDownloadError(e.message ?: "Unknown error")
            }
        }
    }

    private fun verifyAndComplete() {
        Log.d(TAG, "verifyAndComplete: start")
        val file = tempFile ?: run {
            Log.e(TAG, "verifyAndComplete: tempFile is null")
            return
        }
        val target = targetPath ?: run {
            Log.e(TAG, "verifyAndComplete: targetPath is null")
            return
        }
        val md5 = expectedMD5 ?: run {
            Log.e(TAG, "verifyAndComplete: expectedMD5 is null")
            return
        }

        Log.d(TAG, "verifyAndComplete: file=${file.absolutePath}, length=${file.length()}, target=$target, expectedMD5=$md5")

        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "verifyAndComplete: file is empty or doesn't exist")
            onDownloadError("Downloaded file is empty")
            return
        }

        // 检查文件大小，固件文件通常大于1MB
        val minFirmwareSize = 1024 * 1024L // 1MB
        if (file.length() < minFirmwareSize) {
            Log.e(TAG, "verifyAndComplete: file too small (${file.length()} < $minFirmwareSize)")
            file.delete()
            onDownloadError("Downloaded file is too small (${file.length()} bytes), likely invalid")
            return
        }

        Log.d(TAG, "verifyAndComplete: calculating MD5...")
        val calculatedMD5 = MD5Utils.calculateFileMD5(file)
        Log.d(TAG, "verifyAndComplete: calculatedMD5=$calculatedMD5")
        
        if (!calculatedMD5.equals(md5, ignoreCase = true)) {
            Log.e(TAG, "verifyAndComplete: MD5 mismatch, expected=$md5, calculated=$calculatedMD5")
            file.delete()
            onDownloadError("MD5 verification failed")
            return
        }
        
        Log.d(TAG, "verifyAndComplete: MD5 verified successfully")

        val targetFile = File(target)
        if (targetFile.exists()) {
            Log.d(TAG, "verifyAndComplete: deleting existing target file")
            targetFile.delete()
        }

        Log.d(TAG, "verifyAndComplete: renaming temp file to target")
        if (file.renameTo(targetFile)) {
            Log.d(TAG, "verifyAndComplete: rename successful, saving state")
            saveTaskState(Stage.DOWNLOADED)
            updateNotificationComplete()
            
            // 发送完成广播，通知 UI
            val completeIntent = Intent(ACTION_DOWNLOAD_COMPLETE).apply {
                setPackage(packageName)
            }
            sendBroadcast(completeIntent)
            
            Log.d(TAG, "verifyAndComplete: download completed successfully!")
        } else {
            Log.e(TAG, "verifyAndComplete: rename failed")
            onDownloadError("Failed to rename temp file")
        }
    }

    private fun onDownloadError(errorMsg: String) {
        Log.e(TAG, "onDownloadError: $errorMsg")
        tempFile?.let {
            Log.d(TAG, "onDownloadError: deleting temp file ${it.absolutePath}")
            it.delete()
        }
        TaskStateManager.setError(applicationContext, errorMsg)
        updateNotificationError(errorMsg)
        
        // 发送错误广播，通知 UI
        val errorIntent = Intent(ACTION_DOWNLOAD_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, errorMsg)
            setPackage(packageName)
        }
        sendBroadcast(errorIntent)
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun calculateSpeed(): Long {
        val elapsed = System.currentTimeMillis() - downloadStartTime
        return if (elapsed > 0) (downloadedBytes * 1000 / elapsed) else 0
    }

    private fun saveTaskState(stage: Stage) {
        val state = TaskState(
            taskId = "current_task",
            stage = stage,
            url = currentUrl ?: "",
            targetFile = targetPath ?: "",
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            md5Expected = expectedMD5 ?: "",
            headers = emptyArray(),
            pendingVersion = "",
            errorMsg = ""
        )
        TaskStateManager.saveTaskState(applicationContext, state)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "下载服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "固件下载进度通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        downloaded: Long,
        total: Long,
        speed: Long,
        progress: Int
    ): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeAction = if (isPaused.get()) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                "继续",
                createActionIntent(ACTION_RESUME_DOWNLOAD)
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                "暂停",
                createActionIntent(ACTION_PAUSE_DOWNLOAD)
            ).build()
        }

        val cancelAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_delete,
            "取消",
            createActionIntent(ACTION_CANCEL_DOWNLOAD)
        ).build()

        val speedStr = formatSpeed(speed)
        val sizeStr = formatSize(downloaded, total)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在下载固件")
            .setContentText("$sizeStr - $speedStr/s")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, total == 0L)
            .addAction(pauseResumeAction)
            .addAction(cancelAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(downloaded: Long, total: Long, speed: Long) {
        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        val notification = createNotification(downloaded, total, speed, progress)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationComplete() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("下载完成")
            .setContentText("固件已下载完成")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun updateNotificationError(errorMsg: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("下载失败")
            .setContentText(errorMsg)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createActionIntent(action: String): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatSpeed(speed: Long): String {
        return when {
            speed >= 1024 * 1024 -> "%.1f MB".format(speed / (1024.0 * 1024.0))
            speed >= 1024 -> "%.1f KB".format(speed / 1024.0)
            else -> "$speed B"
        }
    }

    private fun formatSize(downloaded: Long, total: Long): String {
        val downloadedStr = when {
            downloaded >= 1024 * 1024 -> "%.1f MB".format(downloaded / (1024.0 * 1024.0))
            downloaded >= 1024 -> "%.1f KB".format(downloaded / 1024.0)
            else -> "$downloaded B"
        }
        val totalStr = when {
            total >= 1024 * 1024 -> "%.1f MB".format(total / (1024.0 * 1024.0))
            total >= 1024 -> "%.1f KB".format(total / 1024.0)
            else -> if (total > 0) "$total B" else "--"
        }
        return "$downloadedStr / $totalStr"
    }
}
