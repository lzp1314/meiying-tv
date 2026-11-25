package com.lik.iptvplayer

import com.lik.iptvplayer.player.warmUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 预热地址改写：只认 /live/ 与 /ysp/，密码段前缀原样保留，query 丢掉。 */
class WarmerTest {

    @Test fun `live 地址去掉 m3u8 后缀`() {
        assertEquals(
            "http://nas:8080/warm/cctv1",
            warmUrl("http://nas:8080/live/cctv1.m3u8"),
        )
    }

    @Test fun `不带后缀也能改`() {
        assertEquals(
            "http://nas:8080/warm/cctv1",
            warmUrl("http://nas:8080/live/cctv1"),
        )
    }

    @Test fun `密码段前缀原样保留`() {
        assertEquals(
            "http://nas:8080/xxxsecret/warm/cctv1",
            warmUrl("http://nas:8080/xxxsecret/live/cctv1.m3u8"),
        )
    }

    @Test fun `ysp 地址同样改`() {
        assertEquals(
            "http://nas:8080/warm/ysp1",
            warmUrl("http://nas:8080/ysp/ysp1.m3u8"),
        )
    }

    @Test fun `query 被丢掉`() {
        assertEquals(
            "http://nas:8080/warm/cctv1",
            warmUrl("http://nas:8080/live/cctv1.m3u8?token=abc&expires=1"),
        )
    }

    @Test fun `其他后缀按原样保留`() {
        assertEquals(
            "http://nas:8080/warm/cctv1.ts",
            warmUrl("http://nas:8080/live/cctv1.ts"),
        )
    }

    @Test fun `多个 live 标记取最后一个`() {
        assertEquals(
            "http://nas:8080/live/a/warm/b",
            warmUrl("http://nas:8080/live/a/live/b.m3u8"),
        )
    }

    @Test fun `非直播地址不预热`() {
        assertNull(warmUrl("https://cdn.example.com/hls/channels/1/index.m3u8"))
        assertNull(warmUrl("http://nas:8080/plain/cctv1.m3u8"))
        assertNull(warmUrl(""))
    }

    @Test fun `空 slug 不预热`() {
        assertNull(warmUrl("http://nas:8080/live/"))
        assertNull(warmUrl("http://nas:8080/ysp/?token=abc"))
    }
}
