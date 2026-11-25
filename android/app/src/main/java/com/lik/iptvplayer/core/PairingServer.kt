package com.lik.iptvplayer.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

data class PairInfo(val code: String, val url: String, val lanIp: String, val port: Int)

/**
 * 设备端极简局域网表单服务（不引入 Ktor / NanoHTTPD）。
 * 方向：设备（TV 或手机）显示二维码 -> 另一台手机的浏览器打开表单 -> 提交订阅地址
 * -> 设备端界面立即出现一条「待保存」的源 -> 用户点保存落盘。
 *
 * 只在设置面板打开期间启动，面板关闭即停；端口优先级 8791 起，被占用则顺延。
 * 允许重复提交（一次要加多个源是常态），二维码里的 code 仍是当前这次会话的凭证。
 */
class PairingServer(
    private val deviceLabel: String,
    private val onPush: (url: String, name: String, epg: String) -> Boolean,
) {
    private var scope: CoroutineScope? = null
    private var socket: ServerSocket? = null
    private var code: String = ""

    /** 全部端口被占用时返回 null，UI 提示改用手填。 */
    fun start(preferredPort: Int = 8791): PairInfo? {
        stop()
        code = Random.nextInt(100000, 1000000).toString()
        val s = (preferredPort..preferredPort + 9).firstNotNullOfOrNull { p ->
            runCatching { ServerSocket(p, 16, InetAddress.getByName("0.0.0.0")) }.getOrNull()
        } ?: return null

        socket = s
        val ip = lanIp()
        val info = PairInfo(code, "http://$ip:${s.localPort}/pair?code=$code", ip, s.localPort)
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        newScope.launch { acceptLoop(s, info) }
        return info
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        scope?.cancel()
        scope = null
    }

    private suspend fun acceptLoop(s: ServerSocket, info: PairInfo) = withContext(Dispatchers.IO) {
        while (isActive && !s.isClosed) {
            val conn = try { s.accept() } catch (e: Exception) { break }
            launch { runCatching { handle(conn, info) } }
        }
    }

    private fun handle(conn: Socket, info: PairInfo) {
        conn.use { c ->
            c.soTimeout = 5000
            val reader = c.getInputStream().bufferedReader(Charsets.UTF_8)
            val line = reader.readLine() ?: return
            val parts = line.split(' ')
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "/" }
            var length = 0
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Content-Length:", true)) {
                    length = h.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            // 表单使用 encodeURIComponent，body 为纯 ASCII，按字符数读取即等价字节数
            val body = if (length > 0) {
                val buf = CharArray(length.coerceAtMost(64 * 1024))
                var read = 0
                while (read < buf.size) {
                    val n = reader.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            val out = c.getOutputStream()
            when {
                method == "GET" && path.startsWith("/pair") -> {
                    val ok = path.substringAfter("code=", "").substringBefore('&') == code
                    respond(
                        out, if (ok) 200 else 400, "text/html; charset=utf-8",
                        if (ok) page() else "<meta charset='utf-8'><h3>二维码已失效，请在设备上重新打开设置</h3>",
                    )
                }
                method == "POST" && path == "/api/pair/submit" -> {
                    val form = parseForm(body)
                    val url = form["url"].orEmpty()
                    val name = form["name"].orEmpty().ifBlank { "扫码添加的源" }
                    val epg = form["epg"].orEmpty()
                    val ok = form["code"] == code &&
                        M3uParser.normalizeSourceUrl(url) != null &&
                        (epg.isBlank() || M3uParser.normalizeSourceUrl(epg) != null) &&
                        onPush(url, name, epg)
                    respond(
                        out, if (ok) 200 else 400, "application/json; charset=utf-8",
                        if (ok) "{\"ok\":true}" else "{\"error\":\"地址不合法或二维码已失效\"}",
                    )
                }
                else -> respond(out, 404, "text/plain; charset=utf-8", "Not Found")
            }
        }
    }

    private fun parseForm(body: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (pair in body.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            out[k] = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
        return out
    }

    private fun respond(out: OutputStream, status: Int, type: String, bodyText: String) {
        val bytes = bodyText.toByteArray(Charsets.UTF_8)
        val st = when (status) { 200 -> "200 OK"; 400 -> "400 Bad Request"; else -> "404 Not Found" }
        val head = "HTTP/1.1 $st\r\nContent-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun page() = """<!DOCTYPE html><html lang="zh-CN"><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>添加直播源</title><style>
*{box-sizing:border-box}
body{font:15px/1.7 -apple-system,system-ui,sans-serif;background:#0b1220;color:#e2e8f0;margin:0;padding:20px}
.wrap{max-width:480px;margin:0 auto;background:#111c30;border:1px solid #1e2c46;border-radius:14px;padding:20px}
h1{font-size:18px;margin:0 0 4px}
.sub{font-size:12px;color:#8fa3c0;margin:0 0 16px}
label{display:block;font-size:12px;font-weight:600;color:#a9bcd8;margin:14px 0 6px}
input{width:100%;padding:12px;border:1px solid #2a3d5c;border-radius:9px;background:#0b1424;color:#fff;font-size:15px}
input:focus{outline:none;border-color:#3b82f6}
button{width:100%;margin-top:20px;padding:14px;border:0;border-radius:9px;background:#2563eb;color:#fff;font-size:16px;font-weight:600}
button:disabled{background:#334155}
.msg{margin-top:14px;font-size:13px;min-height:20px;color:#7dd3fc}
.hint{margin-top:16px;font-size:12px;color:#64748b;line-height:1.6}
</style></head><body><div class="wrap">
<h1>添加直播源</h1>
<p class="sub">推送到 $deviceLabel · 配对码 $code</p>
<form id="f">
<label>M3U 订阅地址（必填）</label>
<input id="url" type="url" required autocomplete="off" placeholder="http://NAS:9797/密码/playlist.m3u">
<label>名称（可选）</label><input id="name" autocomplete="off" placeholder="家里 NAS">
<label>节目单地址（可选，留空自动发现）</label><input id="epg" autocomplete="off" placeholder="http://NAS:9797/密码/epg.xml">
<button type="submit" id="btn">确定添加</button>
<div class="msg" id="msg"></div>
</form>
<p class="hint">点「确定添加」后，源会立刻出现在${deviceLabel}的列表里，确认无误再点保存。可以连续添加多个。</p>
</div><script>
var code="$code";
document.getElementById("f").addEventListener("submit",async function(e){
 e.preventDefault();
 var msg=document.getElementById("msg"),btn=document.getElementById("btn");
 msg.style.color="#7dd3fc";msg.textContent="正在推送…";btn.disabled=true;
 var body="code="+encodeURIComponent(code)+
  "&url="+encodeURIComponent(document.getElementById("url").value.trim())+
  "&name="+encodeURIComponent(document.getElementById("name").value.trim())+
  "&epg="+encodeURIComponent(document.getElementById("epg").value.trim());
 try{
  var r=await fetch("/api/pair/submit",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:body});
  var d=await r.json();
  if(r.ok){
   msg.style.color="#86efac";
   msg.textContent="已推送，回${deviceLabel}点保存生效。可继续添加下一个。";
   document.getElementById("url").value="";
   document.getElementById("name").value="";
   document.getElementById("epg").value="";
  }else{
   msg.style.color="#fca5a5";msg.textContent="失败："+(d.error||r.status);
  }
 }catch(err){msg.style.color="#fca5a5";msg.textContent="失败："+err.message;}
 btn.disabled=false;
});
</script></body></html>"""

    /** 优先取 192.168 / 10. / 172.16-31 私有网段，避开 VPN、虚拟网卡拿到不可达地址。 */
    private fun lanIp(): String {
        var fallback: String? = null
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        for (n in interfaces) {
            if (!n.isUp || n.isLoopback) continue
            for (a in n.inetAddresses) {
                if (a.isLoopbackAddress || a !is Inet4Address) continue
                val ip = a.hostAddress ?: continue
                if (ip.startsWith("192.168.") || ip.startsWith("10.") || is172Private(ip)) return ip
                if (fallback == null && !ip.startsWith("169.254.")) fallback = ip
            }
        }
        return fallback ?: "127.0.0.1"
    }

    private fun is172Private(ip: String): Boolean {
        if (!ip.startsWith("172.")) return false
        val second = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }
}
