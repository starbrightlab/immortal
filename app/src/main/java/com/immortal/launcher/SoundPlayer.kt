/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One-shot `MediaPlayer` wrapper for MQTT-notify chimes (see `MqttPublisher.handleNotify`).
 *
 * Routes to the notification stream — `USAGE_NOTIFICATION` + `CONTENT_TYPE_SONIFICATION`
 * via [AudioAttributes], **not** `STREAM_MUSIC` — so a doorbell isn't capped by Spotify's
 * current volume and we don't duck our own chime. Requests transient-may-duck focus so any
 * `STREAM_MUSIC` playback (Snapcast, cast, Spotify) ducks for the chime's duration and
 * restores after.
 *
 * Single in-flight player; a new [play] releases the previous one. [release] is idempotent.
 * Failures (bad URL, codec mismatch, network) surface as a single `Log.w` and never throw —
 * the visual toast still renders. See `docs/design/mqtt-notifications.md` § *Sound*.
 */
object SoundPlayer {
  private const val TAG = "ImmortalSoundPlayer"
  private val main = Handler(Looper.getMainLooper())

  @Volatile private var current: MediaPlayer? = null
  @Volatile private var focusRequest: AudioFocusRequest? = null
  @Volatile private var stashedContext: Context? = null

  /**
   * Play [source] (http(s) URL or local URI) at [volume] (0.0–1.0 of the notification-stream
   * max, bounded by the user's system slider). Releases any in-flight player first. Returns
   * immediately; preparation and playback happen on `MediaPlayer`'s own thread.
   */
  fun play(context: Context, source: String, volume: Float) {
    if (source.isBlank()) return
    Log.i(TAG, "play(volume=$volume) source=$source")
    main.post { startInternal(context.applicationContext, source, volume.coerceIn(0f, 1f)) }
  }

  /** Stop and free any active player. Safe to call from any thread; safe to call repeatedly. */
  fun release() {
    main.post { releaseInternal() }
  }

  private fun startInternal(appContext: Context, source: String, volume: Float) {
    stashedContext = appContext
    // Release a previous player, but on a worker so a slow `stop()` doesn't ANR if we're
    // already on the main thread. The new player gets installed immediately below.
    val previous = current
    current = null
    if (previous != null) {
      Thread { runCatching { previous.release() } }.apply { isDaemon = true }.start()
    }

    val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    // STREAM_ALARM, not STREAM_NOTIFICATION: on Portal, the "media volume" slider drives a
    // single linked group (SYSTEM/RING/MUSIC/NOTIFICATION/SYSTEM_ENFORCED/DTMF/TTS), so
    // STREAM_NOTIFICATION isn't actually separable from music volume on this hardware. Only
    // STREAM_ALARM and STREAM_VOICE_CALL are independent. Alarm is loud-by-default and the
    // semantic match for "audible alert that needs attention." DND suppression is still
    // enforced upstream in MqttPublisher.handleNotify (we gate the call to play() on the
    // interruption filter), so this routing change doesn't break the polite-in-DND rule.
    val attrs =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    // Transient-may-duck on STREAM_NOTIFICATION: STREAM_MUSIC ducks for our chime and
    // resumes when we abandon focus. If a call holds STREAM_VOICE_CALL focus, this request
    // is denied — we silently don't play, which is the documented in-call behavior.
    val granted = requestFocus(am, attrs)
    if (!granted) {
      // Common when a Messenger/WhatsApp call is active; documented behavior.
      Log.i(TAG, "audio focus denied; skipping chime")
      return
    }

    val player =
        runCatching {
              MediaPlayer().apply {
                setAudioAttributes(attrs)
                setVolume(volume, volume)
                setDataSource(appContext, Uri.parse(source))
                setOnPreparedListener { it.start() }
                setOnCompletionListener { mp ->
                  if (current === mp) current = null
                  runCatching { mp.release() }
                  abandonFocus(am)
                }
                setOnErrorListener { mp, what, extra ->
                  Log.w(TAG, "playback error what=$what extra=$extra src=$source")
                  if (current === mp) current = null
                  runCatching { mp.release() }
                  abandonFocus(am)
                  true // we've handled it
                }
                prepareAsync()
              }
            }
            .onFailure {
              Log.w(TAG, "setDataSource failed for $source", it)
              abandonFocus(am)
            }
            .getOrNull()

    current = player
  }

  private fun releaseInternal() {
    val mp = current
    current = null
    if (mp != null) runCatching { mp.release() }
    val am = stashedContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    abandonFocus(am)
  }

  private fun requestFocus(am: AudioManager?, attrs: AudioAttributes): Boolean {
    if (am == null) return false
    return if (Build.VERSION.SDK_INT >= 26) {
      val req =
          AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
              .setAudioAttributes(attrs)
              .build()
      focusRequest = req
      am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } else {
      @Suppress("DEPRECATION")
      am.requestAudioFocus(
          null,
          AudioManager.STREAM_ALARM,
          AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
      ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
  }

  private fun abandonFocus(am: AudioManager?) {
    am ?: return
    if (Build.VERSION.SDK_INT >= 26) {
      focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
    } else {
      @Suppress("DEPRECATION") runCatching { am.abandonAudioFocus(null) }
    }
    focusRequest = null
  }
}
