## 文档信息
- **版本**：v1.4（正式发布）
- **日期**：2026-06-06
- **修订记录**：
  - v1.4：修正存储路径为 `/data/ota_package/`，适配 SELinux；引入免解压 Offset 直接升级方案；补充 `BOOT_COMPLETED` 广播清理；增加 SELinux 协同要求；附录说明 `getDataOffset` 编译隐藏 API 应对方案。
  - v1.3：增加升级后未重启场景下的重复升级检查逻辑，完善 A/B 槽位感知。
  - v1.2：增加异常文件清理策略，避免文件堆积。
  - v1.1：优化下载目录为外部存储、细化状态机、明确 payload_properties 解析方式。
  - v1.0：初稿。
- **状态**：审批通过，可进入开发阶段

---

## 1. 概述

### 1.1 背景
Rockchip 平台 Android 14 设备采用 A/B 分区系统，需开发一款系统级固件升级 APP。用户可通过扫码或手动输入配置 OTA 服务器地址，检查并下载最新固件，调用系统 `update_engine` 实现静默升级。方案需覆盖断点续传、后台执行、异常恢复、存储安全及升级后未重启的版本判断。

### 1.2 核心设计目标
- **系统适配**：严格遵循 Android 14 SELinux 策略，使用 `/data/ota_package/` 作为 OTA 文件存储目录，确保 `update_engine` 可读。
- **性能优化**：采用“免解压”直接升级方案，从 ZIP 中流式提取 `payload.bin` 的偏移量与属性，消除 GB 级解压耗时与双倍空间占用。
- **生命周期完备**：利用 `BOOT_COMPLETED` 广播在开机后台完成残留清理，不依赖用户主动打开 APP。
- **槽位感知**：通过 `REBOOT_PENDING` 状态与 `pending_slot_version` 持久化令牌，彻底避免升级后未重启时反复提示下载的循环。

---

## 2. 功能需求

| 功能模块 | 详细描述 |
|----------|----------|
| **服务器配置** | 扫码（ZXing）或手动输入完整 URL，保存至 SharedPreferences。 |
| **检查更新** | 请求服务器 `update_info.txt`，获取最新版本号、MD5、文件名；结合槽位状态智能比对，防止重复提示。 |
| **版本提示与升级许可** | 无更新提示“已是最新”；有新版本显示版本号；若处于 `REBOOT_PENDING` 则只提示重启，不重复下载。 |
| **固件下载** | 目标目录 `/data/ota_package/`，HTTP 断点续传，前台 Service 后台运行，MD5 边下边校验。 |
| **升级准备（免解压）** | 流式读取 ZIP 内 `payload_properties.txt` 并获取 `payload.bin` 的偏移量与大小，直接提交给 `update_engine`。 |
| **系统升级** | 通过 `android.os.UpdateEngine` API 应用 payload，传入文件路径、偏移量、大小及属性头数组；实时进度上报。 |
| **升级完成与重启** | 收到 `UPDATED_NEED_REBOOT` 后弹窗；确认重启或取消；取消后持久化待重启状态，后续启动 APP 或广播触发清理。 |
| **异常恢复与清理** | 五阶段状态机，每阶段异常时自动清除无效临时文件；`BOOT_COMPLETED` 广播自动校验并清理残留 OTA 包。 |

---

## 3. 系统架构

### 3.1 架构分层
```
┌──────────────────────────────────────────────┐
│                   UI Layer                    │
│  MainActivity  ScanActivity  SettingsActivity │
├──────────────────────────────────────────────┤
│                Business Layer                 │
│  UpdateChecker  DownloadService               │
│  UpdateEngineWrapper  TaskStateManager        │
│  FileCleaner  SlotVersionHelper               │
├──────────────────────────────────────────────┤
│               System Services                 │
│  update_engine (AIDL)  PowerManager           │
│  BootControl (optional)                       │
└──────────────────────────────────────────────┘
```

