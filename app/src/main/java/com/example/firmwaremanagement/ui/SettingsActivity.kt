package com.example.firmwaremanagement.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.firmwaremanagement.storage.PrefsManager
import com.example.firmwaremanagement.ui.theme.FirmwareManagementTheme
import java.net.URL

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrefsManager.init(this)
        setContent {
            FirmwareManagementTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var serverUrl by remember { mutableStateOf(PrefsManager.getServerBaseUrl() ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手动设置服务器地址") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("取消")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    errorMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务器地址") },
                placeholder = { Text("http://192.168.1.100:8080") },
                trailingIcon = {
                    if (serverUrl.isNotEmpty()) {
                        TextButton(onClick = { serverUrl = "" }) {
                            Text("清除")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val url = serverUrl.trim()
                        when {
                            url.isEmpty() -> {
                                errorMessage = "服务器地址不能为空"
                            }
                            !isValidUrl(url) -> {
                                errorMessage = "请输入有效的服务器地址"
                            }
                            else -> {
                                PrefsManager.setServerBaseUrl(url)
                                Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show()
                                onBack()
                            }
                        }
                    }
                ),
                isError = errorMessage != null,
                supportingText = {
                    if (errorMessage != null) {
                        Text(text = errorMessage!!, color = Color.Red)
                    } else {
                        Text("示例：http://192.168.1.100:8080")
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    focusedLabelColor = Color.Black,
                    unfocusedLabelColor = Color.Gray,
                    focusedPlaceholderColor = Color.Gray,
                    unfocusedPlaceholderColor = Color.Gray,
                    focusedBorderColor = Color(0xFF2196F3),
                    unfocusedBorderColor = Color.Gray,
                    cursorColor = Color(0xFF2196F3)
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val url = serverUrl.trim()
                    when {
                        url.isEmpty() -> {
                            errorMessage = "服务器地址不能为空"
                        }
                        !isValidUrl(url) -> {
                            errorMessage = "请输入有效的服务器地址"
                        }
                        else -> {
                            PrefsManager.setServerBaseUrl(url)
                            Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show()
                            onBack()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors()
            ) {
                Text("保存地址")
            }
        }
    }
}

private fun isValidUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    if (!url.startsWith("http://") && !url.startsWith("https://")) return false
    return try {
        URL(url).host.isNotEmpty()
    } catch (e: Exception) {
        false
    }
}
