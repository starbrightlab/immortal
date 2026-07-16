/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Invisible one-shot activity that raises the CAMERA runtime-permission dialog,
 * then finishes. Exists for fleet-provisioned installs that never went through
 * USB adb (`pm grant`) — the kit can POST `/eye/permission` to pop the dialog
 * on-device instead. See [StarEye].
 */
class EyePermissionActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (intent.getBooleanExtra(EXTRA_KICK, false)) {
      // Foreground kick: the Portal's Android 9 camera service refuses opens
      // from a UID it considers background — even with our foreground service
      // running. Being briefly TOP with a (translucent, invisible) activity
      // satisfies it. Stay up long enough for one capture, then vanish.
      setShowWhenLocked(true)
      setTurnScreenOn(true)
      window.decorView.postDelayed({ finish() }, KICK_MS)
      return
    }
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      finish()
      return
    }
    requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
  }

  companion object {
    const val EXTRA_KICK = "kick"
    private const val KICK_MS = 4000L
  }

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<out String>,
      grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    finish()
  }
}
