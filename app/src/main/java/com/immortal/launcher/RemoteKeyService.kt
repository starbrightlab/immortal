/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Remaps the Portal TV remote's three branded app buttons (Netflix, Amazon Prime, Facebook Watch)
 * to Immortal actions. Those services are gone from the device, so the keys are dead weight.
 *
 * The remote is an ordinary Bluetooth HID device bound to its own key layout
 * (`/system/usr/keylayout/Vendor_1915_Product_eeee.kl`, vendor 0x1915 product 0xeeee), which maps
 * the buttons to standard Android keycodes — verified with `getevent -lt` on a Portal TV (ripley):
 *
 * | Button          | Linux key        | Android keycode     |
 * |-----------------|------------------|---------------------|
 * | Netflix         | KEY_RED   (398)  | KEYCODE_PROG_RED    |
 * | Facebook Watch  | KEY_GREEN (399)  | KEYCODE_PROG_BLUE   |
 * | Amazon Prime    | KEY_BLUE  (401)  | KEYCODE_PROG_GREEN  |
 * | Microphone      | KEY_SEARCH(217)  | KEYCODE_SEARCH      |
 *
 * (Meta's key layout really does cross green and blue.) Nothing on the device handles `PROG_*`, so
 * consuming them breaks no existing behaviour; every other key is passed straight through.
 *
 * The microphone button is the exception worth knowing about: it reports as `SEARCH` (HID usage
 * 0x000C0221, "AC Search") because it opened Portal's voice assistant, which is gone. `SEARCH` is a
 * standard key an app could legitimately want, so claiming it is opt-in like the rest — left at
 * "Nothing" it passes through untouched.
 *
 * Requires `canRequestFilterKeyEvents` (see `res/xml/remote_key_service.xml`) and the user enabling
 * the service in Android Settings → Accessibility — the same one-time step as
 * [ImmortalBackGestureService].
 */
class RemoteKeyService : AccessibilityService() {

  private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

  override fun onServiceConnected() {
    super.onServiceConnected()
    Log.i(TAG, "remote-key service connected")
  }

  /**
   * Key events arrive here before any app sees them. Return true to consume the key, false to let
   * it through. Only the three `PROG_*` keys are ever consumed, and only when the user has mapped
   * them to something — an unmapped button keeps its stock (no-op) behaviour.
   */
  override fun onKeyEvent(event: KeyEvent): Boolean {
    val action = actionFor(event.keyCode) ?: return false
    if (action == ImmortalSettings.KEY_ACTION_NONE) return false
    // Act on key-up so a long press doesn't repeat-fire, but consume both edges: leaving the
    // down event to propagate would let an app act on a key we've claimed.
    if (event.action == KeyEvent.ACTION_UP) {
      Log.i(TAG, "remote key ${event.keyCode} -> $action")
      runCatching { perform(action) }.onFailure { Log.w(TAG, "action $action failed", it) }
    }
    return true
  }

  private fun actionFor(keyCode: Int): String? {
    val s = ImmortalSettings.load(this)
    if (!s.remoteKeysEnabled) return null
    return when (keyCode) {
      KeyEvent.KEYCODE_PROG_RED -> s.progRedAction
      KeyEvent.KEYCODE_PROG_GREEN -> s.progGreenAction
      KeyEvent.KEYCODE_PROG_BLUE -> s.progBlueAction
      KeyEvent.KEYCODE_SEARCH -> s.searchAction
      else -> null
    }
  }

  private fun perform(action: String) {
    when (action) {
      // Software mic mute — the same flag the MQTT mic_mute switch drives, which does mute a live
      // call on Portal hardware. Re-publish so Home Assistant doesn't drift out of sync.
      ImmortalSettings.KEY_ACTION_MIC_MUTE -> {
        audio.isMicrophoneMute = !audio.isMicrophoneMute
        MqttService.sync(this)
      }
      ImmortalSettings.KEY_ACTION_HOME ->
          startActivity(
              Intent(Intent.ACTION_MAIN)
                  .addCategory(Intent.CATEGORY_HOME)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      ImmortalSettings.KEY_ACTION_SCREENSAVER ->
          startActivity(
              Intent(this, PhotoFramePreviewActivity::class.java)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      ImmortalSettings.KEY_ACTION_SCREEN_OFF -> ScreenControl.sleep(this)
      ImmortalSettings.KEY_ACTION_BACK ->
          BackHelper.performBack(this) { performGlobalAction(GLOBAL_ACTION_BACK) }
      else -> Log.w(TAG, "unknown action $action")
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // No-op: this service only filters key events.
  }

  override fun onInterrupt() {
    Log.w(TAG, "remote-key service interrupted")
  }

  private companion object {
    const val TAG = "ImmortalRemoteKey"
  }
}
