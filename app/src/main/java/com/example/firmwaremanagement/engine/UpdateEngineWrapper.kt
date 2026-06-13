package com.example.firmwaremanagement.engine

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log

object UpdateEngineWrapper {

    private const val TAG = "UpdateEngineWrapper"

    // update_engine 状态码常量（对应 UpdateEngine.UpdateStatusConstants / IUpdateEngine）
    const val STATUS_IDLE = 0
    const val STATUS_CHECKING_FOR_UPDATE = 1
    const val STATUS_UPDATE_AVAILABLE = 2
    const val STATUS_DOWNLOADING = 3
    const val STATUS_VERIFYING = 4
    const val STATUS_FINALIZING = 5
    const val STATUS_UPDATED_NEED_REBOOT = 6
    const val STATUS_REPORTING_ERROR_EVENT = 7
    const val STATUS_ATTEMPTING_ROLLBACK = 8
    const val STATUS_DISABLED = 9
    const val STATUS_CLEANUP_PREVIOUS_UPDATE = 11  // Rockchip Android 14

    /** 将状态码转为可读的阶段描述 */
    fun describeStatus(status: Int): String = when (status) {
        STATUS_IDLE -> "引擎空闲"
        STATUS_CHECKING_FOR_UPDATE -> "正在检查更新"
        STATUS_UPDATE_AVAILABLE -> "检测到可用更新"
        STATUS_DOWNLOADING -> "正在写入固件"            // update_engine 将 payload 写入分区
        STATUS_VERIFYING -> "正在校验固件"
        STATUS_FINALIZING -> "正在完成安装"              // 后处理：sync、slot 切换等
        STATUS_UPDATED_NEED_REBOOT -> "安装完成，请重启系统"
        STATUS_REPORTING_ERROR_EVENT -> "上报错误信息"
        STATUS_ATTEMPTING_ROLLBACK -> "正在回滚"
        STATUS_DISABLED -> "更新已禁用"
        STATUS_CLEANUP_PREVIOUS_UPDATE -> "正在清理，请稍候"
        else -> "状态码: $status"
    }

    /** 判断当前状态是否为升级进行中（返回 true 说明升级尚未结束） */
    fun isApplying(status: Int): Boolean = status in listOf(
        STATUS_IDLE, STATUS_CHECKING_FOR_UPDATE, STATUS_UPDATE_AVAILABLE,
        STATUS_DOWNLOADING, STATUS_VERIFYING, STATUS_FINALIZING,
        STATUS_CLEANUP_PREVIOUS_UPDATE
    )

    /** 判断当前状态是否表示升级已结束（成功或失败） */
    fun isTerminal(status: Int): Boolean = when (status) {
        STATUS_UPDATED_NEED_REBOOT -> true
        STATUS_REPORTING_ERROR_EVENT -> true
        STATUS_ATTEMPTING_ROLLBACK -> true
        STATUS_DISABLED -> true
        else -> false
    }

    private var engine: Any? = null          // IUpdateEngine 代理对象
    private var engineClass: Class<*>? = null
    private var engineCallbackStubClass: Class<*>? = null
    private var callback: UpdateEngineCallbackAdapter? = null

