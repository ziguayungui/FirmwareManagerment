# Firmware Upgrade APP Specification

## Why
Rockchip 平台 Android 14 设备采用 A/B 分区系统，需要一款系统级固件升级 APP，实现从 OTA 服务器检查、下载固件，并通过系统 `update_engine` 完成静默升级。需解决断点续传、后台执行、异常恢复、存储安全及升级后未重启的版本判断等核心问题。

## What Changes

### 新增功能模块
- **服务器配置**：支持扫码（ZXing）和手动输入 OTA 服务器地址
- **检查更新**：从服务器获取 `update_info.txt`，智能比对版本防止重复提示
- **固件下载**：前台 Service 后台执行，支持断点续传和 MD5 校验
- **免解压升级准备**：从 ZIP 直接提取 `payload.bin` 偏移量，无需完整解压
- **系统升级**：调用 `android.os.UpdateEngine` API 实现静默升级
- **重启管理**：升级完成后弹窗确认，支持延迟重启和 `BOOT_COMPLETED` 广播清理

### 架构设计
- **状态机**：五阶段状态机（IDLE → CHECK_PREPARE → DOWNLOADING → PREPARING → APPLYING → REBOOT_PENDING）
- **存储路径**：`/data/ota_package/` 作为 OTA 文件存储目录
- **系统集成**：使用系统签名，作为系统应用安装至 `/system/priv-app/`

## Impact

### 影响范围
- 新增 UI 界面：主界面、扫码界面、手动设置界面
- 新增 Service：DownloadService（前台下载服务）
- 新增 Receiver：BootCompletedReceiver（开机广播清理）
- 新增 Manager 类：PrefsManager、TaskStateManager、FileCleaner、UpdateEngineWrapper
- 新增工具类：VersionUtils、ZipPayloadExtractor、MD5Utils、SysPropUtils
- 新增 Model：UpdateInfo、TaskStatus

### 关键文件
- 技术方案：`doc/Rockchip Android 14 固件升级 APP 技术方案.md`
- UI 设计：`doc/UI 线框图设计说明.md`

---

## ADDED Requirements

### Requirement: 服务器配置与存储
系统 SHALL 提供扫码和手动输入两种方式配置 OTA 服务器地址，并持久化至 SharedPreferences。

#### Scenario: 扫码配置成功
- **WHEN** 用户打开扫码界面并扫描包含 URL 的二维码
- **THEN** 系统识别 URL 并弹出确认对话框，用户确认后保存至 SharedPreferences

#### Scenario: 手动输入配置
- **WHEN** 用户在输入框输入有效 URL 并点击保存
- **THEN** 系统校验 URL 格式，保存并返回主界面显示新地址

---

### Requirement: 检查更新与版本比对
系统 SHALL 从服务器获取 `update_info.txt`，结合槽位状态智能比对，防止重复提示升级。

#### Scenario: 已是最新版本
- **WHEN** 当前运行版本 >= 服务器最新版本 且不在 REBOOT_PENDING 状态
- **THEN** 显示"已是最新版本"提示

#### Scenario: 发现新版本
- **WHEN** 当前运行版本 < 服务器最新版本
- **THEN** 显示新版本信息对话框，询问是否下载

#### Scenario: 升级后未重启的重复提示预防
- **WHEN** 状态为 REBOOT_PENDING 且 pending_slot_version == 服务器版本
- **THEN** 直接弹出重启确认框，不触发下载

---

### Requirement: 固件下载（断点续传）
系统 SHALL 支持固件下载的断点续传，存储于 `/data/ota_package/`，并实时计算 MD5 校验。

#### Scenario: 正常下载
- **WHEN** 用户确认下载后
- **THEN** 启动前台 Service 显示进度通知，下载完成后校验 MD5 并进入下一阶段

#### Scenario: 下载中断后恢复
- **WHEN** 下载中断后重新启动 APP
- **THEN** 系统检测到已下载部分，从断点继续下载

---

### Requirement: 免解压升级准备
系统 SHALL 采用"免解压"方案，从 ZIP 直接获取 `payload.bin` 的偏移量和属性，提交给 `update_engine`。

#### Scenario: 免解压解析成功
- **WHEN** ZIP 文件解析成功获取 offset、size、headers
- **THEN** 直接进入 APPLYING 阶段，无临时文件产生

#### Scenario: 解析失败
- **WHEN** ZIP 缺少必要文件或反射获取偏移失败
- **THEN** 删除 firmware.zip，重置状态，提示重新下载

---

### Requirement: 系统升级与进度上报
系统 SHALL 调用 `android.os.UpdateEngine.applyPayload()` 实现静默升级，并实时上报进度。

#### Scenario: 升级成功需要重启
- **WHEN** UpdateEngine 回调 status == UPDATED_NEED_REBOOT (6)
- **THEN** 弹出重启确认对话框，用户确认后执行 PowerManager.reboot()

#### Scenario: 升级失败
- **WHEN** UpdateEngine 回调 errorCode != 0
- **THEN** 清理临时文件，提示用户升级失败

---

### Requirement: 重启管理与自动清理
系统 SHALL 在用户取消立即重启后保存待重启状态，并在 BOOT_COMPLETED 广播时自动清理残留文件。

#### Scenario: 用户取消立即重启
- **WHEN** 用户在升级完成弹窗点击"取消"
- **THEN** 保存 pending_slot_version，删除 firmware.zip，界面显示"重启设备"按钮

#### Scenario: 重启后自动清理
- **WHEN** 设备重启后 BOOT_COMPLETED 广播触发
- **THEN** 读取任务状态，若版本匹配则清理所有 OTA 文件并重置状态

---

### Requirement: 五阶段状态机
系统 SHALL 实现五阶段状态机，每阶段异常时自动清除无效临时文件，状态持久化至内部存储。

#### States:
- **IDLE**：初始状态
- **CHECK_PREPARE**：检查更新和准备阶段
- **DOWNLOADING**：固件下载中
- **PREPARING**：免解压解析阶段
- **APPLYING**：系统升级应用阶段
- **REBOOT_PENDING**：等待重启阶段

#### Transitions:
- 任意阶段 ERROR → 清理文件 → IDLE
- APPLYING 成功 → REBOOT_PENDING
- REBOOT_PENDING 重启 → IDLE

---

### Requirement: UI 界面交互
系统 SHALL 按照 UI 线框图实现以下界面：主界面空闲状态、下载进度界面、升级进度界面、重启待命界面、扫码配置界面、手动设置界面。

#### Scenario: 主界面空闲状态
- **WHEN** 用户打开 APP 且无进行中任务
- **THEN** 显示服务器地址、当前版本、"检查更新"按钮

#### Scenario: 下载/升级进度界面
- **WHEN** 下载或升级进行中
- **THEN** 显示进度条、百分比、下载速度/写入状态，通知栏同步显示

---

## MODIFIED Requirements

### Requirement: AndroidManifest.xml 权限配置
系统 SHALL 配置以下权限和组件：
- INTERNET、ACCESS_NETWORK_STATE、FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC
- WAKE_LOCK、REBOOT、RECEIVE_BOOT_COMPLETED
- usesCleartextTraffic=true
- DownloadService（前台服务）
- BootCompletedReceiver（静态广播接收器）

---

## REMOVED Requirements

### Requirement: 传统解压升级方式
**Reason**: 采用免解压方案，避免 GB 级固件的双倍空间占用和漫长解压时间
**Migration**: 直接从 ZIP 流式提取偏移量，绕过完整解压流程
