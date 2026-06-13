package com.example.firmwaremanagement

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.firmwaremanagement.engine.UpdateEngineCallbackAdapter
import com.example.firmwaremanagement.engine.UpdateEngineWrapper
import com.example.firmwaremanagement.model.Stage
import com.example.firmwaremanagement.model.UpdateInfo
import com.example.firmwaremanagement.network.DownloadService
import com.example.firmwaremanagement.network.UpdateChecker
import com.example.firmwaremanagement.network.UpdateCheckResult
import com.example.firmwaremanagement.scanner.ScanActivity
import com.example.firmwaremanagement.storage.PrefsManager
import com.example.firmwaremanagement.storage.TaskStateManager
import com.example.firmwaremanagement.ui.SettingsActivity
import com.example.firmwaremanagement.ui.theme.FirmwareManagementTheme
import com.example.firmwaremanagement.utils.OtaPathProvider
import com.example.firmwaremanagement.utils.ZipPayloadExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val TechBlue = Color(0xFF2196F3)
val TechBlueDark = Color(0xFF1976D2)
val DarkBackground = Color(0xFFFFFFFF)  // 白色背景
val CardBackground = Color(0xFFF5F5F5)  // 浅灰色卡片背景
val WhiteText = Color(0xFFFFFFFF)      // 白色文字（用于蓝色按钮上）
val GrayText = Color(0xFF000000)       // 黑色文字（用于白色背景上）

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var checkUpdateJob: Job? = null
    private var downloadService: DownloadService? = null
    private var serviceBound = false
    private var showRebootDialogFlag by mutableStateOf(false)
    private var stage by mutableStateOf(Stage.IDLE)
    private var errorMessage by mutableStateOf<String?>(null)
    private var pendingVersionForSlot by mutableStateOf("")
    private var pendingPayloadPath by mutableStateOf("")     // 记录 payload 路径，供轮询路径调用 setShouldSwitchSlotOnReboot
    private var showNoUpdateDialog by mutableStateOf(false)
    private var showNewUpdateDialog by mutableStateOf(false)
    private var pendingUpdateInfo by mutableStateOf<UpdateInfo?>(null)
    private var resumeRefreshKey by mutableStateOf(0)  // 用于在onResume时刷新UI
    private var downloadProgress by mutableFloatStateOf(0f)  // 下载进度 0.0~1.0
    private var applyProgress by mutableFloatStateOf(0f)     // 升级进度 0.0~1.0
    var applyStatusLabel by mutableStateOf("")           // 当前升级阶段描述（public for Composable access）

    // 下载事件广播接收器
    private val downloadEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadService.ACTION_DOWNLOAD_PROGRESS -> {
                    val downloaded = intent.getLongExtra(DownloadService.EXTRA_PROGRESS_BYTES, 0L)
                    val total = intent.getLongExtra(DownloadService.EXTRA_TOTAL_BYTES, 0L)
                    if (total > 0) {
                        downloadProgress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    }
                }
                DownloadService.ACTION_DOWNLOAD_ERROR -> {
                    val errorMsg = intent.getStringExtra(DownloadService.EXTRA_ERROR_MESSAGE) ?: "下载失败"
                    errorMessage = errorMsg
                    Log.w(TAG, "stage -> ERROR (download): $errorMsg")
                    stage = Stage.ERROR
                }
                DownloadService.ACTION_DOWNLOAD_COMPLETE -> {
                    Log.d(TAG, "stage -> DOWNLOADED")
                    stage = Stage.DOWNLOADED
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DownloadService.DownloadBinder
            downloadService = binder.getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrefsManager.init(this)
        setContent {
            FirmwareManagementTheme {
                MainScreen(
                    onNavigateToScan = { navigateToScan() },
                    onNavigateToSettings = { navigateToSettings() },
                    onCheckUpdate = { startUpdateCheck() },
                    onStartDownload = { info -> startDownload(info) },
                    onReboot = { reboot() },
                    onApplyPayload = { applyPayloadAfterDownload() },
                    checkUpdateJob = checkUpdateJob,
                    onJobCancelled = { checkUpdateJob?.cancel() },
                    stage = stage,
                    onStageChange = { newStage -> stage = newStage },
                    showRebootDialog = showRebootDialogFlag,
                    onDismissRebootDialog = { showRebootDialogFlag = false },
                    errorMessage = errorMessage,
                    showNoUpdateDialog = showNoUpdateDialog,
                    onDismissNoUpdate = { showNoUpdateDialog = false },
                    showNewUpdateDialog = showNewUpdateDialog,
                    onStartNewDownload = {
                        showNewUpdateDialog = false
                        pendingUpdateInfo?.let { startDownload(it) }
                    },
                    onDismissNewUpdate = { showNewUpdateDialog = false },
                    pendingUpdateInfo = pendingUpdateInfo,
                    resumeRefreshKey = resumeRefreshKey,
                    downloadProgress = downloadProgress,
                    applyProgress = applyProgress,
                    applyStatusLabel = applyStatusLabel
                )
            }
        }

        checkPendingUpdateOnStart()
    }

    // 启动时检测是否有已完成的升级等待重启
    private fun checkPendingUpdateOnStart() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (UpdateEngineWrapper.checkPendingUpdate()) {
                    Log.d(TAG, "onCreate: pending update detected, showing reboot prompt")
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "stage -> REBOOT_PENDING (checkPendingOnStart)")
                        stage = Stage.REBOOT_PENDING
                        showRebootDialogFlag = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: checkPendingUpdate failed: ${e.message}")
            }
        }
    }

    private fun navigateToScan() {
        val intent = Intent(this, ScanActivity::class.java)
        startActivity(intent)
    }

    private fun navigateToSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun startUpdateCheck() {
        checkUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            val serverUrl = PrefsManager.getServerBaseUrl()
            if (serverUrl.isNullOrBlank()) {
                errorMessage = "请先配置服务器地址"
                Log.w(TAG, "stage -> ERROR: no server URL")
                stage = Stage.ERROR
                return@launch
            }

            Log.d(TAG, "stage -> CHECK_PREPARE")
            stage = Stage.CHECK_PREPARE
            delay(500)

            val result = withContext(Dispatchers.IO) {
                UpdateChecker.checkForUpdateWithVersion(serverUrl)
            }

            when (result) {
                UpdateCheckResult.NoUpdate -> {
                    showNoUpdateDialog = true
                    Log.d(TAG, "stage -> IDLE (no update)")
                    stage = Stage.IDLE
                }
                is UpdateCheckResult.NewUpdate -> {
                    pendingUpdateInfo = result.info
                    showNewUpdateDialog = true
                    Log.d(TAG, "stage -> IDLE (new update found)")
                    stage = Stage.IDLE
                }
                is UpdateCheckResult.Error -> {
                    errorMessage = result.message
                    Log.w(TAG, "stage -> ERROR (check): ${result.message}")
                    stage = Stage.ERROR
                }
            }
        }
    }

    private fun startDownload(info: UpdateInfo) {
        Log.d(TAG, "startDownload: version=${info.version}, url=${info.filename}")
        val targetFile = OtaPathProvider.getTargetFile(this).absolutePath
        val tempFile = OtaPathProvider.getTempFile(this).absolutePath
        val downloadUrl = "${PrefsManager.getServerBaseUrl()}/${info.filename}"
        
        pendingVersionForSlot = info.version
        Log.d(TAG, "stage -> DOWNLOADING")
        stage = Stage.DOWNLOADING
        
        // 通过 Intent 启动 DownloadService，传递下载参数
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, downloadUrl)
            putExtra(DownloadService.EXTRA_TARGET_PATH, targetFile)
            putExtra(DownloadService.EXTRA_EXPECTED_MD5, info.md5)
        }
        startForegroundService(intent)
        
        // 绑定 Service 以获取通信接口
        if (!serviceBound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun reboot() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.reboot(null)
        } catch (e: Exception) {
            Runtime.getRuntime().exec("reboot")
        }
    }

    private fun applyPayloadAfterDownload() {
        CoroutineScope(Dispatchers.Main).launch {
            val targetFile = OtaPathProvider.getTargetFile(this@MainActivity).absolutePath
            
            try {
                Log.d(TAG, "stage -> PREPARING")
                stage = Stage.PREPARING
                applyProgress = 0f
                
                // 从 ZIP 中提取 payload.bin
                val payloadInfo = withContext(Dispatchers.IO) {
                    ZipPayloadExtractor.extract(this@MainActivity, targetFile)
                }
                pendingPayloadPath = payloadInfo.payloadFile  // 记录路径，供轮询路径调用 setShouldSwitchSlotOnReboot
                
                // 提取完成后删除原 ZIP 文件，释放空间
                withContext(Dispatchers.IO) {
                    val zipFile = java.io.File(targetFile)
                    if (zipFile.exists()) {
                        zipFile.delete()
                        Log.d(TAG, "applyPayloadAfterDownload: deleted original zip")
                    }
                }
                
                Log.d(TAG, "stage -> APPLYING")
                stage = Stage.APPLYING
                applyStatusLabel = ""

                // ═══════════════════════════════════════════════════════
                // [SEQ] 关键序列：resetStatus → bind → applyPayload → polling
                // ═══════════════════════════════════════════════════════
                Log.d(TAG, "========== [SEQ] applyPayloadAfterDownload: BEGIN upgrade sequence  thread=${Thread.currentThread().name} ==========")

                // 在 bind 之前重置引擎状态，防止上一次升级的残留状态（如 UPDATED_NEED_REBOOT）
                // 在 bind 回调注册时立即触发 onPayloadApplicationComplete，导致过早调用 setShouldSwitchSlotOnReboot
                Log.d(TAG, "[SEQ] >>> about to call resetStatus()")
                UpdateEngineWrapper.resetStatus()
                Log.d(TAG, "[SEQ] <<< resetStatus() returned")

                Log.d(TAG, "[SEQ] >>> about to call UpdateEngineWrapper.bind()")
                val success = UpdateEngineWrapper.bind(object : UpdateEngineCallbackAdapter() {
                    override fun onStatusUpdate(status: Int, percent: Float) {
                        CoroutineScope(Dispatchers.Main).launch {
                            // AIDL 回调的 percentage 是 0.0~1.0 的浮点数
                            // 兼容某些设备可能返回 0~100 的情况
                            val normalized = if (percent > 1f) percent / 100f else percent
                            applyProgress = normalized.coerceIn(0f, 1f)
                            applyStatusLabel = UpdateEngineWrapper.describeStatus(status)
                            Log.d(TAG, "applyPayloadAfterDownload: onStatusUpdate status=$status ($applyStatusLabel), progress=$applyProgress")
                        }
                    }

                    override fun onPayloadApplicationComplete(errorCode: Int) {
                        Log.d(TAG, "[SEQ] onPayloadApplicationComplete CALLBACK ENTER  errorCode=$errorCode  thread=${Thread.currentThread().name}")
                        CoroutineScope(Dispatchers.Main).launch {
                            Log.d(TAG, "[SEQ] onPayloadApplicationComplete CoroutineScope.launch EXECUTING  thread=${Thread.currentThread().name}")
                            if (errorCode == 0) {
                                // 防御性检查：仅在能确定引擎未处于终态时才拦截
                                // getStatus() 在某些设备上可能返回 -1（AIDL 方法不存在），此时信任回调
                                val engineStatus = UpdateEngineWrapper.getStatus()
                                Log.d(TAG, "[SEQ] onPayloadApplicationComplete: engineStatus=$engineStatus")
                                if (engineStatus >= 0 && !UpdateEngineWrapper.isTerminal(engineStatus)) {
                                    Log.w(TAG, "[SEQ] onPayloadApplicationComplete IGNORED — engine status=$engineStatus (not terminal, likely stale callback from previous update)")
                                    return@launch
                                }
                                Log.d(TAG, "[SEQ] onPayloadApplicationComplete: proceeding to setShouldSwitchSlotOnReboot (status=${if (engineStatus >= 0) engineStatus else "unknown"})")
                                // 通知 update_engine/bootloader：重启后切换到新分区
                                val switchOk = UpdateEngineWrapper.setShouldSwitchSlotOnReboot(payloadInfo.payloadFile)
                                Log.d(TAG, "applyPayloadAfterDownload: setShouldSwitchSlotOnReboot result=$switchOk")

                                PrefsManager.setPendingSlotVersion(pendingVersionForSlot)
                                Log.d(TAG, "stage -> REBOOT_PENDING (callback errorCode=0)")
                                stage = Stage.REBOOT_PENDING
                                showRebootDialogFlag = true
                            } else {
                                Log.w(TAG, "stage -> ERROR (callback errorCode=$errorCode)")
                                stage = Stage.ERROR
                                errorMessage = "升级失败，错误码: $errorCode"
                            }

                            // 清理提取的 payload 文件（在 setShouldSwitchSlotOnReboot 之后）
                            ZipPayloadExtractor.cleanup(this@MainActivity)
                        }
                    }
                })
                Log.d(TAG, "[SEQ] <<< UpdateEngineWrapper.bind() returned  success=$success")

                if (success) {
                    Log.d(TAG, "applyPayloadAfterDownload: UpdateEngine bound")

                    Log.d(TAG, "[SEQ] >>> about to call applyPayload()")
                    UpdateEngineWrapper.applyPayload(
                        "file://${payloadInfo.payloadFile}",
                        0,
                        payloadInfo.size,
                        payloadInfo.headers
                    )
                    Log.d(TAG, "[SEQ] <<< applyPayload() returned")
                    Log.d(TAG, "applyPayloadAfterDownload: applyPayload called, starting polling")

                    // 轮询检测更新进度（防止回调没收到导致卡死）
                    Log.d(TAG, "[SEQ] >>> about to start polling")
                    startApplyPolling()
                    Log.d(TAG, "[SEQ] <<< startApplyPolling() launched")
                } else {
                    Log.e(TAG, "applyPayloadAfterDownload: failed to bind UpdateEngine")
                    ZipPayloadExtractor.cleanup(this@MainActivity)
                    Log.w(TAG, "stage -> ERROR (bind failed)")
                    stage = Stage.ERROR
                    errorMessage = "设备不支持OTA升级"
                }
                Log.d(TAG, "========== [SEQ] applyPayloadAfterDownload: END upgrade sequence ==========")
            } catch (e: Exception) {
                Log.e(TAG, "applyPayloadAfterDownload: exception: ${e.message}", e)
                ZipPayloadExtractor.cleanup(this@MainActivity)
                Log.w(TAG, "stage -> ERROR (exception: ${e.message})")
                stage = Stage.ERROR
                errorMessage = "准备升级失败: ${e.message}"
            }
        }
    }

    /**
     * 轮询 update_engine 进度，防止 Proxy 回调未注册导致界面卡死
     */
    private fun startApplyPolling() {
        Log.d(TAG, "startApplyPolling: [POLL-V2] begin polling (no getLastResultCode, callback-driven)")
        CoroutineScope(Dispatchers.Main).launch {
            var elapsed = 0L
            val maxPollTime = 600_000L // 绝对最大轮询10分钟（回调是主路径，超时仅作兜底）
            val pollInterval = 3_000L   // 每3秒轮询一次
            var lastProgress = -1f
            var stalledPolls = 0
            val maxStalledPolls = 10   // 连续30秒进度无变化即判定为卡死
            var loopCount = 0
            Log.d(TAG, "startApplyPolling: entering while loop, maxPollTime=$maxPollTime, pollInterval=$pollInterval")

            while (elapsed < maxPollTime) {
                loopCount++
                Log.d(TAG, "startApplyPolling: [LOOP #$loopCount] before delay, elapsed=$elapsed")
                delay(pollInterval)
                elapsed += pollInterval
                Log.d(TAG, "startApplyPolling: [LOOP #$loopCount] after delay, elapsed=$elapsed")

                // 如果已经通过回调切换了状态，停止轮询
                if (stage != Stage.APPLYING) {
                    Log.d(TAG, "startApplyPolling: stage changed to $stage, stopping polling")
                    return@launch
                }

                // 轮询进度（仅用于 UI 刷新；真正的完成/失败由 Binder 回调 onPayloadApplicationComplete 负责）
                val progress = UpdateEngineWrapper.getProgress()
                if (progress >= 0f) {
                    applyProgress = progress.coerceIn(0f, 1f)
                    Log.d(TAG, "startApplyPolling: polled progress=$progress")
                    if (progress != lastProgress) {
                        stalledPolls = 0
                        lastProgress = progress
                    } else {
                        stalledPolls++
                        Log.d(TAG, "startApplyPolling: progress stalled, stalledPolls=$stalledPolls/$maxStalledPolls")
                    }
                }

                // 进度连续停滞超过阈值 → 判定为卡死，结束轮询
                if (stalledPolls >= maxStalledPolls) {
                    Log.w(TAG, "startApplyPolling: progress stalled for ${stalledPolls * pollInterval / 1000}s, breaking loop")
                    break
                }
            }

            Log.d(TAG, "startApplyPolling: EXITED while loop, elapsed=$elapsed, stage=$stage")
            // 超时仍未完成，默认显示重启提示
            if (stage == Stage.APPLYING) {
                Log.w(TAG, "startApplyPolling: timeout, assuming update completed")
                UpdateEngineWrapper.setShouldSwitchSlotOnReboot(pendingPayloadPath)
                ZipPayloadExtractor.cleanup(this@MainActivity)
                PrefsManager.setPendingSlotVersion(pendingVersionForSlot)
                Log.d(TAG, "stage -> REBOOT_PENDING (poll timeout)")
                stage = Stage.REBOOT_PENDING
                showRebootDialogFlag = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        // 注册下载事件广播接收器
        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_DOWNLOAD_PROGRESS)
            addAction(DownloadService.ACTION_DOWNLOAD_ERROR)
            addAction(DownloadService.ACTION_DOWNLOAD_COMPLETE)
        }
        registerReceiver(downloadEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        
        // 刷新UI以更新服务器地址显示
        resumeRefreshKey++
    }

    override fun onPause() {
        super.onPause()
        // 取消注册广播接收器
        try {
            unregisterReceiver(downloadEventReceiver)
        } catch (e: Exception) {
            // receiver might not be registered
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 退出时取消下载，不保留后台下载
        if (serviceBound) {
            try {
                downloadService?.cancelDownload()
            } catch (_: Exception) {}
            unbindService(serviceConnection)
            serviceBound = false
        }
        // 停止前台服务
        val stopIntent = Intent(this, DownloadService::class.java)
        stopService(stopIntent)
        // 清理任务状态
        TaskStateManager.clearTaskState(this)
        Log.d(TAG, "stage -> IDLE (onDestroy)")
        stage = Stage.IDLE
        errorMessage = null
        checkUpdateJob?.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onCheckUpdate: () -> Unit,
    onStartDownload: (UpdateInfo) -> Unit,
    onReboot: () -> Unit,
    onApplyPayload: () -> Unit,
    checkUpdateJob: Job?,
    onJobCancelled: () -> Unit,
    stage: Stage,
    onStageChange: (Stage) -> Unit,
    showRebootDialog: Boolean,
    onDismissRebootDialog: () -> Unit,
    errorMessage: String?,
    showNoUpdateDialog: Boolean,
    onDismissNoUpdate: () -> Unit,
    showNewUpdateDialog: Boolean,
    onStartNewDownload: () -> Unit,
    onDismissNewUpdate: () -> Unit,
    pendingUpdateInfo: UpdateInfo?,
    resumeRefreshKey: Int = 0,
    downloadProgress: Float = 0f,
    applyProgress: Float = 0f,
    applyStatusLabel: String = ""
) {
    val context = LocalContext.current
    var serverUrl by mutableStateOf(PrefsManager.getServerBaseUrl() ?: "")
    var currentVersion by mutableStateOf(PrefsManager.getCurrentVersion())

    // 监听resumeRefreshKey变化，当从设置页面返回时更新服务器地址
    LaunchedEffect(resumeRefreshKey) {
        serverUrl = PrefsManager.getServerBaseUrl() ?: ""
        currentVersion = PrefsManager.getCurrentVersion()
    }

    // 监听stage变化，当从设置页面返回时，stage变化会触发重组，从而更新serverUrl
    LaunchedEffect(stage) {
        if (stage == Stage.IDLE || stage == Stage.CHECK_PREPARE) {
            serverUrl = PrefsManager.getServerBaseUrl() ?: ""
            currentVersion = PrefsManager.getCurrentVersion()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("固件升级", color = WhiteText) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TechBlue
                ),
                navigationIcon = {
                    IconButton(onClick = { (context as ComponentActivity).finish() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "退出",
                            tint = WhiteText
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToScan) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "扫码配置",
                            tint = WhiteText
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = WhiteText
                        )
                    }
                }
            )
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (stage) {
                Stage.IDLE -> IdleScreen(
                    serverUrl = serverUrl,
                    currentVersion = currentVersion,
                    onEditServerUrl = onNavigateToSettings,
                    onCheckUpdate = onCheckUpdate
                )
                Stage.CHECK_PREPARE -> LoadingScreen("正在检查更新...")
                Stage.DOWNLOADING -> DownloadScreen(progress = downloadProgress)
                Stage.DOWNLOADED -> DownloadedScreen(onApplyPayload = { onApplyPayload() })
                Stage.PREPARING -> LoadingScreen("准备中...")
                Stage.APPLYING -> ApplyingScreen(progress = applyProgress, stageText = applyStatusLabel)
                Stage.REBOOT_PENDING -> RebootPendingScreen(
                    onReboot = onReboot,
                    onDismiss = { onStageChange(Stage.IDLE) }
                )
                Stage.ERROR -> ErrorScreen(
                    message = errorMessage ?: "未知错误",
                    onDismiss = { onStageChange(Stage.IDLE) }
                )
            }
        }
    }

    if (showNoUpdateDialog) {
        AlertDialog(
            onDismissRequest = { onDismissNoUpdate() },
            containerColor = Color.White,
            title = { Text("提示", color = Color.Black) },
            text = { Text("已是最新版本", color = Color.Black) },
            confirmButton = {
                TextButton(onClick = { onDismissNoUpdate() }) {
                    Text("确定", color = Color(0xFF2196F3))
                }
            }
        )
    }

    if (showNewUpdateDialog && pendingUpdateInfo != null) {
        AlertDialog(
            onDismissRequest = { onDismissNewUpdate() },
            containerColor = Color.White,
            title = { Text("发现新版本", color = Color.Black) },
            text = {
                Column {
                    Text("版本: ${pendingUpdateInfo?.version}", color = Color.Black)
                    Text("文件名: ${pendingUpdateInfo?.filename}", color = Color.Black)
                }
            },
            confirmButton = {
                TextButton(onClick = { onStartNewDownload() }) {
                    Text("立即更新", color = Color(0xFF2196F3))
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismissNewUpdate() }) {
                    Text("暂不更新", color = Color.Gray)
                }
            }
        )
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { onDismissRebootDialog() },
            containerColor = Color.White,
            title = { Text("重启确认", color = Color.Black) },
            text = { Text("系统已准备就绪，请立即重启设备以完成升级", color = Color.Black) },
            confirmButton = {
                TextButton(onClick = {
                    onDismissRebootDialog()
                    onReboot()
                }) {
                    Text("立即重启", color = Color(0xFF2196F3))
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismissRebootDialog() }) {
                    Text("稍后重启", color = Color.Gray)
                }
            }
        )
    }
}

