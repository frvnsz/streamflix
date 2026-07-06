package com.streamflixreborn.streamflix.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class WheelKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onTextChanged: ((String) -> Unit)? = null
    var onSearch: ((String) -> Unit)? = null
    var onDone: ((String) -> Unit)? = null
    var onVoiceSearch: (() -> Unit)? = null
    var hintText: String = "Search movies, TV shows"
        set(value) {
            field = value
            invalidate()
        }

    private var state = WheelKeyboardState()
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatDirection = 0
    private var repeatStartedAt = 0L

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f * resources.displayMetrics.scaledDensity
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = 24f * resources.displayMetrics.scaledDensity
    }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 22f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val selectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE50914.toInt() }
    private val itemFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE50914.toInt()
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (repeatDirection == 0) return
            rotate(repeatDirection)
            repeatHandler.postDelayed(this, currentRepeatDelay())
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setWillNotDraw(false)
    }

    fun setText(value: String) {
        state = state.withText(value)
        onTextChanged?.invoke(state.text)
        invalidate()
    }

    fun getText(): String = state.text

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                startOrReverseRepeat(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                startOrReverseRepeat(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                stopRepeat()
                updateState(state.moveCursor(-1), notifyText = false)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                stopRepeat()
                updateState(state.moveCursor(1), notifyText = false)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                stopRepeat()
                activateCurrentItem()
                true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                onSearch?.invoke(state.text)
                true
            }
            KeyEvent.KEYCODE_DEL -> {
                updateState(state.deleteBeforeCursor())
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            stopRepeat()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun activateCurrentItem() {
        when (val item = state.currentItem) {
            is WheelKeyboardItem.Letter -> updateState(state.insert(item.value.toString()))
            is WheelKeyboardItem.Digit -> updateState(state.insert(item.value.toString()))
            WheelKeyboardItem.Space -> updateState(state.insert(" "))
            WheelKeyboardItem.Delete -> updateState(state.deleteBeforeCursor())
            WheelKeyboardItem.Search -> onSearch?.invoke(state.text)
            WheelKeyboardItem.VoiceSearch -> onVoiceSearch?.invoke()
            WheelKeyboardItem.Done -> onDone?.invoke(state.text)
            WheelKeyboardItem.ClearText -> updateState(state.clearText())
        }
    }

    private fun startOrReverseRepeat(direction: Int) {
        if (repeatDirection != direction) {
            repeatDirection = direction
            repeatStartedAt = SystemClock.uptimeMillis()
            repeatHandler.removeCallbacks(repeatRunnable)
            rotate(direction)
            repeatHandler.postDelayed(repeatRunnable, currentRepeatDelay())
        }
    }

    private fun stopRepeat() {
        repeatDirection = 0
        repeatHandler.removeCallbacks(repeatRunnable)
    }

    private fun currentRepeatDelay(): Long {
        val heldFor = SystemClock.uptimeMillis() - repeatStartedAt
        return when {
            heldFor < 400L -> 320L
            heldFor < 1_200L -> 140L
            else -> 65L
        }
    }

    private fun rotate(direction: Int) = updateState(state.rotate(direction), notifyText = false)

    private fun updateState(next: WheelKeyboardState, notifyText: Boolean = true) {
        val changedText = next.text != state.text
        state = next
        if (notifyText && changedText) onTextChanged?.invoke(state.text)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = 24f * resources.displayMetrics.density
        val fieldTop = padding
        val baseline = fieldTop + 44f * resources.displayMetrics.density
        val textStart = padding

        if (state.text.isEmpty()) {
            canvas.drawText(hintText, textStart, baseline, hintPaint)
        } else {
            canvas.drawText(state.text, textStart, baseline, textPaint)
        }
        val cursorText = state.text.take(state.cursorPosition)
        val cursorX = textStart + textPaint.measureText(cursorText)
        canvas.drawLine(cursorX, fieldTop + 8f, cursorX, baseline + 8f, cursorPaint)

        val centerY = height * 0.67f
        val itemWidth = max(88f * resources.displayMetrics.density, (width - padding * 2) / 9f)
        val itemHeight = 56f * resources.displayMetrics.density
        for (offset in -4..4) {
            val index = (state.wheelIndex + offset).floorMod(state.items.size)
            val item = state.items[index]
            val centerX = width / 2f + offset * itemWidth
            val alpha = (255 - abs(offset) * 42).coerceIn(80, 255)
            val rect = RectF(centerX - itemWidth * .45f, centerY - itemHeight / 2f, centerX + itemWidth * .45f, centerY + itemHeight / 2f)
            if (offset == 0) canvas.drawRoundRect(rect, 18f, 18f, selectedFill) else canvas.drawRoundRect(rect, 18f, 18f, itemFill)
            itemPaint.color = if (offset == 0) Color.WHITE else Color.argb(alpha, 255, 255, 255)
            canvas.drawText(item.label, centerX, centerY + itemPaint.textSize / 3f, itemPaint)
        }

        canvas.drawText("Up/Down rotate • Left/Right move cursor • OK selects", padding, height - padding, hintPaint)
    }
}

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other
