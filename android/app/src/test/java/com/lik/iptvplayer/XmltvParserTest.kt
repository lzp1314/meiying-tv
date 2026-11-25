package com.lik.iptvplayer

import com.lik.iptvplayer.core.XmltvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 与 prototype/tests/run.js 的 XMLTV 用例保持一致。 */
class XmltvParserTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="demo.ch1"><display-name>演示频道一</display-name></channel>
          <programme start="20261124200000 +0800" stop="20261124210000 +0800" channel="demo.ch1">
            <title>当前时段节目</title><desc>合成条目</desc>
          </programme>
          <programme start="20261124210000 +0800" stop="20261124220000 +0800" channel="demo.ch1">
            <title>下一时段节目</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test fun `带时区时间按 UTC 归算`() {
        val expected = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(2026, Calendar.NOVEMBER, 24, 12, 0, 0)
        }.timeInMillis
        assertEquals(expected, XmltvParser.parseTime("20261124200000 +0800")!!)
        assertEquals(expected, XmltvParser.parseTime("2026-11-24T20:00:00+08:00")!!)
    }

    @Test fun `空时间返回 null`() {
        assertNull(XmltvParser.parseTime(""))
        assertNull(XmltvParser.parseTime("not-a-time"))
    }

    @Test fun `频道与节目解析`() {
        val epg = XmltvParser.parse(xml)
        assertEquals("演示频道一", epg.channels["demo.ch1"])
        assertEquals(2, epg.programmes["demo.ch1"]!!.size)
    }

    @Test fun `当前与下一节目对表`() {
        val epg = XmltvParser.parse(xml)
        val now = XmltvParser.parseTime("20261124203000 +0800")!!
        val nn = XmltvParser.nowNext(epg, "demo.ch1", "演示频道一", now)
        assertTrue(nn.matched)
        assertEquals("当前时段节目", nn.current?.title)
        assertEquals("下一时段节目", nn.next?.title)
    }

    @Test fun `display-name 反查兜底`() {
        val epg = XmltvParser.parse(xml)
        val now = XmltvParser.parseTime("20261124203000 +0800")!!
        val nn = XmltvParser.nowNext(epg, "演示频道一", "", now)
        assertTrue(nn.matched)
        assertEquals("当前时段节目", nn.current?.title)
    }

    @Test fun `未匹配的 tvg-id 明确返回未命中`() {
        val epg = XmltvParser.parse(xml)
        assertFalse(XmltvParser.nowNext(epg, "not.exist", "", System.currentTimeMillis()).matched)
    }

    @Test fun `XML 实体解码`() {
        val epg = XmltvParser.parse("<tv><channel id=\"x\"><display-name>A &amp; B</display-name></channel></tv>")
        assertEquals("A & B", epg.channels["x"])
    }

    // ---- 流式解析：线上真正走的是这条路径，节目单动辄十几 MB ----

    /** 每次只吐出几个字节，逼着解析器处理「标签跨数据块」的情况。 */
    private class DripStream(private val data: ByteArray, private val chunk: Int) : java.io.InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(chunk, len, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test fun `流式分块解析能跨数据块拼接标签`() {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        val epg = XmltvParser.parseStream(DripStream(bytes, 7))
        assertEquals("演示频道一", epg.channels["demo.ch1"])
        assertEquals(2, epg.programmes["demo.ch1"]!!.size)
    }

    private val twoChannels = """
        <tv>
          <channel id="cctv1"><display-name>CCTV1</display-name></channel>
          <channel id="hunan"><display-name>湖南卫视</display-name></channel>
          <programme start="20261124200000 +0800" stop="20261124210000 +0800" channel="cctv1">
            <title>新闻联播</title>
          </programme>
          <programme start="20261124200000 +0800" stop="20261124210000 +0800" channel="hunan">
            <title>快乐大本营</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test fun `只保留本源存在的频道`() {
        // 订阅源里写的是「CCTV-1 综合」，节目单里是 cctv1/CCTV1：去掉「综合」后应能对上
        val epg = XmltvParser.parseStream(
            twoChannels.byteInputStream(),
            wantedIds = emptySet(),
            wantedNames = setOf("CCTV-1 综合"),
        )
        assertEquals(1, epg.programmes.size)
        assertEquals("新闻联播", epg.programmes["cctv1"]!!.first().title)
    }

    @Test fun `频道名与节目单 display-name 一致时也能命中`() {
        val epg = XmltvParser.parseStream(
            twoChannels.byteInputStream(),
            wantedIds = emptySet(),
            wantedNames = setOf("湖南卫视"),
        )
        assertEquals(1, epg.programmes.size)
        assertEquals("快乐大本营", epg.programmes["hunan"]!!.first().title)
    }

    @Test fun `不给白名单时保留全部频道`() {
        val epg = XmltvParser.parseStream(twoChannels.byteInputStream())
        assertEquals(2, epg.programmes.size)
    }
}
