package com.example

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

data class TsTrackInfo(val pid: Int, val lang: String?, val streamType: Int)

object TeleonlineTsProxy {
    private const val TAG = "TeleonlineProxy"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
    private const val IDLE_MS = 180000L
    private const val PROBE_LIMIT = 3 * 1024 * 1024

    private val AUDIO_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "teleonline-ts-proxy").also { it.isDaemon = true }
    }

    private val lastUse = AtomicLong(System.currentTimeMillis())
    private val cache = ConcurrentHashMap<String, DropInfo>()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    private data class DropInfo(val dropPids: Set<Int>, val patPid: Int, val pmtPid: Int)
    private data class PatInfo(val index: Int, val section: ByteArray, val pmtPid: Int)
    private data class PmtInfo(val index: Int, val pid: Int, val section: ByteArray, val tracks: List<TsTrackInfo>)

    fun playlistUrl(variantUrl: String, keepPid: Int): String? {
        val s = ensureServer() ?: return null
        return "http://127.0.0.1:${s.localPort}/live.m3u8?u=${enc(variantUrl)}&p=$keepPid"
    }

    fun absolute(base: String, ref: String): String? = try {
        URI(base).resolve(ref).toString()
    } catch (e: Exception) {
        null
    }

    private fun newCall(url: String, timeoutMs: Long): okhttp3.Call {
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
        if (timeoutMs > 0) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        return call
    }

    fun fetchText(url: String, timeoutMs: Long = 0L): String? = try {
        newCall(url, timeoutMs).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchText fallo: ${e.message}")
        null
    }

    fun firstSegment(variantUrl: String, timeoutMs: Long = 0L): ByteArray? {
        val text = fetchText(variantUrl, timeoutMs) ?: return null
        val uri = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val abs = absolute(variantUrl, uri) ?: return null
        return fetchBytes(abs, PROBE_LIMIT, timeoutMs)
    }

    fun probeTracks(segment: ByteArray): List<TsTrackInfo>? {
        val pmt = findPmt(segment) ?: return null
        val audio = pmt.tracks.filter { AUDIO_TYPES.contains(it.streamType) }
        if (audio.size < 2) return null
        Log.d(TAG, "PMT pid=${pmt.pid} pcr/audio=${audio.joinToString { "${it.pid}:${it.lang}" }}")
        return audio
    }

    private fun ensureServer(): ServerSocket? {
        server?.let { return it }
        return synchronized(this) {
            server?.let { return it }
            try {
                val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                server = s
                port = s.localPort
                pool.execute { acceptLoop(s) }
                pool.execute { reaper(s) }
                Log.d(TAG, "proxy escuchando en 127.0.0.1:${s.localPort}")
                s
            } catch (e: Exception) {
                Log.e(TAG, "no se pudo abrir el proxy: ${e.message}")
                null
            }
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                val socket = s.accept()
                pool.execute { handle(socket) }
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun reaper(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                Thread.sleep(15000L)
            } catch (_: Exception) {
                break
            }
            if (System.currentTimeMillis() - lastUse.get() > IDLE_MS) {
                synchronized(this) {
                    if (server === s) server = null
                }
                try {
                    s.close()
                } catch (_: Exception) {
                }
                Log.d(TAG, "proxy detenido por inactividad")
                break
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 20000
            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return
            var line = readLine(input)
            while (!line.isNullOrEmpty()) line = readLine(input)
            lastUse.set(System.currentTimeMillis())
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val out = BufferedOutputStream(socket.getOutputStream())
            when (path) {
                "/live.m3u8" -> servePlaylist(out, query)
                "/seg" -> serveSegment(out, query)
                else -> writeBody(out, 404, "text/plain", "no encontrado".toByteArray())
            }
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "handle fallo: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun servePlaylist(out: BufferedOutputStream, query: Map<String, String>) {
        val variant = query["u"]
        val keep = query["p"]?.toIntOrNull() ?: -1
        if (variant.isNullOrBlank()) {
            writeBody(out, 400, "text/plain", "falta u".toByteArray())
            return
        }
        val text = fetchText(variant)
        if (text == null) {
            writeBody(out, 502, "text/plain", "upstream".toByteArray())
            return
        }
        val sb = StringBuilder()
        var segments = 0
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#")) {
                sb.append(l).append('\n')
                continue
            }
            val abs = absolute(variant, l) ?: continue
            sb.append("http://127.0.0.1:").append(port)
                .append("/seg?u=").append(enc(abs))
                .append("&v=").append(enc(variant))
                .append("&p=").append(keep)
                .append('\n')
            segments++
        }
        val body = sb.toString().toByteArray(Charsets.UTF_8)
        Log.d(TAG, "playlist $variant keep=$keep segmentos=$segments")
        writeBody(out, 200, "application/vnd.apple.mpegurl", body, noCache = true)
    }

    private fun serveSegment(out: BufferedOutputStream, query: Map<String, String>) {
        val url = query["u"]
        val variant = query["v"] ?: url
        val keep = query["p"]?.toIntOrNull() ?: -1
        if (url.isNullOrBlank() || variant.isNullOrBlank()) {
            writeBody(out, 400, "text/plain", "falta u".toByteArray())
            return
        }
        val data = fetchBytes(url, Int.MAX_VALUE)
        if (data == null) {
            writeBody(out, 502, "text/plain", "upstream".toByteArray())
            return
        }
        val body = if (keep > 0) filterTs(data, variant, keep) else data
        writeBody(out, 200, "video/mp2t", body, noCache = true)
    }

    private fun filterTs(data: ByteArray, key: String, keepPid: Int): ByteArray {
        try {
            val base = alignOffset(data)
            if (base < 0) return data
            val pmt = findPmt(data)
            val pat = findPat(data)
            val cacheKey = "$key|$keepPid"
            var cached = cache[cacheKey]
            val drop = HashSet<Int>()
            if (pmt != null) {
                for (t in pmt.tracks) {
                    if (AUDIO_TYPES.contains(t.streamType) && t.pid != keepPid) drop.add(t.pid)
                }
                val patPid = pat?.pmtPid ?: pmt.pid
                cached = DropInfo(drop, patPid, pmt.pid)
                cache[cacheKey] = cached
                if (patPid != pmt.pid) {
                    Log.d(TAG, "PAT declara PMT ${patPid}, PMT real ${pmt.pid}")
                }
            } else if (cached != null) {
                drop.addAll(cached.dropPids)
            } else {
                return data
            }
            val fixPat = pat != null && cached != null && cached.patPid != cached.pmtPid
            val out = ByteArrayOutputStream(data.size)
            var off = base
            while (off + 188 <= data.size) {
                val pid = packetPid(data, off)
                if (drop.contains(pid)) {
                    off += 188
                    continue
                }
                var packet: ByteArray? = null
                if (hasPusi(data, off)) {
                    val idx = (off - base) / 188
                    if (pmt != null && pid == pmt.pid) {
                        val sec = readSection(data, off, idx)
                        if (sec != null && fitsPacket(data, off, sec)) {
                            if (sec.isNotEmpty() && sec[0].toInt() == 0x02) {
                                val rebuilt = buildPmtSection(sec, drop)
                                if (rebuilt != null) packet = rewriteSection(data, off, rebuilt)
                            }
                        }
                    } else if (fixPat && pid == 0x00 && cached != null) {
                        val sec = readSection(data, off, idx)
                        if (sec != null && fitsPacket(data, off, sec)) {
                            if (sec.isNotEmpty() && sec[0].toInt() == 0x00) {
                                val rebuilt = buildPatSection(sec, cached.pmtPid)
                                if (rebuilt != null) packet = rewriteSection(data, off, rebuilt)
                            }
                        }
                    }
                }
                if (packet != null) out.write(packet, 0, 188) else out.write(data, off, 188)
                off += 188
            }
            val result = out.toByteArray()
            if (pmt != null) Log.d(TAG, "seg keep=$keepPid drop=${drop.joinToString()} ${data.size}->${result.size}")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "filtro TS fallo: ${e.message}")
            return data
        }
    }

    private fun alignOffset(data: ByteArray): Int {
        val lim = min(188, data.size - 376)
        if (lim <= 0) return -1
        for (cand in 0 until lim) {
            if (data[cand].toInt() != 0x47) continue
            if (data[cand + 188].toInt() == 0x47 && data[cand + 376].toInt() == 0x47) return cand
        }
        return -1
    }

    private fun findPat(data: ByteArray): PatInfo? {
        val base = alignOffset(data)
        if (base < 0) return null
        var off = base
        while (off + 188 <= data.size) {
            val idx = (off - base) / 188
            if (hasPusi(data, off) && packetPid(data, off) == 0x00) {
                val section = readSection(data, off, idx) ?: break
                if (section.isNotEmpty() && section[0].toInt() == 0x00 && totalLength(section) == section.size) {
                    val pmtPid = patPmtPid(section)
                    if (pmtPid > 0) return PatInfo(idx, section, pmtPid)
                }
            }
            off += 188
        }
        return null
    }

    private fun findPmt(data: ByteArray): PmtInfo? {
        val base = alignOffset(data)
        if (base < 0) return null
        var off = base
        while (off + 188 <= data.size) {
            val idx = (off - base) / 188
            if (hasPusi(data, off)) {
                val pid = packetPid(data, off)
                val section = readSection(data, off, idx)
                if (section != null && section.isNotEmpty() && section[0].toInt() == 0x02) {
                    val total = totalLength(section)
                    if (total == section.size) {
                        val pmt = parsePmt(idx, pid, section)
                        if (pmt != null) return pmt
                    }
                }
            }
            off += 188
        }
        return null
    }

    private fun readSection(data: ByteArray, packetOff: Int, packetIndex: Int): ByteArray? {
        val start = sectionStart(data, packetOff)
        if (start < 0) return null
        val len = (((data[packetOff + start + 1].toInt() and 0x0F) shl 8) or
            (data[packetOff + start + 2].toInt() and 0xFF))
        val total = 3 + len
        if (total < 3 || total > 4096) return null
        val pid = packetPid(data, packetOff)
        val buf = ByteArray(total)
        var filled = 0
        var off = packetOff
        var first = true
        var idx = packetIndex
        while (filled < total) {
            val from = if (first) start else payloadOffset(data, off)
            if (from < 0 || from >= 188) return null
            val avail = 188 - from
            val n = min(avail, total - filled)
            System.arraycopy(data, off + from, buf, filled, n)
            filled += n
            first = false
            idx++
            off += 188
            if (filled < total) {
                if (off + 188 > data.size) return null
                if (packetPid(data, off) != pid) return null
            }
        }
        return buf
    }

    private fun parsePmt(index: Int, pid: Int, section: ByteArray): PmtInfo? {
        val total = totalLength(section)
        if (total != section.size || total < 16) return null
        val pil = ((section[10].toInt() and 0x0F) shl 8) or (section[11].toInt() and 0xFF)
        var pos = 12 + pil
        val end = total - 4
        if (pos > end) return null
        val tracks = ArrayList<TsTrackInfo>()
        while (pos + 5 <= end) {
            val st = section[pos].toInt() and 0xFF
            val esPid = ((section[pos + 1].toInt() and 0x1F) shl 8) or (section[pos + 2].toInt() and 0xFF)
            val esl = ((section[pos + 3].toInt() and 0x0F) shl 8) or (section[pos + 4].toInt() and 0xFF)
            if (pos + 5 + esl > end) break
            var lang: String? = null
            var d = pos + 5
            val dEnd = d + esl
            while (d + 2 <= dEnd) {
                val tag = section[d].toInt() and 0xFF
                val dl = section[d + 1].toInt() and 0xFF
                if (tag == 0x0A && dl >= 3 && d + 2 + dl <= dEnd) {
                    lang = String(section, d + 2, 3, Charsets.ISO_8859_1).trim('\u0000', ' ')
                }
                d += 2 + dl
            }
            tracks.add(TsTrackInfo(esPid, lang, st))
            pos += 5 + esl
        }
        if (tracks.isEmpty()) return null
        return PmtInfo(index, pid, section, tracks)
    }

    private fun patPmtPid(section: ByteArray): Int {
        val end = totalLength(section) - 4
        var pos = 8
        while (pos + 4 <= end) {
            val prog = ((section[pos].toInt() and 0xFF) shl 8) or (section[pos + 1].toInt() and 0xFF)
            if (prog != 0) {
                return ((section[pos + 2].toInt() and 0x1F) shl 8) or (section[pos + 3].toInt() and 0xFF)
            }
            pos += 4
        }
        return 0
    }

    private fun buildPmtSection(section: ByteArray, drop: Set<Int>): ByteArray? {
        val total = totalLength(section)
        if (total != section.size) return null
        val pil = ((section[10].toInt() and 0x0F) shl 8) or (section[11].toInt() and 0xFF)
        val headEnd = 12 + pil
        val end = total - 4
        if (headEnd > end) return null
        val body = ByteArrayOutputStream()
        body.write(section, 0, headEnd)
        var pos = headEnd
        while (pos + 5 <= end) {
            val esl = ((section[pos + 3].toInt() and 0x0F) shl 8) or (section[pos + 4].toInt() and 0xFF)
            if (pos + 5 + esl > end) break
            val pid = ((section[pos + 1].toInt() and 0x1F) shl 8) or (section[pos + 2].toInt() and 0xFF)
            if (!drop.contains(pid)) body.write(section, pos, 5 + esl)
            pos += 5 + esl
        }
        val head = body.toByteArray()
        val newLen = head.size - 3 + 4
        if (newLen > 1021) return null
        val out = ByteArray(head.size + 4)
        System.arraycopy(head, 0, out, 0, head.size)
        out[1] = ((out[1].toInt() and 0xF0) or ((newLen shr 8) and 0x0F)).toByte()
        out[2] = (newLen and 0xFF).toByte()
        val crc = crc32Mpeg(out, 0, out.size - 4)
        out[out.size - 4] = ((crc ushr 24) and 0xFF).toByte()
        out[out.size - 3] = ((crc ushr 16) and 0xFF).toByte()
        out[out.size - 2] = ((crc ushr 8) and 0xFF).toByte()
        out[out.size - 1] = (crc and 0xFF).toByte()
        return out
    }

    private fun buildPatSection(section: ByteArray, pmtPid: Int): ByteArray? {
        val total = totalLength(section)
        if (total != section.size || total < 12) return null
        val out = section.copyOf()
        val end = total - 4
        var pos = 8
        var done = false
        while (pos + 4 <= end) {
            val prog = ((out[pos].toInt() and 0xFF) shl 8) or (out[pos + 1].toInt() and 0xFF)
            if (prog != 0) {
                out[pos + 2] = ((out[pos + 2].toInt() and 0xE0) or ((pmtPid shr 8) and 0x1F)).toByte()
                out[pos + 3] = (pmtPid and 0xFF).toByte()
                done = true
                break
            }
            pos += 4
        }
        if (!done) return null
        val crc = crc32Mpeg(out, 0, out.size - 4)
        out[out.size - 4] = ((crc ushr 24) and 0xFF).toByte()
        out[out.size - 3] = ((crc ushr 16) and 0xFF).toByte()
        out[out.size - 2] = ((crc ushr 8) and 0xFF).toByte()
        out[out.size - 1] = (crc and 0xFF).toByte()
        return out
    }

    private fun rewriteSection(data: ByteArray, off: Int, section: ByteArray): ByteArray? {
        if (section.size > 182) return null
        val cc = data[off + 3].toInt() and 0x0F
        val afl = 182 - section.size
        val out = data.copyOfRange(off, off + 188)
        out[3] = ((3 shl 4) or cc).toByte()
        out[4] = afl.toByte()
        if (afl >= 1) {
            out[5] = 0x00
            for (k in 6 until 5 + afl) out[k] = 0xFF.toByte()
        }
        val payload = 5 + afl
        out[payload] = 0
        System.arraycopy(section, 0, out, payload + 1, section.size)
        return out
    }

    private fun totalLength(section: ByteArray): Int {
        if (section.size < 3) return 0
        return 3 + (((section[1].toInt() and 0x0F) shl 8) or (section[2].toInt() and 0xFF))
    }

    private fun fitsPacket(data: ByteArray, off: Int, section: ByteArray): Boolean {
        val start = sectionStart(data, off)
        if (start < 0) return false
        return start + section.size <= 188
    }

    private fun hasPusi(data: ByteArray, off: Int) = (data[off + 1].toInt() and 0x40) != 0

    private fun packetPid(data: ByteArray, off: Int): Int =
        ((data[off + 1].toInt() and 0x1F) shl 8) or (data[off + 2].toInt() and 0xFF)

    private fun payloadOffset(data: ByteArray, off: Int): Int {
        val afc = (data[off + 3].toInt() shr 4) and 0x03
        return if (afc and 0x02 != 0) 4 + 1 + (data[off + 4].toInt() and 0xFF) else 4
    }

    private fun sectionStart(data: ByteArray, off: Int): Int {
        val po = payloadOffset(data, off)
        if (po < 0 || po >= 188) return -1
        val pointer = data[off + po].toInt() and 0xFF
        val start = po + 1 + pointer
        if (start + 3 > 188) return -1
        return start
    }

    private fun crc32Mpeg(data: ByteArray, from: Int, to: Int): Int {
        var crc = -1
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
            for (b in 0 until 8) {
                crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        return crc
    }

    private fun fetchBytes(url: String, limit: Int, timeoutMs: Long = 0L): ByteArray? = try {
        newCall(url, timeoutMs).execute().use { r ->
            val ins = if (r.isSuccessful) r.body?.byteStream() else null
            if (ins == null) {
                null
            } else {
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (buf.size() < limit) {
                    val n = ins.read(chunk, 0, min(chunk.size, limit - buf.size()))
                    if (n <= 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchBytes fallo: ${e.message}")
        null
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c = input.read()
        if (c == -1) return null
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            val k = part.substringBefore('=')
            val v = part.substringAfter('=', "")
            try {
                map[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (_: Exception) {
            }
        }
        return map
    }

    private fun writeBody(out: BufferedOutputStream, code: Int, type: String, body: ByteArray, noCache: Boolean = false) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            else -> "Error"
        }
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        head.append("Content-Type: ").append(type).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        if (noCache) head.append("Cache-Control: no-store\r\n")
        head.append("Accept-Ranges: none\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(body)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