### 3.2 关键模块职责
- **网络层**：检查更新、断点续传。
- **任务管理**：五阶段状态机持久化与恢复。
- **升级引擎封装**：`UpdateEngine` 绑定、偏移量参数调用、回调适配。
- **文件清理**：按阶段自动清除 `/data/ota_package/` 下的无效文件，开机广播触发全量清理。
- **槽位版本感知**：持久化 `pending_slot_version`，防止重复升级提示。

---

## 4. 详细设计

### 4.1 服务器地址配置
- **扫码**：ZXing 嵌入式 Activity，扫描结果直接为 URL 字符串，自动保存。
- **手动输入**：提供 EditText，校验 URL 格式后保存至 SharedPreferences (`server_base_url`)。
- **存储格式**：`"http://192.168.1.100:8080"`，末尾不带斜杠。

### 4.2 检查更新与准备（Stage 1：Check & Prepare）
#### 4.2.1 请求与解析
- **请求**：`GET {server_base_url}/update_info.txt`
- **响应内容示例**：
  ```
  version=V2.0.1
  md5=5d41402abc4b2a76b9719d911017c592
  filename=firmware_v2.0.1.zip
  ```
- **解析**：提取 `version`、`md5`、`filename`，下载 URL = `server_base_url + "/" + filename`。

#### 4.2.2 槽位感知的版本比对
- **获取当前运行版本**：`SystemProperties.get("ro.build.version.incremental")`。
- **获取待重启版本**：从 SharedPreferences 读取 `pending_slot_version`（仅当 Stage 为 `REBOOT_PENDING` 时有效）。
- **比对逻辑**：
  1. 若当前阶段为 `REBOOT_PENDING` 且 `pending_slot_version` == 服务器最新版本 → **直接弹出重启确认框**，不触发下载。
  2. 若 `REBOOT_PENDING` 但版本不同（服务器又更新） → 提示用户：“已有更高版本固件，当前未重启的升级将被覆盖，是否继续？” 确认则重置状态并开始新流程；否则保持重启提示。
  3. 其他情况：当前运行版本 < 服务器版本 → 进入 Stage 2。
- **版本比较**：使用去除非数字前缀、按数字分段比较的方式。

#### 4.2.3 存储空间检查
- 目标目录 `/data/ota_package/`，需确保剩余空间 ≥ 固件 ZIP 大小 × 1.1。
- 不足时提示“系统空间不足，请清理后重试”。

### 4.3 固件下载（Stage 2：Downloading）
#### 4.3.1 存储路径（重要）
- **下载目录**：**`/data/ota_package/`**（系统预留 OTA 专用目录）
  - SELinux 上下文：`ota_package_file`，`update_engine` 拥有读权限。
  - 作为系统应用（UID `system`），本 APP 可直接写入该目录。
- **临时文件**：`firmware.zip.tmp`。
- **最终文件**：MD5 校验通过后重命名为 `firmware.zip`。
- **任务状态文件**：存放于内部存储 `files/task_state.json`（不受 OTA 分区清理影响）。

#### 4.3.2 断点续传与 MD5 流式计算
- 使用 OkHttp，根据 `download_task.json` 中的 `downloadedBytes` 设置 `Range` 头。
- 使用 `DigestInputStream` 边下边计算 MD5，每 1MB 更新 JSON 中的进度。
- 下载完成后校验 MD5：
  - 匹配：重命名为正式文件，状态 `DOWNLOADED`。
  - 不匹配：删除临时文件，状态重置为 `PENDING`，提示用户或自动重试。

#### 4.3.3 前台 Service
- `DownloadService` 为前台服务，通知栏展示进度。
- 用户可随时返回主界面或退出 APP，下载持续进行。

### 4.4 升级准备：免解压与属性解析（Stage 3：Preparing）
#### 4.4.1 免解压原理
`update_engine.applyPayload` 支持传入文件 URI、偏移量（offset）和大小（size），因此无需将 `payload.bin` 从 ZIP 中完整解压。只需：
- 获取 ZIP 内 `payload.bin` 的数据起始偏移量及大小；
- 提取 `payload_properties.txt` 的内容并转为 `String[]`；
- 将原始 ZIP 文件路径、偏移量、大小、headers 直接传给 `update_engine`。

