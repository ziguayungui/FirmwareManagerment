# Checklist - Firmware Upgrade APP Implementation

## Phase 1: 项目基础配置与权限

- [x] AndroidManifest.xml 已配置 INTERNET、ACCESS_NETWORK_STATE、FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC 权限
- [x] AndroidManifest.xml 已配置 WAKE_LOCK、REBOOT、RECEIVE_BOOT_COMPLETED 权限
- [x] AndroidManifest.xml 已设置 usesCleartextTraffic=true
- [x] AndroidManifest.xml 已注册 DownloadService（前台服务）
- [x] AndroidManifest.xml 已注册 BootCompletedReceiver（静态广播）
- [x] build.gradle.kts 已添加 ZXing 依赖（用于二维码扫描）
- [x] build.gradle.kts 已添加 OkHttp 依赖（用于网络请求）

## Phase 2: 数据模型与存储

- [x] UpdateInfo 数据模型包含 version、md5、filename 字段
- [x] UpdateInfo 提供解析 update_info.txt 的方法
- [x] Stage 枚举包含所有状态（IDLE、CHECK_PREPARE、DOWNLOADING、PREPARING、APPLYING、REBOOT_PENDING、ERROR）
- [x] TaskState JSON 结构包含 taskId、stage、url、downloadedBytes、totalBytes、md5Expected、headers、pendingVersion、errorMsg
- [x] PrefsManager 提供服务器地址存取（server_base_url）
- [x] PrefsManager 提供 pending_slot_version 存取
- [x] TaskStateManager 提供任务状态 JSON 持久化至内部存储
- [x] TaskStateManager 提供状态恢复与重置功能

## Phase 3: 工具类

- [x] VersionUtils 版本比较逻辑正确（去除非数字前缀，按数字分段比较）
- [x] MD5Utils 提供文件 MD5 计算方法
- [x] MD5Utils 提供流式 MD5 计算（DigestInputStream）
- [x] SysPropUtils 提供获取当前运行版本方法（ro.build.version.incremental）
- [x] ZipPayloadExtractor 可打开 ZIP 获取 payload.bin 和 payload_properties.txt
- [x] ZipPayloadExtractor 通过反射调用 getDataOffset() 获取偏移量
- [x] ZipPayloadExtractor 流式读取 headers

## Phase 4: 网络层

- [x] UpdateChecker 请求服务器 update_info.txt 并解析
- [x] UpdateChecker 调用 VersionUtils 比对版本
- [x] DownloadService 支持断点续传（Range 头设置）
- [x] DownloadService 支持 MD5 边下边校验
- [x] DownloadService 前台通知栏进度展示
- [x] DownloadService 下载完成后 MD5 校验与文件重命名

## Phase 5: 升级引擎封装

- [x] UpdateEngineCallbackAdapter 处理 onStatusUpdate 进度回调
- [x] UpdateEngineCallbackAdapter 处理 onPayloadApplicationComplete 结果回调
- [x] UpdateEngineWrapper bind 绑定回调
- [x] UpdateEngineWrapper applyPayload 调用（传入 offset、size、headers）
- [x] UpdateEngineWrapper 支持状态重绑定恢复

## Phase 6: 文件清理

- [x] FileCleaner.cleanupStage 按阶段清理
- [x] FileCleaner.cleanAll 清理所有 OTA 文件
- [x] FileCleaner.cleanupOtaFiles 清理 /data/ota_package/ 下文件

## Phase 7: 广播接收器

- [x] BootCompletedReceiver 监听 BOOT_COMPLETED 广播
- [x] BootCompletedReceiver 版本比对后自动清理
- [x] BootCompletedReceiver 在 AndroidManifest.xml 注册为静态广播

## Phase 8: UI 层

- [x] MainActivity 显示服务器地址（可点击编辑）
- [x] MainActivity 显示当前版本
- [x] MainActivity "检查更新"按钮功能完整
- [x] MainActivity "扫码配置"和"手动输入"按钮功能完整
- [x] MainActivity REBOOT_PENDING 状态显示"重启设备"按钮
- [x] MainActivity 下载/升级进度展示
- [x] ScanActivity ZXing 扫码功能正常
- [x] ScanActivity 扫描成功弹出确认对话框
- [x] SettingsActivity 服务器地址输入框功能完整
- [x] SettingsActivity URL 格式校验功能
- [x] SettingsActivity 保存功能

## Phase 9: 业务逻辑集成

- [x] 五阶段状态机流程正确（IDLE → CHECK_PREPARE → DOWNLOADING → PREPARING → APPLYING → REBOOT_PENDING）
- [x] 任意阶段 ERROR 处理与状态重置正确
- [x] 状态持久化与恢复功能正常
- [x] REBOOT_PENDING 状态特殊处理（直接弹重启确认框）
- [x] 新版本覆盖确认流程正确
- [x] UPDATED_NEED_REBOOT 弹窗功能正常
- [x] 用户选择"取消"后保存状态和删除 ZIP
- [x] "重启设备"按钮功能正常
