package com.lik.iptvplayer.core

import android.content.Context
import com.lik.iptvplayer.R
import java.security.KeyStore
import java.security.cert.CertificateException
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
 * 背景：Gitee 等服务已把根证书换成 TrustAsia TLS RSA Root CA（2025-10 签发），
 * 靠 Certum Trusted Network CA 交叉根做锚点。Android 7–9 的运营商定制盒子
 * （如 HG680-GC）系统信任库出厂即冻结，不可能内置这张 2025 年才出现的证书，
 * 于是访问时报 `Unacceptable certificate: CN=TrustAsia TLS RSA Root CA`，
 * 在线更新直接不可用。
 *
 * 做法：把这一个 CA 打包进 res/raw，与系统信任库合并。
 * 校验顺序是「系统优先、内置兜底」——绝大多数站点仍走系统校验，行为完全不变；
 * 只有系统不认的那条链才用内置 CA 再验一次。
 *
 * 安全性：只追加 TrustAsia 这一个正规 CA（DigiCert 体系），
 * 不是 `TrustAllCerts`，不会放过自签/伪造证书。
 */
object Trust {

    /** 内置的 TrustAsia TLS RSA Root CA（DER，有效期至 2029-01-14）。 */
    private const val EXTRA_CA = R.raw.trustasia_tls_rsa_root_ca

    /**
     * 构造合并了内置 CA 的 SSLSocketFactory 与 TrustManager。
     * 任何一步失败都回退到纯系统实现，保证不会因为兜底逻辑本身把网络搞挂。
     */
    fun sslSocketFactory(context: Context): Pair<SSLSocketFactory, X509TrustManager> {
        val system = systemTrustManager()
        val merged = runCatching { mergeWithExtraCa(context, system) }.getOrElse { system }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(merged), null)
        return ctx.socketFactory to merged
    }

    private fun mergeWithExtraCa(
        context: Context,
        system: X509TrustManager,
    ): X509TrustManager {
        val extra = extraCaTrustManager(context) ?: return system
        return object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    // 系统不认才轮到内置 CA；仍不认就照原样抛给上层
                    extra.checkServerTrusted(chain, authType)
                }
            }

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                system.checkClientTrusted(chain, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                system.acceptedIssuers + extra.acceptedIssuers
        }
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** 只装内置那一个 CA 的 TrustManager；资源缺失或解析失败返回 null。 */
    private fun extraCaTrustManager(context: Context): X509TrustManager? = runCatching {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val cf = CertificateFactory.getInstance("X.509")
        context.resources.openRawResource(EXTRA_CA).use { input ->
            ks.setCertificateEntry("trustasia-tls-rsa-root", cf.generateCertificate(input))
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()
}
