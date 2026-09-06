package com.encourage.app

import android.util.Log

/**
 * 调试日志工具（简化版）。
 * 直接转发到 android.util.Log，不再收集日志或维护 StateFlow。
 */
object DebugLog {
    private const val TAG = "AGDebugLog"

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
    }

    fun fatal(tag: String, message: String) {
        Log.e(tag, "FATAL: $message")
    }

    fun setModelStatus(status: String) {
        Log.i("ModelStatus", status)
    }

    fun clear() {
        // no-op
    }

    fun getAllLogsText(): String {
        return ""
    }
}