#### 4.4.2 实现步骤
1. 使用 `java.util.zip.ZipFile` 打开 `firmware.zip`。
2. 获取 `payload.bin` 的 `ZipEntry`，计算偏移量 `offset` 和大小 `size`（**注意**：`ZipEntry.getDataOffset()` 为 `@hide` 方法，需通过反射调用，详见附录 12.4）。
3. 获取 `payload_properties.txt` 的 `ZipEntry`，通过 `getInputStream()` 流式读取文本，逐行存入 `List<String>`，构建 `String[] headers`。
4. 生成 `file:///data/ota_package/firmware.zip` 的 URI，传递给 `applyPayload`。
5. 此阶段不产生任何临时文件，性能开销极低。

#### 4.4.3 异常处理
- 若 ZIP 内缺少指定文件、或反射获取偏移失败，则视为文件损坏，删除 `firmware.zip`，状态重置，提示用户重新下载。

### 4.5 调用 update_engine（Stage 4：Engine Applying）
#### 4.5.1 调用方式
- 系统应用可直接使用 `android.os.UpdateEngine`。
- 示例代码：
```java
UpdateEngine engine = new UpdateEngine();
UpdateEngineCallback callback = new UpdateEngineCallback() {
    @Override
    public void onStatusUpdate(int status, float percent) {
        // 状态码 0~8，进度 0~1
    }
    @Override
    public void onPayloadApplicationComplete(int errorCode) {
        if (errorCode == 0) {
            enterStage(Stage.REBOOT_PENDING);
        } else {
            FileCleaner.cleanupStage(context, Stage.APPLYING);
            notifyError("升级失败，请重试");
        }
    }
};
engine.bind(callback);

String fileUri = "file:///data/ota_package/firmware.zip";
engine.applyPayload(fileUri, payloadOffset, payloadSize, headers);
```

- **状态码**：
  - `0`：IDLE
  - `6`：UPDATED_NEED_REBOOT（升级成功，等待重启）
  - 其他：错误，需记录日志并清理文件。

#### 4.5.2 后台与恢复
- `applyPayload` 后，升级由 `update_engine` 守护进程接管，即使 APP 退出或崩溃，升级仍继续。
- APP 重启后可通过 `engine.bind(callback)` 重新绑定，立即获得当前进度与状态。

### 4.6 升级完成与重启管理（Stage 5：Reboot Pending）
#### 4.6.1 弹窗逻辑
- 收到 `UPDATED_NEED_REBOOT` 时弹出对话框：“系统升级已完成，是否立即重启？”
  - **确定**：`PowerManager.reboot(null)` 立即重启。
  - **取消**：关闭对话框，执行以下操作：
    - 将最新版本号存入 `pending_slot_version`。
    - 更新任务状态为 `REBOOT_PENDING`。
    - **立即删除 `/data/ota_package/firmware.zip`**（`update_engine` 已完成读取），释放空间。
    - 主界面显示“重启设备”按钮。

#### 4.6.2 未重启状态下的重复升级预防
- 下次进入 APP 或点击“检查更新”时，若状态为 `REBOOT_PENDING`：
  - 若 `pending_slot_version` == 服务器最新版本 → 直接弹重启对话框。
  - 若版本不同 → 询问是否覆盖，确认后重置状态并开始新流程。

#### 4.6.3 重启后的自动清理（广播方式）
- 注册静态广播接收器：
```xml
<receiver android:name=".receiver.BootCompletedReceiver"
          android:exported="false"
          android:directBootAware="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```
- 广播接收器逻辑：
  1. 读取任务状态，若为 `REBOOT_PENDING`，获取当前运行版本。
  2. 若当前版本 == `pending_slot_version` → 升级成功，删除 `/data/ota_package/` 下所有文件，清空状态与版本令牌。
  3. 若当前版本 ≠ `pending_slot_version`（回滚场景） → 清理残留文件，保留状态供用户处理。

