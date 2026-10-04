/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

/** Exercises the singleTask relaunch without starting an Android UI. */
class PhotoFramePreviewActivityTest {
  private fun activity(nightClock: Boolean = false, overnightEnabled: Boolean = true): PhotoFramePreviewActivity {
    val activity = mock(PhotoFramePreviewActivity::class.java, CALLS_REAL_METHODS)
    // Mockito skips constructors; supply the listener list used by super.onNewIntent.
    ComponentActivity::class.java.getDeclaredField("mOnNewIntentListeners").apply {
      isAccessible = true
      set(activity, CopyOnWriteArrayList<Any>())
    }
    val prefs = mock(SharedPreferences::class.java)
    doReturn(prefs).`when`(activity).getSharedPreferences("immortal_screensaver", Context.MODE_PRIVATE)
    doNothing().`when`(activity).setIntent(any(Intent::class.java))
    doNothing().`when`(activity).recreate()
    `when`(prefs.getBoolean("overnight_enabled", false)).thenReturn(overnightEnabled)
    `when`(prefs.getBoolean("overnight_night_clock", false)).thenReturn(true)
    val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    // Keep the real scheduler inside the window even if the test crosses a minute or midnight.
    `when`(prefs.getInt("overnight_start_min", 22 * 60)).thenReturn((now + 1430) % 1440)
    `when`(prefs.getInt("overnight_end_min", 7 * 60)).thenReturn((now + 10) % 1440)
    PhotoFramePreviewActivity::class.java.getDeclaredField("nightClock").apply {
      isAccessible = true
      setBoolean(activity, nightClock)
    }
    return activity
  }

  private fun relaunch(activity: PhotoFramePreviewActivity, intent: Intent) {
    PhotoFramePreviewActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply {
      isAccessible = true
      invoke(activity, intent)
    }
  }

  @Test
  fun relaunchDuringOvernight_recreatesDaytimeFrameAsNightClock() {
    val activity = activity()
    val intent = mock(Intent::class.java)
    relaunch(activity, intent)
    val order = inOrder(activity)
    order.verify(activity).setIntent(intent)
    order.verify(activity).recreate()
  }

  @Test
  fun relaunchDuringOvernight_keepsExistingNightClock() {
    val activity = activity(nightClock = true)
    relaunch(activity, mock(Intent::class.java))
    verify(activity, never()).recreate()
  }
  @Test
  fun relaunchAfterOvernight_recreatesNightClockAsDaytimeFrame() {
    val activity = activity(nightClock = true, overnightEnabled = false)
    relaunch(activity, mock(Intent::class.java))
    verify(activity).recreate()
  }

  @Test
  fun daytimeRelaunch_keepsFrameAndUsesLatestDismissChoice() {
    val activity = activity(overnightEnabled = false)
    val intent = mock(Intent::class.java)
    val dismiss = PhotoFramePreviewActivity::class.java.getDeclaredField("launchDismissOnExit").apply {
      isAccessible = true
    }
    `when`(intent.getBooleanExtra(PhotoFramePreviewActivity.EXTRA_LAUNCH_DISMISS_APP, false))
        .thenReturn(true, false)
    relaunch(activity, intent)
    assertTrue(dismiss.getBoolean(activity))
    relaunch(activity, intent)
    assertFalse(dismiss.getBoolean(activity))
    verify(activity, never()).recreate()
  }
}
