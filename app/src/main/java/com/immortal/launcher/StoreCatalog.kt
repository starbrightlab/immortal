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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/** One installable catalog entry (schema v2 — all v2 fields optional/additive). */
data class CatalogApp(
    val name: String,
    val packageName: String,
    val source: String, // "fdroid" | "url"
    val fdroidId: String?,
    val apkUrl: String?,
    val versionCode: Long?, // optional pin (e.g. the arm64 build of a multi-ABI app)
    val versionUrl: String? = null, // optional: a JSON URL to live-resolve the latest versionCode (url source)
    val description: String,
    val category: String,
    val minSdk: Int? = null,
    val longDescription: String? = null,
    val iconUrl: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    val submittedBy: String? = null,
    val devices: List<String> = emptyList(), // empty = every Portal
)

/** Broadcast action the PackageInstaller session reports status to. */
const val STORE_INSTALL_ACTION = "com.immortal.launcher.STORE_INSTALL_STATUS"
const val STORE_EXTRA_PKG = "pkg"

/**
 * Catalog loader + installer. Reads the JSON catalog (from a hosted URL, with a
 * bundled asset fallback), resolves each entry to a concrete APK URL —
 * F-Droid entries are resolved live via the F-Droid API so they never go
 * stale — downloads it, and installs via [PackageInstaller]. Installs still
 * require the user-confirm dialog unless the device's verifier is disabled
 * (see the provisioning kit).
 */
object StoreCatalog {
  // Point this at your hosted catalog (e.g. a GitHub raw URL). Falls back to the
  // bundled assets/catalog.json if the network copy can't be reached.
  private const val CATALOG_URL =
      "https://raw.githubusercontent.com/starbrightlab/immortal/main/catalog.json"

  private val io = Executors.newSingleThreadExecutor()
  // Lazy so touching the pure helpers (parse/resolveApkUrl) in a unit test doesn't
  // eagerly construct a main-looper Handler (unavailable off-device).
  private val main by lazy { Handler(Looper.getMainLooper()) }

  private const val TAG = "ImmortalStore"

  fun loadCatalog(context: Context, onResult: (List<CatalogApp>) -> Unit) {
    io.execute {
      // Asset-first: the bundled catalog always renders instantly and offline,
      // so the store is never empty even if the network or remote file fails.
      val bundled =
          runCatching {
                context.assets.open("catalog.json").use { it.readBytes().toString(Charsets.UTF_8) }
              }
              .mapCatching { parse(it) }
              .onFailure { android.util.Log.w(TAG, "bundled catalog failed", it) }
              .getOrDefault(emptyList())
      if (bundled.isNotEmpty()) main.post { onResult(bundled) }

      // Then refresh from the hosted catalog if reachable and newer-shaped.
      val remote =
          runCatching { httpGet(CATALOG_URL) }
              .onFailure { android.util.Log.w(TAG, "remote catalog fetch failed", it) }
              .mapCatching { parse(it) }
              .onFailure { android.util.Log.w(TAG, "remote catalog parse failed", it) }
              .getOrDefault(emptyList())
      android.util.Log.i(TAG, "catalog loaded: bundled=${bundled.size} remote=${remote.size}")
      if (remote.isNotEmpty()) main.post { onResult(remote) }
      else if (bundled.isEmpty()) main.post { onResult(emptyList()) }
    }
  }

  internal fun parse(json: String): List<CatalogApp> {
    val out = mutableListOf<CatalogApp>()
    val cats = JSONObject(json).getJSONArray("categories")
    for (i in 0 until cats.length()) {
      val cat = cats.getJSONObject(i)
      val catName = cat.getString("name")
      val apps = cat.getJSONArray("apps")
      for (j in 0 until apps.length()) {
        val a = apps.getJSONObject(j)
        val devicesArr = a.optJSONArray("devices")
        out +=
            CatalogApp(
                name = a.getString("name"),
                packageName = a.getString("packageName"),
                source = a.optString("source", "fdroid"),
                fdroidId = a.optString("fdroidId").ifBlank { null },
                apkUrl = a.optString("apkUrl").ifBlank { null },
                versionCode = if (a.has("versionCode")) a.getLong("versionCode") else null,
                versionUrl = a.optString("versionUrl").ifBlank { null },
                description = a.optString("description", ""),
                category = catName,
                minSdk = if (a.has("minSdk")) a.getInt("minSdk") else null,
                longDescription = a.optString("longDescription").ifBlank { null },
                iconUrl = a.optString("iconUrl").ifBlank { null },
                author = a.optString("author").ifBlank { null },
                homepage = a.optString("homepage").ifBlank { null },
                submittedBy = a.optString("submittedBy").ifBlank { null },
                devices =
                    (0 until (devicesArr?.length() ?: 0)).mapNotNull {
                      devicesArr?.optString(it)?.ifBlank { null }
                    },
            )
      }
    }
    return out
  }

  fun isInstalled(context: Context, pkg: String): Boolean =
      runCatching { context.packageManager.getPackageInfo(pkg, 0); true }
          .getOrDefault(false)

  /** Whether [app] can run on this device (pure for tests). */
  internal fun isCompatible(minSdk: Int?, deviceSdk: Int): Boolean =
      minSdk == null || minSdk <= deviceSdk

  fun isCompatible(app: CatalogApp): Boolean = isCompatible(app.minSdk, Build.VERSION.SDK_INT)

  /** "Needs Android 11 or newer" label for an incompatible app (pure for tests). */
  internal fun incompatibleLabel(minSdk: Int): String {
    val release =
        mapOf(26 to "8", 27 to "8.1", 28 to "9", 29 to "10", 30 to "11", 31 to "12", 32 to "12",
            33 to "13", 34 to "14", 35 to "15", 36 to "16")
    return "Needs Android ${release[minSdk] ?: "API $minSdk"}+"
  }

