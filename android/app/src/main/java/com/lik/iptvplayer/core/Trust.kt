package com.lik.iptvplayer.core

import android.content.Context
import com.lik.iptvplayer.R
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 老设备的证书信任兜底。
 *
 * 问题背景：Gitee 已把根证书换成 TrustAsia 体系，链为
 * `*.gitee.com` ← `TrustAsia DV TLS RSA CA 2024` ← `TrustAsia TLS RSA Root CA`
 * ← `Certum Trusted Network CA`（自签名根，2008 年签发）。
 * Android 7–9 的运营商定制盒子（如 HG680-GC）系统信任库出厂冻结，不认这条链，
 * 访问时报 `Unacceptable certificate`，在线更新直接不可用。
 *
 * 做法：把 **自签名根 Certum** 和 **交叉根 TrustAsia** 都打包进 res/raw，
 * 与系统 CA 一起塞进同一个 KeyStore，用 TrustManagerFactory 一次性初始化。
 *
 * 为什么是「合并 KeyStore」而不是「两个 TrustManager 链式调用」：
 *  1) 链式调用依赖「系统先抛异常才会转内置」这条隐式契约，且拿到的 TM 不是
 *     Android 原生的 X509TrustManagerImpl，OkHttp 的链清理会退回 Basic 实现；
 *  2) 合并后只有一个原生 X509TrustManagerImpl，整条链在同一个信任库里找锚点，
 *     走的就是系统原生校验路径，没有边界情况。
 *
 * 安全性：只追加这两张正规 CA（Certum / TrustAsia，均属公开可信体系），
 * 不是 `TrustAllCerts`，伪造或自签证书依然会被拒绝。
 */
object Trust {

    /** 自签名根：优先用它，链能完整走到根，最标准。 */
    private const val CA_CERTUM = R.raw.certum_trusted_network_ca

    /** 交叉根：Gitee 若改用非交叉链时兜底。 */
    private const val CA_TRUSTASIA = R.raw.trustasia_tls_rsa_root_ca

    /** 最近一次初始化结果，给设置页做现场诊断（失败时能直接看出原因）。 */
    @Volatile
    var status: String = "未初始化"
        private set

    fun sslSocketFactory(context: Context): Pair<SSLSocketFactory, X509TrustManager> {
        val tm = mergedTrustManager(context)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), null)
        return ctx.socketFactory to tm
    }

    private fun mergedTrustManager(context: Context): X509TrustManager {
        val merged = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

        // 1. 拷贝系统 CA（AndroidCAStore 只读，必须先复制到可写库）
        var systemCount = 0
        runCatching {
            val system = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            for (alias in system.aliases()) {
                val cert = system.getCertificate(alias) ?: continue
                merged.setCertificateEntry("sys-$systemCount", cert)
                systemCount++
            }
        }

        // 2. 追加内置 CA：任何一张加载失败都不影响另一张
        val cf = CertificateFactory.getInstance("X.509")
        var extraCount = 0
        for ((alias, resId) in listOf(
            "extra-certum-root" to CA_CERTUM,
            "extra-trustasia-cross" to CA_TRUSTASIA,
        )) {
            runCatching {
                context.resources.openRawResource(resId).use { input ->
                    merged.setCertificateEntry(alias, cf.generateCertificate(input))
                }
                extraCount++
            }
        }

        // 3. 一次性初始化，拿到的是 Android 原生 X509TrustManagerImpl
        val tmf = TrustManagerFactory.getInstance("X509")
        tmf.init(merged)
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()

        status = "已启用（系统 CA $systemCount 张 + 内置 $extraCount 张）"
        return tm
    }

    /** 由调用方在兜底流程整体失败时回填原因，方便上屏。 */
    fun markFailed(error: Throwable) {
        status = "失败：" + (error.message ?: error.javaClass.simpleName)
    }
}
