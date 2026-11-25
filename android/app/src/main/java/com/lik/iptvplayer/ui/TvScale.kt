package com.lik.iptvplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * TV 端屏幕自适应。
 *
 * 问题：电视从 32 寸到 100 寸，系统上报的 dp 宽度能差 4 倍（720p 盒子常见 1280dp，
 * 1080p 常见 960dp，4K 有的报 1920dp）。界面里写死的 26sp / 48dp 在这几种屏上
 * 视觉占比完全不同——小屏字挤在一起，大屏字小得看不清。
 *
 * 做法：以 960dp 为设计基准等比缩放 Compose 的 Density。
 * density 一改，dp 与 sp 同时按同一系数放大/缩小，
 * 于是「字占屏宽的比例」在所有尺寸上都一致，不需要给每个控件写两套尺寸。
 *
 * 只影响 Compose 内部换算（Configuration 里的 screenWidthDp 是系统值，不会被反过来影响），
 * fillMaxWidth(0.28f) 这类比例约束本来就是按父容器算的，不受影响。
 */
@Composable
fun TvScale(baseDp: Float = 960f, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val k = if (widthDp > 0) (widthDp / baseDp).coerceIn(0.8f, 2.4f) else 1f
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * k, base.fontScale),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}
