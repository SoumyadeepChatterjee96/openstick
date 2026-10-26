package org.openstick.engine

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale

class WsFrame(val opcode: Int, val payload: ByteArray)

class WebVncServer(
    val port: Int = 6080,
    val vncPort: Int = 5900,
    private val openAsset: (String) -> InputStream?
) {
    companion object {
        const val OP_CONTINUATION = 0
        const val OP_TEXT = 1
        const val OP_BINARY = 2
        const val OP_CLOSE = 8
        const val OP_PING = 9
        const val OP_PONG = 10

        @Volatile
        var current: WebVncServer? = null
            private set

        fun startServer(openAsset: (String) -> InputStream?): WebVncServer? {
            current?.stop()
            val server = WebVncServer(6080, 5900, openAsset)
            return if (server.start()) {
                current = server
                server
            } else {
                null
            }
        }

        fun stopServer() {
            current?.stop()
            current = null
        }
    }

    private var server: ServerSocket? = null
    private val sockets = Collections.synchronizedSet(LinkedHashSet<Socket>())

    val boundPort: Int
        get() = server?.localPort ?: -1

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port), 16)
            server = ss

            val t = Thread({ acceptLoop(ss) }, "web-vnc")
            t.isDaemon = true
            t.start()
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {}
        server = null

        val list: List<Socket>
        synchronized(sockets) {
            list = sockets.toList()
        }
        for (s in list) {
            try { s.close() } catch (_: Exception) {}
        }
        sockets.clear()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (_: Exception) {
                null
            } ?: return

            if (sockets.size >= 64) {
                try { client.close() } catch (_: Exception) {}
            } else {
                sockets.add(client)
                val t = Thread({ serve(client) }, "web-vnc-client")
                t.isDaemon = true
                t.start()
            }
        }
    }

    private fun serve(c: Socket) {
        try {
            c.soTimeout = 15000
            c.tcpNoDelay = true
            val bis = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            val head = readHead(bis)
            if (head == null) {
                c.close()
                sockets.remove(c)
                return
            }

            val lines = head.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(" ") ?: emptyList()
            if (requestLine.size < 3) {
                c.close()
                sockets.remove(c)
                return
            }

            val headers = mutableMapOf<String, String>()
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().lowercase(Locale.ROOT)
                    val v = line.substring(idx + 1).trim()
                    headers[k] = v
                }
            }

            val wsKey = headers["sec-websocket-key"]
            if (headers["upgrade"].equals("websocket", ignoreCase = true) && wsKey != null) {
                c.soTimeout = 0
                relay(c, bis, out, wsKey)
            } else if (requestLine[0] == "GET" || requestLine[0] == "HEAD") {
                serveFile(out, requestLine[1], requestLine[0] == "HEAD")
            } else {
                respond(out, 405, "Method Not Allowed", "text/plain", "Not allowed\n".toByteArray(Charsets.UTF_8), false)
            }
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
            sockets.remove(c)
        }
    }

    private fun serveFile(out: OutputStream, target: String, headOnly: Boolean) {
        val cleanPath = webPath(target)
        if (cleanPath == null) {
            respond(out, 404, "Not Found", "text/plain", "Not found\n".toByteArray(Charsets.UTF_8), headOnly)
            return
        }
        if (cleanPath.isEmpty()) {
            val body = "Moved\n".toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 302 Found\r\nLocation: /vnc.html\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            out.write(header)
            if (!headOnly) out.write(body)
            out.flush()
            return
        }

        val bytes = try {
            openAsset("novnc/$cleanPath")?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }

        if (bytes == null) {
            respond(out, 404, "Not Found", "text/plain", "Not found\n".toByteArray(Charsets.UTF_8), headOnly)
            return
        }

        respond(out, 200, "OK", mimeType(cleanPath), bytes, headOnly)
    }

    private fun relay(client: Socket, input: InputStream, out: OutputStream, key: String) {
        val vncSocket = Socket()
        try {
            vncSocket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), vncPort), 3000)
            vncSocket.tcpNoDelay = true
            sockets.add(vncSocket)

            val acceptHeader = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            out.write(acceptHeader)
            out.flush()

            val lock = Any()
            val downThread = Thread({
                val buf = ByteArray(65536)
                try {
                    val vncIn = vncSocket.getInputStream()
                    while (true) {
                        val len = vncIn.read(buf)
                        if (len < 0) break
                        synchronized(lock) {
                            writeFrame(out, OP_BINARY, buf, 0, len)
                        }
                    }
                } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }, "web-vnc-down")
            downThread.isDaemon = true
            downThread.start()

            val vncOut = vncSocket.getOutputStream()
            while (true) {
                val frame = readFrame(input) ?: break
                when (frame.opcode) {
                    OP_CONTINUATION, OP_TEXT, OP_BINARY -> {
                        vncOut.write(frame.payload)
                        vncOut.flush()
                    }
                    OP_CLOSE -> {
                        synchronized(lock) {
                            writeFrame(out, OP_CLOSE, ByteArray(0), 0, 0)
                        }
                        break
                    }
                    OP_PING -> {
                        synchronized(lock) {
                            writeFrame(out, OP_PONG, frame.payload, 0, frame.payload.size)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            val msg = "VNC desktop session is not listening on port 5900.\n".toByteArray(Charsets.UTF_8)
            respond(out, 502, "Bad Gateway", "text/plain", msg, false)
        } finally {
            try { vncSocket.close() } catch (_: Exception) {}
            sockets.remove(vncSocket)
        }
    }

    private fun respond(out: OutputStream, code: Int, reason: String, type: String, body: ByteArray, headOnly: Boolean) {
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        out.write(header)
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun acceptKey(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest((key.trim() + magic).toByteArray(Charsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun webPath(target: String): String? {
        val path = target.substringBefore('?').substringBefore('#')
        if (!path.startsWith('/')) return null
        val decoded = try {
            URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            return null
        }
        val segments = decoded.split('/').drop(1)
        if (segments.size == 1 && segments[0].isEmpty()) return ""
        for (seg in segments) {
            if (seg.isEmpty() || seg.startsWith('.')) return null
            for (c in seg) {
                if (!webChar(c)) return null
            }
        }
        return segments.joinToString("/")
    }

    private fun webChar(c: Char): Boolean {
        return (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '.' || c == '_' || c == '-'
    }

    private fun mimeType(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }

    private fun readHead(input: InputStream, max: Int = 16384): String? {
        val baos = ByteArrayOutputStream()
        val marker = byteArrayOf(13, 10, 13, 10)
        var mIdx = 0
        while (baos.size() < max) {
            val b = input.read()
            if (b < 0) break
            baos.write(b)
            if (b.toByte() == marker[mIdx]) {
                mIdx++
            } else {
                mIdx = if (b == 13) 1 else 0
            }
            if (mIdx == 4) {
                val bytes = baos.toByteArray()
                return String(bytes, Charsets.ISO_8859_1).removeSuffix("\r\n\r\n")
            }
        }
        return null
    }

    private fun readFrame(input: InputStream, maxPayload: Int = 1048576): WsFrame? {
        val b0 = input.read()
        val b1 = input.read()
        if (b0 < 0 || b1 < 0) return null

        val isMasked = (b1 and 128) != 0
        var len = (b1 and 127).toLong()
        if (len == 126L) {
            val ext = readExactly(input, 2) ?: return null
            len = (((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)).toLong()
        } else if (len == 127L) {
            val ext = readExactly(input, 8) ?: return null
            len = 0L
            for (b in ext) {
                len = (len shl 8) or (b.toLong() and 0xFF)
            }
        }

        if (len < 0 || len > maxPayload) return null

        val maskKey = if (isMasked) readExactly(input, 4) ?: return null else null
        val payload = readExactly(input, len.toInt()) ?: return null

        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return WsFrame(b0 and 15, payload)
    }

    private fun writeFrame(out: OutputStream, opcode: Int, buf: ByteArray, offset: Int, length: Int) {
        val b0 = (opcode or 128).toByte()
        val header = if (length < 126) {
            byteArrayOf(b0, length.toByte())
        } else if (length < 65536) {
            byteArrayOf(b0, 126.toByte(), (length shr 8).toByte(), length.toByte())
        } else {
            val h = ByteArray(10)
            h[0] = b0
            h[1] = 127.toByte()
            for (i in 0..7) {
                h[9 - i] = ((length.toLong() shr (i * 8)) and 0xFF).toByte()
            }
            h
        }
        out.write(header)
        out.write(buf, offset, length)
        out.flush()
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) return null
            read += r
        }
        return buf
    }
}