### 4.7 文件清理策略汇总
| 阶段 / 事件 | 清理操作 |
|--------------|----------|
| 下载异常（MD5 失败、网络中断不续传） | 删除 `firmware.zip.tmp` |
| Preparing 阶段解析失败 | 删除 `firmware.zip` |
| applyPayload 返回错误 | 删除 `firmware.zip` |
| 进入 REBOOT_PENDING 状态 | **立即删除 `firmware.zip`** |
| 用户手动“清除升级缓存” | 删除 `/data/ota_package/` 下所有文件，重置状态 |
| BOOT_COMPLETED 广播触发 | 根据版本比对，清理残留文件与状态 |
| 任意阶段用户取消或异常重置 | 按当前阶段调用清理函数，确保无效文件清除 |

### 4.8 任务状态机
```
IDLE → CHECK_PREPARE → DOWNLOADING → PREPARING → APPLYING → REBOOT_PENDING
  ↓         ↓               ↓             ↓           ↓              ↓
  └─── ERROR ←──────────────┴─────────────┴───────────┴──────────────┘
```
- **PREPARING**：免解压解析，几乎无耗时。

---

## 5. 界面与交互流程

1. **启动与配置**：无服务器地址则引导扫码/手动输入。
2. **主界面**：显示服务器地址、当前版本；若处于 `REBOOT_PENDING` 则显示“重启设备”按钮。
3. **检查更新**：
   - 若 `REBOOT_PENDING` 且版本一致 → 弹重启对话框。
   - 若 `REBOOT_PENDING` 但版本更新 → 询问是否覆盖。
   - 否则正常请求更新信息，有更新则显示详情。
4. **下载**：点击“立即更新”，主界面显示进度条，通知同步。
5. **准备与升级**：下载完成后自动进入“正在准备…”（瞬时），随后“正在升级系统…”，进度由引擎回调。
6. **完成**：升级成功弹窗“是否立即重启？”，确定重启，取消则显示“重启设备”入口。

---

## 6. 代码结构建议

```
com.example.otaupdater
├── MainActivity.java
├── scanner/
│   └── ScanActivity.java
├── ui/
│   └── SettingsActivity.java
├── receiver/
│   └── BootCompletedReceiver.java
├── network/
│   ├── UpdateChecker.java
│   └── DownloadService.java
├── engine/
│   ├── UpdateEngineWrapper.java
│   └── UpdateEngineCallbackAdapter.java
├── storage/
│   ├── PrefsManager.java
│   ├── TaskStateManager.java
│   └── FileCleaner.java
├── utils/
│   ├── VersionUtils.java
│   ├── ZipPayloadExtractor.java   // 免解压解析，含偏移量获取
│   ├── MD5Utils.java
│   └── SysPropUtils.java
└── model/
    ├── UpdateInfo.java
    └── TaskStatus.java
```

---

## 7. 系统权限与协同要求

### 7.1 AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REBOOT" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<application
    android:usesCleartextTraffic="true"
    ...>
    <service ... />
    <receiver android:name=".receiver.BootCompletedReceiver" ... />
