/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * A tiny, dependency-free HTTP/1.1 server for the [FleetAgentService]. One accept
 * thread hands each connection to a small thread pool; requests are parsed and
 * passed to [handler], whose [Response] is written back with `Connection: close`.
 *
 * Deliberately minimal — it serves the agent's control + file/log endpoints on the
 * home LAN, not arbitrary web traffic — and adds no Gradle dependency, matching
 * the hand-rolled `HttpURLConnection` net code elsewhere in the app
 * ([StoreCatalog]). The actual API lives in [FleetRoutes]; this layer only does
 * sockets, parsing, a LAN-only peer guard, and JSON/binary/streamed responses.
 *
 * `Connection: close` means one request per connection, so request bodies are read
 * lazily (handlers call [Request.bodyText] or [Request.streamBodyTo]) and an unread
 * body simply dies with the socket — no keep-alive framing to get wrong.
 */
class FleetHttpServer(
    private val port: Int,
    private val handler: (Request) -> Response,
) {
  private val pool = Executors.newFixedThreadPool(4)
  @Volatile private var server: ServerSocket? = null
  @Volatile private var running = false
  private var acceptThread: Thread? = null

  class Request(
      val method: String,
      val path: String,
      val query: String,
      val headers: Map<String, String>, // keys lower-cased
      private val input: InputStream,
      val contentLength: Int,
  ) {
    private var consumed = 0

    fun header(name: String): String? = headers[name.lowercase()]

    /** A query-string parameter, URL-decoded (e.g. ?path=%2Fsdcard). */
    fun queryParam(name: String): String? {
      for (pair in query.split('&')) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        if (pair.substring(0, eq) == name) {
          return runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrNull()
        }
      }
      return null
    }

    /** Read the body (capped) as text — for small JSON command bodies. */
    fun bodyText(maxBytes: Int = 1 * 1024 * 1024): String {
      val want = minOf(contentLength, maxBytes)
      if (want <= 0) return ""
      val buf = ByteArray(want)
      var read = 0
      while (read < want) {
        val n = input.read(buf, read, want - read)
        if (n == -1) break
        read += n
      }
      consumed += read
      return String(buf, 0, read, Charsets.UTF_8)
    }

    /** Stream the full request body to [out] (for uploads); returns bytes copied. */
    fun streamBodyTo(out: OutputStream): Long {
      var remaining = contentLength.toLong() - consumed
      val buf = ByteArray(64 * 1024)
      var total = 0L
      while (remaining > 0) {
        val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
        if (n == -1) break
        out.write(buf, 0, n)
        remaining -= n
        total += n
      }
      consumed += total.toInt()
      return total
    }

    /** Discard any body the handler didn't read, so closing the socket doesn't RST
     *  the connection and cost the client our response. Called by the server. */
    internal fun drainRemaining() {
      var remaining = contentLength - consumed
      val buf = ByteArray(64 * 1024)
      while (remaining > 0) {
        val n = input.read(buf, 0, minOf(buf.size, remaining))
        if (n == -1) break
        remaining -= n
      }
    }
  }

  /**
   * Either an in-memory JSON body or a streamed one (file/logcat). For a stream,
   * [streamLength] >= 0 sends Content-Length; < 0 relies on `Connection: close`
   * (fine for unknown-length output like logcat).
   */
  class Response
  private constructor(
      val status: Int,
      val contentType: String,
      val extraHeaders: Map<String, String>,
      val jsonBytes: ByteArray?,
      val streamLength: Long,
      val streamWriter: ((OutputStream) -> Unit)?,
  ) {
    /** Source-compatible JSON response (what [FleetRoutes] returns everywhere). */
    constructor(
        status: Int,
        json: String,
    ) : this(status, "application/json; charset=utf-8", emptyMap(), json.toByteArray(Charsets.UTF_8), -1, null)

    companion object {
      fun stream(
          status: Int,
          contentType: String,
          length: Long,
          headers: Map<String, String> = emptyMap(),
          writer: (OutputStream) -> Unit,
      ) = Response(status, contentType, headers, null, length, writer)

      /** A 204 CORS preflight reply (empty body) for an OPTIONS request. */
      fun preflight() =
          Response(
              204,
              "text/plain",
              mapOf(
                  "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                  "Access-Control-Allow-Headers" to "Authorization, Content-Type",
                  "Access-Control-Max-Age" to "600",
              ),
              null,
              0L,
          ) {}
    }
  }

  /** Bind and start accepting. Throws on bind failure so the caller can fall back. */
  fun start() {
    val s = ServerSocket(port)
    s.reuseAddress = true
    server = s
    running = true
    acceptThread =
        Thread({ acceptLoop(s) }, "fleet-accept").apply {
          isDaemon = true
          start()
        }
    Log.i(TAG, "fleet agent listening on :$port")
  }

  fun stop() {
    running = false
    runCatching { server?.close() }
    pool.shutdownNow()
  }

  private fun acceptLoop(s: ServerSocket) {
    while (running) {
      val socket =
          runCatching { s.accept() }
              .getOrElse {
                if (running) Log.w(TAG, "accept failed", it)
                return
              }
      runCatching { pool.execute { serve(socket) } }
          .onFailure { runCatching { socket.close() } }
    }
  }

  private fun serve(socket: Socket) {
    socket.use {
      // Trusted-LAN appliance management: refuse anything that isn't a private or
      // loopback peer before we even parse. Token auth (FleetRoutes) is the second gate.
      if (!isLanAddress(it.inetAddress)) {
        Log.w(TAG, "rejecting non-LAN peer ${it.inetAddress?.hostAddress}")
        return
      }
      it.soTimeout = 30_000
      val input = it.getInputStream()
      val out = it.getOutputStream()
      val head = readHead(input)
      if (head == null) {
        runCatching { writeResponse(out, Response(400, """{"ok":false,"error":"bad_request"}""")) }
        return
      }
      // CORS preflight: the phone remote, served by one Portal, calls other Portals'
      // agents cross-origin (multi-device). Answer OPTIONS here, before auth, with the
      // permissive headers — the LAN peer guard above and the bearer token remain the
      // real gates (CORS is only a browser read-policy, not an auth bypass).
      if (head.method == "OPTIONS") {
        runCatching { writeResponse(out, Response.preflight()) }
        return
      }
      val len = head.headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
      val req = Request(head.method, head.path, head.query, head.headers, input, len)
      // Honour Expect: 100-continue (curl sends it for larger uploads) so the
      // client actually sends the body before we try to read it.
      if (req.header("expect")?.contains("100-continue", ignoreCase = true) == true) {
        runCatching { out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII)); out.flush() }
      }
      val resp =
          runCatching { handler(req) }
              .getOrElse {
                Log.w(TAG, "handler error", it)
                Response(500, """{"ok":false,"error":"server_error"}""")
              }
      runCatching { req.drainRemaining() } // so close() can't RST away our response
      runCatching { writeResponse(out, resp) }
    }
  }

  // --- parsing (pure helpers are internal for unit tests) ---------------------

  /** Read up to the blank line and parse the request line + headers. */
  private fun readHead(input: InputStream): Head? {
    val headBytes = ByteArrayOutputStream()
    var last4 = 0
    while (true) {
      val b = input.read()
      if (b == -1) return null
      headBytes.write(b)
      last4 = (last4 shl 8) or b
      if (last4 == CRLF_CRLF) break // "\r\n\r\n"
      if (headBytes.size() > MAX_HEAD_BYTES) return null
    }
    return parseHead(headBytes.toString("UTF-8"))
  }

  internal data class Head(
      val method: String,
      val path: String,
      val query: String,
      val headers: Map<String, String>,
  )

  internal companion object {
    private const val TAG = "ImmortalFleet"
    private const val MAX_HEAD_BYTES = 64 * 1024
    private const val CRLF_CRLF = 0x0D0A0D0A

    /** Parse the request line + headers (text up to and including the blank line). */
    internal fun parseHead(text: String): Head? {
      val lines = text.split("\r\n")
      val requestLine = lines.firstOrNull()?.trim().orEmpty()
      val parts = requestLine.split(" ")
      if (parts.size < 2) return null
      val method = parts[0].uppercase()
      val target = parts[1]
      val q = target.indexOf('?')
      val path = if (q >= 0) target.substring(0, q) else target
      val query = if (q >= 0) target.substring(q + 1) else ""
      val headers = mutableMapOf<String, String>()
      for (i in 1 until lines.size) {
        val line = lines[i]
        if (line.isEmpty()) break
        val c = line.indexOf(':')
        if (c <= 0) continue
        headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
      }
      return Head(method, path, query, headers)
    }

    /** Loopback / private (10/8, 172.16/12, 192.168/16) / link-local (169.254) only. */
    internal fun isLanAddress(addr: InetAddress?): Boolean =
        addr != null &&
            (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress)

    private fun reason(code: Int): String =
        when (code) {
          200 -> "OK"
          400 -> "Bad Request"
          401 -> "Unauthorized"
          403 -> "Forbidden"
          404 -> "Not Found"
          405 -> "Method Not Allowed"
          409 -> "Conflict"
          500 -> "Internal Server Error"
          503 -> "Service Unavailable"
          else -> "OK"
        }
  }

  private fun writeResponse(out: OutputStream, resp: Response) {
    val header =
        buildString {
          append("HTTP/1.1 ${resp.status} ${reason(resp.status)}\r\n")
          append("Content-Type: ${resp.contentType}\r\n")
          // Allow the cross-origin phone remote (multi-device) to read responses. The LAN
          // peer guard + bearer token are the real gates; CORS is only a browser read-policy.
          append("Access-Control-Allow-Origin: *\r\n")
          for ((k, v) in resp.extraHeaders) append("$k: $v\r\n")
          if (resp.jsonBytes != null) append("Content-Length: ${resp.jsonBytes.size}\r\n")
          else if (resp.streamLength >= 0) append("Content-Length: ${resp.streamLength}\r\n")
          append("Connection: close\r\n\r\n")
        }
    out.write(header.toByteArray(Charsets.US_ASCII))
    if (resp.jsonBytes != null) out.write(resp.jsonBytes) else resp.streamWriter?.invoke(out)
    out.flush()
  }
}
