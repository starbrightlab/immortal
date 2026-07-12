/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/** Source-selection Module for the photo-frame Screensaver. */
sealed class PhotoFrameSource {
  data object DefaultFeed : PhotoFrameSource()

  data class Folder(
      val path: String,
      val includeVideo: Boolean,
      val shuffle: Boolean,
  ) : PhotoFrameSource()

  data class SharedAlbum(
      val url: String,
      val shuffle: Boolean,
  ) : PhotoFrameSource()

  data class Immich(
      val url: String,
      val key: String,
      val albumId: String?,
      val includeVideo: Boolean,
      val shuffle: Boolean,
  ) : PhotoFrameSource()

  data class WebDav(
      val url: String,
      val user: String,
      val pass: String,
      val includeVideo: Boolean,
      val shuffle: Boolean,
  ) : PhotoFrameSource()

  data class Smb(
      val host: String,
      val share: String,
      val path: String,
      val user: String,
      val pass: String,
      val shuffle: Boolean,
  ) : PhotoFrameSource()

  data class WebPage(val url: String) : PhotoFrameSource()

  val setupKey: String
    get() =
        when (this) {
          DefaultFeed -> "default"
          is Folder -> "folder"
          is SharedAlbum -> "album"
          is Immich -> "immich"
          is WebDav -> "dav"
          is Smb -> "smb"
          is WebPage -> "web"
        }

  companion object {
    /**
     * Pick the active source from persisted Screensaver settings.
     *
     * Missing required fields deliberately fall back to [DefaultFeed], matching the product promise
     * that the frame is never blank because of an unset or unreachable configured source.
     */
    fun from(settings: ScreensaverConfig.Settings, allowWebPage: Boolean = true): PhotoFrameSource =
        when {
          allowWebPage && settings.usesWebUrl -> WebPage(settings.webUrl!!)
          settings.usesFolder ->
              Folder(settings.folderPath!!, includeVideo = settings.includeVideo, shuffle = settings.shuffle)
          settings.usesUrl -> SharedAlbum(settings.albumUrl!!, shuffle = settings.shuffle)
          settings.usesImmich ->
              Immich(
                  url = settings.immichUrl!!,
                  key = settings.immichKey!!,
                  albumId = settings.immichAlbumId,
                  includeVideo = settings.includeVideo,
                  shuffle = settings.shuffle,
              )
          settings.usesDav ->
              WebDav(
                  url = settings.davUrl!!,
                  user = settings.davUser.orEmpty(),
                  pass = settings.davPass.orEmpty(),
                  includeVideo = settings.includeVideo,
                  shuffle = settings.shuffle,
              )
          settings.usesSmb ->
              Smb(
                  host = settings.smbHost!!,
                  share = settings.smbShare!!,
                  path = settings.smbPath.orEmpty(),
                  user = settings.smbUser.orEmpty(),
                  pass = settings.smbPass.orEmpty(),
                  shuffle = settings.shuffle,
              )
          else -> DefaultFeed
        }
  }
}
