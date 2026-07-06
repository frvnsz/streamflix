package com.streamflixreborn.streamflix.keyboard

sealed class WheelKeyboardItem(val label: String) {
    data class Letter(val value: Char) : WheelKeyboardItem(value.toString())
    data class Digit(val value: Char) : WheelKeyboardItem(value.toString())
    data object Space : WheelKeyboardItem("Space")
    data object Delete : WheelKeyboardItem("Delete")
    data object Search : WheelKeyboardItem("Search")
    data object VoiceSearch : WheelKeyboardItem("Voice")
    data object Done : WheelKeyboardItem("Done")
    data object ClearText : WheelKeyboardItem("Clear")

    companion object {
        val defaultItems: List<WheelKeyboardItem> =
            ('A'..'Z').map { Letter(it) } +
                ('0'..'9').map { Digit(it) } +
                listOf(Space, Delete, Search, VoiceSearch, Done, ClearText)
    }
}
