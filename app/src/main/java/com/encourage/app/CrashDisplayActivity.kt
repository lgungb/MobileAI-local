package com.encourage.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全局崩溃显示页面：应用发生未捕获异常时，不直接闪退，而是显示完整堆栈信息，
 * 方便用户截图反馈。用户可复制错误信息或重启应用。
 */
class CrashDisplayActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val errorInfo = intent.getStringExtra(EXTRA_ERROR_INFO) ?: "未知错误"
    setContent {
      MaterialTheme {
        CrashScreen(errorInfo = errorInfo)
      }
    }
  }

  companion object {
    const val EXTRA_ERROR_INFO = "error_info"

    fun start(context: Context, errorInfo: String) {
      val intent =
        Intent(context, CrashDisplayActivity::class.java).apply {
          putExtra(EXTRA_ERROR_INFO, errorInfo)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
      context.startActivity(intent)
    }
  }
}

@Composable
private fun CrashScreen(errorInfo: String) {
  val context = LocalContext.current
  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(Color(0xFF1A1A2E))
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = "应用崩溃了",
      color = Color(0xFFFF6B6B),
      fontSize = 22.sp,
    )
    Text(
      text = "请截图或复制下方信息发给开发者",
      color = Color(0xFFB0B0B0),
      fontSize = 14.sp,
    )
    // 设备信息
    val deviceInfo =
      "设备: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
        "应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
        "ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n" +
        "────────────────────\n"
    Text(
      text = deviceInfo + errorInfo,
      color = Color(0xFFE0E0E0),
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace,
      modifier =
        Modifier.weight(1f)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .background(Color(0xFF0D0D1A))
          .padding(12.dp),
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(
        onClick = {
          val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          clipboard.setPrimaryClip(ClipData.newPlainText("crash", deviceInfo + errorInfo))
          Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.weight(1f),
      ) {
        Text("复制错误信息")
      }
      Button(
        onClick = {
          val pm = context.packageManager
          val intent = pm.getLaunchIntentForPackage(context.packageName)
          context.startActivity(intent)
          (context as Activity).finishAffinity()
          Runtime.getRuntime().exit(0)
        },
        modifier = Modifier.weight(1f),
      ) {
        Text("重启应用")
      }
    }
  }
}
