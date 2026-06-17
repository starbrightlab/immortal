/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverConfigTest {

  @Test
  fun clampInterval_keepsWithinBounds() {
    assertEquals(5, ScreensaverConfig.clampInterval(1))
    assertEquals(5, ScreensaverConfig.clampInterval(-10))
    assertEquals(30, ScreensaverConfig.clampInterval(30))
    assertEquals(600, ScreensaverConfig.clampInterval(99999))
  }

  @Test
  fun usesFolder_requiresFolderSourceAndPath() {
    assertTrue(
        ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_FOLDER, folderPath = "/storage/emulated/0/DCIM")
            .usesFolder)
    assertFalse(
        ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_FOLDER, folderPath = null)
            .usesFolder)
    assertFalse(
        ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_FOLDER, folderPath = "")
            .usesFolder)
    assertFalse(
        ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_DEFAULT, folderPath = "/storage/emulated/0/DCIM")
            .usesFolder)
  }

  @Test
  fun defaults_areSensible() {
    val s = ScreensaverConfig.Settings()
    assertEquals(ScreensaverConfig.SOURCE_DEFAULT, s.source)
    assertEquals(ScreensaverConfig.FIT_FILL, s.fit)
    assertEquals(30, s.intervalSec)
    assertEquals(60, s.albumRefreshMin)
    assertFalse(s.shuffle)
    assertTrue(s.includeVideo)
  }

  @Test
  fun usesUrl_requiresUrlSourceAndLink() {
    assertTrue(
        ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_URL,
                albumUrl = "https://www.icloud.com/sharedalbum/#B1abcDEF")
            .usesUrl)
    assertFalse(
        ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_URL, albumUrl = null).usesUrl)
    assertFalse(
        ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_URL, albumUrl = "").usesUrl)
    assertFalse(
        ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_DEFAULT,
                albumUrl = "https://www.icloud.com/sharedalbum/#B1abcDEF")
            .usesUrl)
  }

  @Test
  fun clampAlbumRefresh_keepsWithinBounds() {
    assertEquals(15, ScreensaverConfig.clampAlbumRefresh(1))
    assertEquals(15, ScreensaverConfig.clampAlbumRefresh(-10))
    assertEquals(60, ScreensaverConfig.clampAlbumRefresh(60))
    assertEquals(24 * 60, ScreensaverConfig.clampAlbumRefresh(99999))
  }
}
