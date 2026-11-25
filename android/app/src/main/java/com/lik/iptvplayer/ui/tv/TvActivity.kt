package com.lik.iptvplayer.ui.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.lik.iptvplayer.App
import com.lik.iptvplayer.ui.KeyBridge
import com.lik.iptvplayer.ui.PlayerScreen
import com.lik.iptvplayer.ui.TvScale

/**
 * TV 端：进应用即全屏播放；遥控器 OK 键出设置，上下键 / CH+ CH- 换台。
 * 按键在 dispatchKeyEvent 里先拦（早于焦点视图），面板打开时让位给 Compose 焦点系统。
 */
class TvActivity : ComponentActivity() {
    private val keys = KeyBridge()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 只处理按下瞬间，按住不连发，避免一次按键跳好几个台
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (keys.onKey?.invoke(event.keyCode) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 32 寸到 100 寸统一按 960dp 基准等比缩放，字号/间距在不同电视上视觉占比一致
            TvScale {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    PlayerScreen(
                        repo = (application as App).repository,
                        isTv = true,
                        keys = keys,
                    )
                }
            }
        }
    }
}