  fun installedVersionCode(context: Context, pkg: String): Long? =
      runCatching {
            val pi = context.packageManager.getPackageInfo(pkg, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
          }
          .getOrNull()

  /**
   * Find updates for INSTALLED catalog apps: resolves the latest versionCode (see
   * [latestVersionCode]) and compares with what's on the device. Skips our own
   * package — the launcher has its own self-updater. Calls [onResult] on the main
   * thread with packageName -> latest versionCode.
   */
  fun findUpdates(context: Context, apps: List<CatalogApp>, onResult: (Map<String, Long>) -> Unit) {
    io.execute {
      val updates = mutableMapOf<String, Long>()
      for (app in apps) {
        if (app.packageName == context.packageName) continue
        val installed = installedVersionCode(context, app.packageName) ?: continue
        val latest = latestVersionCode(app) ?: continue
        if (latest > installed) updates[app.packageName] = latest
      }
      main.post { onResult(updates) }
    }
  }

  /**
   * The newest versionCode available for [app], or null if it can't be determined.
   * Resolution order, first hit wins:
   *  1. a catalog `versionCode` — an explicit pin, authoritative for ANY source
   *     (the ABI pin for a multi-ABI F-Droid build, or a frozen version for a
   *     direct-URL app). Bump it in the catalog whenever you publish a new APK.
   *  2. a `versionUrl` — a JSON document (e.g. the app's own `version.json`) with a
   *     `versionCode` field, fetched live. This lets a self-published direct-URL
   *     app stay current with NO catalog edit per release, the same way F-Droid
   *     entries do: point `apkUrl` at a stable `releases/latest/download/...` URL
   *     and `versionUrl` at the manifest the release process already updates.
   *  3. for `fdroid` sources with neither, the suggested build from the F-Droid API.
   * A direct-URL entry with none of these carries no version metadata, so it can't
   * be update-checked — it just won't show an Update badge.
   *
   * [http] is injectable so the URL/pin paths are unit-testable without a network.
   */
  internal fun latestVersionCode(app: CatalogApp, http: (String) -> String = ::httpGet): Long? {
    app.versionCode?.let { return it }
    app.versionUrl?.let { url ->
      return runCatching { JSONObject(http(url)).getLong("versionCode") }.getOrNull()
    }
    if (app.source != "fdroid") return null
    return runCatching {
          JSONObject(http("https://f-droid.org/api/v1/packages/${app.fdroidId ?: app.packageName}"))
              .getLong("suggestedVersionCode")
        }
        .getOrNull()
  }

  /**
   * Resolve -> download -> commit. [status] is called on the main thread with
   * progress text; terminal SUCCESS/FAILURE arrives via the STORE_INSTALL_ACTION
   * broadcast handled by the host activity.
   */
  fun install(context: Context, app: CatalogApp, status: (String, String) -> Unit) {
    // Gen-1 with the daemon down: the system installer is broken, so don't even
    // download — tell the user how to re-enable installs (it's not a bug).
    if (InstallDaemon.installPaused(context)) {
      status(app.packageName, "Paused — connect to your computer to add apps")
      return
    }
    status(app.packageName, "Resolving…")
    io.execute {
      try {
        val url = resolveApkUrl(app)
        main.post { status(app.packageName, "Downloading…") }
        val apk = File(context.cacheDir, "${app.packageName}.apk")
        download(url, apk)
        main.post { status(app.packageName, "Installing…") }
        if (InstallDaemon.isAvailable(context)) {
          // Silent install via the provisioning daemon — no system dialog.
          val ok = InstallDaemon.install(context, apk, app.packageName)
          main.post { status(app.packageName, if (ok) "Installed ✓" else "Install failed") }
        } else {
          // Android-10 models — and Gen-1 once the overlay fix makes the stock
          // dialog visible — use the system installer dialog.
          PackageInstallSessions.commit(context, apk, STORE_INSTALL_ACTION) {
            putExtra(STORE_EXTRA_PKG, app.packageName)
          }
        }
      } catch (t: Throwable) {
        main.post { status(app.packageName, "Error: ${t.message ?: t.javaClass.simpleName}") }
      }
    }
  }

  internal fun resolveApkUrl(app: CatalogApp): String {
    if (app.source == "url" && !app.apkUrl.isNullOrBlank()) return app.apkUrl!!
    val id = app.fdroidId ?: app.packageName
    // A pinned versionCode (e.g. the arm64 build of a multi-ABI app like VLC)
    // wins; otherwise resolve the current suggested build from the F-Droid API.
    val vc =
        app.versionCode
            ?: JSONObject(httpGet("https://f-droid.org/api/v1/packages/$id"))
                .getLong("suggestedVersionCode")
    return "https://f-droid.org/repo/${id}_$vc.apk"
  }

  // --- net helpers ------------------------------------------------------------
  private fun httpGet(spec: String): String {
    val c = open(spec)
    return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
  }

  /** Download [spec] to [dest]. Internal so the fleet agent's headless install can
   *  reuse the exact same fetch (resolve via [resolveApkUrl], then download). */
  internal fun download(spec: String, dest: File) {
    val c = open(spec)
    c.inputStream.use { input -> dest.outputStream.use { input.copyTo(it) } }
  }

  private val File.outputStream
    get() = java.io.FileOutputStream(this)

  private fun open(spec: String): HttpURLConnection {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 10000
    c.readTimeout = 30000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "PortalStore/1.0")
    return c
  }
}