    /**
     * 绑定 update_engine 服务并注册回调。
     * 通过 ServiceManager 获取 IBinder，再通过 IUpdateEngine.Stub.asInterface() 转为 AIDL 代理。
     * 回调通过自定义 Binder + IUpdateEngineCallback.Stub.asInterface() 实现，确保 Binder IPC 兼容。
     */
    fun bind(callback: UpdateEngineCallbackAdapter): Boolean {
        this.callback = callback

        // --- 步骤1：获取 update_engine 服务 ---
        val service = try {
            getUpdateEngineService()
        } catch (e: Exception) {
            Log.e(TAG, "bind: [step1] ServiceManager.getService failed: ${e.message}", e)
            null
        }
        if (service == null) {
            Log.e(TAG, "bind: [step1] update_engine service not found, running diagnose...")
            diagnose()
            this.callback = null
            return false
        }
        Log.d(TAG, "bind: [step1] got update_engine service: $service")

        // --- 步骤2：IUpdateEngine.Stub.asInterface ---
        try {
            engineClass = Class.forName("android.os.IUpdateEngine")
            val stubClass = Class.forName("android.os.IUpdateEngine\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            engine = asInterface.invoke(null, service)
        } catch (e: Exception) {
            Log.e(TAG, "bind: [step2] IUpdateEngine.Stub.asInterface failed: ${e.message}", e)
            engine = null
            engineClass = null
            this.callback = null
            return false
        }
        Log.d(TAG, "bind: [step2] IUpdateEngine.Stub.asInterface success: $engine")

        // --- 步骤3：创建 Binder 回调 + asInterface 包装 ---
        val cbWrapper: Any
        try {
            engineCallbackStubClass = Class.forName("android.os.IUpdateEngineCallback\$Stub")

            // 3a. 读取 AIDL descriptor 和交易码
            val descriptor = readStaticStringField(engineCallbackStubClass!!, "DESCRIPTOR")
                ?: "android.os.IUpdateEngineCallback"
            val transOnStatusUpdate = readStaticIntField(engineCallbackStubClass!!, "TRANSACTION_onStatusUpdate")
            val transOnPayloadComplete = readStaticIntField(engineCallbackStubClass!!, "TRANSACTION_onPayloadApplicationComplete")
            Log.d(TAG, "bind: [step3] descriptor=$descriptor, onStatusUpdate=$transOnStatusUpdate, onPayloadComplete=$transOnPayloadComplete")

            // 3b. 创建 Binder 子类，处理来自 update_engine 服务的回调
            val binder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    try {
                        when (code) {
                            transOnStatusUpdate -> {
                                data.enforceInterface(descriptor)
                                val status = data.readInt()
                                val percentage = data.readFloat()
                                callback.onStatusUpdate(status, percentage)
                                Log.d(TAG, "onStatusUpdate: status=$status, percentage=$percentage")
                                return true
                            }
                            transOnPayloadComplete -> {
                                data.enforceInterface(descriptor)
                                val errorCode = data.readInt()
                                callback.onPayloadApplicationComplete(errorCode)
                                Log.d(TAG, "onPayloadApplicationComplete: errorCode=$errorCode")
                                return true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "onTransact error: ${e.message}", e)
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }

            // 3c. 通过 Stub.asInterface(binder) 包装为 IUpdateEngineCallback
            val asInterfaceCb = engineCallbackStubClass!!.getMethod("asInterface", IBinder::class.java)
            cbWrapper = asInterfaceCb.invoke(null, binder)!!
            Log.d(TAG, "bind: [step3] callback Binder created: $cbWrapper")
        } catch (e: Exception) {
            Log.e(TAG, "bind: [step3] callback creation failed: ${e.message}", e)
            engine = null
            engineClass = null
            engineCallbackStubClass = null
            this.callback = null
            return false
        }

        // --- 步骤4：engine.bind(callback) ---
        return try {
            val callbackIface = Class.forName("android.os.IUpdateEngineCallback")
            val bindMethod = engineClass!!.getMethod("bind", callbackIface)
            val result = bindMethod.invoke(engine, cbWrapper) as Boolean
            Log.d(TAG, "bind: [step4] engine.bind returned $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "bind: [step4] engine.bind invocation failed: ${e.message}", e)
            engine = null
            engineClass = null
            engineCallbackStubClass = null
            this.callback = null
            false
        }
    }

    /** 解绑并释放资源 */
    fun unbind() {
        try {
            val eng = engine
            val cls = engineClass
            val cbIface = Class.forName("android.os.IUpdateEngineCallback")
            if (eng != null && cls != null) {
                val unbindMethod = cls.getMethod("unbind", cbIface)
                unbindMethod.invoke(eng, null) // 传 null 解绑所有回调
            }
        } catch (_: Exception) {
        }
        engine = null
        engineClass = null
        engineCallbackStubClass = null
        callback = null
        Log.d(TAG, "unbind: UpdateEngine released")
    }

    /** 发起 payload 升级 */
    fun applyPayload(fileUri: String, offset: Long, size: Long, headers: Array<String>) {
        val eng = engine ?: run {
            Log.e(TAG, "applyPayload: UpdateEngine not initialized")
            callback?.onPayloadApplicationComplete(1)
            return
        }
        val cls = engineClass ?: run {
            callback?.onPayloadApplicationComplete(1)
            return
        }
        Log.d(TAG, "applyPayload: fileUri=$fileUri, offset=$offset, size=$size")
        try {
            val method = cls.getMethod(
                "applyPayload",
                String::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Array<String>::class.java
            )
            method.invoke(eng, fileUri, offset, size, headers)
            Log.d(TAG, "applyPayload: called successfully via AIDL")
        } catch (e: Exception) {
            Log.e(TAG, "applyPayload: failed: ${e.message}")
            callback?.onPayloadApplicationComplete(1)
        }
    }

    fun isBound(): Boolean = engine != null

    /** 取消当前升级 */
    fun cancel() {
        val eng = engine ?: return
        val cls = engineClass ?: return
        try {
            val method = cls.getMethod("cancel")
            method.invoke(eng)
            Log.d(TAG, "cancel: update cancelled")
        } catch (_: Exception) {
        }
    }

    /** 重置 update_engine 状态 */
    fun resetStatus() {
        try {
            val service = getUpdateEngineService() ?: return
            val cls = Class.forName("android.os.IUpdateEngine")
            val stubClass = Class.forName("android.os.IUpdateEngine\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val tempEngine = asInterface.invoke(null, service)
            val method = cls.getMethod("resetStatus")
            method.invoke(tempEngine)
            Log.d(TAG, "resetStatus: done")
        } catch (e: Exception) {
            Log.e(TAG, "resetStatus: failed: ${e.message}")
        }
    }

    /**
     * 设置重启后切换到新分区（A/B slot 切换）。
     * 必须在 applyPayload 成功后调用，否则重启后仍从旧分区启动。
     * 会尝试多种 metadata 路径，确保 Rockchip/标准 AOSP 都能生效。
     * @param metadataFilename 首选路径（通常是 payload 所在文件路径）
     */
    fun setShouldSwitchSlotOnReboot(metadataFilename: String): Boolean {
        // 候选路径列表（按优先级排列）
        val candidatePaths = mutableListOf(metadataFilename)

        // 标准 update_engine prefs 目录路径
        try {
            val prefsDir = java.io.File("/data/misc/update_engine/prefs")
            if (prefsDir.parentFile?.exists() == true) {
                candidatePaths.add("/data/misc/update_engine/prefs")
            }
        } catch (_: Exception) {}
        // 某些变体直接使用目录名
        candidatePaths.add("/data/misc/update_engine")

        var success = false
        var lastError: String? = null

        for (path in candidatePaths.distinct()) {
            try {
                val service = getUpdateEngineService()
                if (service == null) {
                    lastError = "service not available"
                    continue
                }
                val cls = Class.forName("android.os.IUpdateEngine")
                val stubClass = Class.forName("android.os.IUpdateEngine\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                val tempEngine = asInterface.invoke(null, service)
                val method = cls.getMethod("setShouldSwitchSlotOnReboot", String::class.java)
                method.invoke(tempEngine, path)
                Log.d(TAG, "setShouldSwitchSlotOnReboot: SUCCESS with path='$path'")
                success = true
                break
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.cause
                val detail = if (cause != null) "${cause.javaClass.name}: ${cause.message}" else "no cause"
                Log.w(TAG, "setShouldSwitchSlotOnReboot: failed path='$path' → $detail")
                lastError = detail
            } catch (e: Exception) {
                val detail = "${e.javaClass.name}: ${e.message}"
                Log.w(TAG, "setShouldSwitchSlotOnReboot: failed path='$path' → $detail")
                lastError = detail
            }
        }

        if (!success) {
            Log.e(TAG, "setShouldSwitchSlotOnReboot: ALL AIDL paths failed, lastError=$lastError")
            // 备选方案：通过 bootctl 直接切换分区
            if (tryBootctlSwitchSlot()) {
                Log.d(TAG, "setShouldSwitchSlotOnReboot: bootctl fallback SUCCEEDED")
                success = true
            }
        }

        // 验证：重新读取 update_engine_client --status 确认 SWITCH_SLOT_ON_REBOOT 标志已置位
        verifySwitchSlotFlag()

        return success
    }

    /**
     * 备选方案：通过 bootctl set-active-boot-slot 切换到另一个分区。
     * @return true 如果切换命令执行成功
     */
    private fun tryBootctlSwitchSlot(): Boolean {
        try {
            val current = getCurrentSlot()
            if (current.isEmpty()) {
                Log.w(TAG, "tryBootctlSwitchSlot: cannot determine current slot")
                return false
            }

            val target = when (current) {
                "_a", "a" -> "b"
                "_b", "b" -> "a"
                else -> {
                    Log.w(TAG, "tryBootctlSwitchSlot: unexpected current slot '$current'")
                    return false
                }
            }

            val rt = Runtime.getRuntime()
            val proc = rt.exec(arrayOf("bootctl", "set-active-boot-slot", target))
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText().trim()
            val err = proc.errorStream.bufferedReader().readText().trim()

            if (proc.exitValue() == 0) {
                Log.d(TAG, "tryBootctlSwitchSlot: bootctl set-active-boot-slot $target SUCCESS (exit 0)")
                // 也标记为目标分区可启动
                try {
                    val proc2 = rt.exec(arrayOf("bootctl", "mark-boot-successful"))
                    proc2.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    Log.d(TAG, "tryBootctlSwitchSlot: mark-boot-successful done")
                } catch (_: Exception) {}

                // 验证 active slot 是否已变更
                Thread.sleep(300)
                val verified = getCurrentSlot()
                val verifyActive = try {
                    val p = rt.exec(arrayOf("bootctl", "get-active-boot-slot"))
                    p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                    p.inputStream.bufferedReader().readText().trim()
                } catch (_: Exception) { "" }
                Log.d(TAG, "tryBootctlSwitchSlot: verified current=$verified, active=$verifyActive")

                return true
            } else {
                Log.e(TAG, "tryBootctlSwitchSlot: bootctl failed, exit=${proc.exitValue()}, out=$out, err=$err")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryBootctlSwitchSlot: exception: ${e.message}")
            return false
        }
    }

    /** 获取当前启动的 slot 后缀（_a 或 _b），空字符串表示获取失败 */
    fun getCurrentSlot(): String {
        try {
            // 方式1: bootctl
            val proc = Runtime.getRuntime().exec(arrayOf("bootctl", "get-current-slot"))
            proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            val slot = proc.inputStream.bufferedReader().readText().trim()
            if (slot.isNotEmpty() && slot in listOf("0", "1", "_a", "_b", "a", "b")) {
                return if (slot == "0") "_a" else if (slot == "1") "_b" else slot
            }
        } catch (_: Exception) {}

        try {
            // 方式2: getprop
            val proc = Runtime.getRuntime().exec(arrayOf("getprop", "ro.boot.slot_suffix"))
            proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            val slot = proc.inputStream.bufferedReader().readText().trim()
            if (slot.isNotEmpty()) return slot
        } catch (_: Exception) {}

        try {
            // 方式3: /proc/cmdline
            val cmdline = java.io.File("/proc/cmdline").readText()
            val m = Regex("androidboot\\.slot_suffix\\s*=\\s*(\\S+)").find(cmdline)
            if (m != null) return m.groupValues[1]
        } catch (_: Exception) {}

        return ""
    }

    /** 验证 SWITCH_SLOT_ON_REBOOT 标志是否已真正置位 */
    private fun verifySwitchSlotFlag() {
        try {
            Thread.sleep(500)
            // 先检测当前 slot 和 active slot 确认是否已经切换
            val currentSlot = getCurrentSlot()
            Log.d(TAG, "verifySwitchSlotFlag: current slot = '${currentSlot}'")

            val rt = Runtime.getRuntime()
            val proc = rt.exec(arrayOf("update_engine_client", "--status"))
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            val output = proc.inputStream.bufferedReader().readText()
            val errOut = proc.errorStream.bufferedReader().readText()

            if (output.contains("SWITCH_SLOT_ON_REBOOT") || output.contains("CURRENT_STATE")) {
                val switchFlag = Regex("SWITCH_SLOT_ON_REBOOT[=\\s:]+(\\w+)", RegexOption.IGNORE_CASE).find(output)
                if (switchFlag != null) {
                    Log.d(TAG, "verifySwitchSlotFlag: SWITCH_SLOT_ON_REBOOT = ${switchFlag.groupValues[1]}")
                } else {
                    Log.w(TAG, "verifySwitchSlotFlag: SWITCH_SLOT_ON_REBOOT not found in output")
                }
            } else if (errOut.isNotEmpty() && errOut.contains("unknown command")) {
                // Rockchip 不支持 --status，改用 bootctl 验证
                Log.d(TAG, "verifySwitchSlotFlag: --status not supported, falling back to bootctl verification")
                try {
                    val proc2 = rt.exec(arrayOf("bootctl", "get-active-boot-slot"))
                    proc2.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                    val active = proc2.inputStream.bufferedReader().readText().trim()
                    Log.d(TAG, "verifySwitchSlotFlag: active boot slot = $active (current = $currentSlot)")
                } catch (_: Exception) {}
            } else {
                Log.d(TAG, "verifySwitchSlotFlag: output=${output.take(200)}, err=${errOut.take(200)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "verifySwitchSlotFlag: failed: ${e.message}")
        }
    }

    /** 检测是否有已完成的升级等待重启 */
    fun checkPendingUpdate(): Boolean {
        try {
            val pendingVersion = com.example.firmwaremanagement.storage.PrefsManager.getPendingSlotVersion()
            if (!pendingVersion.isNullOrEmpty()) {
                Log.d(TAG, "checkPendingUpdate: PrefsManager has pending version=$pendingVersion")
                return true
            }
        } catch (_: Exception) {
        }

        try {
            val rt = Runtime.getRuntime()
            val proc = rt.exec(arrayOf("update_engine_client", "--status"))
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            val output = proc.inputStream.bufferedReader().readText()
            if (Regex("CURRENT_STATE[=: ]\\s*UPDATED_NEED_REBOOT").containsMatchIn(output)) {
                Log.d(TAG, "checkPendingUpdate: UPDATED_NEED_REBOOT detected via command")
                return true
            }
        } catch (_: Exception) {
        }

        return false
    }

    fun getProgress(): Float {
        try {
            val eng = engine
            val cls = engineClass
            if (eng != null && cls != null) {
                try {
                    val method = cls.getMethod("getProgress")
                    val value = method.invoke(eng) as Float
                    if (value >= 0f) return value
                } catch (_: NoSuchMethodException) {
                }
            }
        } catch (_: Exception) {
        }

        try {
            for (arg in arrayOf("--status", "--progress")) {
                try {
                    val rt = Runtime.getRuntime()
                    val proc = rt.exec(arrayOf("update_engine_client", arg))
                    proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    val output = proc.inputStream.bufferedReader().readText()
                    val progressMatch = Regex("(?:PROGRESS|progress)[=: ]\\s*([\\d.]+)").find(output)
                    if (progressMatch != null) {
                        val value = progressMatch.groupValues[1].toFloatOrNull() ?: -1f
                        if (value >= 0f) return value
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return -1f
    }

    fun getLastResultCode(): Int {
        try {
            val eng = engine
            val cls = engineClass
            if (eng != null && cls != null) {
                try {
                    val method = cls.getMethod("getResult", Int::class.javaPrimitiveType)
                    val value = method.invoke(eng, 0) as Int
                    return value
                } catch (_: NoSuchMethodException) {
                }
            }
        } catch (_: Exception) {
        }

        try {
            val rt = Runtime.getRuntime()
            val proc = rt.exec(arrayOf("update_engine_client", "--status"))
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = Regex("LAST_ATTEMPT_ERROR[=: ]\\s*(\\d+)").find(output)
            if (exitCode != null) {
                return exitCode.groupValues[1].toIntOrNull() ?: -1
            }
            if (Regex("CURRENT_STATE[=: ]\\s*UPDATED_NEED_REBOOT").containsMatchIn(output)) {
                return 0
            }
        } catch (_: Exception) {
        }
        return -1
    }

    // ========== 内部方法 ==========

    /** 从类读取静态 int 字段 */
    private fun readStaticIntField(cls: Class<*>, fieldName: String): Int {
        val field = cls.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.getInt(null)
    }

    /** 从类读取静态 String 字段 */
    private fun readStaticStringField(cls: Class<*>, fieldName: String): String? {
        return try {
            val field = cls.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(null) as? String
        } catch (_: Exception) {
            null
        }
    }

    private val SERVICE_NAMES = arrayOf(
        "android.os.UpdateEngineService",   // Rockchip Android 14
        "update_engine",                     // 标准 AOSP
    )

    private fun getUpdateEngineService(): IBinder? {
        return try {
            val cls = Class.forName("android.os.ServiceManager")
            val method = cls.getMethod("getService", String::class.java)
            for (name in SERVICE_NAMES) {
                val service = method.invoke(null, name) as? IBinder
                if (service != null) {
                    Log.d(TAG, "getUpdateEngineService: found service with name '$name'")
                    return service
                }
            }
            Log.e(TAG, "getUpdateEngineService: none of ${SERVICE_NAMES.contentToString()} found")
            null
        } catch (e: Exception) {
            Log.e(TAG, "getUpdateEngineService: failed: ${e.message}")
            null
        }
    }

    /** 诊断设备上 update_engine 相关的类和服务的可用性，以及 A/B 分区和 slot 状态。 */
    fun diagnose() {
        Log.w(TAG, "========== update_engine DIAGNOSE START ==========")

        // 0. A/B 分区 / Slot 信息
        diagSlots()

        // 1. ServiceManager
        try {
            val sm = Class.forName("android.os.ServiceManager")
            Log.d(TAG, "diag: [OK] ServiceManager class found")
            try {
                val listMethod = sm.getMethod("listServices")
                @Suppress("UNCHECKED_CAST")
                val services = listMethod.invoke(null) as? Array<String>
                val updateServices = services?.filter { it.contains("update", ignoreCase = true) }
                Log.d(TAG, "diag: update-related services: $updateServices")
                if (!updateServices.isNullOrEmpty()) {
                    val getService = sm.getMethod("getService", String::class.java)
                    for (name in updateServices) {
                        val svc = getService.invoke(null, name)
                        Log.d(TAG, "diag: service '$name' -> ${if (svc != null) "available" else "null"}")
                    }
                } else {
                    Log.w(TAG, "diag: [WARN] No update-related service found!")
                }
            } catch (e: Exception) {
                Log.w(TAG, "diag: listServices failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "diag: [FAIL] ServiceManager class not found: ${e.message}")
        }

        // 2. IUpdateEngine
        try {
            Class.forName("android.os.IUpdateEngine")
            Log.d(TAG, "diag: [OK] IUpdateEngine class found")
        } catch (e: Exception) {
            Log.e(TAG, "diag: [FAIL] IUpdateEngine: ${e.message}")
        }

        // 3. IUpdateEngine$Stub
        try {
            Class.forName("android.os.IUpdateEngine\$Stub")
            Log.d(TAG, "diag: [OK] IUpdateEngine\$Stub class found")
        } catch (e: Exception) {
            Log.e(TAG, "diag: [FAIL] IUpdateEngine\$Stub: ${e.message}")
        }

        // 4. IUpdateEngineCallback & Stub
        try {
            val cb = Class.forName("android.os.IUpdateEngineCallback")
            Log.d(TAG, "diag: [OK] IUpdateEngineCallback, isInterface=${cb.isInterface}")
        } catch (e: Exception) {
            Log.e(TAG, "diag: [FAIL] IUpdateEngineCallback: ${e.message}")
        }
        try {
            val stub = Class.forName("android.os.IUpdateEngineCallback\$Stub")
            Log.d(TAG, "diag: [OK] IUpdateEngineCallback\$Stub found")
            for (name in arrayOf("TRANSACTION_onStatusUpdate", "TRANSACTION_onPayloadApplicationComplete")) {
                try {
                    val field = stub.getDeclaredField(name)
                    field.isAccessible = true
                    Log.d(TAG, "diag: $name = ${field.getInt(null)}")
                } catch (e: Exception) {
                    Log.w(TAG, "diag: $name not found: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "diag: [FAIL] IUpdateEngineCallback\$Stub: ${e.message}")
        }

        // 5. update_engine_client
        diagUpdateEngineClient()

        // 6. service list
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("service", "list"))
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            val output = proc.inputStream.bufferedReader().readText()
            val engineLines = output.lines().filter { it.contains("update", ignoreCase = true) }
            Log.d(TAG, "diag: 'service list' update entries: ${engineLines.toList()}")
        } catch (e: Exception) {
            Log.w(TAG, "diag: 'service list' failed: ${e.message}")
        }

        Log.w(TAG, "========== update_engine DIAGNOSE END ==========")
    }

    private fun diagSlots() {
        try {
            val rt = Runtime.getRuntime()

            // --- bootctl 系列命令 ---
            try {
                val bootctlCmds = arrayOf(
                    arrayOf("bootctl", "get-current-slot"),
                    arrayOf("bootctl", "get-suffix"),
                    arrayOf("bootctl", "get-number-slots"),
                    arrayOf("bootctl", "is-slot-marked-successful"),
                    arrayOf("bootctl", "is-slot-bootable"),
                    arrayOf("bootctl", "get-slot-unbootable"),
                    arrayOf("bootctl", "get-slot-retry-count"),
                )
                for (cmd in bootctlCmds) {
                    try {
                        val proc = rt.exec(cmd)
                        proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        val out = proc.inputStream.bufferedReader().readText().trim()
                        if (out.isNotEmpty()) {
                            Log.d(TAG, "diag: bootctl ${cmd.contentToString()} -> $out")
                        } else {
                            val err = proc.errorStream.bufferedReader().readText().trim()
                            if (err.isNotEmpty()) Log.d(TAG, "diag: bootctl ${cmd.contentToString()} err: $err")
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                Log.w(TAG, "diag: bootctl not available")
            }

            // --- 尝试 bootctl set 操作（只探测，不执行） ---
            try {
                val proc = rt.exec(arrayOf("bootctl", "set-active-boot-slot"))
                proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                val out = proc.inputStream.bufferedReader().readText().trim()
                val err = proc.errorStream.bufferedReader().readText().trim()
                if (out.isNotEmpty() || err.isNotEmpty()) {
                    Log.d(TAG, "diag: bootctl set-active-boot-slot available, output='${out.take(100)}', err='${err.take(100)}'")
                }
            } catch (_: Exception) {}

            // --- /proc/cmdline ---
            try {
                val cmdline = java.io.File("/proc/cmdline").readText().trim()
                Log.d(TAG, "diag: /proc/cmdline = ${cmdline.take(300)}")
                val slotMatch = Regex("androidboot\\.slot_suffix\\s*=\\s*(\\S+)").find(cmdline)
                if (slotMatch != null) {
                    Log.d(TAG, "diag: current slot (cmdline) = ${slotMatch.groupValues[1]}")
                }
                val slotCountMatch = Regex("androidboot\\.slot_count\\s*=\\s*(\\d+)").find(cmdline)
                if (slotCountMatch != null) {
                    Log.d(TAG, "diag: slot count (cmdline) = ${slotCountMatch.groupValues[1]}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "diag: /proc/cmdline read failed: ${e.message}")
            }

            // --- getprop ---
            for (prop in arrayOf("ro.boot.slot_suffix", "ro.boot.slot", "ro.bootimage.slot_suffix",
                "ro.build.version.incremental", "ro.build.fingerprint", "ro.product.board",
                "ro.boot.hardware", "ro.boot.verifiedbootstate"))
            {
                try {
                    val proc = rt.exec(arrayOf("getprop", prop))
                    proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                    val value = proc.inputStream.bufferedReader().readText().trim()
                    if (value.isNotEmpty()) Log.d(TAG, "diag: $prop = $value")
                } catch (_: Exception) {}
            }

            // --- update_engine prefs ---
            val prefsDir = java.io.File("/data/misc/update_engine/prefs")
            if (prefsDir.exists()) {
                Log.d(TAG, "diag: /data/misc/update_engine/prefs exists")
                prefsDir.listFiles()?.forEach {
                    Log.d(TAG, "diag:   prefs: ${it.name} (${it.length()}B)")
                    if (it.length() < 4096) {
                        try {
                            Log.d(TAG, "diag:   ${it.name} content: ${it.readText().take(200)}")
                        } catch (_: Exception) {}
                    }
                }
            } else {
                Log.w(TAG, "diag: /data/misc/update_engine/prefs does NOT exist, trying parent...")
                val parent = java.io.File("/data/misc/update_engine")
                if (parent.exists()) {
                    Log.d(TAG, "diag: /data/misc/update_engine exists, contents:")
                    parent.listFiles()?.forEach { Log.d(TAG, "diag:   ${it.name}") }
                } else {
                    Log.w(TAG, "diag: /data/misc/update_engine does NOT exist either")
                }
            }

            // --- metadata partition (misc) ---
            try {
                val proc = rt.exec(arrayOf("ls", "-l", "/metadata/ota"))
                proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                val out = proc.inputStream.bufferedReader().readText().trim()
                if (out.isNotEmpty()) Log.d(TAG, "diag: /metadata/ota: $out")
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.e(TAG, "diag: slot diag failed: ${e.message}")
        }
    }

    private fun diagUpdateEngineClient() {
        val rt = Runtime.getRuntime()

        // 0. 探测 update_engine_client 支持的命令
        val flagTests = arrayOf(
            arrayOf("update_engine_client", "--status"),
            arrayOf("update_engine_client", "--help"),
            arrayOf("update_engine_client", "-h"),
            arrayOf("update_engine_client", "--follow"),
            arrayOf("update_engine_client", "--merge"),
            arrayOf("update_engine_client", "--reset"),
            arrayOf("update_engine_client", "--resume"),
            arrayOf("update_engine_client", "--suspend"),
            arrayOf("update_engine_client", "--cancel"),
            arrayOf("update_engine_client", "--update"),
            arrayOf("update_engine_client", "--allocate"),
            arrayOf("update_engine_client", "--verify"),
            arrayOf("update_engine_client", "--set_switch_slot_on_reboot"),
            arrayOf("update_engine_client", "--reset_status"),
        )
        for (cmd in flagTests) {
            try {
                val proc = rt.exec(cmd)
                proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                val out = proc.inputStream.bufferedReader().readText().trim()
                val err = proc.errorStream.bufferedReader().readText().trim()
                val code = if (proc.isAlive) { proc.destroy(); "timeout" } else proc.exitValue().toString()
                val summary = if (out.isNotEmpty()) out.take(150) else err.take(150)
                if (summary.isNotEmpty() || code != "1") {
                    Log.d(TAG, "diag: CLI '${cmd.contentToString()}' exit=$code: $summary")
                }
            } catch (_: Exception) {}
        }

        // 1. 查找是否还有其他更新相关 CLI 工具
        for (tool in arrayOf("update_engine_client", "update_client", "rkupdate_engine_client",
            "updateEngine", "rkupdate", "ota_client", "OtaClient"))
        {
            try {
                val proc = rt.exec(arrayOf("which", tool))
                proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                val out = proc.inputStream.bufferedReader().readText().trim()
                if (out.isNotEmpty()) {
                    Log.d(TAG, "diag: found tool: $out")
                }
            } catch (_: Exception) {}
        }

        // 2. 尝试标准 update_engine_client --status
        try {
            val proc = rt.exec(arrayOf("update_engine_client", "--status"))
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            val output = proc.inputStream.bufferedReader().readText()
            val errOut = proc.errorStream.bufferedReader().readText()

            if (output.contains("CURRENT_STATE") || output.contains("PROGRESS")) {
                Log.d(TAG, "diag: update_engine_client --status OK, output=${output.take(500)}")
                val currentState = Regex("CURRENT_STATE[=\\s:]+(\\w+)", RegexOption.IGNORE_CASE).find(output)
                if (currentState != null) Log.d(TAG, "diag:   CURRENT_STATE = ${currentState.groupValues[1]}")
                val switchFlag = Regex("SWITCH_SLOT_ON_REBOOT[=\\s:]+(\\w+)", RegexOption.IGNORE_CASE).find(output)
                if (switchFlag != null) Log.d(TAG, "diag:   SWITCH_SLOT_ON_REBOOT = ${switchFlag.groupValues[1]}")
                else Log.w(TAG, "diag:   SWITCH_SLOT_ON_REBOOT NOT SET")
                val progress = Regex("PROGRESS[=\\s:]+([\\d.]+)").find(output)
                if (progress != null) Log.d(TAG, "diag:   PROGRESS = ${progress.groupValues[1]}")
            } else if (errOut.isNotEmpty()) {
                Log.w(TAG, "diag: update_engine_client --status failed: ${errOut.take(500)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "diag: update_engine_client --status exception: ${e.message}")
        }
    }
}
