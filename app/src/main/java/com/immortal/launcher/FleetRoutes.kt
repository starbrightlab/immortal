/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Fleet Agent's HTTP API — the only place that touches app subsystems
 * ([StoreCatalog], [InstallDaemon], [PresenceHub], [FleetConfig], [SettingsGuard]).
 * Kept separate from the socket plumbing ([FleetHttpServer]) so the routing and
 * JSON shaping are JVM-unit-testable.
 *
 * Every request must carry `Authorization: Bearer <token>` (the per-device token
 * from [FleetConfig]); anything else gets 401 before any work happens. This is a
 * pure CONSUMER of existing APIs — it adds no install/catalog logic of its own,
 * so the App Store and the fleet always install apps the exact same way.
 */
class FleetRoutes(private val context: Context) {

  @Volatile private var catalog: List<CatalogApp> = emptyList()
  private val inFlight = ConcurrentHashMap.newKeySet<String>()

  /** The phone-remote surface (`/remote/…`), which does its own pairing/session auth. */
  private val remote = RemoteRoutes(context)

  /** Load (and later refresh) the catalog snapshot used by /info, /install, /update. */
  fun refreshCatalog() {
    StoreCatalog.loadCatalog(context) { catalog = it }
  }

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    // The phone remote authenticates by paired session (or fleet token), not by the
    // bearer gate below — so it's routed first, before the token check.
    if (req.path.startsWith("/remote/")) return remote.handle(req)
    if (!authorized(req)) return resp(401, err("unauthorized"))
    return when (req.path) {
      "/info" -> requireMethod("GET", req) { info() }
      "/apps" -> requireMethod("GET", req) { apps() }
      "/install" -> requireMethod("POST", req) { install(req) }
      "/update" -> requireMethod("POST", req) { update(req) }
      "/dev" -> dev(req)
      "/config" -> requireMethod("POST", req) { config(req) }
      "/calendar" -> calendar(req)
      "/screensaver" -> screensaver(req)
      "/action" -> requireMethod("POST", req) { action(req) }
      "/fs/list" -> requireMethod("GET", req) { resp(200, FleetFs.list(req.queryParam("path") ?: "/sdcard")) }
      "/fs/read" -> requireMethod("GET", req) { fsRead(req) }
      "/fs/write" -> requireMethod("POST", req) { fsWrite(req) }
      "/logcat" -> requireMethod("GET", req) { logcat(req) }
      "/diag" -> requireMethod("GET", req) { resp(200, FleetDiag.snapshot()) }
      else -> resp(404, err("not_found"))
    }
  }

  // --- endpoints --------------------------------------------------------------

  private fun info(): FleetHttpServer.Response {
    val (code, vname) = appVersion()
    val m = installMode()
    val p = PresenceHub.current
    val o =
        ok()
            .put("name", FleetConfig.name(context))
            .put("model", Build.MODEL ?: "")
            .put("device", Build.DEVICE ?: "")
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("app", JSONObject().put("versionCode", code).put("versionName", vname))
            .put("ip", currentIp() ?: "")
            .put("port", FleetConfig.port(context))
            .put(
                "presence",
                JSONObject()
                    .put("presence", p.presence.name)
                    .put("screen", p.screen.name)
                    .put("confident", p.confident))
            .put(
                "install",
                JSONObject()
                    .put("mode", m.mode)
                    .put("daemonAvailable", m.daemonAvailable)
                    .put("legacyInstaller", m.legacy)
                    .put("dialogFixed", m.dialogFixed)
                    .put("paused", m.paused)
                    // dialog-mode is still unattended when auto-confirm is on
                    .put("autoConfirm", SettingsGuard.isInstallConfirmEnabled(context)))
            .put("canWriteSecureSettings", SettingsGuard.canWriteSecureSettings(context))
            .put("devMode", DevMode.isEnabled(context))
            .put(
                "capabilities",
                JSONObject().put("files", true).put("diag", true).put("calendar", true).put("screensaver", true).put("dev", true).put("logcat", canReadLogs()))
    // Installed catalog apps + a CHEAP (no-network) update hint from the catalog pin.
    // The authoritative F-Droid check is POST /update {"dryRun":true}, kept off the
    // hot dashboard-poll path so /info stays fast across a whole wall of devices.
    val arr = JSONArray()
    for (app in catalog) {
      val installed = StoreCatalog.installedVersionCode(context, app.packageName) ?: continue
      val a = JSONObject().put("packageName", app.packageName).put("name", app.name).put("installed", installed)
      app.versionCode?.let {
        a.put("catalogVersion", it)
        a.put("updateAvailable", it > installed)
      }
      arr.put(a)
    }
    o.put("apps", arr)
    return resp(200, o)
  }

  private fun apps(): FleetHttpServer.Response {
    if (catalog.isEmpty()) return resp(503, err("catalog_not_ready"))
    val arr = JSONArray()
    for (app in catalog) {
      val a =
          JSONObject()
              .put("name", app.name)
              .put("packageName", app.packageName)
              .put("source", app.source)
              .put("category", app.category)
              .put("installed", StoreCatalog.isInstalled(context, app.packageName))
      app.versionCode?.let { a.put("versionCode", it) }
      arr.put(a)
    }
    return resp(200, ok().put("apps", arr))
  }

  private fun install(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return resp(400, err("bad_json"))
    // Local-build install: a device-side APK path (pushed via /fs/write), installed
    // directly — no catalog lookup, no versionCode gate. This is the dev-iteration
    // path (e.g. `fleetctl dev update`). The package is derived from the APK if not
    // given. NOTE: an in-place update is OS signature-checked, so a local build must
    // be signed with the same key as the installed one (see the dev-mode docs).
    //
    // Gated behind dev mode: installing an arbitrary device-side APK sidesteps the
    // catalog/versionCode gate, so it's only allowed when the operator has explicitly
    // opted in via /dev. Outside dev mode the token only installs known catalog apps.
    val localPath = body.optString("path").ifBlank { null }
    if (localPath != null) {
      if (!DevMode.isEnabled(context)) return resp(403, err("dev_mode_required"))
      return installFile(localPath, body.optString("packageName").ifBlank { null })
    }

    val pkg = body.optString("packageName").ifBlank { null } ?: return resp(400, err("packageName_required"))
    val apkUrl = body.optString("apkUrl").ifBlank { null }
    val app =
        if (apkUrl != null) syntheticUrlApp(pkg, apkUrl)
        else
            catalog.firstOrNull { it.packageName == pkg }
                ?: return if (catalog.isEmpty()) resp(503, err("catalog_not_ready"))
                else resp(404, err("unknown_package"))
    val result = doInstall(app)
    val status = if (result == BUSY) 409 else 200
    return resp(
        status,
        JSONObject()
            .put("ok", result == "installed")
            .put("packageName", pkg)
            .put("result", result)
            .put("mode", installMode().mode))
  }

  private fun update(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: JSONObject()
    val updates = findUpdatesBlocking(catalog)
    if (body.optBoolean("dryRun", false)) {
      val arr = JSONArray()
      updates.forEach { (p, v) -> arr.put(JSONObject().put("packageName", p).put("latest", v)) }
      return resp(200, ok().put("updates", arr))
    }
    val targets =
        when {
          body.optBoolean("all", false) -> updates.keys.toList()
          body.optString("packageName").isNotBlank() ->
              body.getString("packageName").let { if (updates.containsKey(it)) listOf(it) else emptyList() }
          else -> return resp(400, err("specify_all_or_packageName"))
        }
    val results = JSONArray()
    for (p in targets) {
      val app = catalog.firstOrNull { it.packageName == p } ?: continue
      results.put(JSONObject().put("packageName", p).put("result", doInstall(app)))
    }
    return resp(200, ok().put("count", targets.size).put("updated", results))
  }

  /**
   * Read (GET) or toggle (POST `{"enabled":bool}`) developer mode. When on, the
   * device's official self-updater is paused so a locally-pushed build isn't
   * overwritten (see [DevMode] / `fleetctl dev`).
   */
  private fun dev(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.method) {
        "GET" -> resp(200, ok().put("dev", devStatus()))
        "POST" -> {
          val body = parseJson(req.bodyText())
          if (body == null) resp(400, err("bad_json"))
          else {
            if (body.has("enabled")) DevMode.setEnabled(context, body.optBoolean("enabled"))
            resp(200, ok().put("dev", devStatus()))
          }
        }
        else -> resp(405, err("method_not_allowed"))
      }

  private fun devStatus(): JSONObject {
    val (code, name) = appVersion()
    return DevMode.statusJson(DevMode.isEnabled(context), code, name)
  }

  private fun config(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return resp(400, err("bad_json"))
    if (body.has("name")) FleetConfig.setName(context, body.getString("name"))
    body.optJSONObject("set")?.let { set ->
      for (k in set.keys()) FleetConfig.setValue(context, k, set.get(k).toString())
    }
    val cfg = JSONObject()
    FleetConfig.allValues(context).forEach { (k, v) -> cfg.put(k, v) }
    return resp(200, ok().put("name", FleetConfig.name(context)).put("config", cfg))
  }

  /**
   * Read (GET) or push (POST) the screensaver calendar's feed link + display range,
   * so a fleet can be configured over WiFi without wireless ADB. Pushes apply live —
   * the running photo frame reloads the calendar on its refresh loop — so no reboot
   * or re-dream is needed.
   */
  private fun calendar(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.method) {
        "GET" -> resp(200, ok().put("calendar", FleetCalendar.toJson(ScreensaverConfig.load(context))))
        "POST" -> {
          val body = parseJson(req.bodyText())
          if (body == null) resp(400, err("bad_json"))
          else {
            val applied = FleetCalendar.apply(context, body)
            resp(
                200,
                ok()
                    .put("applied", JSONArray(applied))
                    .put("calendar", FleetCalendar.toJson(ScreensaverConfig.load(context))))
          }
        }
        else -> resp(405, err("method_not_allowed"))
      }

  /**
   * Read (GET) or push (POST) the photo-frame screensaver config — source, fit,
   * interval, shuffle, videos, now-playing, presence/power, and idle/overnight
   * screen-off. Lets a fleet be configured over WiFi without wireless ADB. Display
   * changes take effect on the next screensaver cycle (as in the in-app settings);
   * `enabled` and overnight changes apply immediately — we reaffirm screensaver
   * ownership and reschedule the overnight window after a push.
   */
  private fun screensaver(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.method) {
        "GET" ->
            resp(200, ok().put("screensaver", FleetScreensaver.toJson(ScreensaverConfig.load(context))))
        "POST" -> {
          val body = parseJson(req.bodyText())
          if (body == null) resp(400, err("bad_json"))
          else {
            val applied = FleetScreensaver.apply(context, body)
            SettingsGuard.reaffirmScreensaver(context)
            // Overnight alarms are armed eagerly so a window change takes effect now.
            if (applied.any { it.startsWith("overnight") }) SleepScheduler.applyOvernightNow(context)
            resp(
                200,
                ok()
                    .put("applied", JSONArray(applied))
                    .put("screensaver", FleetScreensaver.toJson(ScreensaverConfig.load(context))))
          }
        }
        else -> resp(405, err("method_not_allowed"))
      }

  private fun action(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return resp(400, err("bad_json"))
    return when (body.optString("action")) {
      "reaffirm" -> {
        SettingsGuard.reaffirmAdb(context)
        SettingsGuard.reaffirmScreensaver(context)
        resp(200, ok().put("action", "reaffirm"))
      }
      "identify" -> {
        identify()
        resp(200, ok().put("action", "identify"))
      }
      // Needs the signature/privileged REBOOT permission we don't hold — report honestly.
      "reboot" -> resp(200, JSONObject().put("ok", false).put("action", "reboot").put("result", "not_permitted"))
      else -> resp(400, err("unknown_action"))
    }
  }

  // --- install plumbing -------------------------------------------------------

  /**
   * Headless install. Uses the silent shell-daemon path when it's available,
   * otherwise falls back to a PackageInstaller dialog that [InstallConfirmService]
   * auto-confirms — so a rebooted Portal (daemon gone) can still install unattended.
   * Only a truly paused Gen-1 (no daemon AND no working dialog) is refused.
   */
  private fun doInstall(app: CatalogApp): String {
    val m = installMode()
    if (m.paused) return "paused"
    if (!inFlight.add(app.packageName)) return BUSY
    return try {
      if (m.mode == "silent") silentInstall(app) else dialogInstall(app)
    } finally {
      inFlight.remove(app.packageName)
    }
  }

  /**
   * Install a local device-path APK directly (the dev-iteration path). Mirrors
   * [doInstall] but skips any download — the file is already on the device (pushed
   * via /fs/write). [pkg] labels the daemon queue entry / install session.
   */
  private fun installFile(path: String, pkgArg: String?): FleetHttpServer.Response {
    val apk = File(path)
    if (!apk.isFile || !apk.canRead()) return resp(404, err("file_not_found"))
    val pkg =
        pkgArg
            ?: runCatching { context.packageManager.getPackageArchiveInfo(path, 0)?.packageName }
                .getOrNull()
            ?: return resp(400, err("packageName_required"))
    val result = doInstallFile(apk, pkg)
    val status = if (result == BUSY) 409 else 200
    return resp(
        status,
        JSONObject()
            .put("ok", result == "installed")
            .put("packageName", pkg)
            .put("result", result)
            .put("mode", installMode().mode))
  }

  private fun doInstallFile(apk: File, pkg: String): String {
    val m = installMode()
    if (m.paused) return "paused"
    if (!inFlight.add(pkg)) return BUSY
    return try {
      val ok =
          if (m.mode == "silent") InstallDaemon.install(context, apk, pkg)
          else HeadlessInstaller.install(context, apk, pkg)
      if (ok) "installed" else "failed"
    } finally {
      inFlight.remove(pkg)
    }
  }

  /** Silent path: hand off to the store's shell-daemon install, latch its status. */
  private fun silentInstall(app: CatalogApp): String {
    val latch = CountDownLatch(1)
    val result = AtomicReference<Boolean?>(null)
    StoreCatalog.install(context, app) { _, msg ->
      terminalResult(msg)?.let {
        result.set(it)
        latch.countDown()
      }
    }
    val done = latch.await(190, TimeUnit.SECONDS)
    return when {
      !done -> "timeout"
      result.get() == true -> "installed"
      else -> "failed"
    }
  }

  /** No daemon: download then PackageInstaller, with the installer dialog
   *  auto-confirmed by [InstallConfirmService] (needs SYSTEM_ALERT_WINDOW). */
  private fun dialogInstall(app: CatalogApp): String {
    val apk = File(context.cacheDir, "${app.packageName}.fleet.apk")
    return try {
      StoreCatalog.download(StoreCatalog.resolveApkUrl(app), apk)
      if (HeadlessInstaller.install(context, apk, app.packageName)) "installed" else "failed"
    } catch (t: Throwable) {
      "failed"
    } finally {
      runCatching { apk.delete() }
    }
  }

  private fun findUpdatesBlocking(apps: List<CatalogApp>, timeoutMs: Long = 30_000): Map<String, Long> {
    val latch = CountDownLatch(1)
    val ref = AtomicReference<Map<String, Long>>(emptyMap())
    StoreCatalog.findUpdates(context, apps) {
      ref.set(it)
      latch.countDown()
    }
    latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    return ref.get()
  }

  private fun syntheticUrlApp(pkg: String, url: String) =
      CatalogApp(
          name = pkg,
          packageName = pkg,
          source = "url",
          fdroidId = null,
          apkUrl = url,
          versionCode = null,
          description = "",
          category = "")

  // --- files + logs -----------------------------------------------------------

  private fun fsRead(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val path = req.queryParam("path") ?: return resp(400, err("path_required"))
    val f = File(path)
    if (!f.isFile || !f.canRead()) return resp(404, err("not_a_readable_file"))
    val name = f.name.replace("\"", "")
    return FleetHttpServer.Response.stream(
        200,
        "application/octet-stream",
        f.length(),
        headers = mapOf("Content-Disposition" to "attachment; filename=\"$name\"")) { out ->
          f.inputStream().use { it.copyTo(out) }
        }
  }

  private fun fsWrite(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val path = req.queryParam("path") ?: return resp(400, err("path_required"))
    val f = File(path)
    return try {
      f.parentFile?.mkdirs()
      val written = f.outputStream().use { req.streamBodyTo(it) }
      resp(200, ok().put("path", f.absolutePath).put("bytes", written))
    } catch (t: Throwable) {
      resp(200, err("write_failed").put("detail", t.message ?: ""))
    }
  }

  private fun logcat(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val lines = req.queryParam("lines")?.toIntOrNull() ?: 500
    val bytes = FleetDiag.logcat(lines).toByteArray(Charsets.UTF_8)
    return FleetHttpServer.Response.stream(200, "text/plain; charset=utf-8", bytes.size.toLong()) {
      it.write(bytes)
    }
  }

  private fun canReadLogs(): Boolean =
      context.checkSelfPermission(android.Manifest.permission.READ_LOGS) ==
          android.content.pm.PackageManager.PERMISSION_GRANTED

  // --- misc helpers -----------------------------------------------------------

  private fun identify() {
    Handler(Looper.getMainLooper()).post {
      runCatching {
        Toast.makeText(context, "Immortal Fleet: ${FleetConfig.name(context)}", Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun appVersion(): Pair<Long, String> =
      runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code =
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                else @Suppress("DEPRECATION") pi.versionCode.toLong()
            code to (pi.versionName ?: "")
          }
          .getOrDefault(0L to "")

  private data class InstallModeInfo(
      val mode: String,
      val daemonAvailable: Boolean,
      val legacy: Boolean,
      val dialogFixed: Boolean,
      val paused: Boolean,
  )

  private fun installMode(): InstallModeInfo {
    val daemon = InstallDaemon.isAvailable(context)
    val paused = InstallDaemon.installPaused(context)
    return InstallModeInfo(
        modeFor(daemon, paused),
        daemon,
        InstallDaemon.legacyInstaller(),
        InstallDaemon.installerDialogFixed(context),
        paused)
  }

  /** Current LAN IPv4, preferring the wlan interface (computed fresh — DHCP-safe). */
  private fun currentIp(): String? =
      runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { it.name.startsWith("wlan") }
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull {
                  it is Inet4Address &&
                      !it.isLoopbackAddress &&
                      (it.isSiteLocalAddress || it.isLinkLocalAddress)
                }
                ?.hostAddress
          }
          .getOrNull()

  // --- auth + response shaping ------------------------------------------------

  private fun authorized(req: FleetHttpServer.Request): Boolean {
    val got = req.header("authorization")?.removePrefix("Bearer ")?.trim() ?: return false
    return constantTimeEquals(got, FleetConfig.token(context))
  }

  private inline fun requireMethod(
      method: String,
      req: FleetHttpServer.Request,
      block: () -> FleetHttpServer.Response,
  ): FleetHttpServer.Response = if (req.method == method) block() else resp(405, err("method_not_allowed"))

  private fun ok(): JSONObject = JSONObject().put("ok", true)

  private fun err(code: String): JSONObject = JSONObject().put("ok", false).put("error", code)

  private fun parseJson(s: String): JSONObject? =
      runCatching { JSONObject(if (s.isBlank()) "{}" else s) }.getOrNull()

  private fun resp(status: Int, obj: JSONObject) = FleetHttpServer.Response(status, obj.toString())

  internal companion object {
    const val BUSY = "busy"

    /** Derive the install-mode label reported in /info and gating /install. Pure. */
    internal fun modeFor(daemonAvailable: Boolean, paused: Boolean): String =
        when {
          daemonAvailable -> "silent"
          paused -> "paused"
          else -> "dialog"
        }

    /** Map a store status string to a terminal result, or null for progress updates. Pure. */
    internal fun terminalResult(msg: String): Boolean? =
        when {
          msg.contains("Installed ✓") -> true
          msg.startsWith("Install failed") || msg.startsWith("Error") || msg.startsWith("Paused") -> false
          else -> null
        }

    /** Length-checked constant-time token compare. Pure. */
    internal fun constantTimeEquals(a: String, b: String): Boolean {
      if (a.length != b.length) return false
      var r = 0
      for (i in a.indices) r = r or (a[i].code xor b[i].code)
      return r == 0
    }
  }
}
