package com.lik.iptvplayer.ui.mobile

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.lik.iptvplayer.App
import com.lik.iptvplayer.ui.KeyBridge
import com.lik.iptvplayer.ui.PlayerScreen

/**
 * 手机端：进应用即全屏横屏播放，点一下屏幕出设置。
 * 也接一份按键桥，方便外接遥控器 / 蓝牙手柄直接换台。
 */
class MobileActivity : ComponentActivity() {
    private val keys = KeyBridge()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (keys.onKey?.invoke(event.keyCode) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen(
                    repo = (application as App).repository,
                    isTv = false,
                    keys = keys,
                )
            }
        }
    }
}
