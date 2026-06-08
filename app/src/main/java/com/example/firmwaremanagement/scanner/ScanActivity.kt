package com.example.firmwaremanagement.scanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.firmwaremanagement.storage.PrefsManager
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class ScanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrefsManager.init(this)
        setContent {
            ScanScreen(
                onUrlScanned = { url ->
                    if (isValidUrl(url)) {
                        PrefsManager.setServerBaseUrl(url)
                        setResult(Activity.RESULT_OK)
                        finish()
                    } else {
                        Toast.makeText(this, "无效的二维码", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                },
                onCancel = {
                    finish()
                }
            )
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onUrlScanned: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var isScanning by remember { mutableStateOf(true) }
    var showDialog by remember { mutableStateOf<String?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "需要相机权限才能扫描二维码", Toast.LENGTH_SHORT).show()
            onCancel()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫码配置服务器地址") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Text("取消")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isScanning && hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            DecoratedBarcodeView(ctx).apply {
                                decodeContinuous(object : BarcodeCallback {
                                    override fun barcodeResult(result: BarcodeResult?) {
                                        result?.text?.let { text ->
                                            isScanning = false
                                            showDialog = text
                                        }
                                    }

                                    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {
                                    }
                                })
                            }
                        },
                        update = { view ->
                            view.resume()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Text(
                    text = "将二维码放入框内，自动识别",
                    modifier = Modifier.padding(16.dp)
                )
            } else if (!hasCameraPermission) {
                Text(
                    text = "正在请求相机权限...",
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (showDialog != null) {
                AlertDialog(
                    onDismissRequest = {
                        isScanning = true
                        showDialog = null
                    },
                    title = { Text("识别到服务器地址") },
                    text = { Text(showDialog!!) },
                    confirmButton = {
                        TextButton(onClick = {
                            showDialog?.let { onUrlScanned(it) }
                        }) {
                            Text("确认使用")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            isScanning = true
                            showDialog = null
                        }) {
                            Text("重新扫描")
                        }
                    }
                )
            }
        }
    }
}
