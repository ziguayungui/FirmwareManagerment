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
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1
        private const val PROGRESS_SAVE_THRESHOLD = 1024 * 1024L // 1MB

        const val ACTION_START_DOWNLOAD = "com.example.firmwaremanagement.ACTION_START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.example.firmwaremanagement.ACTION_PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.example.firmwaremanagement.ACTION_RESUME_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.firmwaremanagement.ACTION_CANCEL_DOWNLOAD"
        
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TARGET_PATH = "extra_target_path"
        const val EXTRA_EXPECTED_MD5 = "extra_expected_md5"
    }

    inner class DownloadBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    private val binder = DownloadBinder()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
        if (isDownloading.get()) return

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

        saveTaskState(Stage.DOWNLOADING)

        Thread {
            isDownloading.set(true)
            startForeground(NOTIFICATION_ID, createNotification(0, 0, 0, 0))
            executeDownload(false)
            isDownloading.set(false)
        }.start()
    }

    fun pauseDownload() {
        isPaused.set(true)
        saveTaskState(Stage.IDLE)
    }

    fun resumeDownload() {
        if (!isPaused.get() || currentUrl == null || targetPath == null || expectedMD5 == null) return

        isPaused.set(false)
        saveTaskState(Stage.DOWNLOADING)

        Thread {
            executeDownload(true)
        }.start()
    }

    fun cancelDownload() {
        isCancelled.set(true)
        isPaused.set(false)

        try {
            inputStream?.close()
            response?.close()
        } catch (_: Exception) {
        }

        tempFile?.delete()
        TaskStateManager.clearTaskState(applicationContext)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun executeDownload(isResume: Boolean) {
        try {
            val requestBuilder = Request.Builder().url(currentUrl!!)

            if (isResume && downloadedBytes > 0) {
                requestBuilder.addHeader("Range", "bytes=$downloadedBytes-")
            }

            response = client.newCall(requestBuilder.build()).execute()

            if (!response!!.isSuccessful && response!!.code != 206) {
                onDownloadError("Download failed: ${response!!.code}")
                return
            }

            if (!isResume) {
                totalBytes = response!!.body?.contentLength() ?: 0L
            } else {
                val contentRange = response!!.header("Content-Range")
                if (contentRange != null) {
                    val totalPart = contentRange.substringAfter("/")
                    if (totalPart != "*") {
                        totalBytes = totalPart.toLongOrNull() ?: totalBytes
                    }
                }
            }

            inputStream = response!!.body?.byteStream()

            if (inputStream == null) {
                onDownloadError("Failed to get input stream")
                return
            }

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var lastProgressUpdate = 0L

            FileOutputStream(tempFile, isResume).use { outputStream ->
                while (inputStream!!.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled.get()) {
                        return
                    }

                    while (isPaused.get() && !isCancelled.get()) {
                        Thread.sleep(100)
                    }

                    if (isCancelled.get()) return

                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    if (downloadedBytes - lastSaveBytes >= PROGRESS_SAVE_THRESHOLD) {
                        saveTaskState(Stage.DOWNLOADING)
                        lastSaveBytes = downloadedBytes
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate >= 500) {
                        val speed = calculateSpeed()
                        updateNotification(downloadedBytes, totalBytes, speed)
                        lastProgressUpdate = now
                    }
                }
            }

            inputStream?.close()
            response?.close()

            if (isCancelled.get()) return

            verifyAndComplete()

        } catch (e: Exception) {
            if (!isCancelled.get()) {
                onDownloadError(e.message ?: "Unknown error")
            }
        }
    }

    private fun verifyAndComplete() {
        val file = tempFile ?: return
        val target = targetPath ?: return
        val md5 = expectedMD5 ?: return

        if (!file.exists() || file.length() == 0L) {
            onDownloadError("Downloaded file is empty")
            return
        }

        val calculatedMD5 = MD5Utils.calculateFileMD5(file)
        if (!calculatedMD5.equals(md5, ignoreCase = true)) {
            file.delete()
            onDownloadError("MD5 verification failed")
            return
        }

        val targetFile = File(target)
        if (targetFile.exists()) {
            targetFile.delete()
        }

        if (file.renameTo(targetFile)) {
            saveTaskState(Stage.DOWNLOADED)
            updateNotificationComplete()
        } else {
            onDownloadError("Failed to rename temp file")
        }
    }

    private fun onDownloadError(errorMsg: String) {
        tempFile?.delete()
        TaskStateManager.setError(applicationContext, errorMsg)
        updateNotificationError(errorMsg)
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
