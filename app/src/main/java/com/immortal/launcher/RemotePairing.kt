/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.security.SecureRandom

/**
 * Pairing + session auth for the phone remote ([RemoteRoutes]). The fleet bearer token
 * ([FleetConfig]) is a 32-char secret meant for the laptop CLI — miserable to type on a
 * phone — so the remote uses a **PIN/QR pairing** instead:
 *
 *  1. The Portal shows a short-lived 6-digit PIN ([RemotePairActivity], via [newPin]).
 *  2. The phone opens `/remote/ui` and submits the PIN to `/remote/pair`.
 *  3. On a match we mint a long-lived **session token**, persist it, and hand it back;
 *     the phone stores it and sends it as `Authorization: Bearer …` on every call.
 *
 * The PIN is the security gate: redeeming it requires physically reading the Portal's
 * screen, so only someone in the room can pair. Sessions persist across reboots (so a
 * paired phone keeps working), and [revokeAll] drops them all. PINs live in memory only
 * and expire quickly. The remote feature is opt-in per device ([isEnabled]) in the same
 * spirit as the agent itself.
 */
object RemotePairing {
  private const val PREFS = "remote_pairing"
  private const val KEY_ENABLED = "enabled"
  private const val KEY_SESSIONS = "sessions" // newline-separated session tokens
  internal const val PIN_TTL_MS = 5 * 60_000L

  @Volatile private var pin: String? = null
  @Volatile private var pinExpiresAt = 0L

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  // --- opt-in -----------------------------------------------------------------

  fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)

  fun setEnabled(c: Context, on: Boolean) {
    prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
  }

  // --- PIN (in-memory, short-lived) -------------------------------------------

  /** Generate, store, and return a fresh 6-digit PIN to show on the Portal. */
  fun newPin(now: Long = System.currentTimeMillis()): String {
    val fresh = randomDigits(6)
    pin = fresh
    pinExpiresAt = now + PIN_TTL_MS
    return fresh
  }

  /** The current PIN if still valid, else null (for the on-Portal display). */
  fun currentPin(now: Long = System.currentTimeMillis()): String? =
      if (pinMatches(pin, pinExpiresAt, pin ?: "", now)) pin else null

  /**
   * Redeem a candidate PIN. On a valid, unexpired match: clear the PIN (single use),
   * mint + persist a session token, and return it. Otherwise return null.
   */
  fun redeem(c: Context, candidate: String, now: Long = System.currentTimeMillis()): String? {
    if (!pinMatches(pin, pinExpiresAt, candidate, now)) return null
    pin = null
    pinExpiresAt = 0L
    val token = randomToken()
    val sessions = loadSessions(c).toMutableSet().apply { add(token) }
    prefs(c).edit().putString(KEY_SESSIONS, sessions.joinToString("\n")).apply()
    return token
  }

  // --- sessions ---------------------------------------------------------------

  fun isValidSession(c: Context, token: String): Boolean =
      token.isNotBlank() && loadSessions(c).any { FleetRoutes.constantTimeEquals(it, token) }

  fun revokeAll(c: Context) {
    prefs(c).edit().remove(KEY_SESSIONS).apply()
  }

  private fun loadSessions(c: Context): Set<String> =
      prefs(c).getString(KEY_SESSIONS, null)?.split("\n")?.filter { it.isNotBlank() }?.toSet()
          ?: emptySet()

  // --- pure helpers (unit-tested) ---------------------------------------------

  /**
   * Whether [candidate] matches the [stored] PIN and the window hasn't expired. A null
   * or blank stored PIN never matches (so "no PIN issued" can't be redeemed with "").
   */
  internal fun pinMatches(stored: String?, expiresAt: Long, candidate: String, now: Long): Boolean {
    if (stored.isNullOrBlank()) return false
    if (now >= expiresAt) return false
    return FleetRoutes.constantTimeEquals(stored, candidate.trim())
  }

  private val rng = SecureRandom()

  private fun randomDigits(n: Int): String =
      buildString { repeat(n) { append(rng.nextInt(10)) } }

  private fun randomToken(): String {
    val bytes = ByteArray(16)
    rng.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}
