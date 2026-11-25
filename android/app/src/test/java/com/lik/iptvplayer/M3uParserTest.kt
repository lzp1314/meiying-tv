package com.lik.iptvplayer

import com.lik.iptvplayer.core.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 prototype/tests/run.js 的 M3U 用例保持一致。 */
class M3uParserTest {

    @Test fun `解析头部声明与频道字段`() {
        val text = """
            #EXTM3U x-tvg-url="http://nas:9797/pwd/epg.xml" url-tvg="http://nas:9797/pwd/epg.xml"
            # === 中文注释不应产生孤立地址 ===
            #EXTINF:-1 tvg-id="a.ch1" tvg-name="频道甲" group-title="卫视,高清",频道甲
            http://nas/live/1.m3u8
            #EXTINF:-1 tvg-id="a.ch2" group-title="体育",频道乙
            #EXTVLCOPT:http-user-agent=IPTVPlayer/0.1
            #EXTVLCOPT:http-referrer=http://nas/
            http://nas/live/2.ts
        """.trimIndent()

        val p = M3uParser.parse(text)
        assertEquals("http://nas:9797/pwd/epg.xml", p.declaredEpgUrl)
        assertEquals(2, p.channels.size)
        assertEquals("频道甲", p.channels[0].name)
        assertEquals("卫视,高清", p.channels[0].group)
        assertEquals("a.ch1", p.channels[0].tvgId)
        assertEquals("频道乙", p.channels[1].name)
        assertEquals("IPTVPlayer/0.1", p.channels[1].userAgent)
        assertEquals("http://nas/", p.channels[1].referrer)
        assertTrue(p.warnings.isEmpty())
    }

    @Test fun `推导 EPG 时保留访问密码路径段`() {
        assertEquals(
            "http://nas:9797/tv7Kd2x/epg.xml",
            M3uParser.resolveEpgUrl("http://nas:9797/tv7Kd2x/playlist.m3u", null),
        )
        assertEquals(
            "http://nas:9797/epg.xml",
            M3uParser.resolveEpgUrl("http://nas:9797/playlist.m3u", null),
        )
    }

    @Test fun `M3U 声明优先于同源推导`() {
        assertEquals(
            "https://epg.example.com/cn.xml",
            M3uParser.resolveEpgUrl("http://nas:9797/pwd/playlist.m3u", "https://epg.example.com/cn.xml"),
        )
        assertEquals(
            "http://nas:9797/guide.xml",
            M3uParser.resolveEpgUrl("http://nas:9797/pwd/playlist.m3u", "../guide.xml"),
        )
    }

    @Test fun `非法地址被拒绝`() {
        assertNull(M3uParser.resolveEpgUrl("file:///tmp/x.m3u", null))
        assertNull(M3uParser.normalizeSourceUrl("ftp://nas/x"))
        assertNull(M3uParser.normalizeSourceUrl("not a url"))
    }

    @Test fun `分组按出现顺序聚合`() {
        val p = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="甲",一
            http://nas/1.m3u8
            #EXTINF:-1 group-title="乙",二
            http://nas/2.m3u8
            #EXTINF:-1 group-title="甲",三
            http://nas/3.m3u8
            """.trimIndent(),
        )
        val groups = M3uParser.group(p.channels)
        assertEquals(2, groups.size)
        assertEquals("甲", groups[0].name)
        assertEquals(2, groups[0].channels.size)
    }
}
