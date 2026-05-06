package com.example.browndustbot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnRequestOverlay: Button
    private lateinit var btnOpenAccessibility: Button
    private lateinit var btnRequestCapture: Button
    private lateinit var btnLoadConfig: Button
    private lateinit var btnStartTask: Button
    private lateinit var btnStopTask: Button
    private lateinit var btnRefreshConfigs: Button
    private lateinit var spinnerConfig: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var imageMatcher: ImageMatcher
    private lateinit var textRecognizer: GameTextRecognizer
    private var taskEngine: TaskEngine? = null
    private var currentTaskConfig: TaskConfig? = null
    private val logBuilder = StringBuilder()

    private val configFiles = mutableListOf<File>()

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _: ActivityResult ->
        if (Settings.canDrawOverlays(this)) {
            toast("悬浮窗权限已授权")
        } else {
            toast("悬浮窗权限被拒绝")
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val metrics = getDisplayMetrics()
            screenCaptureManager.init(result.resultCode, result.data!!, metrics)
            appendLog("截屏权限已授权")
            toast("截屏权限已授权")
        } else {
            toast("截屏权限被拒绝")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        initComponents()
        setupButtons()

        if (!OpenCVLoader.initLocal()) {
            appendLog("OpenCV 初始化失败！图像匹配将不可用")
            toast("OpenCV 初始化失败，图像匹配不可用")
        } else {
            appendLog("OpenCV 初始化成功")
        }

        scanConfigFiles()
        requestNotificationPermission()
    }

    private fun bindViews() {
        btnRequestOverlay = findViewById(R.id.btnRequestOverlay)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnRequestCapture = findViewById(R.id.btnRequestCapture)
        btnLoadConfig = findViewById(R.id.btnLoadConfig)
        btnStartTask = findViewById(R.id.btnStartTask)
        btnStopTask = findViewById(R.id.btnStopTask)
        btnRefreshConfigs = findViewById(R.id.btnRefreshConfigs)
        spinnerConfig = findViewById(R.id.spinnerConfig)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
    }

    private fun initComponents() {
        screenCaptureManager = ScreenCaptureManager(this)
        imageMatcher = ImageMatcher()
        textRecognizer = GameTextRecognizer()
    }

    private fun setupButtons() {
        btnRequestOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } else {
                toast("已有悬浮窗权限")
            }
        }

        btnOpenAccessibility.setOnClickListener {
            if (AutoClickService.instance == null) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } else {
                toast("无障碍服务已开启")
            }
        }

        btnRequestCapture.setOnClickListener {
            val captureServiceIntent = Intent(this, ScreenCaptureService::class.java)
            startForegroundService(captureServiceIntent)
            screenCaptureManager.requestPermissionForResult(screenCaptureLauncher)
        }

        btnRefreshConfigs.setOnClickListener {
            scanConfigFiles()
        }

        btnLoadConfig.setOnClickListener {
            loadTaskConfig()
        }

        btnStartTask.setOnClickListener {
            startTask()
        }

        btnStopTask.setOnClickListener {
            taskEngine?.stopTask()
            updateStatus("已停止")
        }
    }

    private fun scanConfigFiles() {
        val dir = getExternalFilesDir(null) ?: return
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                (dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).toList()
            }
            configFiles.clear()
            configFiles.addAll(files)

            if (configFiles.isEmpty()) {
                appendLog("请将配置文件放入：${dir.absolutePath}")
                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, listOf("（无配置文件）"))
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerConfig.adapter = adapter
                spinnerConfig.isEnabled = false
                btnLoadConfig.isEnabled = false
                return@launch
            }

            val displayNames = withContext(Dispatchers.IO) {
                configFiles.map { file ->
                    try {
                        val config = Gson().fromJson(file.readText(), TaskConfig::class.java)
                        config?.name?.takeIf { it.isNotBlank() } ?: file.name
                    } catch (e: Exception) {
                        file.name
                    }
                }
            }

            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, displayNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerConfig.adapter = adapter
            spinnerConfig.isEnabled = true
            btnLoadConfig.isEnabled = true
            appendLog("已找到 ${configFiles.size} 个配置文件")
        }
    }

    private fun loadTaskConfig() {
        if (configFiles.isEmpty()) {
            val dir = getExternalFilesDir(null)
            appendLog("没有找到配置文件，请将配置文件放入：${dir?.absolutePath}")
            toast("没有找到配置文件")
            return
        }

        val selectedIndex = spinnerConfig.selectedItemPosition
        if (selectedIndex < 0 || selectedIndex >= configFiles.size) {
            toast("请先选择配置文件")
            return
        }

        val configFile = configFiles[selectedIndex]
        try {
            val json = configFile.readText()
            currentTaskConfig = Gson().fromJson(json, TaskConfig::class.java)
            appendLog("已加载配置: ${currentTaskConfig?.name}")
            toast("配置加载成功")
        } catch (e: Exception) {
            appendLog("加载配置失败: ${e.message}")
            toast("加载配置失败")
        }
    }

    private fun startTask() {
        val config = currentTaskConfig
        if (config == null) {
            toast("请先加载任务配置")
            return
        }

        val clickService = AutoClickService.instance
        if (clickService == null) {
            toast("请先开启无障碍服务")
            return
        }

        if (!screenCaptureManager.isInitialized()) {
            toast("请先授权截屏权限")
            return
        }

        if (taskEngine == null) {
            taskEngine = TaskEngine(screenCaptureManager, imageMatcher, textRecognizer, clickService)
            taskEngine?.onStatusChanged = { status ->
                runOnUiThread { updateStatus(status) }
            }
            taskEngine?.onLogMessage = { message ->
                runOnUiThread { appendLog(message) }
            }
        }

        taskEngine?.startTask(config, lifecycleScope)
        updateStatus("运行中: ${config.name}")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }
        return metrics
    }

    private fun updateStatus(status: String) {
        tvStatus.text = "状态：$status"
    }

    private fun appendLog(message: String) {
        logBuilder.appendLine(message)
        tvLog.text = logBuilder.toString()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        taskEngine?.stopTask()
        textRecognizer.release()
        screenCaptureManager.release()
    }
}
