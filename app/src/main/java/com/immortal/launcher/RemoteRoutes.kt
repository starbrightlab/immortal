/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * The phone-remote HTTP surface (`/remote/…`), delegated to from [FleetRoutes]. Served
 * by the same always-on, LAN-guarded fleet agent, but with its **own** auth: the public
 * setup page and PIN exchange need no token, while the input endpoints require a paired
 * session token (or the fleet bearer token, so the laptop CLI can drive it too). See
 * [RemotePairing] for the pairing model and [RemoteInput] for what input we can inject.
 *
 * Phase 1 is the Tier-A soft remote: global-action nav buttons + an app-launcher grid.
 * Keyboard, focus nav, gesture touchpad and presets extend these same routes later.
 */
class RemoteRoutes(private val context: Context) {

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response =
      when (req.path) {
        // Public (LAN-guarded only): the page itself carries no secrets, and pairing
        // is gated by the PIN shown on the Portal's screen.
        "/remote/ui" -> requireMethod("GET", req) { html(RemoteHtml.PAGE) }
        "/remote/pair" -> requireMethod("POST", req) { pair(req) }
        // App icons aren't sensitive; serving them unauthenticated keeps the token out
        // of <img> URLs. Still behind the server's LAN-only peer guard.
        "/remote/icon" -> requireMethod("GET", req) { icon(req) }
        // Authenticated: anything that reads the app list or drives input.
        "/remote/apps" -> authed(req) { apps() }
        "/remote/key" -> authed(req) { key(req) }
        "/remote/launch" -> authed(req) { launch(req) }
        "/remote/text" -> authed(req) { text(req) }
        "/remote/cursor" -> authed(req) { cursor(req) }
        "/remote/tap" -> authed(req) { tap() }
        "/remote/swipe" -> authed(req) { swipe(req) }
        else -> json(404, err("not_found"))
      }

  // --- endpoints --------------------------------------------------------------

  private fun pair(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    val token = RemotePairing.redeem(context, body.optString("pin")) ?: return json(401, err("bad_pin"))
    return json(200, ok().put("token", token).put("name", FleetConfig.name(context)))
  }

  private fun apps(): FleetHttpServer.Response =
      json(200, ok().put("apps", RemoteApps.listJson(context)))

  private fun icon(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val pkg = req.queryParam("pkg") ?: return json(400, err("pkg_required"))
    val png = RemoteApps.iconPng(context, pkg) ?: return json(404, err("no_icon"))
    return FleetHttpServer.Response.stream(200, "image/png", png.size.toLong()) { it.write(png) }
  }

  private fun key(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    val action = body.optString("action")
    // The Portal has no system Recents; "apps" opens the launcher's own app switcher instead.
    if (action == "apps") return openAppSwitcher()
    if (RemoteInput.globalActionCode(action) == null) return json(400, err("unknown_action"))
    if (!RemoteInput.available()) return json(503, err("no_accessibility"))
    val dispatched = RemoteInput.globalAction(action)
    return json(200, ok().put("action", action).put("dispatched", dispatched))
  }

  /** Bring up Immortal's in-app app switcher ([AppSwitcherActivity]) — our stand-in for the
   *  Portal's missing system Recents. Same-app start, so the non-exported activity is fine. */
  private fun openAppSwitcher(): FleetHttpServer.Response {
    val ok =
        runCatching {
              context.startActivity(
                  Intent(context, AppSwitcherActivity::class.java)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              true
            }
            .getOrDefault(false)
    return json(if (ok) 200 else 500, JSONObject().put("ok", ok).put("action", "apps"))
  }

  private fun launch(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    val pkg = body.optString("packageName").ifBlank { null } ?: return json(400, err("packageName_required"))
    val launched = RemoteApps.launch(context, pkg)
    return json(if (launched) 200 else 404, JSONObject().put("ok", launched).put("packageName", pkg))
  }

  /** Edit the focused field: `{"mode":"set|append|backspace|clear","text":"…"}`. */
  private fun text(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    if (!RemoteInput.available()) return json(503, err("no_accessibility"))
    val mode = body.optString("mode").ifBlank { "set" }
    val applied = RemoteInput.typeText(body.optString("text"), mode)
    // applied=false usually means no editable field has focus — report it so the UI can hint.
    return json(200, ok().put("applied", applied).put("mode", mode))
  }

  /** Move the on-TV pointer by a relative delta: `{"dx":12.0,"dy":-4.0}` (screen px). */
  private fun cursor(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    if (!RemoteInput.gesturesAvailable()) return json(503, err("no_gestures"))
    RemoteInput.cursorMove(body.optDouble("dx", 0.0).toFloat(), body.optDouble("dy", 0.0).toFloat())
    return json(200, ok())
  }

  /** Tap at the current pointer position (synthesized touch). */
  private fun tap(): FleetHttpServer.Response {
    if (!RemoteInput.gesturesAvailable()) return json(503, err("no_gestures"))
    return json(200, ok().put("dispatched", RemoteInput.tap()))
  }

  /** Scroll/swipe from the pointer by a relative delta: `{"dx":0.0,"dy":-300.0}`. */
  private fun swipe(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText()) ?: return json(400, err("bad_json"))
    if (!RemoteInput.gesturesAvailable()) return json(503, err("no_gestures"))
    return json(
        200,
        ok()
            .put(
                "dispatched",
                RemoteInput.swipe(
                    body.optDouble("dx", 0.0).toFloat(), body.optDouble("dy", 0.0).toFloat())))
  }

  // --- auth + helpers ---------------------------------------------------------

  /** Run [block] only for a request bearing a valid session or the fleet token. */
  private inline fun authed(
      req: FleetHttpServer.Request,
      block: () -> FleetHttpServer.Response,
  ): FleetHttpServer.Response = if (authorized(req)) block() else json(401, err("unauthorized"))

  private fun authorized(req: FleetHttpServer.Request): Boolean {
    val token = bearer(req.header("authorization")) ?: return false
    return RemotePairing.isValidSession(context, token) ||
        FleetRoutes.constantTimeEquals(token, FleetConfig.token(context))
  }

  private inline fun requireMethod(
      method: String,
      req: FleetHttpServer.Request,
      block: () -> FleetHttpServer.Response,
  ): FleetHttpServer.Response = if (req.method == method) block() else json(405, err("method_not_allowed"))

  private fun html(page: String): FleetHttpServer.Response {
    val bytes = page.toByteArray(Charsets.UTF_8)
    return FleetHttpServer.Response.stream(200, "text/html; charset=utf-8", bytes.size.toLong()) {
      it.write(bytes)
    }
  }

  private fun ok() = JSONObject().put("ok", true)

  private fun err(code: String) = JSONObject().put("ok", false).put("error", code)

  private fun parseJson(s: String): JSONObject? =
      runCatching { JSONObject(if (s.isBlank()) "{}" else s) }.getOrNull()

  private fun json(status: Int, obj: JSONObject) = FleetHttpServer.Response(status, obj.toString())

  internal companion object {
    /** Extract the token from an `Authorization: Bearer <token>` header. Pure. */
    internal fun bearer(header: String?): String? {
      val h = header?.trim() ?: return null
      if (!h.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
      return h.substring(7).trim().ifBlank { null }
    }
  }
}
