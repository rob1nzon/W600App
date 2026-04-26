package com.w600.glasses.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(val ts: String, val level: Char, val tag: String, val msg: String)

object AppLogger {
    private const val MAX = 500
    private val entries = ArrayDeque<LogEntry>(MAX)
    private val _flow = MutableStateFlow<List<LogEntry>>(emptyList())
    val flow: StateFlow<List<LogEntry>> = _flow

    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized fun d(tag: String, msg: String) { Log.d(tag, msg); add('D', tag, msg) }
    @Synchronized fun i(tag: String, msg: String) { Log.i(tag, msg); add('I', tag, msg) }
    @Synchronized fun w(tag: String, msg: String) { Log.w(tag, msg); add('W', tag, msg) }
    @Synchronized fun e(tag: String, msg: String) { Log.e(tag, msg); add('E', tag, msg) }
    @Synchronized fun e(tag: String, msg: String, t: Throwable) { Log.e(tag, msg, t); add('E', tag, "$msg: ${t.message}") }

    private fun add(level: Char, tag: String, msg: String) {
        if (entries.size >= MAX) entries.removeFirst()
        entries.addLast(LogEntry(sdf.format(Date()), level, tag, msg))
        _flow.value = entries.toList()
    }

    @Synchronized fun clear() { entries.clear(); _flow.value = emptyList() }

    @Synchronized fun all(): String =
        entries.joinToString("\n") { "${it.ts} ${it.level}/${it.tag}: ${it.msg}" }
}
