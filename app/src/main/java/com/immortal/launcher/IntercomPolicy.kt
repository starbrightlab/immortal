/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Locale

/** The action implied by the persisted intercom settings. */
enum class IntercomCommand {
  STOP,
  BROADCAST,
  RECEIVE,
  INVALID,
}

/**
 * Pure rules for the dependency-free LAN intercom. Keeping them out of [IntercomService] makes
 * the settings boundary and restart behavior testable without Android audio or sockets.
 */
object IntercomPolicy {
  const val MODE_OFF = "off"
  const val MODE_BROADCAST = "broadcast"
  const val MODE_RECEIVE = "receive"
  const val PORT = 8724

  private const val MAX_HOST_LENGTH = 255

  /** Accept only the closed enum; an unrecognized remote value must not become a default mode. */
  fun normalizeMode(raw: String): String? =
      when (raw.trim().lowercase(Locale.ROOT)) {
        MODE_OFF -> MODE_OFF
        MODE_BROADCAST -> MODE_BROADCAST
        MODE_RECEIVE -> MODE_RECEIVE
        else -> null
      }

  /**
   * Accept a blank value (meaning unset) or a compact LAN host: IPv4, IPv6, or an ASCII DNS/mDNS
   * name. The service resolves the host at connect time, so this validates syntax without making
   * network availability part of settings persistence.
   */
  fun normalizePeerHost(raw: String): String? {
    val host = raw.trim()
    if (host.isEmpty()) return ""
    if (host.length > MAX_HOST_LENGTH || host.any { it.isWhitespace() || it.isISOControl() })
        return null

    // A colon is legal only for IPv6 (including its scoped-zone suffix). Requiring hex and colon
    // characters keeps an accidental "host:8724" from being stored as a host name.
    if (host.contains(":")) {
      return host.takeIf { value ->
        value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '%' }
      }
    }

    val labels = host.split('.')
    return host.takeIf { value ->
      labels.all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
            !label.startsWith("-") && !label.endsWith("-")
        }
      }
}

  fun commandFor(rawMode: String, rawPeerHost: String): IntercomCommand =
      when (normalizeMode(rawMode)) {
        MODE_OFF -> IntercomCommand.STOP
        MODE_BROADCAST -> IntercomCommand.BROADCAST
        MODE_RECEIVE -> {
          val peerHost = normalizePeerHost(rawPeerHost)
          if (peerHost.isNullOrEmpty()) IntercomCommand.INVALID else IntercomCommand.RECEIVE
        }
        else -> IntercomCommand.INVALID
      }
}
