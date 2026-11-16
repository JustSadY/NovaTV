package com.novatv.player.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatting helpers. Must be used from the main thread (SimpleDateFormat is not thread-safe). */
object TimeFmt {
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayHm = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
    private val weekdayHm = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

    fun hm(millis: Long): String = hm.format(Date(millis))

    fun dateTime(millis: Long): String = dayHm.format(Date(millis))

    fun weekdayTime(millis: Long): String = weekdayHm.format(Date(millis))

    fun now(): String = hm(System.currentTimeMillis())
}
