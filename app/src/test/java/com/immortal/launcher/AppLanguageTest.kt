/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
  @Test
  fun localeFor_followsTheDeviceUnlessALanguageIsChosen() {
    assertNull(AppLanguage.localeFor(AppLanguage.SYSTEM))
    assertNull(AppLanguage.localeFor(null))
    assertNull(AppLanguage.localeFor("klingon")) // unknown stored value: follow the device
    assertEquals(Locale.ENGLISH, AppLanguage.localeFor(AppLanguage.ENGLISH))
    assertEquals("es", AppLanguage.localeFor(AppLanguage.SPANISH)!!.language)
  }
}
