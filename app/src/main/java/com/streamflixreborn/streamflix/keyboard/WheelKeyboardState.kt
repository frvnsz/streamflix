package com.streamflixreborn.streamflix.keyboard

import kotlin.math.max
import kotlin.math.min

data class WheelKeyboardState(
    val text: String = "",
    val cursorPosition: Int = 0,
    val wheelIndex: Int = 0,
    val items: List<WheelKeyboardItem> = WheelKeyboardItem.defaultItems,
) {
    val currentItem: WheelKeyboardItem get() = items[wheelIndex.floorMod(items.size)]

    fun rotate(delta: Int): WheelKeyboardState = copy(wheelIndex = (wheelIndex + delta).floorMod(items.size))

    fun moveCursor(delta: Int): WheelKeyboardState {
        val nextCursor = (cursorPosition + delta).coerceIn(0, text.length)
        return copy(cursorPosition = nextCursor).syncWheelToCursorCharacter()
    }

    fun insert(value: String): WheelKeyboardState {
        val nextText = text.substring(0, cursorPosition) + value + text.substring(cursorPosition)
        return copy(text = nextText, cursorPosition = cursorPosition + value.length)
    }

    fun deleteBeforeCursor(): WheelKeyboardState {
        if (cursorPosition <= 0 || text.isEmpty()) return this
        val nextText = text.removeRange(cursorPosition - 1, cursorPosition)
        return copy(text = nextText, cursorPosition = max(0, cursorPosition - 1)).syncWheelToCursorCharacter()
    }

    fun clearText(): WheelKeyboardState = copy(text = "", cursorPosition = 0)

    fun withText(value: String): WheelKeyboardState = copy(text = value, cursorPosition = value.length).syncWheelToCursorCharacter()

    private fun syncWheelToCursorCharacter(): WheelKeyboardState {
        if (text.isEmpty()) return this
        val characterIndex = min(cursorPosition, text.lastIndex)
        val char = text[characterIndex].uppercaseChar()
        val matchingIndex = items.indexOfFirst {
            when (it) {
                is WheelKeyboardItem.Letter -> it.value == char
                is WheelKeyboardItem.Digit -> it.value == char
                WheelKeyboardItem.Space -> char == ' '
                else -> false
            }
        }
        return if (matchingIndex >= 0) copy(wheelIndex = matchingIndex) else this
    }
}

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other
