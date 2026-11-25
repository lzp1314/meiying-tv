package com.lik.iptvplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 真全屏：隐藏状态栏与导航栏，手机端同时切到横屏。
 *
 * 前提：对应 Activity 必须在 Manifest 声明 configChanges（orientation|screenSize 等），
 * 否则旋转会重建 Activity，播放器随 onDispose 释放，表现为「切全屏后黑屏、回到 0 秒」。
 * 这里只改窗口状态，不碰播放器实例，播放不中断。
 */
@Composable
fun FullscreenEffect(enabled: Boolean, landscape: Boolean) {
    val view = LocalView.current
    val activity = view.context as? Activity ?: return

    DisposableEffect(enabled, landscape) {
        val window = activity.window
        val bars = WindowInsetsControllerCompat(window, view)
        if (enabled) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            if (landscape) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            bars.show(WindowInsetsCompat.Type.systemBars())
            if (landscape) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose {
            // 离开组合（如 Activity 销毁）时恢复系统栏，避免把状态栏「带走」
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