</application>
```

### 7.2 SELinux 策略定制（ROM 团队务必添加）
在 `device/rockchip/common/sepolicy/vendor/system_app.te` 中追加：
```bash
# 允许系统 APP 写入 OTA 目录
allow system_app ota_package_file:dir { create_dir_perms };
allow system_app ota_package_file:file { create_file_perms };
# 允许发现 update_engine 服务
allow system_app update_engine_service:service_manager find;
# binder 调用
binder_call(system_app, update_engine)
# 系统属性读取
get_prop(system_app, platform_prop)
```

### 7.3 应用签名与安装
- APK 必须使用 `platform` 签名。
- 安装至 `/system/priv-app/` 目录。

---

## 8. 服务器侧约定
- 提供 HTTP 静态服务，根目录下放置 `update_info.txt` 和固件 ZIP。
- `update_info.txt` 格式示例：
  ```
  version=V2.0.1
  md5=5d41402abc4b2a76b9719d911017c592
  filename=firmware_v2.0.1.zip
  ```
- ZIP 内部包含 `payload.bin` 和 `payload_properties.txt`，无多余层级。
- 服务器须支持 HTTP `Range` 请求。

---

## 9. 测试要点

| 测试项 | 重点验证 |
|--------|----------|
| 下载路径权限 | `/data/ota_package/` 可写可读，`update_engine` 能成功应用。 |
| 免解压升级 | 偏移量、大小准确，升级正常无报错。 |
| 未重启重复提示 | 升级后取消重启，再次检查更新不提示下载，只提示重启。 |
| 新版本覆盖 | 未重启状态下服务器又推新版本，覆盖流程正确。 |
| 广播清理 | 重启后即使未打开 APP，残留 ZIP 文件被自动清理。 |
| 各阶段异常恢复 | 在每一阶段杀死 APP，重启后恢复正确状态，无重复下载或文件泄漏。 |
| SELinux 拒绝测试 | 非系统签名的 APK 写入 `/data/ota_package/` 应被 SELinux 拒绝。 |

---

## 10. 风险与应对

| 风险 | 应对措施 |
|------|----------|
| `ZipEntry.getDataOffset()` 为隐藏 API，标准 SDK 编译不过 | 使用反射调用，或手动读取 ZIP 本地文件头计算偏移量（见附录 12.4）。系统应用反射不受限制。 |
| 系统分区 `/data` 空间不足 | 下载前估算空间，不足时阻止下载并提示。 |
| 广播接收器延迟导致文件短期残留 | 残留文件仅在 `/data/ota_package/`，不影响用户存储，且下次 APP 启动亦可二次清理。 |

---

## 11. 后续迭代建议
- 自动重启倒计时（如 24 小时未手动重启则自动重启）。
- 支持增量 OTA（非零 offset / size 参数）。
- 多服务器灾备切换。

---

## 12. 附录

### 12.1 任务状态 JSON 结构
```json
{
  "taskId": "uuid",
  "stage": "APPLYING",
  "url": "http://.../firmware.zip",
  "targetFile": "/data/ota_package/firmware.zip",
  "downloadedBytes": 1234567890,
  "totalBytes": 1234567890,
  "md5Expected": "abc...",
  "headers": ["FILE_HASH=...", "FILE_SIZE=..."],
  "pendingVersion": "V2.0.1",
  "errorMsg": ""
}
```

### 12.2 免解压解析代码示例
```java
public static PayloadInfo extract(ZipFile zip) throws IOException {
    ZipEntry binEntry = zip.getEntry("payload.bin");
    ZipEntry propEntry = zip.getEntry("payload_properties.txt");
    if (binEntry == null || propEntry == null) throw new IOException("Invalid OTA zip");

    long offset = getDataOffsetReflectively(binEntry); // 见 12.4
    long size = binEntry.getSize();

    List<String> headers = new ArrayList<>();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(zip.getInputStream(propEntry)))) {
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.trim().isEmpty()) headers.add(line.trim());
        }
    }
    return new PayloadInfo(offset, size, headers.toArray(new String[0]));
}
```

### 12.3 BOOT_COMPLETED 清理逻辑伪代码
```java
public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (TaskStateManager.getStage() == Stage.REBOOT_PENDING) {
            String pendingVer = PrefsManager.getPendingSlotVersion();
            String currentVer = SysPropUtils.getCurrentVersion();
            if (currentVer.equals(pendingVer)) {
                FileCleaner.cleanAll(context);
                PrefsManager.clearPendingSlotVersion();
            } else {
                FileCleaner.cleanupOtaFiles(context);
            }
        }
    }
}
```

### 12.4 应对 `getDataOffset()` 隐藏 API 的反射方法
```java
public static long getDataOffsetReflectively(ZipEntry entry) {
    try {
        Method method = entry.getClass().getMethod("getDataOffset");
        return (long) method.invoke(entry);
    } catch (Exception e) {
        throw new RuntimeException("Failed to get data offset, possible corrupted zip", e);
    }
}
```
- 因应用具备平台签名和 `system` UID，该反射不受隐藏 API 限制，可直接调用 `libcore` 内部方法。

---

*本方案已针对 Android 14 系统特性、SELinux 安全策略、性能优化及边缘场景进行全面设计，可直接指导编码实现。*