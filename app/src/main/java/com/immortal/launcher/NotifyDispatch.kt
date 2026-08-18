/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Renders a [NotifyPayload] — the one place a notification actually becomes a toast, a
 * chime, and/or a spoken line.
 *
 * Two producers feed it and neither owns the behaviour: [MqttPublisher.handleNotify]
 * (Home Assistant, fire-and-forget) and the fleet agent's `POST /notify` (any HTTP
 * caller, answers with what it did). Keeping delivery here is what stops the two
 * surfaces from drifting — one parser ([NotifyPayload.parse]), one renderer.
 *
 * Behaviour contract lives in `docs/design/mqtt-notifications.md`; the pieces are
 * [NotificationOverlay] (visual), [SoundPlayer] (audio file), and [ChimePlayer.announce]
 * (TTS through the voice picked in Sounds settings).
 */
object NotifyDispatch {
  private const val TAG = "ImmortalNotify"
  private val main = Handler(Looper.getMainLooper())

  /**
   * Deliver [spec]. Safe to call from any thread — the fleet agent's HTTP handler runs on a
   * socket worker, so the TTS hand-off is posted to the main looper (the overlay and the
   * sound player already post their own).
   *
   * Audio (sound and speech alike) is suppressed under Do Not Disturb; the visual toast
   * still renders, because a silent on-screen alert is also the "it arrived" signal.
   */
  fun deliver(context: Context, spec: NotifyPayload) {
    val app = context.applicationContext
    if (spec.hasVisual) {
      // wake_screen defaults to true. Always call wake when requested — idempotent if the
      // device is already interactive, dismisses the photo dream if it's in front (PowerManager
      // reports isInteractive=true while dreaming, so a screen-on check alone misses it; and
      // PresenceHub.current.screen only reflects THIS process's dream service, which doesn't
      // help if a sibling package owns the active dream). 3s auto-release wake lock, no harm.
      if (spec.wakeScreen) ScreenControl.wake(app)
      val tap: (() -> Unit)? = spec.onTap?.let { target -> { routeTarget(app, target) } }
      NotificationOverlay.show(spec, tap)
    }
    val audible = dndOff(app)
    if (!spec.sound.isNullOrBlank()) {
      if (audible) SoundPlayer.play(app, spec.sound, spec.volume)
      else Log.i(TAG, "DND active; suppressing notify sound")
    }
    if (!spec.speak.isNullOrBlank()) {
      // TTS is an audible interruption like the chime, so it takes the same DND gate. It
      // rides the platform engine via ChimePlayer rather than a second TTS instance —
      // same voice the spoken clock and timers use.
      if (audible) main.post { ChimePlayer.announce(app, spec.speak, spec.volume) }
      else Log.i(TAG, "DND active; suppressing notify speech")
    }
  }

  /**
   * Dispatch a target string without touching any entity state: a full URL (http/https →
   * browser, homeassistant:// → HA), an installed package name (launch it), or a bare HA
   * dashboard path like "today-home/security" (deep-linked into the HA app). Returns true
   * when a target was launched, false for a blank or unroutable input (e.g. no HA app
   * installed for a path).
   *
   * Reuses [ScreensaverDismiss]'s HA helpers so the behaviour matches the screensaver picker.
   */
  fun routeTarget(context: Context, payload: String): Boolean {
    val app = context.applicationContext
    val t = payload.trim()
    if (t.isBlank()) return false
    val pm = app.packageManager
    when {
      t.startsWith("http://") || t.startsWith("https://") || t.startsWith("homeassistant://") ->
          app.startActivity(
              Intent(Intent.ACTION_VIEW, Uri.parse(t)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      pm.getLaunchIntentForPackage(t) != null ->
          app.startActivity(pm.getLaunchIntentForPackage(t)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      else -> {
        val pkg = ScreensaverDismiss.installedHaPackage(app) ?: return false
        app.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(ScreensaverDismiss.haDeepLink(t)))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    }
    return true
  }

  /** True when Do Not Disturb is off (or unknowable) — the gate for anything audible. */
  private fun dndOff(context: Context): Boolean {
    val nm =
        context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? android.app.NotificationManager
    return nm == null ||
        nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL
  }
}
