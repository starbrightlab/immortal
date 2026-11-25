/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The system-wide "touch & keypress sounds" master flag
 * ([Settings.System.SOUND_EFFECTS_ENABLED]).
 *
 * Portal ships it **off** (`0`), which silences the whole `/system/media/audio/ui`
 * keypress set for *every* app — including any sound the launcher itself plays via
 * `View.playSoundEffect`, which is gated on this same flag. The "Touch & keypress
 * sounds" toggle in [ImmortalSettingsActivity] flips it.
 *
 * Writing it needs the WRITE_SETTINGS app-op ("Modify system settings").
 * The touch sounds toggle opens Android’s Modify system settings screen when access is missing.
 */
object SystemSounds {

  /** Whether the system-wide touch/keypress sound effects are currently on. */
  fun touchSoundsEnabled(context: Context): Boolean =
      runCatching {
        Settings.System.getInt(
            context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 0)
      }.getOrDefault(0) != 0

  /** Whether we may write [Settings.System] (the WRITE_SETTINGS app-op). */
  fun canWrite(context: Context): Boolean =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(context)
      else true

  /**
   * Flip the system-wide touch/keypress sounds. Also (un)loads the AudioManager
   * effect cache so the change takes hold without a reboot. Returns true only if
   * the new value actually stuck (i.e. the write was permitted).
   */
  fun setTouchSounds(context: Context, on: Boolean): Boolean {
    runCatching {
      Settings.System.putInt(
          context.contentResolver,
          Settings.System.SOUND_EFFECTS_ENABLED,
          if (on) 1 else 0)
    }
    runCatching {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      if (on) am.loadSoundEffects() else am.unloadSoundEffects()
    }
    return touchSoundsEnabled(context) == on
  }

  /** Open the system "Modify system settings" grant screen for this app. */
  fun requestWriteAccess(context: Context) {
    runCatching {
      context.startActivity(
          Intent(
              Settings.ACTION_MANAGE_WRITE_SETTINGS,
              Uri.parse("package:${context.packageName}"))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }
}
