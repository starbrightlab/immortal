/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A tiny embedded HTTP server so a phone or laptop on the same Wi-Fi can fill in a photo source's
 * details (Immich URL + API key, NAS credentials, a WebDAV/web/album URL) with a real keyboard,
 * instead of typing them on the Portal. Offered *in addition* to the on-Portal connect screens.
 *
 * It serves one page: GET returns the setup form (pre-filled with the current config); POST saves
 * the submitted values through the same [ScreensaverConfig] setters the on-Portal screens use, then
 * notifies the host via [onSaved]. It runs only while [LanSetupActivity] is foreground (started on
 * resume, stopped on pause), keeping the unauthenticated write window short on the trusted home LAN.
 */
class LanSetupServer(
    private val context: Context,
    private val onSaved: (String) -> Unit, // source label, delivered on the main thread
) {
  private val app = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  @Volatile private var running = false
  private var server: ServerSocket? = null

  /** The port we bound (0 until [start]). */
  var port: Int = 0
    private set

  fun start() {
    if (running) return
    val ss = runCatching { ServerSocket(PREFERRED_PORT) }.getOrElse { runCatching { ServerSocket(0) }.getOrNull() }
    if (ss == null) {
      Log.w(TAG, "couldn't bind a setup port")
      return
    }
    server = ss
    port = ss.localPort
    running = true
    thread(isDaemon = true, name = "lan-setup") { loop(ss) }
    Log.i(TAG, "setup server on ${lanIp()}:$port")
  }

  fun stop() {
    running = false
    runCatching { server?.close() }
    server = null
  }

  private fun loop(ss: ServerSocket) {
    while (running) {
      val sock = runCatching { ss.accept() }.getOrNull() ?: continue // throws when closed → exit
      runCatching { handle(sock) }.onFailure { Log.w(TAG, "request failed", it) }
      runCatching { sock.close() }
    }
  }

  private fun handle(sock: Socket) {
    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
    val requestLine = reader.readLine() ?: return
    val method = requestLine.substringBefore(' ')
    var contentLength = 0
    while (true) {
      val line = reader.readLine() ?: break
      if (line.isEmpty()) break
      if (line.startsWith("Content-Length:", ignoreCase = true))
          contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
    }
    if (method == "POST") {
      val buf = CharArray(contentLength)
      var read = 0
      while (read < contentLength) {
        val r = reader.read(buf, read, contentLength - read)
        if (r < 0) break
        read += r
      }
      val form = parseForm(String(buf, 0, read))
      val label = applyConfig(form)
      respond(sock, successHtml(label))
      if (label != null) main.post { onSaved(label) }
    } else {
      respond(sock, formHtml(ScreensaverConfig.load(app)))
    }
  }

  /** Save the submitted source through the same setters the on-Portal screens use. */
  private fun applyConfig(f: Map<String, String>): String? {
    fun v(k: String) = f[k]?.trim().orEmpty()
    return when (f["source"]) {
      "immich" -> {
        if (v("immich_url").isBlank() || v("immich_key").isBlank()) return null
        ScreensaverConfig.setImmich(app, v("immich_url"), v("immich_key"))
        "Immich"
      }
      "smb" -> {
        if (v("smb_host").isBlank() || v("smb_share").isBlank()) return null
        ScreensaverConfig.setSmb(
            app, v("smb_host"), v("smb_share"), v("smb_path"), v("smb_user"), v("smb_pass"))
        "Network share"
      }
      "dav" -> {
        if (v("dav_url").isBlank()) return null
        ScreensaverConfig.setDav(app, v("dav_url"), v("dav_user"), v("dav_pass"))
        "WebDAV folder"
      }
      "web" -> {
        if (v("web_url").isBlank()) return null
        ScreensaverConfig.setWebUrl(app, v("web_url"))
        "Web page"
      }
      "album" -> {
        if (v("album_url").isBlank()) return null
        ScreensaverConfig.setAlbumUrl(app, v("album_url"))
        "Shared album"
      }
      else -> null
    }
  }

  private fun parseForm(body: String): Map<String, String> =
      body.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i < 0) null
        else dec(it.substring(0, i)) to dec(it.substring(i + 1))
      }.toMap()

  private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

  private fun respond(sock: Socket, html: String) {
    val bytes = html.toByteArray(Charsets.UTF_8)
    val out = sock.getOutputStream()
    out.write(
        ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n")
            .toByteArray(Charsets.UTF_8))
    out.write(bytes)
    out.flush()
  }

  companion object {
    private const val TAG = "ImmortalLanSetup"
    private const val PREFERRED_PORT = 8765

    /** The device's site-local IPv4 (e.g. 192.168.x.x), or null if not on a LAN. */
    fun lanIp(): String? =
        runCatching {
              NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback }
                  .flatMap { it.inetAddresses.asSequence() }
                  .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                  ?.hostAddress
            }
            .getOrNull()
  }
}

/** A black-on-white QR code [Bitmap] for [text] (square, [sizePx] px), or null on failure. */
fun lanSetupQr(text: String, sizePx: Int): Bitmap? =
    runCatching {
          val matrix =
              QRCodeWriter().encode(
                  text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 1))
          val w = matrix.width
          val h = matrix.height
          val pixels = IntArray(w * h)
          for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
          }
          Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
          }
        }
        .getOrNull()

// ── Served pages ─────────────────────────────────────────────────────────────

