/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.service.notification.NotificationListenerService

/**
 * We don't actually care about notifications — this exists purely so the launcher
 * is an *enabled notification listener*, which is the credential
 * `MediaSessionManager.getActiveSessions` checks before it will hand a non-system
 * app the active [android.media.session.MediaController]s. Being bound is the
 * authoritative "session access is now permitted" signal, so we relay the connect/
 * disconnect lifecycle to [MediaSessionReader].
 *
 * Enabled by appending our component to the `enabled_notification_listeners` secure
 * setting (see [SettingsGuard.enableMediaListener]); a no-op dormant service until then.
 */
class MediaNotificationListenerService : NotificationListenerService() {
  override fun onListenerConnected() {
    instance = this
    MediaSessionReader.onListenerConnected(this)
  }

  override fun onListenerDisconnected() {
    if (instance === this) instance = null
    MediaSessionReader.onListenerDisconnected()
  }

  companion object {
    @Volatile private var instance: MediaNotificationListenerService? = null

    /**
     * Active-notification count per package (for app-switcher badges). Empty when the listener
     * isn't bound (e.g. un-provisioned device). Ongoing/group-summary notifications are excluded
     * so a count reflects what the user would actually see.
     */
    fun activeCountsByPackage(): Map<String, Int> =
        instance?.let { svc ->
          runCatching {
                svc.activeNotifications
                    .filter { it.isClearable && (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0 }
                    .groupingBy { it.packageName }
                    .eachCount()
              }
              .getOrNull()
        } ?: emptyMap()
  }
}
