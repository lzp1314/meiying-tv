package com.lik.iptvplayer

import android.app.Application
import com.lik.iptvplayer.core.Diag
import com.lik.iptvplayer.core.Network
import com.lik.iptvplayer.core.PlaybackPrefs
import com.lik.iptvplayer.core.SnapshotStore
import com.lik.iptvplayer.core.SourceRepository
import com.lik.iptvplayer.core.SourceStore

class App : Application() {
    lateinit var repository: SourceRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // 先装崩溃日志：低配盒子闪退时 adb 往往连不上，只能靠落盘的堆栈取证
        Diag.install(this)
        // 冷启动计时起点：四个阶段耗时显示在设置→诊断，用来定位「首次打开慢」卡在哪
        Diag.BootTiming.markStart()
        // 网络层要拿到 Context 才能加载内置 CA：老 Android 设备信任库缺 TrustAsia 新根，
        // 不做这步在线更新会直接报 Unacceptable certificate
        Network.init(this)
        // 源列表与播放在两份 SharedPreferences 里：覆盖安装（包名、签名不变）自动保留
        // 频道与节目单的离线快照放在 filesDir/snapshots，冷启动先读它，不必每次联网
        repository = SourceRepository(SourceStore(this), PlaybackPrefs(this), SnapshotStore(this))
    }
}