@Composable
fun IdleScreen(
    serverUrl: String,
    currentVersion: String,
    onEditServerUrl: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        InfoRow(
            label = "当前服务器地址",
            value = if (serverUrl.isNotBlank()) serverUrl else "未配置",
            onClick = onEditServerUrl
        )

        Spacer(modifier = Modifier.height(16.dp))

        InfoRow(
            label = "当前版本",
            value = if (currentVersion.isNotBlank()) currentVersion else "未知"
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onCheckUpdate,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = TechBlue
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("检查更新", fontSize = 18.sp, color = WhiteText)
        }
        
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .background(CardBackground, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.Black,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            color = Color.Black,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun DownloadedScreen(onApplyPayload: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "下载完成",
            color = WhiteText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "固件已下载，点击下方按钮开始升级",
            color = WhiteText.copy(alpha = 0.7f),
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onApplyPayload,
            colors = ButtonDefaults.buttonColors(containerColor = TechBlue),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.width(200.dp).height(48.dp)
        ) {
            Text("立即更新", color = WhiteText, fontSize = 16.sp)
        }
    }
}

@Composable
fun LoadingScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = TechBlue)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            color = WhiteText,
            fontSize = 16.sp
        )
    }
}

@Composable
fun DownloadScreen(progress: Float) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "正在下载固件...",
            color = WhiteText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when {
                progress <= 0f -> "正在连接服务器..."
                progress < 0.9f -> "正在下载数据..."
                else -> "下载即将完成..."
            },
            color = TechBlue,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (progress > 0f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = TechBlue,
                trackColor = Color(0xFF3D3D3D)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${(progress * 100).toInt()}%",
                color = WhiteText.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = TechBlue,
                trackColor = Color(0xFF3D3D3D)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "${(progress * 100).toInt()}%",
            color = WhiteText,
            fontSize = 14.sp
        )
    }
}

@Composable
fun ApplyingScreen(progress: Float, stageText: String = "") {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "正在升级固件...",
            color = WhiteText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stageText.ifEmpty {
                when {
                    progress <= 0f -> "准备中，请稍候..."
                    progress < 1f -> "正在写入固件..."
                    else -> "写入完成，正在验证..."
                }
            },
            color = TechBlue,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = TechBlue,
            trackColor = Color(0xFF3D3D3D)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "${(progress * 100).toInt()}%",
            color = WhiteText,
            fontSize = 14.sp
        )
    }
}

@Composable
fun RebootPendingScreen(onReboot: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "系统已准备就绪，请重启设备",
            color = WhiteText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onReboot,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = TechBlue
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("立即重启", fontSize = 18.sp, color = WhiteText)
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(onClick = onDismiss) {
            Text("稍后重启", color = Color.Gray)
        }
    }
}

@Composable
fun ErrorScreen(
    message: String,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "错误",
            color = Color(0xFFFF5252),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            color = GrayText,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(
                containerColor = TechBlue
            )
        ) {
            Text("确定", color = WhiteText)
        }
    }
}
