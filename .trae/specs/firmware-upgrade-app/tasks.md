# Tasks - Firmware Upgrade APP Implementation

## Phase 1: 项目基础配置与权限

- [ ] Task 1.1: 更新 AndroidManifest.xml 权限配置
  - 添加 INTERNET、ACCESS_NETWORK_STATE、FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC
  - 添加 WAKE_LOCK、REBOOT、RECEIVE_BOOT_COMPLETED
  - 设置 usesCleartextTraffic=true
  - 注册 DownloadService 和 BootCompletedReceiver

- [ ] Task 1.2: 配置 build.gradle.kts 依赖
  - 添加 ZXing 用于二维码扫描
  - 添加 OkHttp 用于网络请求
  - 配置系统签名支持（platform signing）

## Phase 2: 数据模型与存储

- [ ] Task 2.1: 创建 UpdateInfo 数据模型
  - 定义 version、md5、filename 字段
  - 提供解析 update_info.txt 的方法

- [ ] Task 2.2: 创建 TaskStatus 枚举和任务状态模型
  - 定义 Stage 枚举（IDLE、CHECK_PREPARE、DOWNLOADING、PREPARING、APPLYING、REBOOT_PENDING、ERROR）
  - 定义 TaskState JSON 结构（taskId、stage、url、downloadedBytes、totalBytes 等）

- [ ] Task 2.3: 创建 PrefsManager 存储管理类
  - 服务器地址存取（server_base_url）
  - pending_slot_version 存取
  - 版本信息存取

- [ ] Task 2.4: 创建 TaskStateManager 状态管理类
  - 任务状态 JSON 持久化至内部存储
  - 状态恢复与重置
  - 各阶段状态转换逻辑

## Phase 3: 工具类

- [ ] Task 3.1: 创建 VersionUtils 版本比较工具
  - 版本号解析（去除非数字前缀，按数字分段比较）
  - 版本比较方法实现

- [ ] Task 3.2: 创建 MD5Utils MD5 工具
  - 文件 MD5 计算
  - 流式 MD5 计算（DigestInputStream）

- [ ] Task 3.3: 创建 SysPropUtils 系统属性工具
  - 获取当前运行版本（ro.build.version.incremental）

- [ ] Task 3.4: 创建 ZipPayloadExtractor 免解压解析工具
  - 打开 ZIP 获取 payload.bin 和 payload_properties.txt
  - 通过反射调用 getDataOffset() 获取偏移量
  - 流式读取 headers

## Phase 4: 网络层

- [ ] Task 4.1: 创建 UpdateChecker 检查更新类
  - 请求服务器 update_info.txt
  - 解析版本、MD5、文件名信息
  - 调用 VersionUtils 比对版本

- [ ] Task 4.2: 创建 DownloadService 前台下载服务
  - 断点续传支持（Range 头设置）
  - MD5 边下边校验
  - 前台通知栏进度展示
  - 下载完成后 MD5 校验与文件重命名

## Phase 5: 升级引擎封装

- [ ] Task 5.1: 创建 UpdateEngineCallbackAdapter 回调适配器
  - onStatusUpdate 进度处理
  - onPayloadApplicationComplete 结果处理

- [ ] Task 5.2: 创建 UpdateEngineWrapper 升级引擎封装类
  - bind 绑定回调
  - applyPayload 调用（传入 offset、size、headers）
  - 状态重绑定恢复

## Phase 6: 文件清理

- [ ] Task 6.1: 创建 FileCleaner 文件清理类
  - cleanupStage 按阶段清理
  - cleanAll 清理所有 OTA 文件
  - cleanupOtaFiles 清理 /data/ota_package/ 下文件

## Phase 7: 广播接收器

- [ ] Task 7.1: 创建 BootCompletedReceiver 开机广播接收器
  - 监听 BOOT_COMPLETED 广播
  - 版本比对后自动清理
  - 在 AndroidManifest.xml 注册静态广播

## Phase 8: UI 层

- [ ] Task 8.1: 更新 MainActivity 主界面
  - 显示服务器地址（可点击编辑）
  - 显示当前版本
  - "检查更新"按钮
  - "扫码配置"和"手动输入"按钮
  - REBOOT_PENDING 状态显示"重启设备"按钮
  - 下载/升级进度展示

- [ ] Task 8.2: 创建 ScanActivity 扫码配置界面
  - ZXing 嵌入式扫码
  - 扫描成功弹出确认对话框
  - 扫描失败/重新扫描处理

- [ ] Task 8.3: 创建 SettingsActivity 手动设置界面
  - 服务器地址输入框
  - URL 格式校验
  - 保存功能

## Phase 9: 业务逻辑集成

- [ ] Task 9.1: 实现五阶段状态机流程
  - IDLE → CHECK_PREPARE → DOWNLOADING → PREPARING → APPLYING → REBOOT_PENDING
  - 任意阶段 ERROR 处理与状态重置
  - 状态持久化与恢复

- [ ] Task 9.2: 实现版本比对与重复提示预防逻辑
  - 当前版本 vs 服务器版本比较
  - REBOOT_PENDING 状态特殊处理
  - 新版本覆盖确认流程

- [ ] Task 9.3: 实现升级完成重启管理逻辑
  - UPDATED_NEED_REBOOT 弹窗
  - 用户选择"取消"后保存状态和删除 ZIP
  - "重启设备"按钮功能

## Task Dependencies

- Task 2.1 ~ 2.4 依赖 Task 1.1（需要先配置权限）
- Task 3.1 ~ 3.4 依赖 Task 2.1 ~ 2.2（数据模型定义后）
- Task 4.1 ~ 4.2 依赖 Task 3.1 ~ 3.3（工具类完成后）
- Task 5.1 ~ 5.2 依赖 Task 3.4（需要 ZipPayloadExtractor）
- Task 6.1 依赖 Task 3.2（需要 MD5Utils）
- Task 7.1 依赖 Task 2.3 ~ 2.4 和 Task 6.1
- Task 8.1 ~ 8.3 依赖 Task 2.3 ~ 2.4（需要 PrefsManager）
- Task 9.1 ~ 9.3 依赖 Task 4.1 ~ 4.2、Task 5.1 ~ 5.2、Task 6.1、Task 7.1、Task 8.1 ~ 8.3（所有组件完成后）

## 实施顺序建议

1. **并行**：Task 1.1、Task 2.1、Task 2.2、Task 3.1 ~ 3.3
2. **并行**：Task 2.3、Task 2.4、Task 3.4、Task 4.1
3. **并行**：Task 4.2、Task 5.1 ~ 5.2、Task 6.1、Task 7.1
4. **并行**：Task 8.1 ~ 8.3
5. **最后**：Task 9.1 ~ 9.3（业务逻辑集成）
