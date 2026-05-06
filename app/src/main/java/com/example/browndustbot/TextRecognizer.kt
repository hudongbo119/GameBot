package com.example.browndustbot

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class TextBlock(
    val text: String,
    val boundingBox: Rect?,
    val centerX: Int,
    val centerY: Int,
    val confidence: Float = 0f,
    val language: String = ""
)

data class TextMatchResult(
    val found: Boolean,
    val matchedText: String = "",
    val centerX: Int = 0,
    val centerY: Int = 0,
    val boundingBox: Rect? = null,
    val allMatches: List<TextBlock> = emptyList()
)

class GameTextRecognizer {

    companion object {
        private const val TAG = "GameTextRecognizer"
    }

    private val chineseRecognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    private val latinRecognizer: TextRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    suspend fun recognizeAll(
        bitmap: Bitmap,
        useChinese: Boolean = true
    ): List<TextBlock> {
        val recognizer = if (useChinese) chineseRecognizer else latinRecognizer
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val result = recognizer.process(image).await()
            val blocks = mutableListOf<TextBlock>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox
                    val centerX = box?.centerX() ?: 0
                    val centerY = box?.centerY() ?: 0
                    blocks.add(
                        TextBlock(
                            text = line.text,
                            boundingBox = box,
                            centerX = centerX,
                            centerY = centerY
                        )
                    )
                    for (element in line.elements) {
                        val elemBox = element.boundingBox
                        blocks.add(
                            TextBlock(
                                text = element.text,
                                boundingBox = elemBox,
                                centerX = elemBox?.centerX() ?: 0,
                                centerY = elemBox?.centerY() ?: 0
                            )
                        )
                    }
                }
            }
            blocks
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing text", e)
            emptyList()
        }
    }

    fun findTextFromBlocks(
        blocks: List<TextBlock>,
        targetText: String,
        matchMode: TextMatchMode = TextMatchMode.CONTAINS
    ): TextMatchResult {
        val matches = blocks.filter { block -> matchesText(block.text, targetText, matchMode) }
        return if (matches.isNotEmpty()) {
            val best = matches.first()
            TextMatchResult(
                found = true,
                matchedText = best.text,
                centerX = best.centerX,
                centerY = best.centerY,
                boundingBox = best.boundingBox,
                allMatches = matches
            )
        } else {
            TextMatchResult(found = false, allMatches = emptyList())
        }
    }

    suspend fun findText(
        bitmap: Bitmap,
        targetText: String,
        matchMode: TextMatchMode = TextMatchMode.CONTAINS,
        useChinese: Boolean = true
    ): TextMatchResult {
        val blocks = recognizeAll(bitmap, useChinese)
        val matches = blocks.filter { block ->
            matchesText(block.text, targetText, matchMode)
        }
        return if (matches.isNotEmpty()) {
            val best = matches.first()
            TextMatchResult(
                found = true,
                matchedText = best.text,
                centerX = best.centerX,
                centerY = best.centerY,
                boundingBox = best.boundingBox,
                allMatches = matches
            )
        } else {
            TextMatchResult(found = false, allMatches = emptyList())
        }
    }

    suspend fun findTextInRegion(
        bitmap: Bitmap,
        targetText: String,
        searchRegion: Rect,
        matchMode: TextMatchMode = TextMatchMode.CONTAINS,
        useChinese: Boolean = true
    ): TextMatchResult {
        val croppedBitmap = cropBitmap(bitmap, searchRegion) ?: return TextMatchResult(found = false)
        val result = findText(croppedBitmap, targetText, matchMode, useChinese)
        croppedBitmap.recycle()
        return if (result.found) {
            result.copy(
                centerX = result.centerX + searchRegion.left,
                centerY = result.centerY + searchRegion.top,
                boundingBox = result.boundingBox?.let {
                    Rect(
                        it.left + searchRegion.left,
                        it.top + searchRegion.top,
                        it.right + searchRegion.left,
                        it.bottom + searchRegion.top
                    )
                }
            )
        } else {
            result
        }
    }

    suspend fun readTextInRegion(
        bitmap: Bitmap,
        region: Rect,
        useChinese: Boolean = true
    ): String {
        val croppedBitmap = cropBitmap(bitmap, region) ?: return ""
        val recognizer = if (useChinese) chineseRecognizer else latinRecognizer
        val image = InputImage.fromBitmap(croppedBitmap, 0)
        return try {
            val result = recognizer.process(image).await()
            croppedBitmap.recycle()
            result.text
        } catch (e: Exception) {
            Log.e(TAG, "Error reading text in region", e)
            croppedBitmap.recycle()
            ""
        }
    }

    suspend fun readNumberInRegion(bitmap: Bitmap, region: Rect): Int? {
        val text = readTextInRegion(bitmap, region, useChinese = false)
        val numberRegex = Regex("""\d+""")
        return numberRegex.find(text)?.value?.toIntOrNull()
    }

    private fun matchesText(text: String, target: String, mode: TextMatchMode): Boolean {
        return when (mode) {
            TextMatchMode.EXACT -> text == target
            TextMatchMode.CONTAINS -> text.contains(target)
            TextMatchMode.REGEX -> text.matches(Regex(target))
            TextMatchMode.STARTS_WITH -> text.startsWith(target)
            TextMatchMode.ENDS_WITH -> text.endsWith(target)
        }
    }

    private fun cropBitmap(bitmap: Bitmap, region: Rect): Bitmap? {
        return try {
            val left = region.left.coerceIn(0, bitmap.width)
            val top = region.top.coerceIn(0, bitmap.height)
            val right = region.right.coerceIn(0, bitmap.width)
            val bottom = region.bottom.coerceIn(0, bitmap.height)
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return null
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping bitmap", e)
            null
        }
    }

    fun release() {
        chineseRecognizer.close()
        latinRecognizer.close()
    }
}
