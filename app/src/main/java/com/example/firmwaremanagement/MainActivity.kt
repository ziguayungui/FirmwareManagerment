package com.example.firmwaremanagement

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
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
import com.example.firmwaremanagement.model.TaskState
import com.example.firmwaremanagement.model.UpdateInfo
import com.example.firmwaremanagement.network.DownloadService
import com.example.firmwaremanagement.network.UpdateChecker
import com.example.firmwaremanagement.network.UpdateCheckResult
import com.example.firmwaremanagement.scanner.ScanActivity
import com.example.firmwaremanagement.storage.FileCleaner
import com.example.firmwaremanagement.storage.PrefsManager
import com.example.firmwaremanagement.storage.TaskStateManager
import com.example.firmwaremanagement.ui.SettingsActivity
import com.example.firmwaremanagement.ui.theme.FirmwareManagementTheme
import com.example.firmwaremanagement.utils.ZipPayloadExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

val TechBlue = Color(0xFF2196F3)
val DarkBackground = Color(0xFF121212)
val CardBackground = Color(0xFF1E1E1E)
val WhiteText = Color(0xFFFFFFFF)
val GrayText = Color(0xFFB0B0B0)

class MainActivity : ComponentActivity() {

    private var checkUpdateJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadService: DownloadService? = null
    private var serviceBound = false
    private var showRebootDialogFlag by mutableStateOf(false)
    private var stage by mutableStateOf(Stage.IDLE)
    private var errorMessage by mutableStateOf<String?>(null)
    private var showNoUpdateDialog by mutableStateOf(false)
    private var showNewUpdateDialog by mutableStateOf(false)
    private var pendingUpdateInfo by mutableStateOf<UpdateInfo?>(null)

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
                    pendingUpdateInfo = pendingUpdateInfo
                )
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
                stage = Stage.ERROR
                return@launch
            }

            stage = Stage.CHECK_PREPARE
            delay(500)

            val result = withContext(Dispatchers.IO) {
                UpdateChecker.checkForUpdateWithVersion(serverUrl)
            }

            when (result) {
                UpdateCheckResult.NoUpdate -> {
                    showNoUpdateDialog = true
                    stage = Stage.IDLE
                }
                is UpdateCheckResult.NewUpdate -> {
                    pendingUpdateInfo = result.info
                    showNewUpdateDialog = true
                    stage = Stage.IDLE
                }
                is UpdateCheckResult.Error -> {
                    errorMessage = result.message
                    stage = Stage.ERROR
                }
                else -> {
                    errorMessage = "未知错误"
                    stage = Stage.ERROR
                }
            }
        }
    }

    private fun startDownload(info: UpdateInfo) {
        val targetFile = "/data/ota_package/firmware.zip"
        val tempFile = "/data/ota_package/firmware.zip.tmp"
        val downloadUrl = "${PrefsManager.getServerBaseUrl()}/${info.filename}"
        
        val taskState = TaskState(
            taskId = UUID.randomUUID().toString(),
            stage = Stage.DOWNLOADING,
            url = downloadUrl,
            targetFile = targetFile,
            downloadedBytes = 0,
            totalBytes = 0,
            md5Expected = info.md5,
            headers = emptyArray(),
            pendingVersion = info.version,
            errorMsg = ""
        )
        TaskStateManager.saveTaskState(this, taskState)
        stage = Stage.DOWNLOADING
        
        val intent = Intent(this, DownloadService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        downloadJob = CoroutineScope(Dispatchers.Main).launch {
            delay(100)
            downloadService?.startDownload(downloadUrl, tempFile, info.md5)
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
            val targetFile = "/data/ota_package/firmware.zip"
            
            try {
                TaskStateManager.updateStage(this@MainActivity, Stage.PREPARING)
                stage = Stage.PREPARING
                
                val payloadInfo = withContext(Dispatchers.IO) {
                    ZipPayloadExtractor.extract(targetFile)
                }
                
                TaskStateManager.updateStage(this@MainActivity, Stage.APPLYING)
                stage = Stage.APPLYING
                
                val success = UpdateEngineWrapper.bind(object : UpdateEngineCallbackAdapter() {
                    override fun onPayloadApplicationComplete(errorCode: Int) {
                        CoroutineScope(Dispatchers.Main).launch {
                            if (errorCode == 0) {
                                PrefsManager.setPendingSlotVersion(
                                    TaskStateManager.loadTaskState(this@MainActivity)?.pendingVersion ?: ""
                                )
                                TaskStateManager.updateStage(this@MainActivity, Stage.REBOOT_PENDING)
                                FileCleaner.cleanFinalFile(this@MainActivity)
                                stage = Stage.REBOOT_PENDING
                                showRebootDialogFlag = true
                            } else {
                                FileCleaner.cleanFinalFile(this@MainActivity)
                                TaskStateManager.setError(this@MainActivity, "升级失败，错误码: $errorCode")
                                stage = Stage.ERROR
                                errorMessage = "升级失败，错误码: $errorCode"
                            }
                        }
                    }
                })
                
                if (success) {
                    UpdateEngineWrapper.applyPayload(
                        "file://$targetFile",
                        payloadInfo.offset,
                        payloadInfo.size,
                        payloadInfo.headers
                    )
                }
            } catch (e: Exception) {
                FileCleaner.cleanFinalFile(this@MainActivity)
                TaskStateManager.setError(this@MainActivity, "准备升级失败: ${e.message}")
                stage = Stage.ERROR
                errorMessage = "准备升级失败: ${e.message}"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val state = TaskStateManager.loadTaskState(this)
        if (state != null) {
            stage = state.stage
            if (state.stage == Stage.ERROR) {
                errorMessage = state.errorMsg
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        checkUpdateJob?.cancel()
        downloadJob?.cancel()
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
    pendingUpdateInfo: UpdateInfo?
) {
    val context = LocalContext.current
    var serverUrl by remember { mutableStateOf(PrefsManager.getServerBaseUrl() ?: "") }
    var currentVersion by remember { mutableStateOf(PrefsManager.getCurrentVersion()) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        serverUrl = PrefsManager.getServerBaseUrl() ?: ""
        currentVersion = PrefsManager.getCurrentVersion()
    }

    LaunchedEffect(stage) {
        if (stage == Stage.DOWNLOADED) {
            onApplyPayload()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("固件升级", color = WhiteText) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TechBlue
                ),
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
                Stage.DOWNLOADING -> DownloadScreen(progress = progress)
                Stage.DOWNLOADED -> LoadingScreen("下载完成，准备中...")
                Stage.PREPARING -> LoadingScreen("准备中...")
                Stage.APPLYING -> ApplyingScreen(progress = progress)
                Stage.REBOOT_PENDING -> RebootPendingScreen(onReboot = onReboot)
                Stage.ERROR -> ErrorScreen(
                    message = errorMessage ?: "未知错误",
                    onRetry = { onStageChange(Stage.IDLE) },
                    onDismiss = { onStageChange(Stage.IDLE) }
                )
            }
        }
    }

    if (showNoUpdateDialog) {
        AlertDialog(
            onDismissRequest = { onDismissNoUpdate() },
            title = { Text("提示") },
            text = { Text("已是最新版本") },
            confirmButton = {
                TextButton(onClick = { onDismissNoUpdate() }) {
                    Text("确定")
                }
            }
        )
    }

    if (showNewUpdateDialog && pendingUpdateInfo != null) {
        AlertDialog(
            onDismissRequest = { onDismissNewUpdate() },
            title = { Text("发现新版本") },
            text = {
                Column {
                    Text("版本: ${pendingUpdateInfo?.version}")
                    Text("文件名: ${pendingUpdateInfo?.filename}")
                }
            },
            confirmButton = {
                TextButton(onClick = { onStartNewDownload() }) {
                    Text("立即更新")
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismissNewUpdate() }) {
                    Text("暂不更新")
                }
            }
        )
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { onDismissRebootDialog() },
            title = { Text("重启确认") },
            text = { Text("系统已准备就绪，请立即重启设备以完成升级") },
            confirmButton = {
                TextButton(onClick = {
                    onDismissRebootDialog()
                    onReboot()
                }) {
                    Text("立即重启")
                }
            },
            dismissButton = {
                TextButton(onClick = { onDismissRebootDialog() }) {
                    Text("稍后重启")
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
            color = GrayText,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            color = WhiteText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
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
fun ApplyingScreen(progress: Float) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "正在应用更新...",
            color = WhiteText,
            fontSize = 18.sp,
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
fun RebootPendingScreen(onReboot: () -> Unit) {
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
    }
}

@Composable
fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(onClick = onDismiss) {
                Text("取消", color = TechBlue)
            }
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TechBlue
                )
            ) {
                Text("重试")
            }
        }
    }
}
