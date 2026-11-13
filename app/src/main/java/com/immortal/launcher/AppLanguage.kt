/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * The language Immortal shows its own text in: the device's, or one the user picked under
 * Settings → Immortal → Language. UI text lives in `res/values/strings.xml` (and `values-es/` etc.); this only decides
 * which of those the app reads.
 *
 * Portals run Android 9/10, where the per-app `LocaleManager` (API 33) doesn't exist, so the choice
 * is applied by wrapping a [Context] with [Context.createConfigurationContext]:
 *  - an Activity whose screen is translated calls [wrap] from `attachBaseContext`, so
 *    `stringResource()` / `getString()` inside it follow the choice;
 *  - code that builds labels for another screen (the settings registry, for example) reads them
 *    through [resources] with whatever Context it has.
 *
 * Text that belongs to the user or to another app (folder and album names, app labels, custom clock
 * names) is never routed through string resources, so it stays exactly as it was typed or installed.
 */
object AppLanguage {
  const val SYSTEM = "auto"
  const val ENGLISH = "en"
  const val SPANISH = "es"
  val ALL = listOf(SYSTEM, ENGLISH, SPANISH)

  /** The locale for a stored choice, or null to follow the device. */
  fun localeFor(choice: String?): Locale? =
      when (choice) {
        ENGLISH -> Locale.ENGLISH
        SPANISH -> Locale("es")
        else -> null
      }

  /** [base] with the chosen language applied; [base] itself when following the device. */
  fun wrap(base: Context): Context {
    val locale = localeFor(ImmortalSettings.language(base)) ?: return base
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    return base.createConfigurationContext(config)
  }

  /** Resources in the chosen language, for labels built outside a translated Activity. */
  fun resources(c: Context): Resources = wrap(c).resources
}
