package com.example.browndustbot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TaskEngine(
    private val screenCaptureManager: ScreenCaptureManager,
    private val imageMatcher: ImageMatcher,
    private val textRecognizer: GameTextRecognizer,
    private val clickService: AutoClickService
) {

    companion object {
        private const val TAG = "TaskEngine"
        private const val TEXT_PREVIEW_BLOCK_COUNT = 5
        var instance: TaskEngine? = null
            private set
    }

    private val variables = mutableMapOf<String, Any>()
    private var currentJob: Job? = null
    private var _isRunning = false
    private var _isPaused = false
    private var currentForegroundApp = ""

    var onStatusChanged: ((String) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null

    init {
        instance = this
    }

    fun onForegroundAppChanged(packageName: String) {
        currentForegroundApp = packageName
    }

    fun startTask(config: TaskConfig, scope: CoroutineScope) {
        if (_isRunning) {
            log("Task already running")
            return
        }
        _isRunning = true
        _isPaused = false
        updateStatus("运行中: ${config.name}")

        currentJob = scope.launch(Dispatchers.IO) {
            try {
                val loopCount = if (config.loopCount <= 0) Int.MAX_VALUE else config.loopCount
                for (loop in 0 until loopCount) {
                    if (!_isRunning) break
                    log("开始第 ${loop + 1} 轮")
                    executeSteps(config.steps)
                    if (loop < loopCount - 1 && _isRunning) {
                        delay(config.loopDelayMs)
                    }
                }
                log("任务完成")
                updateStatus("完成")
            } catch (e: CancellationException) {
                log("任务已取消")
                updateStatus("已停止")
            } catch (e: Exception) {
                log("任务出错: ${e.message}")
                updateStatus("出错")
            } finally {
                _isRunning = false
            }
        }
    }

    fun stopTask() {
        _isRunning = false
        _isPaused = false
        currentJob?.cancel()
        currentJob = null
        updateStatus("已停止")
        log("任务已停止")
    }

    fun pauseTask() {
        _isPaused = true
        updateStatus("已暂停")
        log("任务已暂停")
    }

    fun resumeTask() {
        _isPaused = false
        updateStatus("运行中")
        log("任务已继续")
    }

    private suspend fun executeSteps(steps: List<TaskStep>) {
        for (step in steps) {
            if (!_isRunning) return
            while (_isPaused && _isRunning) {
                delay(500)
            }
            if (!_isRunning) return
            executeStep(step)
        }
    }

    private suspend fun executeStep(step: TaskStep) {
        log("执行步骤: ${step.name}")
        delay(step.preDelayMs)

        val screenshot = screenCaptureManager.captureScreenWithRetry()
        if (screenshot == null) {
            log("截屏失败（多次重试后仍失败），跳过步骤: ${step.name}")
            return
        }

        when (step.action) {
            ActionType.WAIT -> {
                screenshot.recycle()
                delay(step.waitTimeoutMs)
                return
            }
            ActionType.READ_TEXT -> {
                executeReadText(step, screenshot)
                return
            }
            ActionType.CONDITION_CHECK -> {
                executeConditionCheck(step, screenshot)
                return
            }
            else -> {}
        }

        val (found, clickX, clickY) = findTarget(step, screenshot)

        if (!found) {
            if (!step.optional) {
                log("未找到目标: ${step.name}")
            }
            screenshot.recycle()
            return
        }

        screenshot.recycle()

        val targetX = (clickX + step.offsetX).toFloat()
        val targetY = (clickY + step.offsetY).toFloat()

        when (step.action) {
            ActionType.CLICK -> {
                clickService.performClick(targetX, targetY)
                log("点击: ($targetX, $targetY)")
            }
            ActionType.LONG_CLICK -> {
                clickService.performLongClick(targetX, targetY, step.longClickDurationMs)
                log("长按: ($targetX, $targetY)")
            }
            ActionType.SWIPE -> {
                clickService.performSwipe(
                    targetX, targetY,
                    step.swipeEndX.toFloat(), step.swipeEndY.toFloat(),
                    step.swipeDurationMs
                )
                log("滑动: ($targetX, $targetY) -> (${step.swipeEndX}, ${step.swipeEndY})")
            }
            ActionType.WAIT_DISAPPEAR -> {
                val timeout = System.currentTimeMillis() + step.waitTimeoutMs
                while (System.currentTimeMillis() < timeout && _isRunning) {
                    delay(500)
                    val newScreen = screenCaptureManager.captureScreenAsync() ?: break
                    val (stillFound, _, _) = findTarget(step, newScreen)
                    newScreen.recycle()
                    if (!stillFound) {
                        log("目标已消失: ${step.name}")
                        break
                    }
                }
            }
            else -> {}
        }

        delay(step.postDelayMs)
    }

    private suspend fun findTarget(step: TaskStep, screenshot: Bitmap): Triple<Boolean, Int, Int> {
        return when (step.matchType) {
            MatchType.IMAGE -> findByImage(step, screenshot)
            MatchType.TEXT -> findByText(step, screenshot)
            MatchType.IMAGE_OR_TEXT -> {
                val imageResult = findByImage(step, screenshot)
                if (imageResult.first) imageResult
                else findByText(step, screenshot)
            }
            MatchType.IMAGE_AND_TEXT -> {
                val imageResult = findByImage(step, screenshot)
                if (!imageResult.first) return Triple(false, 0, 0)
                val textResult = findByText(step, screenshot)
                if (!textResult.first) return Triple(false, 0, 0)
                imageResult
            }
        }
    }

    private fun findByImage(step: TaskStep, screenshot: Bitmap): Triple<Boolean, Int, Int> {
        val templatePath = step.templateImagePath ?: return Triple(false, 0, 0)
        val templateBitmap = BitmapFactory.decodeFile(templatePath) ?: return Triple(false, 0, 0)
        return try {
            val result = imageMatcher.findTemplate(screenshot, templateBitmap, step.imageThreshold)
            log("图像匹配 [${step.name}]: confidence=${result.confidence}, found=${result.found}")
            if (result.found) Triple(true, result.centerX, result.centerY)
            else Triple(false, 0, 0)
        } finally {
            templateBitmap.recycle()
        }
    }

    private suspend fun findByText(step: TaskStep, screenshot: Bitmap): Triple<Boolean, Int, Int> {
        val config = step.textConfig ?: return Triple(false, 0, 0)
        val searchRegion = config.searchRegion?.toRect()

        val (allBlocks, offsetX, offsetY) = if (searchRegion != null) {
            val left = searchRegion.left.coerceIn(0, screenshot.width)
            val top = searchRegion.top.coerceIn(0, screenshot.height)
            val right = searchRegion.right.coerceIn(0, screenshot.width)
            val bottom = searchRegion.bottom.coerceIn(0, screenshot.height)
            val croppedBitmap = try {
                Bitmap.createBitmap(screenshot, left, top, right - left, bottom - top)
            } catch (e: Exception) { null }
            if (croppedBitmap != null) {
                val blocks = textRecognizer.recognizeAll(croppedBitmap, config.useChinese)
                croppedBitmap.recycle()
                Triple(blocks, left, top)
            } else {
                Triple(emptyList(), 0, 0)
            }
        } else {
            Triple(textRecognizer.recognizeAll(screenshot, config.useChinese), 0, 0)
        }

        val preview = allBlocks.take(TEXT_PREVIEW_BLOCK_COUNT).map { it.text }
        log("文字识别 [${step.name}]: 识别到文字=$preview")

        val result = textRecognizer.findTextFromBlocks(allBlocks, config.targetText, config.matchMode)

        return if (result.found && config.clickOnText) {
            Triple(true, result.centerX + offsetX, result.centerY + offsetY)
        } else if (result.found) {
            Triple(true, 0, 0)
        } else {
            Triple(false, 0, 0)
        }
    }

    private suspend fun executeReadText(step: TaskStep, screenshot: Bitmap) {
        val region = step.conditionRegion?.toRect() ?: Rect(0, 0, screenshot.width, screenshot.height)
        val text = textRecognizer.readTextInRegion(screenshot, region)
        step.variableName?.let { variables[it] = text }
        log("读取文字 [${step.variableName}]: $text")
        screenshot.recycle()
    }

    private suspend fun executeConditionCheck(step: TaskStep, screenshot: Bitmap) {
        val region = step.conditionRegion?.toRect() ?: Rect(0, 0, screenshot.width, screenshot.height)

        if (step.conditionMinValue != null) {
            val number = textRecognizer.readNumberInRegion(screenshot, region)
            step.variableName?.let { variables[it] = number ?: 0 }
            val conditionMet = number != null && number >= step.conditionMinValue
            log("条件检查 [${step.variableName}=${number}]: ${if (conditionMet) "满足" else "不满足"}")
        } else if (step.conditionRegex != null) {
            val text = textRecognizer.readTextInRegion(screenshot, region)
            val conditionMet = text.contains(Regex(step.conditionRegex))
            step.variableName?.let { variables[it] = conditionMet }
            log("条件检查 [${step.variableName}=${conditionMet}]: $text")
        }

        screenshot.recycle()
    }

    private fun updateStatus(status: String) {
        onStatusChanged?.invoke(status)
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        onLogMessage?.invoke(message)
    }

    fun getVariable(name: String): Any? = variables[name]
    fun setVariable(name: String, value: Any) { variables[name] = value }
    fun isRunning() = _isRunning
    fun isPaused() = _isPaused
}