private fun esc(s: String?): String =
    (s ?: "").replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

/** The success page shown on the phone after a submit. */
private fun successHtml(label: String?): String {
  val ok = label != null
  val msg =
      if (ok) "Saved. Your Portal is now using <b>${esc(label)}</b>." +
          " You can close this page."
      else "Something was missing — please go back and fill the required fields."
  return PAGE_HEAD +
      "<div class=card><h1>${if (ok) "All set" else "Not saved"}</h1><p>$msg</p>" +
      (if (!ok) "<p><a href=\"/\">Back to the form</a></p>" else "") +
      "</div></body></html>"
}

/** The setup form, pre-filled with the current config. Plain HTML + a little show/hide JS. */
private fun formHtml(s: ScreensaverConfig.Settings): String {
  val sel = { id: String -> if (currentSource(s) == id) "checked" else "" }
  return PAGE_HEAD +
      """
      <form class=card method=post action="/">
        <h1>Set up your photo source</h1>
        <p class=sub>Pick a source and enter its details. This sends them to your Portal.</p>

        <label class=pick><input type=radio name=source value=immich ${sel("immich")} onclick=show('immich')> Immich server</label>
        <label class=pick><input type=radio name=source value=smb ${sel("smb")} onclick=show('smb')> Network share (NAS)</label>
        <label class=pick><input type=radio name=source value=dav ${sel("dav")} onclick=show('dav')> WebDAV folder</label>
        <label class=pick><input type=radio name=source value=web ${sel("web")} onclick=show('web')> Web page</label>
        <label class=pick><input type=radio name=source value=album ${sel("album")} onclick=show('album')> Shared album link</label>

        <div class=fields id=f_immich>
          <input name=immich_url placeholder="Immich URL (http://192.168.x.x:2283)" value="${esc(s.immichUrl)}">
          <input name=immich_key placeholder="API key" value="${esc(s.immichKey)}">
          <p class=hint>Pulls your whole library. Pick a specific album later on the Portal if you like.</p>
        </div>
        <div class=fields id=f_smb>
          <input name=smb_host placeholder="Host or IP (192.168.x.x)" value="${esc(s.smbHost)}">
          <input name=smb_share placeholder="Share name" value="${esc(s.smbShare)}">
          <input name=smb_path placeholder="Folder path (optional)" value="${esc(s.smbPath)}">
          <input name=smb_user placeholder="Username (optional)" value="${esc(s.smbUser)}">
          <input name=smb_pass type=password placeholder="Password (optional)" value="${esc(s.smbPass)}">
        </div>
        <div class=fields id=f_dav>
          <input name=dav_url placeholder="WebDAV URL" value="${esc(s.davUrl)}">
          <input name=dav_user placeholder="Username (optional)" value="${esc(s.davUser)}">
          <input name=dav_pass type=password placeholder="Password (optional)" value="${esc(s.davPass)}">
        </div>
        <div class=fields id=f_web>
          <input name=web_url placeholder="Web page URL" value="${esc(s.webUrl)}">
        </div>
        <div class=fields id=f_album>
          <input name=album_url placeholder="iCloud or Google Photos share link" value="${esc(s.albumUrl)}">
        </div>

        <button type=submit>Send to Portal</button>
      </form>
      <script>
        function show(id){
          var all=document.querySelectorAll('.fields');
          for(var i=0;i<all.length;i++){all[i].style.display='none';}
          var el=document.getElementById('f_'+id); if(el) el.style.display='block';
        }
        var c=document.querySelector('input[name=source]:checked');
        show(c?c.value:'immich');
        if(!c){var first=document.querySelector('input[name=source]'); if(first) first.checked=true;}
      </script>
      </body></html>
      """
          .trimIndent()
}

private fun currentSource(s: ScreensaverConfig.Settings): String =
    when {
      s.usesImmich -> "immich"
      s.usesSmb -> "smb"
      s.usesDav -> "dav"
      s.usesWebUrl -> "web"
      s.usesUrl -> "album"
      else -> "immich"
    }

private val PAGE_HEAD =
    """
    <!doctype html><html><head>
    <meta charset=utf-8>
    <meta name=viewport content="width=device-width,initial-scale=1">
    <title>Immortal setup</title>
    <style>
      *{box-sizing:border-box}
      body{margin:0;background:#0e0e10;color:#fff;font-family:-apple-system,Roboto,Segoe UI,sans-serif;padding:18px}
      .card{max-width:520px;margin:0 auto;background:#1c1c1e;border-radius:18px;padding:22px}
      h1{font-size:22px;margin:0 0 4px}
      .sub{color:#9a9a9a;font-size:14px;margin:0 0 18px}
      .pick{display:block;padding:12px 4px;font-size:17px;border-bottom:1px solid #2a2a2c}
      .pick input{margin-right:10px;transform:scale(1.3)}
      .fields{display:none;padding:14px 0 2px}
      input[type=text],input:not([type]),input[type=password]{width:100%;margin:8px 0;padding:14px;
        font-size:17px;background:#0e0e10;border:1px solid #3a3a3c;border-radius:10px;color:#fff}
      .hint{color:#7c7c7c;font-size:13px;margin:6px 2px}
      button{width:100%;margin-top:20px;padding:16px;font-size:18px;font-weight:600;border:0;
        border-radius:12px;background:#2e6be6;color:#fff}
      a{color:#8ab4f8}
    </style></head><body>
    """
        .trimIndent()
