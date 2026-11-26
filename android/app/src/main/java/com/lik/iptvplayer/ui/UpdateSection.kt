package com.lik.iptvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lik.iptvplayer.core.ApkInstaller
import com.lik.iptvplayer.core.Trust
import com.lik.iptvplayer.core.UpdateChecker
import com.lik.iptvplayer.core.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 设置里的「在线更新」一块，TV 与手机共用。
 *
 * 发布方式：把 APK 和 update.json 一起放到 Gitee 仓库，设置里填 update.json 的 raw 直链。
 * 检查 → 下载 → 交给系统安装器，全程不跳浏览器。
 */
@Composable
internal fun UpdateSection(
    isTv: Boolean,
    url: String,
    onUrlChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var progress by remember { mutableStateOf(-1f) }
    var editing by remember(url) { mutableStateOf(url) }
    // 证书兜底状态：检查一次后把结果同步上来，出问题时能直接看到是哪一环
    var certStatus by remember { mutableStateOf(Trust.status) }
    // 这次清单是从哪个地址拿到的。多镜像之后必须能看到这个，否则出问题只能靠猜
    var lastSource by remember { mutableStateOf("") }

    val labelSize = if (isTv) 15.sp else 13.sp
    val bodySize = if (isTv) 13.sp else 11.sp

    val mine = remember {
        @Suppress("DEPRECATION")
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    @Suppress("DEPRECATION")
    val myCode = mine?.versionCode ?: 0
    val myName = mine?.versionName ?: "?"

    fun check() {
        if (busy) return
        scope.launch {
            busy = true
            info = null
            progress = -1f
            status = "正在检查…"
            runCatching { UpdateChecker.check(editing) }
                .onSuccess { r ->
                    val i = r.info
                    if (i.versionCode > myCode) {
                        info = i
                        status = buildString {
                            append("发现新版本 v${i.versionName}（当前 v$myName）")
                            if (r.skipped > 0) append(" · 跳过 ${r.skipped} 个不可用地址")
                            if (!r.verified) append(" · 证书已降级，下载后会校验哈希")
                        }
                    } else {
                        status = "已是最新：v$myName"
                    }
                    lastSource = r.source
                }
                .onFailure { e ->
                    status = "检查失败：${e.message ?: e.javaClass.simpleName}"
                }
            certStatus = Trust.status
            busy = false
        }
    }

    fun downloadAndInstall(i: UpdateInfo) {
        if (busy) return
        scope.launch {
            busy = true
            progress = 0f
            status = "正在下载 ${i.versionName}…"
            val dest = File(ApkInstaller.dir(context), "iptv-${i.versionCode}.apk")
            runCatching {
                UpdateChecker.download(
                    apkUrl = i.apkUrl,
                    dest = dest,
                    sha256 = i.sha256,
                    fallbacks = i.apkFallbacks,
                ) { p ->
                    // 回调在 IO 线程，切回主线程再写状态
                    scope.launch(Dispatchers.Main) { progress = p }
                }
            }.onSuccess { apk ->
                progress = 1f
                // 先验签名再调安装器：证书降级通道下，这是最后一道「包没被换过」的关卡。
                // 只有确凿的不一致才拦；本机读不出签名（老定制固件常见）交给系统安装器判决。
                val sig = ApkInstaller.checkSignature(context, apk)
                if (sig == ApkInstaller.SigCheck.MISMATCH) {
                    status = "安装包签名与本机不一致，已拒绝安装（可能被篡改，或下错了包）"
                } else if (!ApkInstaller.canInstall(context)) {
                    status = "还需要允许「安装未知应用」：已打开系统设置，允许后回来再点一次下载"
                    if (!ApkInstaller.openUnknownSourcesSettings(context)) {
                        status = "请到系统设置里允许本应用安装未知应用，再重试"
                    }
                } else if (!ApkInstaller.install(context, apk)) {
                    status = "系统安装器没能打开。安装包已存到：${apk.absolutePath}"
                } else if (sig == ApkInstaller.SigCheck.UNKNOWN) {
                    status = "本机读不出包签名，已交给系统安装器判断（下载哈希已核对过）"
                } else {
                    status = "已调起安装器，按提示覆盖安装即可（数据会保留）"
                }
            }.onFailure { e ->
                status = "下载失败：${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("在线更新", color = Color.White, fontSize = labelSize, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text("当前 v$myName", color = TextDim, fontSize = bodySize)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { check() }, enabled = !busy) {
                Text(if (busy) "请稍候" else "检查更新", fontSize = bodySize)
            }
            if (progress >= 0f && progress < 1f) {
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier.width(if (isTv) 220.dp else 120.dp).height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E293B)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(8.dp)
                            .background(Accent, RoundedCornerShape(4.dp)),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("${(progress * 100).toInt()}%", color = TextDim, fontSize = bodySize)
            }
        }
        if (status.isNotBlank()) {
            Text(
                status,
                color = if (status.startsWith("检查失败") || status.startsWith("下载失败")) {
                    Color(0xFFF87171)
                } else {
                    Accent
                },
                fontSize = bodySize,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // 清单到底是从哪个地址拿到的：多镜像之后，这行是排障第一现场
        if (lastSource.isNotBlank()) {
            Text(
                "清单来源：" + lastSource,
                color = Color(0xFF64748B),
                fontSize = if (isTv) 11.sp else 9.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        info?.let { i ->
            Column(
                Modifier.padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF16223A))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "新版本 v${i.versionName}",
                    color = Color.White, fontSize = labelSize, fontWeight = FontWeight.SemiBold,
                )
                if (i.notes.isNotBlank()) {
                    Text(i.notes, color = TextDim, fontSize = bodySize)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { downloadAndInstall(i) }, enabled = !busy) {
                        Text("下载并安装", fontSize = bodySize)
                    }
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = { info = null; status = "" }) {
                        Text("以后再说", fontSize = bodySize)
                    }
                }
            }
        }

        // 地址：电视上没有键盘，只显示；手机可以改（换仓库、换分支时不用重装）
        if (isTv) {
            Text(
                "更新地址：" + editing.ifBlank { "未配置（请用手机端填写）" },
                color = Color(0xFF64748B), fontSize = if (isTv) 11.sp else 9.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            OutlinedTextField(
                value = editing,
                onValueChange = {
                    editing = it
                    onUrlChange(it)
                },
                label = { Text("更新地址（update.json 直链）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        // 现场诊断：兜底没生效时，这行会写明是系统 CA 拷贝失败还是内置证书加载失败
        Text(
            "证书兜底：" + certStatus,
            color = if (certStatus.startsWith("失败")) Color(0xFFF87171) else Color(0xFF64748B),
            fontSize = if (isTv) 11.sp else 9.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
