/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Watches for the system top bar being revealed (immortal hides it by default; the OS shows it
 * transiently on a swipe-down) and tells [QuickBar] to show/hide the quick-button cluster in
 * sync. This is the only way to detect the reveal on the Portal — an overlay can't observe it
 * (insets don't change for sticky-immersive transient bars; verified by spike), but an
 * accessibility service sees a top-of-screen TYPE_SYSTEM window appear/disappear.
 *
 * Not enabled by provisioning. A feature that needs it self-enables it via
 * [SettingsGuard.reconcileBarWatch] — the quick-button cluster or the phone remote — and it's a
 * no-op until one of them is turned on. (Both share this one service, so neither disables it while
 * the other still needs it.)
 */
class BarWatchService : AccessibilityService() {

  override fun onServiceConnected() {
    QuickBar.attach(this) // host the cluster as a TYPE_ACCESSIBILITY_OVERLAY (renders on the bar)
    RemoteInput.register(this) // let the phone-remote routes drive input through us
    RemoteCursor.attach(this) // host the remote touchpad's on-TV pointer overlay
    NotificationOverlay.attach(this) // host MQTT-notify toasts (see MqttPublisher.handleNotify)
    updateBar()
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) updateBar()
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    QuickBar.detach()
    RemoteInput.unregister()
    RemoteCursor.detach()
    NotificationOverlay.detach()
    return super.onUnbind(intent)
  }

  override fun onInterrupt() {}

  private fun updateBar() {
    val ws = runCatching { windows }.getOrNull() ?: return
    val dm = resources.displayMetrics
    val r = Rect()
    // The status/top bar is a TYPE_SYSTEM window pinned to the top edge, spanning most of the
    // width and only a sliver tall — distinct from full-height system windows.
    val barShown =
        ws.any { w ->
          if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
          w.getBoundsInScreen(r)
          r.top <= 0 && r.width() >= dm.widthPixels / 2 && r.height() in 1..(dm.heightPixels / 4)
        }
    QuickBar.setBarVisible(barShown)
  }
}
