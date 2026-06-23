/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

/**
 * Config for the quick-button cluster ([QuickBar]) — a centered overlay button row at the top of
 * the screen (v1: an app-switcher button). Off by default. Mirrors the other prefs objects.
 */
object QuickBarConfig {
  private const val PREFS = "quick_bar"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun isEnabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false)

  fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

  /** True = always visible; false (default) = only while the system top bar is revealed. */
  fun alwaysShow(c: Context): Boolean = prefs(c).getBoolean("always_show", false)

  fun setAlwaysShow(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("always_show", on).apply()

  /** Immutable snapshot, so the settings domain renders through the generic on-device list. */
  data class Settings(val enabled: Boolean = false, val alwaysShow: Boolean = false)

  fun load(c: Context): Settings = Settings(enabled = isEnabled(c), alwaysShow = alwaysShow(c))
}
